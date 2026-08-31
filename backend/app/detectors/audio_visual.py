import os
import logging
from typing import List, Dict, Any, Tuple, Optional
import numpy as np
import scipy.signal as signal

from backend.app.detectors.base import BaseRallyDetector

logger = logging.getLogger(__name__)

class AudioVisualRallyDetector(BaseRallyDetector):
    """
    Audio-Visual Rally Detection Engine for Badminton Match Videos.
    Integrates acoustic hit peak detection (Butterworth 2-6kHz bandpass + Hilbert envelope + find_peaks)
    with visual frame-difference motion energy into a 4-state Rally Finite State Machine (FSM).
    """

    def __init__(
        self,
        sample_rate: int = 16000,
        freq_min: float = 2000.0,
        freq_max: float = 6000.0,
        min_hit_interval: float = 0.25,
        max_silence_gap: float = 2.5,
        min_rally_duration: float = 2.0,
        motion_threshold: float = 0.05,
        visual_fps: int = 5,
        mock_mode: Optional[bool] = None
    ):
        self.sample_rate = sample_rate
        self.freq_min = freq_min
        self.freq_max = freq_max
        self.min_hit_interval = min_hit_interval
        self.max_silence_gap = max_silence_gap
        self.min_rally_duration = min_rally_duration
        self.motion_threshold = motion_threshold
        self.visual_fps = visual_fps
        if mock_mode is None:
            mock_mode = os.getenv("MOCK_MODE", "false").lower() in ("true", "1")
        self.mock_mode = mock_mode

    def analyze_video(self, video_path: str) -> List[Dict[str, Any]]:
        """
        Analyze an input video file and return detected rally segments.

        :param video_path: Path to the input video file.
        :return: Formatted list of rally dicts {"id": int, "start": float, "end": float, "duration": float}.
        """
        # Step 1: Input Validation
        self.validate_video_path(video_path)

        # Return mock rallies directly if mock_mode is set
        if self.mock_mode:
            return self._format_and_validate_rallies(self._get_mock_rallies())

        # Step 2: Attempt Audio and Visual Feature Extractions
        audio_data = None
        audio_sr = self.sample_rate
        audio_err = None

        try:
            audio_data, audio_sr = self._extract_audio(video_path)
        except Exception as e:
            audio_err = e
            logger.warning(f"Audio extraction failed for {video_path}: {e}")

        motion_timestamps = None
        motion_energies = None
        visual_err = None
        try:
            motion_timestamps, motion_energies = self._extract_visual_motion(video_path)
        except Exception as e:
            visual_err = e
            logger.warning(f"Visual motion extraction failed for {video_path}: {e}")

        # Step 3: Determine Detection Strategy Based on Available Signals
        has_audio = audio_data is not None and len(audio_data) > 0
        has_visual = motion_timestamps is not None and len(motion_timestamps) > 0

        if not has_audio and not has_visual:
            err_details = []
            if audio_err:
                err_details.append(f"Audio extraction error: {audio_err}")
            if visual_err:
                err_details.append(f"Visual motion error: {visual_err}")
            err_str = "; ".join(err_details) if err_details else "No valid audio or visual stream found"
            raise RuntimeError(f"Failed to process media file: {err_str}")

        if has_audio:
            # Audio-Visual or Audio-Only detection
            hit_peaks = self._detect_audio_hits(audio_data, audio_sr)
            raw_rallies = self._run_rally_fsm(
                hit_peaks=hit_peaks,
                motion_timestamps=motion_timestamps if has_visual else None,
                motion_energies=motion_energies if has_visual else None
            )
        else:
            # Visual-Only Fallback (silent video)
            raw_rallies = self._run_visual_only_fsm(motion_timestamps, motion_energies)

        raw_rallies = self._refine_rally_boundaries(video_path, raw_rallies)
        return self._format_and_validate_rallies(
            raw_rallies,
            merge_gap_threshold=self._rally_merge_gap_threshold(),
        )

    def _rally_merge_gap_threshold(self) -> float:
        """Adjacent-fragment merge gap used after FSM / boundary refinement."""
        return 0.5

    def _refine_rally_boundaries(
        self,
        video_path: str,
        raw_rallies: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        """Extension point for optional pose or shuttle-trajectory models."""
        return raw_rallies

    def _extract_audio(self, video_path: str) -> Tuple[np.ndarray, int]:
        """Extract PCM mono audio waveform at target sample rate.

        librosa 1.0+ loads via soundfile only, which cannot decode mp4/webm
        containers. Fall back to ffmpeg PCM decode so hit detection still runs.
        """
        try:
            import librosa
            audio, sr = librosa.load(video_path, sr=self.sample_rate, mono=True)
            return audio, sr
        except Exception as exc:
            logger.warning(
                "librosa.load failed for %s (%s); decoding audio via ffmpeg",
                video_path,
                exc,
            )
            return self._extract_audio_ffmpeg(video_path)

    def _extract_audio_ffmpeg(self, video_path: str) -> Tuple[np.ndarray, int]:
        """Decode mono PCM through ffmpeg (works for mp4/webm/aac/opus)."""
        import subprocess

        cmd = [
            "ffmpeg",
            "-v", "error",
            "-nostdin",
            "-i", video_path,
            "-f", "s16le",
            "-acodec", "pcm_s16le",
            "-ac", "1",
            "-ar", str(self.sample_rate),
            "pipe:1",
        ]
        proc = subprocess.run(cmd, capture_output=True, check=False)
        if proc.returncode != 0 or not proc.stdout:
            err = proc.stderr.decode("utf-8", errors="replace").strip()
            raise RuntimeError(
                f"ffmpeg audio decode failed for {video_path}: {err or proc.returncode}"
            )
        audio = np.frombuffer(proc.stdout, dtype=np.int16).astype(np.float32) / 32768.0
        return audio, self.sample_rate

    def _extract_visual_motion(self, video_path: str) -> Tuple[np.ndarray, np.ndarray]:
        """Extract frame difference motion energy downsampled to visual_fps at 320x240."""
        import cv2
        cap = cv2.VideoCapture(video_path)
        if not cap.isOpened():
            cap.release()
            raise RuntimeError(f"Failed to open video file with OpenCV: {video_path}")

        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        frame_interval = max(1, int(fps / self.visual_fps))

        prev_gray = None
        motion_energies = []
        timestamps = []

        frame_idx = 0
        try:
            while cap.isOpened():
                ret, frame = cap.read()
                if not ret:
                    break
                if frame_idx % frame_interval == 0:
                    small = cv2.resize(frame, (320, 240))
                    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
                    t = frame_idx / fps

                    if prev_gray is not None:
                        diff = cv2.absdiff(gray, prev_gray)
                        _, thresh = cv2.threshold(diff, 25, 255, cv2.THRESH_BINARY)
                        motion_energy = np.mean(thresh) / 255.0
                        motion_energies.append(motion_energy)
                        timestamps.append(t)
                    prev_gray = gray
                frame_idx += 1
        finally:
            cap.release()

        return np.array(timestamps), np.array(motion_energies)

    def _design_bandpass_filter(self, sr: int) -> np.ndarray:
        """Design 4th-order Butterworth bandpass filter in SOS format."""
        nyq = sr / 2.0
        low = self.freq_min / nyq
        high = self.freq_max / nyq
        # Ensure cutoffs stay within valid (0, 1) range relative to Nyquist
        low = max(0.01, min(low, 0.95))
        high = max(low + 0.01, min(high, 0.99))
        sos = signal.butter(N=4, Wn=[low, high], btype='bandpass', output='sos')
        return sos

    def _detect_audio_hits(self, audio: np.ndarray, sr: int) -> np.ndarray:
        """
        Apply Butterworth bandpass, zero-phase filtering (sosfiltfilt), Hilbert envelope smoothing,
        and find_peaks with dynamic noise thresholding.
        """
        if len(audio) < sr * 0.1:  # extremely short audio segment
            self._last_hit_heights = np.array([])
            self._last_hit_height_threshold = 0.0
            return np.array([])

        sos = self._design_bandpass_filter(sr)
        filtered_audio = signal.sosfiltfilt(sos, audio)

        # Hilbert envelope
        analytic = signal.hilbert(filtered_audio)
        envelope = np.abs(analytic)

        # Smooth envelope with small window (5ms)
        smooth_len = max(1, int(0.005 * sr))
        smooth_kernel = np.ones(smooth_len) / smooth_len
        smoothed_envelope = np.convolve(envelope, smooth_kernel, mode='same')

        # Dynamic noise thresholding: H_thresh = median + 4.5 * MAD (normalized)
        baseline_median = float(np.median(smoothed_envelope))
        mad = float(np.median(np.abs(smoothed_envelope - baseline_median)))
        normal_mad = mad * 1.4826
        height_threshold = baseline_median + 4.5 * normal_mad

        # Minimum spacing distance between hits
        distance_samples = max(1, int(self.min_hit_interval * sr))

        # Peak detection
        peaks, props = signal.find_peaks(
            smoothed_envelope,
            height=height_threshold if height_threshold > 0 else None,
            distance=distance_samples
        )

        hit_timestamps = peaks / float(sr)
        # Side channel for court-aware grouping (strong vs weak hits).
        self._last_hit_heights = np.asarray(props.get("peak_heights", []), dtype=float)
        self._last_hit_height_threshold = float(height_threshold)
        return hit_timestamps

    def _run_rally_fsm(
        self,
        hit_peaks: np.ndarray,
        motion_timestamps: Optional[np.ndarray] = None,
        motion_energies: Optional[np.ndarray] = None
    ) -> List[Dict[str, Any]]:
        """
        4-State Rally Finite State Machine (FSM):
        IDLE -> SERVE_DETECTED -> RALLY_IN_PROGRESS -> POINT_ENDED
        """
        if len(hit_peaks) == 0:
            return []

        def get_motion_at(t: float) -> float:
            if motion_timestamps is None or motion_energies is None or len(motion_timestamps) == 0:
                return 1.0  # Default to active if visual motion unavailable
            idx = np.searchsorted(motion_timestamps, t)
            idx = min(idx, len(motion_energies) - 1)
            return float(motion_energies[idx])

        rallies = []
        state = "IDLE"
        candidate_start = 0.0
        last_hit = 0.0
        hit_count = 0

        for t in hit_peaks:
            t = float(t)
            if state == "IDLE":
                m_score = get_motion_at(t)
                if m_score >= (self.motion_threshold * 0.5):  # serve start motion check
                    state = "SERVE_DETECTED"
                    candidate_start = t
                    last_hit = t
                    hit_count = 1

            elif state == "SERVE_DETECTED":
                gap = t - last_hit
                if gap <= self.max_silence_gap:
                    state = "RALLY_IN_PROGRESS"
                    last_hit = t
                    hit_count += 1
                else:
                    # Previous serve timed out without 2nd hit
                    m_score = get_motion_at(t)
                    if m_score >= (self.motion_threshold * 0.5):
                        candidate_start = t
                        last_hit = t
                        hit_count = 1
                    else:
                        state = "IDLE"

            elif state == "RALLY_IN_PROGRESS":
                gap = t - last_hit
                if gap <= self.max_silence_gap:
                    last_hit = t
                    hit_count += 1
                else:
                    # Silence gap exceeded max threshold -> Point Ended
                    candidate_end = last_hit + 0.5
                    duration = candidate_end - candidate_start
                    if duration >= self.min_rally_duration and hit_count >= 2:
                        rallies.append({
                            "start": candidate_start,
                            "end": candidate_end,
                            "confidence": 0.95
                        })
                    
                    # Start evaluating current hit for next serve
                    m_score = get_motion_at(t)
                    if m_score >= (self.motion_threshold * 0.5):
                        state = "SERVE_DETECTED"
                        candidate_start = t
                        last_hit = t
                        hit_count = 1
                    else:
                        state = "IDLE"

        # End of peaks check
        if state in ("RALLY_IN_PROGRESS", "SERVE_DETECTED") and hit_count >= 2:
            candidate_end = last_hit + 0.5
            duration = candidate_end - candidate_start
            if duration >= self.min_rally_duration:
                rallies.append({
                    "start": candidate_start,
                    "end": candidate_end,
                    "confidence": 0.95
                })

        return rallies

    def _run_visual_only_fsm(
        self,
        motion_timestamps: np.ndarray,
        motion_energies: np.ndarray
    ) -> List[Dict[str, Any]]:
        """Visual-only fallback detection based on motion threshold energy contiguous blocks."""
        if len(motion_timestamps) == 0:
            return []

        active_indices = np.where(motion_energies >= self.motion_threshold)[0]
        if len(active_indices) == 0:
            return []

        rallies = []
        seg_start = None
        last_t = None

        for idx in active_indices:
            t = float(motion_timestamps[idx])
            if seg_start is None:
                seg_start = t
                last_t = t
            else:
                if t - last_t <= self.max_silence_gap:
                    last_t = t
                else:
                    duration = last_t - seg_start
                    if duration >= self.min_rally_duration:
                        rallies.append({"start": seg_start, "end": last_t, "confidence": 0.85})
                    seg_start = t
                    last_t = t

        if seg_start is not None and last_t is not None:
            duration = last_t - seg_start
            if duration >= self.min_rally_duration:
                rallies.append({"start": seg_start, "end": last_t, "confidence": 0.85})

        return rallies

    @staticmethod
    def _get_mock_rallies() -> List[Dict[str, Any]]:
        """Deterministic mock rally segments matching project specifications."""
        return [
            {"id": 1, "start": 15.0, "end": 28.5, "duration": 13.5, "confidence": 0.95},
            {"id": 2, "start": 45.2, "end": 62.0, "duration": 16.8, "confidence": 0.92},
            {"id": 3, "start": 80.0, "end": 98.4, "duration": 18.4, "confidence": 0.98},
        ]

    @staticmethod
    def generate_synthetic_audio(
        duration: float = 120.0,
        sr: int = 16000,
        rally_timestamps: Optional[List[Tuple[float, float]]] = None
    ) -> Tuple[np.ndarray, List[float]]:
        """
        Helper method to generate synthetic audio waveform with decaying ~3.5kHz sinewave hit pulses
        for unit testing without external media files.
        """
        if rally_timestamps is None:
            rally_timestamps = [(15.0, 28.5), (45.2, 62.0), (80.0, 98.4)]

        total_samples = int(duration * sr)
        rng = np.random.default_rng(42)
        audio = rng.normal(0, 0.005, total_samples)

        true_hits = []
        for r_start, r_end in rally_timestamps:
            hit_times = np.arange(r_start + 0.2, r_end - 0.2, 0.8)
            for ht in hit_times:
                ht_val = float(ht)
                true_hits.append(ht_val)
                idx = int(ht_val * sr)
                decay_t = np.linspace(0, 0.02, int(0.02 * sr), endpoint=False)
                # 3.5kHz decaying impulse
                hit_wave = 0.8 * np.exp(-decay_t / 0.004) * np.sin(2 * np.pi * 3500 * decay_t)
                end_idx = min(idx + len(hit_wave), total_samples)
                actual_len = end_idx - idx
                audio[idx:end_idx] += hit_wave[:actual_len]

        return audio, true_hits
