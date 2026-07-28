"""
Adversarial and Boundary Verification Test Suite for Milestone 2: Audio-Visual Rally Detection Engine.
Tests BaseRallyDetector and AudioVisualRallyDetector against extreme edge cases, corrupt inputs,
distorted audio, parameter boundary extremes, and invalid timestamp structures.
"""

import os
import tempfile
import pytest
import numpy as np

from backend.app.detectors.base import BaseRallyDetector
from backend.app.detectors.audio_visual import AudioVisualRallyDetector


# ============================================================================
# 1. Input File Handling & Extreme File Edge Cases
# ============================================================================

def test_adversarial_nonexistent_video_path():
    """Verify analyze_video raises FileNotFoundError when given a non-existent path."""
    detector = AudioVisualRallyDetector()
    non_existent_path = "/tmp/non_existent_video_file_9999.mp4"
    with pytest.raises(FileNotFoundError) as exc_info:
        detector.analyze_video(non_existent_path)
    assert "Video file not found" in str(exc_info.value)


def test_adversarial_zero_byte_video_path():
    """Verify analyze_video raises ValueError when given a 0-byte video file."""
    detector = AudioVisualRallyDetector()
    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
        tmp_path = tmp.name

    try:
        with pytest.raises(ValueError) as exc_info:
            detector.analyze_video(tmp_path)
        assert "0 bytes" in str(exc_info.value)
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


def test_adversarial_non_media_text_file():
    """
    Verify behavior when given a non-empty, non-media text file (e.g. plain text saved as .mp4).
    When mock_mode is False, raises RuntimeError.
    When mock_mode is True, returns mock rallies.
    """
    detector = AudioVisualRallyDetector(mock_mode=False)
    detector_mock = AudioVisualRallyDetector(mock_mode=True)
    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
        tmp.write(b"This is a non-media plain text file content masquerading as a video.\n" * 10)
        tmp_path = tmp.name

    try:
        with pytest.raises(RuntimeError) as exc_info:
            detector.analyze_video(tmp_path)
        assert "Failed to process media file" in str(exc_info.value)

        rallies = detector_mock.analyze_video(tmp_path)
        assert isinstance(rallies, list)
        assert len(rallies) == 3
        assert rallies[0]["id"] == 1
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


def test_adversarial_corrupt_binary_file():
    """
    Verify behavior when given a file filled with random corrupt binary bytes.
    When mock_mode is False, raises RuntimeError.
    When mock_mode is True, returns mock rallies.
    """
    detector = AudioVisualRallyDetector(mock_mode=False)
    detector_mock = AudioVisualRallyDetector(mock_mode=True)
    with tempfile.NamedTemporaryFile(suffix=".mp4", delete=False) as tmp:
        tmp.write(os.urandom(1024 * 64))  # 64 KB of random noise bytes
        tmp_path = tmp.name

    try:
        with pytest.raises(RuntimeError) as exc_info:
            detector.analyze_video(tmp_path)
        assert "Failed to process media file" in str(exc_info.value)

        rallies = detector_mock.analyze_video(tmp_path)
        assert isinstance(rallies, list)
        assert len(rallies) == 3
    finally:
        if os.path.exists(tmp_path):
            os.remove(tmp_path)


# ============================================================================
# 2. Audio Track Edge Cases & Signal Anomalies
# ============================================================================

def test_adversarial_silent_audio_track():
    """Verify peak detection and FSM behavior on pure silent audio (all zeros)."""
    detector = AudioVisualRallyDetector(sample_rate=16000)
    silent_audio = np.zeros(16000 * 10, dtype=np.float32)  # 10 seconds of silence

    peaks = detector._detect_audio_hits(silent_audio, 16000)
    assert len(peaks) == 0

    raw_rallies = detector._run_rally_fsm(peaks)
    assert len(raw_rallies) == 0


def test_adversarial_white_noise_audio():
    """Verify peak detection under high-amplitude Gaussian white noise."""
    detector = AudioVisualRallyDetector(sample_rate=16000)
    np.random.seed(42)
    # High amplitude white noise
    white_noise = np.random.normal(0, 0.5, 16000 * 10)

    # Peak detection should not throw errors or overflow
    peaks = detector._detect_audio_hits(white_noise, 16000)
    # Dynamic MAD threshold should limit false positives
    assert isinstance(peaks, np.ndarray)


def test_adversarial_extreme_volume_step_change():
    """Verify peak detection when quiet audio suddenly experiences a 1000x volume surge."""
    sr = 16000
    detector = AudioVisualRallyDetector(sample_rate=sr)
    
    # 5s quiet audio (amp 0.0001) followed by 5s loud audio (amp 1.0)
    quiet = np.random.normal(0, 0.0001, sr * 5)
    loud = np.random.normal(0, 1.0, sr * 5)
    audio = np.concatenate([quiet, loud])

    peaks = detector._detect_audio_hits(audio, sr)
    assert isinstance(peaks, np.ndarray)
    # Ensure peaks are finite numbers without inf/nan
    assert np.all(np.isfinite(peaks))


def test_adversarial_high_frequency_tone():
    """Verify that a smooth out-of-band tone pulse (7500 Hz, outside 2-6kHz filter) is attenuated compared to an in-band pulse (3500 Hz)."""
    sr = 16000
    detector = AudioVisualRallyDetector(sample_rate=sr, freq_min=2000.0, freq_max=6000.0)

    duration = 5.0
    total_samples = int(duration * sr)
    
    # Generate background noise
    np.random.seed(42)
    audio = np.random.normal(0, 0.005, total_samples)

    # Add 7500 Hz Hanning-windowed tone pulse (above upper cutoff of 6000 Hz) at t=2.0s
    idx = int(2.0 * sr)
    pulse_t = np.linspace(0, 0.04, int(0.04 * sr), endpoint=False)
    win = np.hanning(len(pulse_t))
    oob_hit = 0.8 * win * np.sin(2 * np.pi * 7500 * pulse_t)
    audio[idx:idx + len(oob_hit)] += oob_hit

    peaks = detector._detect_audio_hits(audio, sr)
    # The 7500 Hz out-of-band smooth pulse at t=2.0s should be attenuated and NOT detected
    hit_near_2s = any(abs(p - 2.0) < 0.05 for p in peaks)
    assert not hit_near_2s, "Out-of-band 7500Hz smooth tone pulse at 2.0s should be attenuated"


# ============================================================================
# 3. Extreme Detector Hyperparameters
# ============================================================================

def test_adversarial_zero_hit_interval():
    """Verify detector with min_hit_interval=0.0 handles hit peaks without division by zero or errors."""
    sr = 16000
    detector = AudioVisualRallyDetector(min_hit_interval=0.0)
    hit_peaks = np.array([10.0, 10.0, 10.5, 11.0, 11.5])
    rallies = detector._run_rally_fsm(hit_peaks)
    assert isinstance(rallies, list)


def test_adversarial_tiny_max_silence_gap():
    """Verify max_silence_gap=0.1 splits hits spaced at normal rally pace (0.8s), returning no rallies."""
    detector = AudioVisualRallyDetector(max_silence_gap=0.1, min_rally_duration=2.0)
    # Hits at 0.8s interval -> gap 0.8 > max_silence_gap 0.1 -> resets FSM state repeatedly
    hit_peaks = np.array([10.0, 10.8, 11.6, 12.4, 13.2])
    rallies = detector._run_rally_fsm(hit_peaks)
    assert len(rallies) == 0


def test_adversarial_huge_min_rally_duration():
    """Verify min_rally_duration=100.0 filters out all shorter rally candidates."""
    detector = AudioVisualRallyDetector(min_rally_duration=100.0)
    # Rally candidates of 10s duration
    hit_peaks = np.array([10.0, 11.0, 12.0, 13.0, 14.0, 15.0, 16.0, 17.0, 18.0, 19.0, 20.0])
    rallies = detector._run_rally_fsm(hit_peaks)
    assert len(rallies) == 0


def test_adversarial_out_of_bounds_frequency_params():
    """Verify freq_min=8000, freq_max=9000 (exceeding Nyquist for sr=16000) does not crash Butterworth filter design."""
    sr = 16000
    detector = AudioVisualRallyDetector(sample_rate=sr, freq_min=8000.0, freq_max=9000.0)
    
    # Filter design safety check
    sos = detector._design_bandpass_filter(sr)
    assert sos is not None
    assert sos.shape[1] == 6  # 2nd-order sections matrix format


def test_adversarial_inverted_frequency_params():
    """Verify freq_min=6000 > freq_max=2000 is safely clamped without crashing Butterworth filter design."""
    sr = 16000
    detector = AudioVisualRallyDetector(sample_rate=sr, freq_min=6000.0, freq_max=2000.0)
    
    sos = detector._design_bandpass_filter(sr)
    assert sos is not None
    assert sos.shape[1] == 6


# ============================================================================
# 4. Boundary Verification on Returned Timestamps
# ============================================================================

def test_boundary_inverted_timestamps():
    """Verify start > end in raw rally dictionaries raises ValueError."""
    raw = [{"start": 15.0, "end": 10.0}]
    with pytest.raises(ValueError) as exc_info:
        BaseRallyDetector._format_and_validate_rallies(raw)
    assert "Invalid rally timestamps" in str(exc_info.value)


def test_boundary_zero_duration_timestamps():
    """Verify start == end (0-duration rally) raises ValueError."""
    raw = [{"start": 10.0, "end": 10.0}]
    with pytest.raises(ValueError) as exc_info:
        BaseRallyDetector._format_and_validate_rallies(raw)
    assert "Invalid rally timestamps" in str(exc_info.value)


def test_boundary_negative_start_timestamp():
    """Verify start < 0.0 raises ValueError."""
    raw = [{"start": -0.5, "end": 5.0}]
    with pytest.raises(ValueError) as exc_info:
        BaseRallyDetector._format_and_validate_rallies(raw)
    assert "Invalid rally start timestamp" in str(exc_info.value)


def test_boundary_complex_overlapping_segments():
    """
    Verify complex overlapping and sub-segment inputs merge into unified, continuous segments.
    Input:
      1) 10.0 -> 20.0
      2) 12.0 -> 18.0 (fully contained)
      3) 19.5 -> 25.0 (overlaps end of #1, gap 0.5s <= merge_gap_threshold)
      4) 30.0 -> 40.0 (distinct segment)
    Expected output:
      Rally 1: 10.0 -> 25.0 (duration 15.0)
      Rally 2: 30.0 -> 40.0 (duration 10.0)
    """
    raw = [
        {"start": 10.0, "end": 20.0, "confidence": 0.9},
        {"start": 12.0, "end": 18.0, "confidence": 0.95},
        {"start": 19.5, "end": 25.0, "confidence": 0.85},
        {"start": 30.0, "end": 40.0, "confidence": 0.99},
    ]
    res = BaseRallyDetector._format_and_validate_rallies(raw, merge_gap_threshold=0.5)
    
    assert len(res) == 2
    assert res[0]["id"] == 1
    assert res[0]["start"] == 10.0
    assert res[0]["end"] == 25.0
    assert res[0]["duration"] == 15.0
    assert res[0]["confidence"] == 0.85  # Min confidence preserved

    assert res[1]["id"] == 2
    assert res[1]["start"] == 30.0
    assert res[1]["end"] == 40.0
    assert res[1]["duration"] == 10.0
    assert res[1]["confidence"] == 0.99


def test_boundary_empty_raw_rallies():
    """Verify passing empty list to _format_and_validate_rallies returns empty list."""
    res = BaseRallyDetector._format_and_validate_rallies([])
    assert res == []


def test_boundary_floating_point_precision():
    """Verify returned dictionary values are rounded to 3 decimal places."""
    raw = [{"start": 10.123456, "end": 25.987654, "confidence": 0.9123}]
    res = BaseRallyDetector._format_and_validate_rallies(raw)
    assert res[0]["start"] == 10.123
    assert res[0]["end"] == 25.988
    assert res[0]["duration"] == 15.865
