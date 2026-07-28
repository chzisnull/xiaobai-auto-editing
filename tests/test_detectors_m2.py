import os
import tempfile
import pytest
import numpy as np
import scipy.signal as signal

from backend.app.detectors.base import BaseRallyDetector
from backend.app.detectors.audio_visual import AudioVisualRallyDetector
from backend.app.detectors.court_aware import CourtAwareRallyDetector
from backend.app.detectors.rally_scorer import MultimodalRallyScorer
from backend.app.detectors.tracknet_v3 import TrackNetV3BoundaryRefiner, TrajectoryPoint
from backend.app.detectors.trajectory_series import TrajectorySeries
from backend.app.schemas.rally import AnalyzeRequest

def test_base_rally_detector_abc_enforcement():
    """Verify that BaseRallyDetector is an ABC and cannot be instantiated directly."""
    with pytest.raises(TypeError):
        BaseRallyDetector()

class DummyDetector(BaseRallyDetector):
    def analyze_video(self, video_path: str):
        return super()._format_and_validate_rallies([
            {"start": 10.0, "end": 20.0}
        ])

def test_base_rally_detector_subclass_instantiation():
    """Verify concrete subclass can be instantiated."""
    detector = DummyDetector()
    assert isinstance(detector, BaseRallyDetector)

def test_validate_video_path_nonexistent():
    """Verify validate_video_path raises FileNotFoundError for nonexistent paths."""
    with pytest.raises(FileNotFoundError) as exc_info:
        BaseRallyDetector.validate_video_path("/nonexistent/path/to/video.mp4")
    assert "Video file not found" in str(exc_info.value)

def test_validate_video_path_empty_file():
    """Verify validate_video_path raises ValueError for empty (0-byte) files."""
    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        with pytest.raises(ValueError) as exc_info:
            BaseRallyDetector.validate_video_path(tmp_path)
        assert "0 bytes" in str(exc_info.value)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

def test_format_and_validate_rallies_sorting_and_indexing():
    """Verify segment sorting, 1-indexing, and duration calculation."""
    raw = [
        {"start": 45.0, "end": 60.0},
        {"start": 10.0, "end": 25.0},
    ]
    res = BaseRallyDetector._format_and_validate_rallies(raw)
    assert len(res) == 2
    assert res[0]["id"] == 1
    assert res[0]["start"] == 10.0
    assert res[0]["end"] == 25.0
    assert res[0]["duration"] == 15.0

    assert res[1]["id"] == 2
    assert res[1]["start"] == 45.0
    assert res[1]["end"] == 60.0
    assert res[1]["duration"] == 15.0

def test_format_and_validate_rallies_adjacent_merging():
    """Verify adjacent/overlapping segments with gap <= threshold merge properly."""
    raw = [
        {"start": 10.0, "end": 20.0},
        {"start": 20.3, "end": 30.0},  # Gap is 0.3s <= 0.5s threshold
        {"start": 40.0, "end": 50.0},  # Gap is 10.0s > 0.5s threshold
    ]
    res = BaseRallyDetector._format_and_validate_rallies(raw, merge_gap_threshold=0.5)
    assert len(res) == 2
    assert res[0]["id"] == 1
    assert res[0]["start"] == 10.0
    assert res[0]["end"] == 30.0
    assert res[0]["duration"] == 20.0

    assert res[1]["id"] == 2
    assert res[1]["start"] == 40.0
    assert res[1]["end"] == 50.0

def test_format_and_validate_rallies_invalid_timestamps():
    """Verify negative start time or end <= start raises ValueError."""
    with pytest.raises(ValueError):
        BaseRallyDetector._format_and_validate_rallies([{"start": -1.0, "end": 5.0}])

    with pytest.raises(ValueError):
        BaseRallyDetector._format_and_validate_rallies([{"start": 10.0, "end": 5.0}])

def test_audio_visual_rally_detector_hyperparameter_defaults():
    """Verify default hyperparameter initializations."""
    detector = AudioVisualRallyDetector()
    assert detector.sample_rate == 16000
    assert detector.freq_min == 2000.0
    assert detector.freq_max == 6000.0
    assert detector.min_hit_interval == 0.25
    assert detector.max_silence_gap == 2.5
    assert detector.min_rally_duration == 2.0
    assert detector.motion_threshold == 0.05
    assert detector.visual_fps == 5
    assert detector.mock_mode is False

def test_audio_visual_rally_detector_custom_hyperparameters():
    """Verify custom hyperparameter configuration."""
    detector = AudioVisualRallyDetector(
        sample_rate=22050,
        freq_min=1500.0,
        freq_max=5000.0,
        min_hit_interval=0.3,
        max_silence_gap=3.0,
        min_rally_duration=1.5,
        motion_threshold=0.08,
        visual_fps=10,
        mock_mode=True
    )
    assert detector.sample_rate == 22050
    assert detector.freq_min == 1500.0
    assert detector.freq_max == 5000.0
    assert detector.min_hit_interval == 0.3
    assert detector.max_silence_gap == 3.0
    assert detector.min_rally_duration == 1.5
    assert detector.motion_threshold == 0.08
    assert detector.visual_fps == 10
    assert detector.mock_mode is True

def test_audio_visual_detector_mock_mode():
    """Verify deterministic mock_mode output."""
    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
        tmp.write(b"dummy video data")
        tmp_path = tmp.name

    try:
        detector = AudioVisualRallyDetector(mock_mode=True)
        rallies = detector.analyze_video(tmp_path)
        assert len(rallies) == 3
        assert rallies[0] == {"id": 1, "start": 15.0, "end": 28.5, "duration": 13.5, "confidence": 0.95}
        assert rallies[1] == {"id": 2, "start": 45.2, "end": 62.0, "duration": 16.8, "confidence": 0.92}
        assert rallies[2] == {"id": 3, "start": 80.0, "end": 98.4, "duration": 18.4, "confidence": 0.98}
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)

def test_butterworth_bandpass_filter_and_peak_detection():
    """Verify SciPy Butterworth bandpass filtering (2kHz-6kHz) and peak detection on synthetic signal."""
    sr = 16000
    duration = 10.0
    detector = AudioVisualRallyDetector(sample_rate=sr, freq_min=2000.0, freq_max=6000.0)

    # Generate signal with 3.5kHz hit pulse at t=2.0s and low-frequency noise (300Hz)
    t = np.linspace(0, duration, int(sr * duration), endpoint=False)
    noise_300hz = 0.5 * np.sin(2 * np.pi * 300 * t)  # Low frequency noise (should be filtered out)

    # 3.5kHz hit impulse at t=2.0s
    audio = noise_300hz.copy()
    idx = int(2.0 * sr)
    decay_t = np.linspace(0, 0.02, int(0.02 * sr), endpoint=False)
    hit_wave = 1.0 * np.exp(-decay_t / 0.004) * np.sin(2 * np.pi * 3500 * decay_t)
    audio[idx:idx + len(hit_wave)] += hit_wave

    peaks = detector._detect_audio_hits(audio, sr)
    assert len(peaks) > 0
    # Confirm peak is detected close to 2.0s
    assert any(abs(p - 2.0) < 0.05 for p in peaks)

def test_synthetic_audio_generator_and_fsm_rally_detection():
    """Verify end-to-end synthetic audio generation and rally FSM segmentation."""
    sr = 16000
    detector = AudioVisualRallyDetector(sample_rate=sr)

    # Generate synthetic audio with rallies at (10.0, 20.0) and (40.0, 55.0)
    target_rallies = [(10.0, 20.0), (40.0, 55.0)]
    audio, true_hits = detector.generate_synthetic_audio(duration=70.0, sr=sr, rally_timestamps=target_rallies)

    hits = detector._detect_audio_hits(audio, sr)
    assert len(hits) >= 4  # Multiple hit peaks detected

    detected_rallies = detector._run_rally_fsm(hits)
    assert len(detected_rallies) == 2
    # Verify detected rally timestamps align with synthetic targets
    assert abs(detected_rallies[0]["start"] - 10.0) < 0.5
    assert abs(detected_rallies[0]["end"] - 20.0) < 1.0
    assert abs(detected_rallies[1]["start"] - 40.0) < 0.5
    assert abs(detected_rallies[1]["end"] - 55.0) < 1.0

def test_fsm_short_rally_filtering():
    """Verify that rallies shorter than min_rally_duration (2.0s) are filtered out by FSM."""
    detector = AudioVisualRallyDetector(min_rally_duration=2.0)
    # 2 hits separated by 0.5s -> total duration ~0.5s + 0.5s = 1.0s < 2.0s
    hit_peaks = np.array([10.0, 10.5])
    rallies = detector._run_rally_fsm(hit_peaks)
    assert len(rallies) == 0

def test_fsm_silence_gap_cutoff():
    """Verify that silence gaps > max_silence_gap (2.5s) split rallies."""
    detector = AudioVisualRallyDetector(max_silence_gap=2.5, min_rally_duration=2.0)
    # Rally 1 hits: 10.0, 11.0, 12.0 (duration 10.0 to 12.5 = 2.5s)
    # Gap: 10 seconds (12.0 to 22.0)
    # Rally 2 hits: 22.0, 23.0, 24.0, 25.0 (duration 22.0 to 25.5 = 3.5s)
    hit_peaks = np.array([10.0, 11.0, 12.0, 22.0, 23.0, 24.0, 25.0])
    rallies = detector._run_rally_fsm(hit_peaks)
    assert len(rallies) == 2
    assert rallies[0]["start"] == 10.0
    assert rallies[0]["end"] == 12.5
    assert rallies[1]["start"] == 22.0
    assert rallies[1]["end"] == 25.5

def test_visual_only_fallback():
    """Verify visual-only fallback algorithm over motion timestamps and energy series."""
    detector = AudioVisualRallyDetector(motion_threshold=0.05, min_rally_duration=2.0, max_silence_gap=2.5)
    
    # 10 FPS motion timeline for 10 seconds
    timestamps = np.linspace(0, 10.0, 100)
    energies = np.zeros(100)
    
    # Active motion between t=2.0s and t=7.0s (index 20 to 70)
    energies[20:70] = 0.15

    rallies = detector._run_visual_only_fsm(timestamps, energies)
    assert len(rallies) == 1
    assert abs(rallies[0]["start"] - 2.0) < 0.2
    assert abs(rallies[0]["end"] - 7.0) < 0.2


def test_court_aware_detector_filters_hits_without_court_motion():
    detector = CourtAwareRallyDetector(
        motion_threshold=0.01,
        serve_lead=0.25,
        landing_delay=0.65,
        residual_motion_window=1.0,
        max_serve_lookback=2.0,
        trajectory_refiner=None,
    )
    hit_peaks = np.array([1.0, 2.0, 10.0, 11.0])
    timestamps = np.arange(0.0, 14.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.7) & (timestamps <= 2.3)] = 0.035

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 1
    assert rallies[0]["start"] == pytest.approx(0.45, abs=0.15)
    assert rallies[0]["end"] >= 2.65 - 0.05
    assert rallies[0]["first_hit"] == pytest.approx(1.0)
    assert rallies[0]["last_hit"] == pytest.approx(2.0)
    assert 0.48 <= rallies[0]["confidence"] <= 0.99


def test_court_aware_keeps_rally_across_missed_mid_hits_with_motion():
    """Missed audio hits mid-rally must not hard-cut while court motion stays live."""
    detector = CourtAwareRallyDetector(
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.6,
        max_silence_gap=3.0,
        max_bridge_gap=5.5,
        residual_motion_window=1.5,
        trajectory_refiner=None,
    )
    # Strong hits at the ends; a 4.2s audio hole in the middle.
    hit_peaks = np.array([1.0, 1.8, 2.5, 6.7, 7.4, 8.1])
    timestamps = np.arange(0.0, 10.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.6) & (timestamps <= 8.6)] = 0.04

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 1
    assert rallies[0]["first_hit"] == pytest.approx(1.0)
    assert rallies[0]["last_hit"] == pytest.approx(8.1)
    assert rallies[0]["start"] < 1.0
    assert rallies[0]["end"] > 8.1


def test_court_aware_still_splits_true_dead_ball_gap():
    """A long quiet gap with no court motion remains two separate rallies."""
    detector = CourtAwareRallyDetector(
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.5,
        max_silence_gap=3.0,
        max_bridge_gap=5.5,
        residual_motion_window=1.0,
        trajectory_refiner=None,
    )
    hit_peaks = np.array([1.0, 1.8, 2.4, 12.0, 12.8, 13.5])
    timestamps = np.arange(0.0, 15.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.6) & (timestamps <= 3.0)] = 0.04
    energies[(timestamps >= 11.5) & (timestamps <= 14.0)] = 0.04

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 2
    assert rallies[0]["last_hit"] == pytest.approx(2.4)
    assert rallies[1]["first_hit"] == pytest.approx(12.0)


def test_court_aware_soft_bridge_hit_glues_fragments():
    """A weak mid-rally hit with mild court motion should glue two fragments."""
    detector = CourtAwareRallyDetector(
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.5,
        max_silence_gap=2.8,
        max_bridge_gap=5.0,
        residual_motion_window=1.0,
        trajectory_refiner=None,
    )
    # Primary hits need high motion; soft bridge hit sits in a quieter pocket.
    hit_peaks = np.array([1.0, 1.7, 4.2, 6.5, 7.2])
    timestamps = np.arange(0.0, 9.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.6) & (timestamps <= 2.2)] = 0.045
    energies[(timestamps >= 3.8) & (timestamps <= 4.6)] = 0.018  # soft bridge support
    energies[(timestamps >= 6.1) & (timestamps <= 7.8)] = 0.045

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 1
    assert rallies[0]["first_hit"] == pytest.approx(1.0)
    assert rallies[0]["last_hit"] == pytest.approx(7.2)


def test_court_aware_expands_late_serve_and_early_end():
    """Serve lead-in and residual landing motion must be kept in the segment."""
    detector = CourtAwareRallyDetector(
        motion_threshold=0.01,
        serve_lead=0.3,
        landing_delay=0.5,
        max_serve_lookback=3.5,
        residual_motion_window=2.5,
        trajectory_refiner=None,
    )
    # Hits only in the middle; players already moving before first hit and after last.
    hit_peaks = np.array([5.0, 5.8, 6.6])
    timestamps = np.arange(0.0, 12.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 2.5) & (timestamps <= 9.0)] = 0.04

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 1
    assert rallies[0]["start"] <= 2.4
    assert rallies[0]["end"] >= 8.8


def test_court_aware_merges_high_motion_gap_between_fragments():
    """Adjacent fragments separated by short active-motion gaps should merge."""
    detector = CourtAwareRallyDetector(
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.4,
        max_silence_gap=2.5,
        max_bridge_gap=6.0,
        residual_motion_window=1.0,
        trajectory_refiner=None,
    )
    hit_peaks = np.array([1.0, 1.6, 2.2, 6.0, 6.6, 7.2])
    timestamps = np.arange(0.0, 9.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.6) & (timestamps <= 7.8)] = 0.04

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 1
    assert rallies[0]["first_hit"] == pytest.approx(1.0)
    assert rallies[0]["last_hit"] == pytest.approx(7.2)


def test_court_aware_detector_normalizes_custom_roi():
    roi = [[0.2, 0.3], [0.8, 0.3], [0.95, 0.95], [0.05, 0.95]]
    detector = CourtAwareRallyDetector(court_roi=roi)
    assert detector.court_roi == tuple(tuple(point) for point in roi)


def test_analyze_request_rejects_degenerate_court_roi():
    with pytest.raises(Exception):
        AnalyzeRequest(court_roi=[
            {"x": 0.1, "y": 0.1},
            {"x": 0.2, "y": 0.1},
            {"x": 0.3, "y": 0.1},
            {"x": 0.4, "y": 0.1},
        ])


def test_tracknet_refiner_expands_boundaries_only_for_moving_trajectory(tmp_path, monkeypatch):
    model_path = tmp_path / "tracknet.pt"
    model_path.write_bytes(b"placeholder")
    refiner = TrackNetV3BoundaryRefiner(model_path=str(model_path))
    rallies = [{
        "start": 8.5,
        "end": 12.4,
        "first_hit": 10.0,
        "last_hit": 12.0,
        "confidence": 0.9,
    }]
    points = [
        *[
            TrajectoryPoint(8.0 + index * 0.2, 0.30 + index * 0.025, 0.50 - index * 0.01, 0.8)
            for index in range(10)
        ],
        TrajectoryPoint(11.95, 0.45, 0.35, 0.8),
        TrajectoryPoint(12.05, 0.50, 0.45, 0.8),
        TrajectoryPoint(12.15, 0.56, 0.60, 0.8),
        TrajectoryPoint(12.25, 0.60, 0.72, 0.8),
    ]
    monkeypatch.setattr(refiner, "_read_window_frames", lambda *_: (30.0, [(0.0, [])]))
    monkeypatch.setattr(refiner, "_predict_points", lambda *_: points)

    result = refiner.refine("match.mp4", rallies, CourtAwareRallyDetector.DEFAULT_COURT_ROI)

    assert result[0]["start"] == pytest.approx(7.7)
    assert result[0]["end"] == pytest.approx(12.5)


def test_tracknet_refiner_is_noop_without_weights(tmp_path):
    refiner = TrackNetV3BoundaryRefiner(model_path=str(tmp_path / "missing.pt"))
    rallies = [{"start": 1.0, "end": 2.0}]

    assert refiner.refine("match.mp4", rallies, CourtAwareRallyDetector.DEFAULT_COURT_ROI) == rallies


def test_court_aware_refinement_preserves_gap_before_next_serve():
    class ExtendingRefiner:
        @staticmethod
        def refine(_video_path, rallies, _court_roi):
            result = [dict(item) for item in rallies]
            result[0]["end"] = 10.2
            return result

    detector = CourtAwareRallyDetector(trajectory_refiner=ExtendingRefiner())
    rallies = [
        {"start": 2.0, "end": 8.0, "last_hit": 7.5},
        {"start": 10.0, "end": 15.0, "first_hit": 10.5},
    ]

    refined = detector._refine_rally_boundaries("match.mp4", rallies)

    assert refined[0]["end"] == pytest.approx(9.55)
    assert refined[1]["start"] == pytest.approx(10.0)


def test_court_aware_refinement_keeps_original_fragments_mergeable():
    class ExtendingRefiner:
        @staticmethod
        def refine(_video_path, rallies, _court_roi):
            result = [dict(item) for item in rallies]
            result[0]["end"] = 10.2
            return result

    detector = CourtAwareRallyDetector(trajectory_refiner=ExtendingRefiner())
    rallies = [
        {"start": 2.0, "end": 9.7, "last_hit": 9.0},
        {"start": 10.0, "end": 15.0, "first_hit": 10.2},
    ]

    refined = detector._refine_rally_boundaries("match.mp4", rallies)

    assert refined[0]["end"] == pytest.approx(10.2)


def test_trajectory_series_flight_helpers():
    points = [
        TrajectoryPoint(1.0 + index * 0.1, 0.3 + index * 0.02, 0.4, 0.9)
        for index in range(8)
    ]
    series = TrajectorySeries.from_points(points, fps=30.0)
    assert series.density(1.0, 2.0) > 0
    assert series.has_flight(1.0, 2.0)
    assert series.flight_fraction(1.0, 2.0) > 0.3
    assert series.earliest_flight_start(1.8, lookback=1.5) is not None
    assert series.latest_flight_end(1.2, lookahead=1.5) is not None


def test_multimodal_scorer_merges_when_trajectory_continues():
    """Free rule scorer: mid-gap flight + hits should glue two coarse fragments."""
    scorer = MultimodalRallyScorer(merge_gap=4.0, serve_lookback=2.0, land_lookahead=1.5)
    rallies = [
        {"start": 1.0, "end": 3.0, "first_hit": 1.2, "last_hit": 2.6, "confidence": 0.8},
        {"start": 4.8, "end": 7.5, "first_hit": 5.0, "last_hit": 7.0, "confidence": 0.8},
    ]
    # Continuous moving shuttle across the short audio hole, with a bridge hit.
    points = [
        TrajectoryPoint(1.0 + index * 0.12, 0.25 + (index % 5) * 0.03, 0.35 + (index % 4) * 0.02, 0.85)
        for index in range(55)
    ]
    series = TrajectorySeries.from_points(points)
    hits = np.array([1.2, 2.0, 2.6, 3.9, 5.0, 6.0, 7.0])
    motion_t = np.arange(0.0, 10.0, 0.1)
    motion_e = np.full_like(motion_t, 0.03, dtype=float)

    refined = scorer.refine(rallies, hits, motion_t, motion_e, series)

    assert len(refined) == 1
    assert refined[0]["start"] <= 1.2
    assert refined[0]["end"] >= 7.0
    assert 0.0 <= refined[0]["confidence"] <= 1.0


def test_multimodal_scorer_keeps_true_dead_gap_split():
    scorer = MultimodalRallyScorer(merge_gap=4.0)
    rallies = [
        {"start": 1.0, "end": 3.0, "first_hit": 1.2, "last_hit": 2.6, "confidence": 0.8},
        {"start": 12.0, "end": 15.0, "first_hit": 12.3, "last_hit": 14.5, "confidence": 0.8},
    ]
    points = [
        *[TrajectoryPoint(1.0 + index * 0.12, 0.3 + index * 0.02, 0.4, 0.8) for index in range(10)],
        *[TrajectoryPoint(12.0 + index * 0.12, 0.3 + index * 0.02, 0.4, 0.8) for index in range(10)],
    ]
    series = TrajectorySeries.from_points(points)
    hits = np.array([1.2, 2.0, 2.6, 12.3, 13.0, 14.5])
    motion_t = np.arange(0.0, 16.0, 0.1)
    motion_e = np.full_like(motion_t, 0.002, dtype=float)
    motion_e[(motion_t >= 1.0) & (motion_t <= 3.2)] = 0.04
    motion_e[(motion_t >= 12.0) & (motion_t <= 15.2)] = 0.04

    refined = scorer.refine(rallies, hits, motion_t, motion_e, series)

    assert len(refined) == 2


def test_multimodal_scorer_splits_overlong_blob():
    scorer = MultimodalRallyScorer(max_rally_duration=20.0)
    rallies = [{
        "start": 0.0,
        "end": 60.0,
        "first_hit": 1.0,
        "last_hit": 58.0,
        "confidence": 0.8,
    }]
    # Two dense hit clusters separated by a long dead gap.
    hits = np.concatenate([
        np.arange(1.0, 12.0, 0.8),
        np.arange(40.0, 55.0, 0.8),
    ])
    series = TrajectorySeries.from_points([])
    motion_t = np.arange(0.0, 60.0, 0.2)
    motion_e = np.full_like(motion_t, 0.01, dtype=float)

    refined = scorer.refine(rallies, hits, motion_t, motion_e, series)

    assert len(refined) >= 2
    assert all(r["end"] - r["start"] < 35 for r in refined)


def test_tracknet_default_mode_is_boundary():
    refiner = TrackNetV3BoundaryRefiner()
    assert refiner.mode == "boundary"


def test_tracknet_candidate_windows_cover_full_span():
    refiner = TrackNetV3BoundaryRefiner(mode="candidate", candidate_pad_pre=1.0, candidate_pad_post=1.5)
    rallies = [{"start": 10.0, "end": 20.0, "first_hit": 11.0, "last_hit": 19.0}]
    windows = refiner._candidate_windows(rallies)
    assert len(windows) == 1
    assert windows[0][0] <= 9.0
    assert windows[0][1] >= 21.0
