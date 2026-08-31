import os
import tempfile
import pytest
import numpy as np
import scipy.signal as signal

from backend.app.detectors.base import BaseRallyDetector
from backend.app.detectors.audio_visual import AudioVisualRallyDetector
from backend.app.detectors.court_aware import CourtAwareRallyDetector
from backend.app.detectors.highlight_filter import StrictHighlightFilter
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
        highlight_mode="standard",
        motion_threshold=0.01,
        serve_lead=0.25,
        landing_delay=0.65,
        residual_motion_window=1.0,
        max_serve_lookback=2.0,
        trajectory_refiner=None,
        highlight_filter=None,
    )
    hit_peaks = np.array([1.0, 1.5, 2.0, 10.0, 10.5, 11.0])
    timestamps = np.arange(0.0, 14.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.7) & (timestamps <= 2.3)] = 0.035

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 1
    assert rallies[0]["first_hit"] == pytest.approx(1.0)
    assert rallies[0]["last_hit"] == pytest.approx(2.0)
    assert 0.48 <= rallies[0]["confidence"] <= 0.99


def test_court_aware_keeps_rally_across_missed_mid_hits_with_motion():
    """Missed audio hits mid-rally must not hard-cut while court motion stays live."""
    detector = CourtAwareRallyDetector(
        highlight_mode="standard",
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.6,
        max_silence_gap=3.0,
        max_bridge_gap=5.5,
        residual_motion_window=1.5,
        trajectory_refiner=None,
        highlight_filter=None,
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
        highlight_mode="standard",
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.5,
        max_silence_gap=3.0,
        max_bridge_gap=5.5,
        residual_motion_window=1.0,
        trajectory_refiner=None,
        highlight_filter=None,
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
        highlight_mode="standard",
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.5,
        max_silence_gap=2.8,
        max_bridge_gap=5.0,
        residual_motion_window=1.0,
        trajectory_refiner=None,
        highlight_filter=None,
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
        highlight_mode="standard",
        motion_threshold=0.01,
        serve_lead=0.3,
        landing_delay=0.5,
        max_serve_lookback=3.5,
        residual_motion_window=2.5,
        trajectory_refiner=None,
        highlight_filter=None,
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
        highlight_mode="standard",
        motion_threshold=0.01,
        serve_lead=0.2,
        landing_delay=0.4,
        max_silence_gap=2.5,
        max_bridge_gap=6.0,
        residual_motion_window=1.0,
        trajectory_refiner=None,
        highlight_filter=None,
    )
    hit_peaks = np.array([1.0, 1.6, 2.2, 6.0, 6.6, 7.2])
    timestamps = np.arange(0.0, 9.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.6) & (timestamps <= 7.8)] = 0.04

    rallies = detector._run_rally_fsm(hit_peaks, timestamps, energies)

    assert len(rallies) == 1
    assert rallies[0]["first_hit"] == pytest.approx(1.0)
    assert rallies[0]["last_hit"] == pytest.approx(7.2)


def test_strict_mode_default_and_filters_sparse_segments():
    detector = CourtAwareRallyDetector(trajectory_refiner=None)
    assert detector.strict is True
    assert detector.highlight_mode == "strict"

    # Dense exchange should survive.
    dense_hits = np.array([1.0, 1.6, 2.2, 2.9, 3.5])
    timestamps = np.arange(0.0, 8.0, 0.1)
    energies = np.full_like(timestamps, 0.002, dtype=float)
    energies[(timestamps >= 0.8) & (timestamps <= 4.0)] = 0.04
    rallies = detector._run_rally_fsm(dense_hits, timestamps, energies)
    assert len(rallies) == 1
    assert rallies[0]["end"] - rallies[0]["start"] < 6.0
    # Anchored near hits, not long dead padding.
    assert rallies[0]["start"] >= 0.0
    assert abs(rallies[0]["start"] - (1.0 - 0.85)) < 0.4


def test_strict_highlight_filter_splits_and_drops_dead_ball():
    filt = StrictHighlightFilter(max_hit_silence=2.2, min_hits=3, max_duration=20.0)
    rallies = [{"start": 0.0, "end": 60.0, "first_hit": 1.0, "last_hit": 55.0, "confidence": 0.8}]
    hits = np.concatenate([
        np.arange(1.0, 10.0, 0.7),
        np.arange(40.0, 50.0, 0.7),
    ])
    out = filt.apply(rallies, hits)
    assert len(out) >= 2
    assert all(item["end"] - item["start"] < 25 for item in out)
    # Single-touch noise is dropped.
    noise = filt.apply(
        [{"start": 1.0, "end": 2.0, "first_hit": 1.2, "last_hit": 1.2, "confidence": 0.7}],
        np.array([1.2]),
    )
    assert noise == []


def test_strict_highlight_filter_keeps_mid_rally_when_motion_live():
    """Hit silence alone must not cut if court motion stays elevated."""
    filt = StrictHighlightFilter(max_hit_silence=2.2, motion_bridge_silence=3.8, min_hits=3)
    rallies = [{"start": 1.0, "end": 10.0, "first_hit": 1.2, "last_hit": 9.0, "confidence": 0.8}]
    # 3.0s hole between hits 3.0 and 6.0, but motion remains active.
    hits = np.array([1.2, 1.9, 2.6, 3.0, 6.0, 6.7, 7.4, 8.2, 9.0])
    motion_t = np.arange(0.0, 12.0, 0.1)
    motion_e = np.full_like(motion_t, 0.002, dtype=float)
    motion_e[(motion_t >= 1.0) & (motion_t <= 9.5)] = 0.04

    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["first_hit"] <= 1.3
    assert out[0]["last_hit"] >= 8.8


def test_strict_highlight_filter_drops_idle_waiting_clip():
    filt = StrictHighlightFilter(min_hits=3, min_duration=1.5)
    rallies = [{"start": 10.0, "end": 15.5, "first_hit": 10.5, "last_hit": 14.8, "confidence": 0.55}]
    hits = np.array([10.5, 11.4, 12.2, 13.1, 14.8])
    motion_t = np.arange(0.0, 20.0, 0.1)
    motion_e = np.full_like(motion_t, 0.002, dtype=float)  # mostly idle

    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert out == []


def test_strict_highlight_filter_keeps_two_hit_net_fault():
    """Serve-into-net (2 hits, ~3s) must survive min_hits=2."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    rallies = [{"start": 86.0, "end": 89.5, "first_hit": 86.5, "last_hit": 86.8, "confidence": 0.8}]
    hits = np.array([86.5, 86.8])
    motion_t = np.arange(80.0, 95.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 86.0) & (motion_t <= 89.0)] = 0.025
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["first_hit"] == pytest.approx(86.5, abs=0.1)
    assert out[0]["end"] - out[0]["start"] < 5.0


def test_strict_highlight_filter_drops_camera_open_walk_in():
    """Walking tagged as a rally at t=0 (user-sample 0:00–0:04) is dropped."""
    filt = StrictHighlightFilter()
    rallies = [{"start": 0.0, "end": 4.6, "first_hit": 0.6, "last_hit": 4.08, "confidence": 0.99}]
    hits = np.array([0.6, 1.45, 1.9, 2.15, 3.14, 3.82, 4.08])
    motion_t = np.arange(0.0, 12.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    motion_e[(motion_t >= 0.0) & (motion_t <= 4.5)] = 0.06
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert out == []


def test_strict_highlight_filter_splits_dead_ball_two_second_gap():
    """~2.1s no-hit + low motion between points must split, not glue."""
    filt = StrictHighlightFilter(max_hit_silence=2.0, motion_bridge_silence=4.2, min_hits=2)
    rallies = [{"start": 10.0, "end": 28.0, "first_hit": 11.0, "last_hit": 25.0, "confidence": 0.9}]
    hits = np.array([11.0, 12.0, 13.0, 16.2, 18.4, 19.6, 22.6, 23.2, 24.0])
    motion_t = np.arange(8.0, 30.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    motion_e[(motion_t >= 10.5) & (motion_t <= 13.4)] = 0.04
    motion_e[(motion_t >= 22.2) & (motion_t <= 24.5)] = 0.04
    # Walking in the 13.4–22.2 hole stays below play motion.
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 2
    assert out[0]["last_hit"] <= 16.5
    assert out[-1]["first_hit"] >= 18.0


def test_strict_highlight_filter_trims_applause_tail():
    filt = StrictHighlightFilter(min_hits=2)
    # Rally hits then handshake clapping every ~0.35s.
    rally_hits = [1.2, 1.9, 2.6, 3.4, 4.8, 6.1, 7.5]
    clap = list(np.arange(9.2, 16.0, 0.33))
    hits = np.array(rally_hits + clap)
    rallies = [{"start": 0.5, "end": 16.5, "first_hit": 1.2, "last_hit": 15.8, "confidence": 0.9}]
    motion_t = np.arange(0.0, 18.0, 0.1)
    motion_e = np.full_like(motion_t, 0.03, dtype=float)
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 1
    assert out[0]["end"] < 12.0
    assert out[0]["last_hit"] < 10.5


def test_strict_highlight_filter_keeps_far_court_serve_like_rally():
    """Low pixel-motion far-court rally with an isolated serve must survive."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    rallies = [{"start": 112.0, "end": 124.0, "first_hit": 113.0, "last_hit": 123.5, "confidence": 0.8}]
    hits = np.array([100.0, 113.0, 114.3, 115.8, 118.5, 120.4, 120.7, 123.5])
    motion_t = np.arange(90.0, 130.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 112.5) & (motion_t <= 124.0)] = 0.012
    motion_e[(motion_t >= 120.2) & (motion_t <= 124.0)] = 0.030
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 1
    main = min(out, key=lambda item: abs(float(item["first_hit"]) - 113.0))
    assert main["first_hit"] == pytest.approx(113.0, abs=0.2)
    assert main["last_hit"] >= 118.0


def test_strict_highlight_filter_drops_adjacent_court_stub():
    """2–8s walking / adjacent-court stub without serve isolation is dropped."""
    filt = StrictHighlightFilter(min_hits=2)
    rallies = [{"start": 217.0, "end": 224.0, "first_hit": 218.1, "last_hit": 223.3, "confidence": 0.88}]
    hits = np.array([216.2, 218.14, 219.40, 219.70, 220.72, 221.82, 222.95, 223.31])
    motion_t = np.arange(210.0, 230.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 217.5) & (motion_t <= 223.5)] = 0.022
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert out == []


def test_strict_highlight_filter_does_not_cut_smash_as_applause():
    """Mid-rally smash ISIs must not trigger applause tail cut (GT4)."""
    filt = StrictHighlightFilter(min_hits=2)
    rally_hits = [72.66, 73.65, 74.79, 75.48, 75.75, 76.44, 76.71, 76.97, 77.79, 79.16]
    hits = np.array([67.74] + rally_hits)
    rallies = [{"start": 71.6, "end": 80.0, "first_hit": 72.66, "last_hit": 79.16, "confidence": 0.9}]
    motion_t = np.arange(60.0, 90.0, 0.1)
    motion_e = np.full_like(motion_t, 0.006, dtype=float)
    motion_e[(motion_t >= 76.2) & (motion_t <= 80.0)] = 0.032
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["last_hit"] >= 77.5


def test_strict_highlight_filter_requires_burst_or_serve_for_two_blips():
    """Two 2–6 kHz blips without a serve gap or ROI burst are not a rally."""
    filt = StrictHighlightFilter(min_hits=2)
    rallies = [{"start": 30.0, "end": 33.0, "first_hit": 30.4, "last_hit": 32.5, "confidence": 0.6}]
    hits = np.array([28.0, 30.4, 32.5, 40.0])
    motion_t = np.arange(20.0, 45.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert out == []


def test_strict_highlight_filter_drops_no_burst_walking_cluster():
    """4+ 2–6 kHz squeaks with no ROI swing are walking, not a rally."""
    filt = StrictHighlightFilter(min_hits=2)
    rallies = [{"start": 90.0, "end": 96.0, "first_hit": 91.0, "last_hit": 95.2, "confidence": 0.7}]
    hits = np.array([80.0, 91.0, 92.1, 93.4, 94.0, 95.2, 110.0])
    motion_t = np.arange(70.0, 120.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert out == []


def test_strict_highlight_filter_trims_post_land_squeaks():
    """Trailing weak hits after a 1.3s+ hole must not extend last_hit (over-run)."""
    filt = StrictHighlightFilter(min_hits=2, land_pad=0.60)
    rally_hits = [230.1, 231.4, 231.9, 232.7, 233.4]
    tail = [234.8, 236.5, 238.1, 239.7]
    hits = np.array([220.0] + rally_hits + tail)
    rallies = [{"start": 229.0, "end": 241.0, "first_hit": 230.1, "last_hit": 239.7, "confidence": 0.9}]
    motion_t = np.arange(220.0, 245.0, 0.1)
    motion_e = np.full_like(motion_t, 0.006, dtype=float)
    motion_e[(motion_t >= 230.0) & (motion_t <= 233.6)] = 0.028
    motion_e[(motion_t >= 234.5) & (motion_t <= 240.0)] = 0.008
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 1
    main = min(out, key=lambda item: abs(float(item["first_hit"]) - 230.1))
    assert main["last_hit"] <= 234.0
    assert main["end"] <= 235.0


def test_strict_highlight_filter_remerges_two_hit_then_continuation():
    """2-hit fragment then 4-hit after a 0.54s pad gap / 2.19s quiet hole (GT7)."""
    filt = StrictHighlightFilter(max_hit_silence=1.85, motion_bridge_silence=4.2, min_hits=2)
    rallies = [
        {"start": 147.254, "end": 149.914, "first_hit": 148.304, "last_hit": 149.314, "confidence": 0.6},
        {"start": 150.458, "end": 156.760, "first_hit": 151.508, "last_hit": 156.160, "confidence": 0.9},
    ]
    hits = np.array([142.175, 148.304, 149.314, 151.508, 153.063, 154.528, 156.160])
    motion_t = np.arange(140.0, 160.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 151.3) & (motion_t <= 156.4)] = 0.030
    out = filt._remerge_over_split(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["first_hit"] == pytest.approx(148.304, abs=0.05)
    assert out[0]["last_hit"] == pytest.approx(156.160, abs=0.05)


def test_strict_highlight_filter_does_not_glue_walk_in_to_rally():
    """Camera-open walking must not rejoin the first real rally (Kaja)."""
    filt = StrictHighlightFilter(max_hit_silence=1.85, motion_bridge_silence=4.2, min_hits=2)
    rallies = [
        {"start": 0.0, "end": 3.268, "first_hit": 0.5, "last_hit": 2.818, "confidence": 0.8},
        {"start": 3.747, "end": 9.342, "first_hit": 4.797, "last_hit": 8.892, "confidence": 0.88},
    ]
    hits = np.array([0.5, 0.889, 1.266, 1.646, 2.818, 4.797, 5.14, 5.523, 5.897, 7.605, 8.892])
    motion_t = np.arange(0.0, 12.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 4.5) & (motion_t <= 9.2)] = 0.030
    out = filt._remerge_over_split(rallies, hits, motion_t, motion_e)
    assert len(out) == 2
    assert out[0]["last_hit"] == pytest.approx(2.818, abs=0.05)
    assert out[1]["first_hit"] == pytest.approx(4.797, abs=0.05)


def test_strict_highlight_filter_does_not_glue_gt1_gt2_style_pair():
    """GT1+GT2 projected ~16s must stay two clips despite a 0.52s pad gap."""
    filt = StrictHighlightFilter(max_hit_silence=1.85, motion_bridge_silence=4.2, min_hits=2)
    rallies = [
        {"start": 10.667, "end": 16.617, "first_hit": 11.717, "last_hit": 16.167, "confidence": 0.7},
        {"start": 17.137, "end": 27.312, "first_hit": 18.187, "last_hit": 26.712, "confidence": 0.75},
    ]
    hits = np.array([4.08, 11.717, 16.167, 18.187, 19.461, 20.755, 22.556, 23.129, 24.028, 24.748, 25.113, 25.468, 26.712])
    motion_t = np.arange(0.0, 32.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 11.5) & (motion_t <= 16.4)] = 0.028
    motion_e[(motion_t >= 20.5) & (motion_t <= 25.2)] = 0.032
    out = filt._remerge_over_split(rallies, hits, motion_t, motion_e)
    assert len(out) == 2
    assert out[0]["last_hit"] == pytest.approx(16.167, abs=0.05)
    assert out[1]["first_hit"] == pytest.approx(18.187, abs=0.05)


def test_strict_highlight_filter_drops_two_hit_walking_stub():
    """2-hit leftover after walking (pre-active high, iso < 4s) is dropped."""
    filt = StrictHighlightFilter(min_hits=2)
    rally = {"start": 163.3, "end": 166.4, "first_hit": 164.35, "last_hit": 165.77, "confidence": 0.8}
    hits = np.array([161.62, 164.35, 165.77, 180.0])
    motion_t = np.arange(155.0, 175.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 161.5) & (motion_t <= 166.0)] = 0.040
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is False


def test_strict_highlight_filter_drops_eight_second_low_isolation_blob():
    """8–11s 4–6 hit pickup blob with no serve gap is dropped (user-sample 4:46)."""
    filt = StrictHighlightFilter(min_hits=2)
    rally = {"start": 286.5, "end": 295.6, "first_hit": 286.9, "last_hit": 295.2, "confidence": 0.85}
    hits = np.array([285.7, 286.9, 288.6, 290.8, 292.5, 294.7, 295.2])
    motion_t = np.arange(270.0, 310.0, 0.1)
    motion_e = np.full_like(motion_t, 0.006, dtype=float)
    motion_e[(motion_t >= 286.5) & (motion_t <= 295.5)] = 0.028
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is False


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
    scorer = MultimodalRallyScorer(merge_gap=4.0, serve_lookback=1.2, land_lookahead=1.2, min_hit_density=0.25)
    rallies = [
        {"start": 1.0, "end": 3.2, "first_hit": 1.2, "last_hit": 3.0, "confidence": 0.8},
        {"start": 4.6, "end": 7.5, "first_hit": 4.8, "last_hit": 7.0, "confidence": 0.8},
    ]
    # Continuous moving shuttle across the short audio hole, with a bridge hit.
    points = [
        TrajectoryPoint(1.0 + index * 0.12, 0.25 + (index % 5) * 0.03, 0.35 + (index % 4) * 0.02, 0.85)
        for index in range(55)
    ]
    series = TrajectorySeries.from_points(points)
    hits = np.array([1.2, 1.8, 2.4, 3.0, 3.9, 4.8, 5.5, 6.2, 7.0])
    motion_t = np.arange(0.0, 10.0, 0.1)
    motion_e = np.full_like(motion_t, 0.03, dtype=float)

    refined = scorer.refine(rallies, hits, motion_t, motion_e, series)

    assert len(refined) == 1
    assert refined[0]["start"] <= 1.2
    assert refined[0]["end"] >= 7.0
    assert 0.0 <= refined[0]["confidence"] <= 1.0


def test_multimodal_scorer_keeps_true_dead_gap_split():
    scorer = MultimodalRallyScorer(merge_gap=4.0, min_hit_density=0.25)
    rallies = [
        {"start": 1.0, "end": 4.0, "first_hit": 1.2, "last_hit": 3.5, "confidence": 0.8},
        {"start": 12.0, "end": 15.5, "first_hit": 12.2, "last_hit": 15.0, "confidence": 0.8},
    ]
    points = [
        *[TrajectoryPoint(1.0 + index * 0.12, 0.3 + index * 0.02, 0.4, 0.8) for index in range(12)],
        *[TrajectoryPoint(12.0 + index * 0.12, 0.3 + index * 0.02, 0.4, 0.8) for index in range(12)],
    ]
    series = TrajectorySeries.from_points(points)
    hits = np.array([1.2, 2.0, 2.7, 3.5, 12.2, 13.0, 13.8, 15.0])
    motion_t = np.arange(0.0, 16.0, 0.1)
    motion_e = np.full_like(motion_t, 0.002, dtype=float)
    motion_e[(motion_t >= 1.0) & (motion_t <= 4.0)] = 0.04
    motion_e[(motion_t >= 12.0) & (motion_t <= 15.5)] = 0.04

    refined = scorer.refine(rallies, hits, motion_t, motion_e, series)

    assert len(refined) == 2


def test_multimodal_scorer_splits_overlong_blob():
    scorer = MultimodalRallyScorer(max_rally_duration=20.0, min_hit_density=0.25)
    rallies = [{
        "start": 0.0,
        "end": 60.0,
        "first_hit": 1.0,
        "last_hit": 58.0,
        "confidence": 0.8,
    }]
    # Two dense hit clusters separated by a long dead gap.
    hits = np.concatenate([
        np.arange(1.0, 12.0, 0.7),
        np.arange(40.0, 55.0, 0.7),
    ])
    series = TrajectorySeries.from_points([])
    motion_t = np.arange(0.0, 60.0, 0.2)
    motion_e = np.full_like(motion_t, 0.01, dtype=float)

    refined = scorer.refine(rallies, hits, motion_t, motion_e, series)

    assert len(refined) >= 2
    assert all(r["end"] - r["start"] < 30 for r in refined)


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


def test_strict_highlight_filter_unsticks_gt5_style_net_fault():
    """Walking glued onto a compact 2-hit net-fault must unstick (user-sample GT5)."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([72.66, 79.16, 80.13, 82.10, 83.27, 84.35, 85.71, 86.51, 86.81, 91.03])
    rallies = [
        {"start": 71.6, "end": 80.73, "first_hit": 72.66, "last_hit": 80.13, "confidence": 0.99},
        {"start": 81.05, "end": 87.41, "first_hit": 82.10, "last_hit": 86.81, "confidence": 0.99},
    ]
    motion_t = np.arange(70.0, 95.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 76.2) & (motion_t <= 80.0)] = 0.032
    motion_e[(motion_t >= 82.0) & (motion_t <= 87.0)] = 0.040
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert any(abs(float(r["last_hit"]) - 80.13) < 0.3 for r in out)
    net = [r for r in out if 85.4 <= float(r.get("first_hit", 0)) <= 87.0]
    assert len(net) == 1
    gs, ge = 86.0, 89.0
    ps, pe = float(net[0]["start"]), float(net[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3
    assert pe - ps < 5.0


def test_strict_highlight_filter_absorbs_landing_after_quiet_high_clear():
    """Absorb the landing hit after a 5.3s quiet high-clear; drop walking (GT19)."""
    filt = StrictHighlightFilter(max_hit_silence=1.85, motion_bridge_silence=4.2, min_hits=2)
    hits = np.array([
        377.97, 378.64, 378.90, 380.88, 386.15,
        387.56, 389.84, 390.18, 390.56, 391.63, 392.57, 393.97, 394.45, 395.00,
    ])
    rallies = [
        {"start": 376.92, "end": 381.48, "first_hit": 377.97, "last_hit": 380.88, "confidence": 0.9},
        {"start": 385.10, "end": 395.60, "first_hit": 386.15, "last_hit": 395.00, "confidence": 0.99},
    ]
    motion_t = np.arange(370.0, 400.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    motion_e[(motion_t >= 377.5) & (motion_t <= 381.2)] = 0.028
    motion_e[(motion_t >= 386.0) & (motion_t <= 386.5)] = 0.016
    motion_e[(motion_t >= 387.3) & (motion_t <= 395.2)] = 0.040
    out = filt.apply(rallies, hits, motion_t, motion_e)
    gs, ge = 379.0, 387.0

    def iou(r):
        ps, pe = float(r["start"]), float(r["end"])
        inter = max(0.0, min(ge, pe) - max(gs, ps))
        union = (ge - gs) + (pe - ps) - inter
        return inter / union if union else 0.0

    tps = [r for r in out if iou(r) >= 0.3]
    assert len(tps) == 1
    assert float(tps[0]["first_hit"]) <= 378.2
    assert float(tps[0]["last_hit"]) >= 385.5
    assert float(tps[0]["last_hit"]) <= 387.8


def test_strict_highlight_filter_lookback_skips_high_motion_prev_end():
    """High-motion previous ending (GT1) must not be stolen as GT2 first_hit."""
    filt = StrictHighlightFilter(min_hits=2)
    hits = np.array([11.72, 16.17, 18.19, 19.46, 20.75, 22.56, 24.03, 26.71])
    rallies = [
        {"start": 10.67, "end": 16.62, "first_hit": 11.72, "last_hit": 16.17, "confidence": 0.7},
        {"start": 17.14, "end": 27.31, "first_hit": 18.19, "last_hit": 26.71, "confidence": 0.75},
    ]
    motion_t = np.arange(8.0, 30.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 11.5) & (motion_t <= 16.4)] = 0.030
    motion_e[(motion_t >= 20.5) & (motion_t <= 25.2)] = 0.032
    out = filt.apply(rallies, hits, motion_t, motion_e)
    # Split 1-hit landing (iso~4.5, next~2.0) is adjacent-style leftover and
    # must not be absorbed as GT2's serve. Real GT1 rematches as 2-hit.
    assert not any(abs(float(r.get("first_hit", 0)) - 16.17) < 0.2 for r in out)
    assert any(abs(float(r.get("first_hit", 0)) - 18.19) < 0.2 for r in out)


def test_strict_highlight_filter_does_not_absorb_four_second_between_points():
    """GT12→GT13 ~4.8s hole must stay two rallies (not GT19 landing absorb)."""
    filt = StrictHighlightFilter(max_hit_silence=1.85, motion_bridge_silence=4.2, min_hits=2)
    hits = np.array([
        267.44, 269.28, 269.94, 270.40, 270.88,
        275.71, 277.02, 277.40, 278.42, 278.78, 279.94, 280.38, 280.68, 281.18,
    ])
    rallies = [
        {"start": 265.70, "end": 271.48, "first_hit": 267.44, "last_hit": 270.88, "confidence": 0.95},
        {"start": 274.37, "end": 281.78, "first_hit": 275.71, "last_hit": 281.18, "confidence": 0.85},
    ]
    motion_t = np.arange(260.0, 290.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 266.5) & (motion_t <= 271.2)] = 0.030
    motion_e[(motion_t >= 275.3) & (motion_t <= 281.5)] = 0.032
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 2
    assert any(abs(float(r["last_hit"]) - 270.88) < 0.4 for r in out)
    assert any(abs(float(r["first_hit"]) - 275.71) < 0.4 for r in out)


def test_strict_highlight_filter_still_trims_long_hole_compact_tail():
    """1.8s post-land hole + compact pickup tail must still trim (GT17)."""
    filt = StrictHighlightFilter(min_hits=2, land_pad=0.60)
    hits = np.array([320.0, 336.22, 337.20, 338.09, 340.53, 342.33, 343.28, 343.53, 360.0])
    rallies = [{"start": 334.07, "end": 344.13, "first_hit": 336.22, "last_hit": 343.53, "confidence": 0.99}]
    motion_t = np.arange(320.0, 350.0, 0.1)
    motion_e = np.full_like(motion_t, 0.006, dtype=float)
    motion_e[(motion_t >= 336.0) & (motion_t <= 341.0)] = 0.028
    motion_e[(motion_t >= 342.0) & (motion_t <= 344.0)] = 0.012
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 1
    main = min(out, key=lambda r: abs(float(r["first_hit"]) - 336.22))
    assert float(main["last_hit"]) <= 341.2


def test_strict_highlight_filter_keeps_isolated_far_court_one_hit():
    """1-hit far-court serve (iso ~3.4s, quiet land, no burst) must survive (GT11)."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([248.21, 251.57, 255.30, 256.52])
    rallies = [{"start": 250.52, "end": 252.17, "first_hit": 251.57, "last_hit": 251.57, "confidence": 0.6}]
    motion_t = np.arange(240.0, 260.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["first_hit"] == pytest.approx(251.57, abs=0.15)


def test_strict_highlight_filter_keeps_long_isolation_one_hit():
    """1-hit with 9s+ isolation kept even when the next gap is only ~2.2s (GT21)."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([437.08, 446.56, 448.75])
    rallies = [{"start": 445.51, "end": 447.16, "first_hit": 446.56, "last_hit": 446.56, "confidence": 0.55}]
    motion_t = np.arange(430.0, 455.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["first_hit"] == pytest.approx(446.56, abs=0.15)


def test_strict_highlight_filter_does_not_glue_isolated_serve_to_prev_extra():
    """GT11 1-hit serve must not be absorbed as a quiet landing of the 4:03 extra."""
    filt = StrictHighlightFilter(max_hit_silence=1.85, motion_bridge_silence=4.2, min_hits=2)
    hits = np.array([244.35, 245.40, 247.29, 248.21, 251.57, 255.30, 256.52])
    rallies = [
        {"start": 243.30, "end": 248.81, "first_hit": 244.35, "last_hit": 248.21, "confidence": 0.76},
        {"start": 250.52, "end": 252.17, "first_hit": 251.57, "last_hit": 251.57, "confidence": 0.53},
        {"start": 253.83, "end": 257.12, "first_hit": 255.30, "last_hit": 256.52, "confidence": 0.80},
    ]
    motion_t = np.arange(240.0, 260.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 244.0) & (motion_t <= 248.5)] = 0.022
    # Walking after the land starts ~2.4s after the serve (user-sample 4:14).
    motion_e[(motion_t >= 254.0) & (motion_t <= 257.0)] = 0.035
    out = filt.apply(rallies, hits, motion_t, motion_e)
    ones = [r for r in out if abs(float(r.get("first_hit", 0)) - 251.57) < 0.2]
    assert len(ones) == 1
    assert float(ones[0]["last_hit"]) < 253.0
    # Walking 2-hit after GT11 (4:13 extra) must not survive.
    assert not any(abs(float(r.get("first_hit", 0)) - 255.30) < 0.2 for r in out)
    gs, ge = 251.0, 254.0
    ps, pe = float(ones[0]["start"]), float(ones[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3


def test_strict_highlight_filter_does_not_keep_mid_iso_one_hit_with_short_next():
    """iso ~4.1s 1-hit with next-gap 2.7s is adjacent-court noise, not GT11."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([243.00, 247.42, 250.12])
    rallies = [{"start": 246.37, "end": 248.02, "first_hit": 247.42, "last_hit": 247.42, "confidence": 0.7}]
    motion_t = np.arange(240.0, 255.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert out == []


def test_strict_highlight_filter_drops_one_hit_burst_with_short_next():
    """Burst-backed 1-hit with next-gap ~2.4s is adjacent leftover (euro-short 0:06)."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([2.41, 6.49, 8.93])
    rallies = [{"start": 4.63, "end": 7.09, "first_hit": 6.49, "last_hit": 6.49, "confidence": 0.75}]
    motion_t = np.arange(0.0, 12.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 6.10) & (motion_t <= 6.90)] = 0.030
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert out == []


def test_strict_highlight_filter_keeps_one_hit_burst_with_between_point_hole():
    """On-court 1-hit + burst survives when the next contact is >=3.2s later."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([1.00, 6.50, 10.00])
    rallies = [{"start": 5.45, "end": 7.10, "first_hit": 6.50, "last_hit": 6.50, "confidence": 0.75}]
    motion_t = np.arange(0.0, 12.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 6.10) & (motion_t <= 6.90)] = 0.030
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["first_hit"] == pytest.approx(6.50, abs=0.15)


def test_strict_highlight_filter_pulls_quiet_far_court_start():
    """Live rally absorbs the isolated quiet serve 5.63s before first_hit (GT20)."""
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2)
    hits = np.array([
        417.76, 419.30, 424.933, 426.871, 427.271, 427.527, 428.170, 428.434, 429.532,
    ])
    rallies = [
        {"start": 425.82, "end": 430.13, "first_hit": 426.871, "last_hit": 429.532, "confidence": 0.99},
    ]
    motion_t = np.arange(415.0, 435.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 426.5) & (motion_t <= 429.8)] = 0.035
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 1
    main = [r for r in out if float(r["last_hit"]) >= 428.0][0]
    assert float(main["first_hit"]) <= 419.5
    gs, ge = 419.0, 429.0
    ps, pe = float(main["start"]), float(main["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3


def test_court_aware_keeps_gt11_style_isolated_serve():
    """FSM emits the almost-strong 1-hit serve with iso 3.3–5.0 and next-gap ≥ 3.6."""
    detector = CourtAwareRallyDetector(
        highlight_filter=None,
        trajectory_refiner=None,
        rally_scorer=None,
    )
    hits = np.array([248.21, 251.57, 255.30, 256.52])
    detector._last_hit_heights = np.array([0.049, 0.0574, 0.094, 0.043])
    detector._last_hit_height_threshold = 0.0376
    motion_t = np.arange(240.0, 260.0, 0.125)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    motion_e[(motion_t >= 255.0) & (motion_t <= 257.2)] = 0.032
    out = detector._run_rally_fsm(hits, motion_t, motion_e)
    assert any(abs(float(r.get("first_hit", 0)) - 251.57) < 0.25 for r in out)


def test_court_aware_drops_weaker_mid_iso_one_hit():
    """Weaker 8:04-style leftover (height < 1.48× thr) stays dropped."""
    detector = CourtAwareRallyDetector(
        highlight_filter=None,
        trajectory_refiner=None,
        rally_scorer=None,
    )
    hits = np.array([480.35, 484.19, 488.21])
    detector._last_hit_heights = np.array([0.038, 0.0426, 0.067])
    detector._last_hit_height_threshold = 0.0376
    motion_t = np.arange(475.0, 495.0, 0.125)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    out = detector._run_rally_fsm(hits, motion_t, motion_e)
    assert not any(abs(float(r.get("first_hit", 0)) - 484.19) < 0.25 for r in out)


def test_court_aware_keeps_gt21_style_long_iso_one_hit():
    """FSM emits the silent-serve 1-hit with iso>=9 and next-gap 1.8-3.5 (GT21)."""
    detector = CourtAwareRallyDetector(
        highlight_filter=None,
        trajectory_refiner=None,
        rally_scorer=None,
    )
    hits = np.array([437.08, 446.56, 448.75])
    detector._last_hit_heights = np.array([0.040, 0.0423, 0.038])
    detector._last_hit_height_threshold = 0.0376
    motion_t = np.arange(430.0, 455.0, 0.125)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    out = detector._run_rally_fsm(hits, motion_t, motion_e)
    assert any(abs(float(r.get("first_hit", 0)) - 446.56) < 0.25 for r in out)


def test_strict_highlight_filter_keeps_long_iso_one_hit_after_serve_lookback():
    """GT21 1-hit with silent-serve lookback (duration>=2.45, no burst) must survive."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35, serve_pad=1.05)
    hits = np.array([437.08, 446.56, 448.75])
    # Pre-walked start like user-sample (7:24.50-7:27.16).
    rallies = [{"start": 444.50, "end": 447.16, "first_hit": 446.56, "last_hit": 446.56, "confidence": 0.55}]
    motion_t = np.arange(430.0, 455.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 444.5) & (motion_t <= 445.1)] = 0.016
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) == 1
    assert out[0]["first_hit"] == pytest.approx(446.56, abs=0.15)
    assert float(out[0]["start"]) <= 441.5
    gs, ge = 441.0, 447.0
    ps, pe = float(out[0]["start"]), float(out[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3


def test_strict_highlight_filter_peels_silent_play_serve_from_walking_blob():
    """Dropped walking blob ending in 1-hit + silent play is peeled (GT22)."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35, serve_pad=1.05, land_pad=0.60)
    serve = 461.88
    nxt = 467.73
    # Preceding walking hit sets isolation of the blob to ~2.16 (not a real serve).
    cluster = [457.50, 457.97, 458.25, 458.66, 459.14, 459.82, 460.30, 460.68, serve]
    hits = np.array([455.34] + cluster + [nxt])
    rallies = [
        {"start": 456.45, "end": 462.48, "first_hit": 457.50, "last_hit": serve, "confidence": 0.85},
    ]
    motion_t = np.arange(450.0, 470.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 457.3) & (motion_t <= 460.9)] = 0.018
    motion_e[(motion_t >= 461.5) & (motion_t <= 465.0)] = 0.055
    out = filt.apply(rallies, hits, motion_t, motion_e)
    peeled = [r for r in out if abs(float(r.get("first_hit", 0)) - serve) < 0.2]
    assert len(peeled) == 1
    assert float(peeled[0]["last_hit"]) == pytest.approx(serve, abs=0.15)
    assert float(peeled[0]["end"]) >= serve + 2.0
    assert not any(abs(float(r.get("first_hit", 0)) - 457.50) < 0.2 for r in out)
    gs, ge = 461.0, 466.0
    ps, pe = float(peeled[0]["start"]), float(peeled[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3


def test_court_aware_peels_gt23_style_zero_court_tail():
    """0-court 4-hit with 1.84s leftover hole peels the compact 3-hit tail (GT23)."""
    detector = CourtAwareRallyDetector(
        highlight_filter=None,
        trajectory_refiner=None,
        rally_scorer=None,
    )
    hits = np.array([465.00, 473.88, 476.25, 478.096, 478.65, 480.35, 484.19])
    detector._last_hit_heights = np.array([0.040, 0.041, 0.056, 0.083, 0.040, 0.038, 0.043])
    detector._last_hit_height_threshold = 0.0376
    motion_t = np.arange(470.0, 490.0, 0.125)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    out = detector._run_rally_fsm(hits, motion_t, motion_e)
    assert any(abs(float(r.get("first_hit", 0)) - 478.096) < 0.25 for r in out)
    assert not any(abs(float(r.get("first_hit", 0)) - 476.25) < 0.25 for r in out)
    assert not any(abs(float(r.get("first_hit", 0)) - 473.88) < 0.25 for r in out)


def test_court_aware_drops_adjacent_court_zero_court_blob():
    """7-hit 0-court adjacent blob (max interior gap 1.44s) stays dropped."""
    detector = CourtAwareRallyDetector(
        highlight_filter=None,
        trajectory_refiner=None,
        rally_scorer=None,
    )
    hits = np.array([461.88, 467.73, 468.26, 469.51, 470.95, 471.68, 472.90, 473.88, 476.25])
    detector._last_hit_heights = np.array([0.168, 0.040, 0.039, 0.091, 0.039, 0.047, 0.039, 0.041, 0.056])
    detector._last_hit_height_threshold = 0.0376
    motion_t = np.arange(455.0, 480.0, 0.125)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    out = detector._run_rally_fsm(hits, motion_t, motion_e)
    assert not any(467.0 <= float(r.get("first_hit", 0)) <= 474.0 for r in out)


def test_strict_highlight_filter_keeps_gt23_far_court_three():
    """Peeled far-court 3-hit with iso 1.84 and no burst must survive (GT23)."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35, serve_pad=1.05, land_pad=0.60)
    hits = np.array([473.88, 476.25, 478.10, 478.65, 480.35, 484.19])
    rallies = [{"start": 477.05, "end": 480.95, "first_hit": 478.10, "last_hit": 480.35, "confidence": 0.6}]
    motion_t = np.arange(470.0, 490.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    out = filt.apply(rallies, hits, motion_t, motion_e)
    kept = [r for r in out if abs(float(r.get("first_hit", 0)) - 478.10) < 0.2]
    assert len(kept) == 1
    assert float(kept[0]["last_hit"]) == pytest.approx(480.35, abs=0.15)
    gs, ge = 479.0, 483.0
    ps, pe = float(kept[0]["start"]), float(kept[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3
    assert pe >= 480.35 + 2.0

def test_strict_highlight_filter_drops_four_hit_walking_cadence():
    """4-hit all-ISI walking cadence (4:03 / 4:18 extras) must drop; GT17-style survives."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    walk_hits = np.array([240.00, 244.35, 246.02, 247.29, 248.21, 251.57])
    walk = {"start": 243.30, "end": 248.81, "first_hit": 244.35, "last_hit": 248.21, "confidence": 0.76}
    gt17_hits = np.array([330.00, 336.22, 337.20, 338.09, 340.53, 348.00])
    gt17 = {"start": 334.07, "end": 340.98, "first_hit": 336.22, "last_hit": 340.53, "confidence": 0.99}
    motion_t = np.arange(230.0, 360.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 244.0) & (motion_t <= 248.5)] = 0.024
    motion_e[(motion_t >= 336.0) & (motion_t <= 341.0)] = 0.032
    out_walk = filt.apply([walk], walk_hits, motion_t, motion_e)
    assert not any(243.0 <= float(r.get("first_hit", 0)) <= 249.0 for r in out_walk)
    out_gt = filt.apply([gt17], gt17_hits, motion_t, motion_e)
    assert any(abs(float(r.get("first_hit", 0)) - 336.22) < 0.3 for r in out_gt)


def test_strict_highlight_filter_drops_gt11_adjacent_two_hit_walk():
    """2-hit leftover with pre_active ~0.46 after GT11 must drop; GT11 stays."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([248.21, 251.57, 255.30, 256.52])
    rallies = [
        {"start": 250.52, "end": 252.17, "first_hit": 251.57, "last_hit": 251.57, "confidence": 0.53},
        {"start": 253.83, "end": 257.12, "first_hit": 255.30, "last_hit": 256.52, "confidence": 0.80},
    ]
    motion_t = np.arange(240.0, 260.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    # Quiet around GT11; walking starts ~2.4s after the serve.
    motion_e[(motion_t >= 254.0) & (motion_t <= 257.0)] = 0.035
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert any(abs(float(r.get("first_hit", 0)) - 251.57) < 0.2 for r in out)
    assert not any(abs(float(r.get("first_hit", 0)) - 255.30) < 0.2 for r in out)


def test_strict_highlight_filter_drops_compact_two_hit_walking_blip():
    """Compact 2-hit walking blip (4:53 extra) drops; GT14 3-hit net-fault stays."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    blip_hits = np.array([292.53, 294.74, 295.17, 298.34, 298.61, 298.92])
    blip = {"start": 293.69, "end": 295.62, "first_hit": 294.74, "last_hit": 295.17, "confidence": 0.85}
    gt14 = {"start": 297.29, "end": 299.37, "first_hit": 298.34, "last_hit": 298.92, "confidence": 0.63}
    motion_t = np.arange(288.0, 305.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 290.5) & (motion_t <= 295.3)] = 0.028
    out_blip = filt.apply([blip], blip_hits, motion_t, motion_e)
    assert not any(abs(float(r.get("first_hit", 0)) - 294.74) < 0.2 for r in out_blip)
    out_gt = filt.apply([gt14], blip_hits, motion_t, motion_e)
    kept = [r for r in out_gt if abs(float(r.get("first_hit", 0)) - 298.34) < 0.2]
    assert len(kept) == 1
    gs, ge = 297.0, 300.0
    ps, pe = float(kept[0]["start"]), float(kept[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3


def test_strict_highlight_filter_drops_adjacent_court_five_hit_tail():
    """5-hit adjacent-court compact tail (2:17 extra) drops; GT10-style 5-hit stays."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    extra_hits = np.array([136.29, 139.24, 141.04, 141.58, 142.18, 142.43, 146.12])
    extra = {"start": 137.97, "end": 142.88, "first_hit": 139.24, "last_hit": 142.43, "confidence": 0.95}
    gt10_hits = np.array([225.00, 228.80, 230.06, 230.63, 231.36, 232.05, 240.00])
    gt10 = {"start": 228.23, "end": 233.82, "first_hit": 228.80, "last_hit": 232.05, "confidence": 0.86}
    motion_t = np.arange(130.0, 245.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 138.6) & (motion_t <= 139.8)] = 0.040
    motion_e[(motion_t >= 228.5) & (motion_t <= 233.5)] = 0.032
    out_x = filt.apply([extra], extra_hits, motion_t, motion_e)
    assert not any(abs(float(r.get("first_hit", 0)) - 139.24) < 0.2 for r in out_x)
    out_tp = filt.apply([gt10], gt10_hits, motion_t, motion_e)
    assert any(abs(float(r.get("first_hit", 0)) - 228.80) < 0.3 for r in out_tp)


def test_strict_highlight_filter_drops_four_hit_pickup_hole():
    """4-hit pickup with 1.5s hole + compact last (2:56 extra) must drop."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([175.02, 177.76, 178.57, 180.14, 180.44, 191.00])
    rallies = [{"start": 176.71, "end": 181.04, "first_hit": 177.76, "last_hit": 180.44, "confidence": 0.79}]
    motion_t = np.arange(170.0, 195.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 177.5) & (motion_t <= 180.8)] = 0.026
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert not any(abs(float(r.get("first_hit", 0)) - 177.76) < 0.2 for r in out)

def test_strict_highlight_filter_pulls_isolated_quiet_serve_across_high_clear():
    """Live n>=5 rally pulls an isolated quiet serve 4.9s earlier (GT4)."""
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2)
    hits = np.array([
        65.56, 67.74, 72.66, 73.65, 74.79, 75.48, 75.75, 76.44, 76.71, 76.97, 77.79, 79.16, 80.13,
    ])
    rallies = [{"start": 71.61, "end": 80.73, "first_hit": 72.66, "last_hit": 80.13, "confidence": 0.99}]
    motion_t = np.arange(60.0, 85.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 72.5) & (motion_t <= 80.2)] = 0.035
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 1
    main = min(out, key=lambda r: abs(float(r["last_hit"]) - 80.13))
    assert float(main["first_hit"]) <= 68.0
    gs, ge = 67.0, 79.0
    ps, pe = float(main["start"]), float(main["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3


def test_strict_highlight_filter_local_lookback_does_not_rescue_compact_tail_extra():
    """2:17-style 5-hit compact tail must stay dropped even with a 2.9s-prior isolated hit."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    extra_hits = np.array([136.29, 139.24, 141.04, 141.58, 142.18, 142.43, 146.12])
    extra = {"start": 137.97, "end": 142.88, "first_hit": 139.24, "last_hit": 142.43, "confidence": 0.95}
    motion_t = np.arange(130.0, 150.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 138.6) & (motion_t <= 139.8)] = 0.040
    out = filt.apply([extra], extra_hits, motion_t, motion_e)
    assert not any(136.0 <= float(r.get("first_hit", 0)) <= 143.0 for r in out)


def test_strict_highlight_filter_does_not_glue_gt1_gt2_after_start_lead():
    """Local start-lead must not glue GT1 last-hit onto GT2."""
    filt = StrictHighlightFilter(min_hits=2)
    hits = np.array([11.72, 16.17, 18.19, 19.46, 20.75, 22.56, 24.03, 26.71])
    rallies = [
        {"start": 10.67, "end": 16.62, "first_hit": 11.72, "last_hit": 16.17, "confidence": 0.7},
        {"start": 17.14, "end": 27.31, "first_hit": 18.19, "last_hit": 26.71, "confidence": 0.75},
    ]
    motion_t = np.arange(8.0, 30.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 11.5) & (motion_t <= 16.4)] = 0.030
    motion_e[(motion_t >= 20.5) & (motion_t <= 25.2)] = 0.032
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert not any(abs(float(r.get("first_hit", 0)) - 16.17) < 0.2 for r in out)
    assert any(abs(float(r.get("first_hit", 0)) - 18.19) < 0.2 for r in out)


def test_strict_highlight_filter_pulls_gt7_style_tight_high_clear_hole():
    """Live n>=5 with compact interior pulls an isolated quiet hit 2.18s earlier (GT7)."""
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2)
    hits = np.array([142.425, 146.120, 148.304, 149.314, 151.508, 153.063, 154.528])
    rallies = [{"start": 147.254, "end": 155.128, "first_hit": 148.304, "last_hit": 154.528, "confidence": 0.60}]
    motion_t = np.arange(140.0, 158.0, 0.1)
    motion_e = np.full_like(motion_t, 0.004, dtype=float)
    motion_e[(motion_t >= 148.2) & (motion_t <= 155.0)] = 0.035
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 1
    main = min(out, key=lambda r: abs(float(r["last_hit"]) - 154.528))
    assert float(main["first_hit"]) <= 146.20
    gs, ge = 142.0, 153.0
    ps, pe = float(main["start"]), float(main["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3


def test_strict_highlight_filter_tight_hole_skips_dead_ball_interior():
    """2.20s isolated hole in front of a 3.0s interior gap must not pull (dead-ball fixture)."""
    filt = StrictHighlightFilter(max_hit_silence=2.0, motion_bridge_silence=4.2, min_hits=2)
    rallies = [{"start": 10.0, "end": 28.0, "first_hit": 11.0, "last_hit": 25.0, "confidence": 0.9}]
    hits = np.array([11.0, 12.0, 13.0, 16.2, 18.4, 19.6, 22.6, 23.2, 24.0])
    motion_t = np.arange(8.0, 30.0, 0.1)
    motion_e = np.full_like(motion_t, 0.003, dtype=float)
    motion_e[(motion_t >= 10.5) & (motion_t <= 13.4)] = 0.04
    motion_e[(motion_t >= 22.2) & (motion_t <= 24.5)] = 0.04
    out = filt.apply(rallies, hits, motion_t, motion_e)
    assert len(out) >= 2
    assert out[0]["last_hit"] <= 16.5
    assert out[-1]["first_hit"] >= 18.0



def test_strict_highlight_filter_peels_primary_court_from_adjacent_glue():
    """Euro-long GT4: dense n=18 blob split on a 1.08s hole; keep primary rally.

    Adjacent-court walking tail must not survive as an extra. User-sample 6:27
    (n=10 dens=1.94 iso=2.28) must still be dropped by the applause gate.
    """
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2, min_duration=1.35)
    gt4_hits = np.array([
        42.00,
        45.56, 46.20, 46.58, 47.06, 47.45, 47.73, 48.14, 48.73, 49.40, 50.24, 50.85,
        51.93, 52.95, 53.48, 53.94, 54.31, 54.67, 55.03,
    ])
    gt4 = {"start": 44.51, "end": 55.63, "first_hit": 45.56, "last_hit": 55.03, "confidence": 0.99}
    motion_t = np.arange(40.0, 58.0, 0.1)
    motion_e = np.full_like(motion_t, 0.010, dtype=float)
    motion_e[(motion_t >= 45.9) & (motion_t <= 51.2)] = 0.040
    motion_e[(motion_t >= 51.7) & (motion_t <= 55.2)] = 0.045
    out = filt.apply([gt4], gt4_hits, motion_t, motion_e)
    kept = [r for r in out if 45.0 <= float(r.get("first_hit", 0)) <= 47.0]
    assert len(kept) == 1
    assert float(kept[0]["last_hit"]) <= 51.2
    gs, ge = 45.0, 51.0
    ps, pe = float(kept[0]["start"]), float(kept[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3
    assert not any(51.5 <= float(r.get("first_hit", 0)) <= 55.5 for r in out)

    walk_hits = np.array([
        386.15, 387.56,
        389.84, 390.18, 390.56, 391.63, 392.57, 392.83, 393.97, 394.45, 394.72, 395.00,
    ])
    walk = {"start": 387.69, "end": 395.60, "first_hit": 389.84, "last_hit": 395.00, "confidence": 0.80}
    motion_t2 = np.arange(380.0, 400.0, 0.1)
    motion_e2 = np.full_like(motion_t2, 0.010, dtype=float)
    motion_e2[(motion_t2 >= 389.5) & (motion_t2 <= 395.3)] = 0.050
    out_walk = filt.apply([walk], walk_hits, motion_t2, motion_e2)
    assert not any(389.0 <= float(r.get("first_hit", 0)) <= 396.0 for r in out_walk)


def test_strict_highlight_filter_peels_gt2_style_interior_hole():
    """Euro-long GT2: 4 in-ROI hits then 2.6s hole into adjacent walking."""
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2, min_duration=1.35)
    hits = np.array([
        11.642,
        19.225, 20.271, 20.631, 21.159,
        23.770, 24.116, 24.464, 24.895, 25.250, 25.613, 25.980, 26.364, 26.806, 27.127, 27.751,
    ])
    blob = {"start": 18.05, "end": 28.35, "first_hit": 19.225, "last_hit": 27.751, "confidence": 0.78}
    motion_t = np.arange(10.0, 30.0, 0.1)
    motion_e = np.full_like(motion_t, 0.010, dtype=float)
    # Continuous in-ROI walking across the 2.6s hole so silence-split
    # does not fire (matches euro-long balcony motion); peel still cuts.
    motion_e[(motion_t >= 19.0) & (motion_t <= 28.0)] = 0.035
    out = filt.apply([blob], hits, motion_t, motion_e)
    kept = [r for r in out if abs(float(r.get("first_hit", 0)) - 19.225) < 0.2]
    assert len(kept) == 1
    assert float(kept[0]["last_hit"]) <= 21.5
    gs, ge = 18.0, 21.0
    ps, pe = float(kept[0]["start"]), float(kept[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3
    assert not any(23.5 <= float(r.get("first_hit", 0)) <= 28.0 for r in out)


def test_strict_highlight_filter_drops_kaja_handshake_applause_blob():
    """Kaja post-point handshake (iso>=3.2, n=24 clap) must not peel-keep."""
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2, min_duration=1.35)
    hits = np.array([
        8.89, 12.14,
        16.17, 17.81, 19.32, 19.63, 19.88, 20.28, 20.69, 21.07, 21.42, 21.77,
        22.16, 22.54, 22.92, 23.29, 23.64, 24.02, 24.27, 24.75, 25.12, 25.46,
        25.82, 26.18, 26.57, 26.85,
    ])
    blob = {"start": 15.12, "end": 27.45, "first_hit": 16.17, "last_hit": 26.85, "confidence": 0.97}
    motion_t = np.arange(8.0, 30.0, 0.1)
    motion_e = np.full_like(motion_t, 0.010, dtype=float)
    motion_e[(motion_t >= 16.0) & (motion_t <= 27.0)] = 0.040
    out = filt.apply([blob], hits, motion_t, motion_e)
    assert not any(15.0 <= float(r.get("first_hit", 0)) <= 27.0 for r in out)



def test_strict_highlight_filter_peels_gt3_style_two_hit_net_fault():
    """Euro-long GT3: 1 in-ROI serve + 2+ out-of-ROI glue; keep serve+return.

    A 1-hit peel fails far_court_one because the next (out-of-ROI) contact
    is only ~1s later. Isolation >= 3.20 applause blob; 1.0s-hole min
    cluster stays 3 (GT2 / Kaja).
    """
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2, min_duration=1.35)
    hits = np.array([
        29.687,
        35.772, 36.781, 37.910, 38.586, 38.949, 39.354, 39.802, 40.281, 40.912, 41.220, 41.747, 41.999,
    ])
    blob = {"start": 34.72, "end": 42.45, "first_hit": 35.772, "last_hit": 41.999, "confidence": 0.78}
    motion_t = np.arange(28.0, 44.0, 0.1)
    motion_e = np.full_like(motion_t, 0.010, dtype=float)
    motion_e[(motion_t >= 35.4) & (motion_t <= 36.2)] = 0.040
    motion_e[(motion_t >= 39.2) & (motion_t <= 42.1)] = 0.028
    out = filt.apply([blob], hits, motion_t, motion_e)
    kept = [r for r in out if abs(float(r.get("first_hit", 0)) - 35.772) < 0.2]
    assert len(kept) == 1
    assert float(kept[0]["last_hit"]) <= 37.2
    gs, ge = 34.0, 36.0
    ps, pe = float(kept[0]["start"]), float(kept[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3
    assert not any(37.5 <= float(r.get("first_hit", 0)) <= 42.5 for r in out)


def test_strict_highlight_filter_peels_gt5_style_single_out_after_in_roi_head():
    """Euro-long GT5: 7 in-ROI hits then 1 out-of-ROI; trimmed blob has no 2+ OUT run."""
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2, min_duration=1.35)
    hits = np.array([
        65.096,
        73.429, 74.127, 74.400, 75.047, 75.413, 76.211, 76.689, 77.564, 77.936, 78.208,
    ])
    blob = {"start": 72.38, "end": 78.66, "first_hit": 73.429, "last_hit": 78.208, "confidence": 0.84}
    motion_t = np.arange(64.0, 81.0, 0.1)
    motion_e = np.full_like(motion_t, 0.010, dtype=float)
    motion_e[(motion_t >= 73.2) & (motion_t <= 76.9)] = 0.040
    motion_e[(motion_t >= 77.0) & (motion_t <= 77.75)] = 0.008
    motion_e[(motion_t >= 78.05) & (motion_t <= 78.5)] = 0.032
    out = filt.apply([blob], hits, motion_t, motion_e)
    kept = [r for r in out if abs(float(r.get("first_hit", 0)) - 73.429) < 0.2]
    assert len(kept) == 1
    assert float(kept[0]["last_hit"]) <= 77.70
    gs, ge = 73.0, 75.0
    ps, pe = float(kept[0]["start"]), float(kept[0]["end"])
    inter = max(0.0, min(ge, pe) - max(gs, ps))
    union = (ge - gs) + (pe - ps) - inter
    assert union > 0 and inter / union >= 0.3
    assert not any(77.4 <= float(r.get("first_hit", 0)) <= 79.0 for r in out)


def test_strict_highlight_filter_peels_gt6_style_short_iso_in_roi_core():
    """Euro-long GT6: OO lead then in-ROI core; iso=2.20 from extra 1:24.

    Do not lower peel iso (user-sample 6:27 is 2.28, all in-ROI walking).
    Extra 1:24 (2 out-of-ROI, no burst, next-gap 2.20s) is dropped.
    """
    filt = StrictHighlightFilter(serve_pad=1.05, land_pad=0.60, min_hits=2, min_duration=1.35)
    hits = np.array([
        80.170,
        85.621, 86.007,
        88.209, 89.452,
        90.007, 90.436, 90.870, 91.160, 91.529, 92.044, 92.580, 92.834,
        93.548, 94.228, 94.494, 95.069, 95.527, 96.261, 96.530, 96.780,
    ])
    extra = {"start": 84.57, "end": 86.46, "first_hit": 85.621, "last_hit": 86.007, "confidence": 0.58}
    blob = {"start": 87.16, "end": 97.23, "first_hit": 88.209, "last_hit": 96.780, "confidence": 0.85}
    motion_t = np.arange(78.0, 99.0, 0.1)
    motion_e = np.full_like(motion_t, 0.010, dtype=float)
    motion_e[(motion_t >= 89.9) & (motion_t <= 91.8)] = 0.040
    motion_e[(motion_t >= 91.85) & (motion_t <= 92.25)] = 0.008
    motion_e[(motion_t >= 92.3) & (motion_t <= 96.9)] = 0.035
    out = filt.apply([extra, blob], hits, motion_t, motion_e)
    gs, ge = 89.0, 94.0
    matching = []
    for r in out:
        ps, pe = float(r["start"]), float(r["end"])
        inter = max(0.0, min(ge, pe) - max(gs, ps))
        union = (ge - gs) + (pe - ps) - inter
        if union > 0 and inter / union >= 0.3:
            matching.append(r)
    assert len(matching) == 1
    assert float(matching[0]["first_hit"]) >= 89.5
    assert float(matching[0]["last_hit"]) <= 95.0
    assert not any(abs(float(r.get("first_hit", 0)) - 85.621) < 0.2 for r in out)

    # 6:27 walking extra (n=10 dens=1.94 iso=2.28 all in-ROI) still dropped.
    walk_hits = np.array([
        386.15, 387.56,
        389.84, 390.18, 390.56, 391.63, 392.57, 392.83, 393.97, 394.45, 394.72, 395.00,
    ])
    walk = {"start": 387.69, "end": 395.60, "first_hit": 389.84, "last_hit": 395.00, "confidence": 0.80}
    motion_t2 = np.arange(380.0, 400.0, 0.1)
    motion_e2 = np.full_like(motion_t2, 0.010, dtype=float)
    motion_e2[(motion_t2 >= 389.5) & (motion_t2 <= 395.3)] = 0.050
    out_walk = filt.apply([walk], walk_hits, motion_t2, motion_e2)
    assert not any(389.0 <= float(r.get("first_hit", 0)) <= 396.0 for r in out_walk)


def test_strict_highlight_filter_drops_euro_long_1m03_two_hit_walk():
    """Euro-long extra 1:03: 2-hit after walking (isi=0.60 iso=3.49 pre high)."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([60.671, 60.999, 64.493, 65.096, 73.429])
    extra = {"start": 63.44, "end": 65.69, "first_hit": 64.493, "last_hit": 65.096, "confidence": 0.70}
    motion_t = np.arange(58.0, 76.0, 0.1)
    motion_e = np.full_like(motion_t, 0.010, dtype=float)
    motion_e[(motion_t >= 62.2) & (motion_t <= 64.0)] = 0.040
    motion_e[(motion_t >= 64.4) & (motion_t <= 65.3)] = 0.032
    out = filt.apply([extra], hits, motion_t, motion_e)
    assert not any(abs(float(r.get("first_hit", 0)) - 64.493) < 0.2 for r in out)


def test_strict_highlight_filter_drops_huddle_talking_plateau():
    """John-carroll huddle: talking-length high-motion plateau drops.

    User-sample GT4-style long rally (mean well below p85) stays.
    """
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    huddle_hits = np.array([
        8.99, 9.45, 9.88, 10.92, 11.21, 13.59, 14.71, 15.23, 15.81,
        16.82, 17.32, 19.31, 19.62, 20.06, 20.49, 21.39, 21.77,
    ])
    huddle = {"start": 7.44, "end": 22.22, "first_hit": 8.99, "last_hit": 21.77, "confidence": 0.99}
    motion_t = np.arange(0.0, 40.0, 0.1)
    motion_e = np.full_like(motion_t, 0.110, dtype=float)
    motion_e[(motion_t >= 8.5) & (motion_t <= 22.0)] = 0.330
    out = filt.apply([huddle], huddle_hits, motion_t, motion_e)
    assert not any(7.0 <= float(r.get("first_hit", 0)) <= 23.0 for r in out)

    # Shorter huddle: 9.4s n=9 first_isi>=2.0 high plateau (john extra 2).
    short_hits = np.array([29.42, 31.67, 33.70, 33.99, 34.29, 35.16, 35.45, 36.23, 37.16, 40.0])
    short = {"start": 28.37, "end": 37.76, "first_hit": 29.42, "last_hit": 37.16, "confidence": 0.99}
    motion_t3 = np.arange(20.0, 50.0, 0.1)
    motion_e3 = np.full_like(motion_t3, 0.110, dtype=float)
    motion_e3[(motion_t3 >= 29.0) & (motion_t3 <= 37.5)] = 0.330
    out_short = filt.apply([short], short_hits, motion_t3, motion_e3)
    assert not any(28.0 <= float(r.get("first_hit", 0)) <= 38.0 for r in out_short)

    gt4_hits = np.array([
        64.50,
        67.68, 72.61, 73.60, 74.74, 75.43, 75.70, 76.39, 76.66, 76.92, 77.74, 79.12, 80.08,
    ])
    gt4 = {"start": 66.63, "end": 80.73, "first_hit": 67.68, "last_hit": 80.08, "confidence": 0.99}
    motion_t2 = np.arange(50.0, 90.0, 0.1)
    motion_e2 = np.full_like(motion_t2, 0.010, dtype=float)
    motion_e2[(motion_t2 >= 72.4) & (motion_t2 <= 80.2)] = 0.028
    out_gt = filt.apply([gt4], gt4_hits, motion_t2, motion_e2)
    assert any(abs(float(r.get("first_hit", 0)) - 67.68) < 0.3 for r in out_gt)


def test_strict_highlight_filter_keeps_john_gt2_two_hit():
    """John GT2 compact 2-hit (isi=0.41 pre~0.54 dur=2.38) must stay a TP."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([73.743, 74.402, 76.636, 77.043, 80.002])
    rally = {"start": 75.11, "end": 77.49, "first_hit": 76.636, "last_hit": 77.043, "confidence": 0.91}
    motion_t = np.arange(70.0, 85.0, 0.1)
    motion_e = np.full_like(motion_t, 0.080, dtype=float)
    # Modest pre-motion (real clip pre~0.54); burst during the 2 hits.
    motion_e[(motion_t >= 74.4) & (motion_t <= 75.9)] = 0.090
    motion_e[(motion_t >= 76.4) & (motion_t <= 77.2)] = 0.160
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is True


def _motion_series(t0, t1, baseline=0.020, spikes=()):
    motion_t = np.arange(t0, t1, 0.1)
    motion_e = np.full_like(motion_t, baseline, dtype=float)
    for lo, hi, val in spikes:
        motion_e[(motion_t >= lo) & (motion_t <= hi)] = val
    return motion_t, motion_e


def test_strict_highlight_filter_extends_john_gt3_like_four_hit():
    """John 1:38 4-hit (last_isi=1.63 next=4.12) should land-pad to cover GT3."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35, land_pad=0.60)
    hits = np.array([94.359, 99.918, 100.453, 101.92, 103.552, 107.672])
    rally = {"start": 98.87, "end": 104.15, "first_hit": 99.918, "last_hit": 103.552, "confidence": 0.9}
    motion_t, motion_e = _motion_series(
        90.0, 115.0,
        spikes=[(99.7, 103.8, 0.16)],
    )
    out = filt.apply([rally], hits, motion_t, motion_e)
    kept = [r for r in out if abs(float(r.get("first_hit", 0)) - 99.918) < 0.3]
    assert kept, out
    assert float(kept[0]["end"]) >= 106.9


def test_strict_highlight_filter_drops_john_sideline_1m18():
    """John extra 1:18 sideline: compact 5-hit high-motion continuation."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([77.043, 80.002, 80.556, 81.154, 81.467, 81.935, 83.822])
    rally = {"start": 78.58, "end": 82.53, "first_hit": 80.002, "last_hit": 81.935, "confidence": 0.63}
    motion_t, motion_e = _motion_series(70.0, 90.0, spikes=[(80.0, 82.1, 0.16)])
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is False


def test_strict_highlight_filter_drops_john_reaction_1m45():
    """John extra 1:45 reaction+scoreboard: n=6 mixed-court compact ISIs."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([103.552, 107.672, 108.368, 109.176, 109.667, 110.295, 110.657, 112.62])
    rally = {"start": 105.94, "end": 111.11, "first_hit": 107.672, "last_hit": 110.657, "confidence": 0.84}
    # Baseline below hit_in_court floor so idle contacts count as out-of-ROI.
    # Burst on the first 2–3 contacts; last 3 sit in idle court (n_in<=3).
    motion_t, motion_e = _motion_series(
        100.0, 120.0, baseline=0.004,
        spikes=[(107.4, 108.6, 0.16)],
    )
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is False


def test_strict_highlight_filter_drops_john_poster_2m14():
    """John extra 2:14 poster: n=3 talking-gap then compact pair, walking pre."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([133.407, 136.419, 138.744, 139.334, 142.455])
    rally = {"start": 134.63, "end": 139.78, "first_hit": 136.419, "last_hit": 139.334, "confidence": 0.77}
    motion_t, motion_e = _motion_series(
        128.0, 145.0,
        spikes=[(134.2, 139.5, 0.16)],
    )
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is False


def test_strict_highlight_filter_drops_john_handshake_2m44():
    """John extra 2:44 handshake: n=3 compact clap, pre-motion already high."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([161.06, 165.324, 165.589, 166.167, 168.436])
    rally = {"start": 164.27, "end": 166.77, "first_hit": 165.324, "last_hit": 166.167, "confidence": 0.74}
    motion_t, motion_e = _motion_series(
        155.0, 175.0,
        spikes=[(163.0, 166.4, 0.16)],
    )
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is False


def test_strict_highlight_filter_keeps_user_gt5_three_hit():
    """User-sample GT5 compact 3-hit (first_isi=0.80 iso=1.36 next=4.21) stays."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([83.30, 85.66, 86.46, 86.76, 90.97])
    rally = {"start": 84.66, "end": 87.41, "first_hit": 85.66, "last_hit": 86.76, "confidence": 0.99}
    motion_t, motion_e = _motion_series(
        80.0, 95.0, baseline=0.08,
        spikes=[(84.4, 87.2, 0.16)],
    )
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is True


def test_strict_highlight_filter_keeps_euro_long_gt1_five_hit():
    """Euro-long GT1 n=5 dens=1.89 next=7.20 max-isi=1.18 must not match sideline."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([1.79, 2.48, 2.85, 4.03, 4.43, 11.63])
    rally = {"start": 0.74, "end": 5.04, "first_hit": 1.79, "last_hit": 4.43, "confidence": 0.93}
    motion_t, motion_e = _motion_series(
        0.0, 15.0,
        spikes=[(1.5, 4.6, 0.16)],
    )
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is True


def test_strict_highlight_filter_keeps_kaja_six_hit():
    """Kaja n=6 dens=1.47 max-isi=1.71 must not match the 1:45 reaction gate."""
    filt = StrictHighlightFilter(min_hits=2, min_duration=1.35)
    hits = np.array([2.0, 4.80, 5.14, 5.52, 5.89, 7.60, 8.89, 12.14])
    rally = {"start": 3.75, "end": 9.34, "first_hit": 4.80, "last_hit": 8.89, "confidence": 0.88}
    motion_t, motion_e = _motion_series(
        0.0, 16.0,
        spikes=[(4.5, 9.0, 0.16)],
    )
    assert filt._is_highlight(rally, hits, motion_t, motion_e) is True

