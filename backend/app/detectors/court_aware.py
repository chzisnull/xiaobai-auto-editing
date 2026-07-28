import logging
import os
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np

from backend.app.detectors.audio_visual import AudioVisualRallyDetector
from backend.app.detectors.tracknet_v3 import TrackNetV3BoundaryRefiner


NormalizedPoint = Tuple[float, float]
logger = logging.getLogger(__name__)


class CourtAwareRallyDetector(AudioVisualRallyDetector):
    """Audio/visual detector whose motion signal is limited to one court."""

    DEFAULT_COURT_ROI: Tuple[NormalizedPoint, ...] = (
        (0.20, 0.34),
        (0.82, 0.34),
        (0.98, 0.96),
        (0.02, 0.96),
    )

    def __init__(
        self,
        court_roi: Optional[Sequence[Sequence[float]]] = None,
        landing_delay: float = 1.4,
        serve_lead: float = 0.35,
        max_serve_lookback: float = 2.5,
        trajectory_refiner: Optional[TrackNetV3BoundaryRefiner] = None,
        **kwargs: Any,
    ) -> None:
        kwargs.setdefault("visual_fps", 8)
        kwargs.setdefault("motion_threshold", 0.01)
        kwargs.setdefault("max_silence_gap", 2.6)
        kwargs.setdefault("min_rally_duration", 1.2)
        super().__init__(**kwargs)
        self.court_roi = self._normalize_court_roi(court_roi)
        self.landing_delay = max(0.2, min(float(landing_delay), 1.5))
        self.serve_lead = max(0.0, min(float(serve_lead), 1.0))
        self.max_serve_lookback = max(self.serve_lead, min(float(max_serve_lookback), 4.0))
        if trajectory_refiner is not None:
            self.trajectory_refiner = trajectory_refiner
        else:
            enabled = os.getenv("TRACKNET_ENABLED", "true").lower() in ("1", "true", "yes")
            self.trajectory_refiner = TrackNetV3BoundaryRefiner() if enabled else None

    @classmethod
    def _normalize_court_roi(
        cls,
        court_roi: Optional[Sequence[Sequence[float]]],
    ) -> Tuple[NormalizedPoint, ...]:
        if court_roi is None:
            return cls.DEFAULT_COURT_ROI
        if len(court_roi) != 4:
            raise ValueError("court_roi must contain exactly four points")
        normalized: List[NormalizedPoint] = []
        for point in court_roi:
            if len(point) != 2:
                raise ValueError("Each court ROI point must contain x and y")
            x, y = float(point[0]), float(point[1])
            if not 0.0 <= x <= 1.0 or not 0.0 <= y <= 1.0:
                raise ValueError("Court ROI coordinates must be between 0 and 1")
            normalized.append((x, y))
        return tuple(normalized)

    def _extract_visual_motion(self, video_path: str) -> Tuple[np.ndarray, np.ndarray]:
        """Measure foreground motion only inside the calibrated court polygon."""
        import cv2

        cap = cv2.VideoCapture(video_path)
        if not cap.isOpened():
            cap.release()
            raise RuntimeError(f"Failed to open video file with OpenCV: {video_path}")

        fps = cap.get(cv2.CAP_PROP_FPS) or 30.0
        frame_interval = max(1, int(round(fps / self.visual_fps)))
        width, height = 320, 240
        polygon = np.array(
            [[round(x * (width - 1)), round(y * (height - 1))] for x, y in self.court_roi],
            dtype=np.int32,
        )
        mask = np.zeros((height, width), dtype=np.uint8)
        cv2.fillPoly(mask, [polygon], 255)
        mask_pixels = max(1, int(np.count_nonzero(mask)))

        previous = None
        timestamps: List[float] = []
        energies: List[float] = []
        frame_index = 0
        kernel = np.ones((3, 3), dtype=np.uint8)

        try:
            while cap.isOpened():
                ok, frame = cap.read()
                if not ok:
                    break
                if frame_index % frame_interval == 0:
                    small = cv2.resize(frame, (width, height), interpolation=cv2.INTER_AREA)
                    gray = cv2.cvtColor(small, cv2.COLOR_BGR2GRAY)
                    gray = cv2.GaussianBlur(gray, (5, 5), 0)
                    if previous is not None:
                        difference = cv2.absdiff(gray, previous)
                        _, changed = cv2.threshold(difference, 18, 255, cv2.THRESH_BINARY)
                        changed = cv2.morphologyEx(changed, cv2.MORPH_OPEN, kernel)
                        changed = cv2.bitwise_and(changed, mask)
                        energies.append(float(np.count_nonzero(changed)) / mask_pixels)
                        timestamps.append(frame_index / fps)
                    previous = gray
                frame_index += 1
        finally:
            cap.release()

        return np.asarray(timestamps), np.asarray(energies)

    def _run_rally_fsm(
        self,
        hit_peaks: np.ndarray,
        motion_timestamps: Optional[np.ndarray] = None,
        motion_energies: Optional[np.ndarray] = None,
    ) -> List[Dict[str, Any]]:
        """Fuse sound candidates with court-local motion before temporal grouping."""
        if len(hit_peaks) == 0:
            return []
        if motion_timestamps is None or motion_energies is None or len(motion_timestamps) == 0:
            return super()._run_rally_fsm(hit_peaks, motion_timestamps, motion_energies)

        median_motion = float(np.median(motion_energies))
        active_motion = float(np.percentile(motion_energies, 85))
        support_threshold = max(
            self.motion_threshold,
            median_motion + 0.28 * max(0.0, active_motion - median_motion),
        )

        def local_motion(timestamp: float) -> float:
            left = np.searchsorted(motion_timestamps, timestamp - 0.35, side="left")
            right = np.searchsorted(motion_timestamps, timestamp + 0.35, side="right")
            if right <= left:
                index = min(np.searchsorted(motion_timestamps, timestamp), len(motion_energies) - 1)
                return float(motion_energies[index])
            return float(np.max(motion_energies[left:right]))

        def serve_motion_start(timestamp: float) -> float:
            """Walk back to the onset of the active run preceding the first audible hit."""
            left = np.searchsorted(
                motion_timestamps,
                max(0.0, timestamp - self.max_serve_lookback),
                side="left",
            )
            right = np.searchsorted(motion_timestamps, timestamp + 0.15, side="right")
            if right <= left:
                return max(0.0, timestamp - self.serve_lead)

            onset_threshold = max(
                self.motion_threshold * 0.55,
                median_motion + 0.12 * max(0.0, active_motion - median_motion),
            )
            active = motion_energies[left:right] >= onset_threshold
            if not np.any(active):
                return max(0.0, timestamp - self.serve_lead)

            anchor = min(
                len(active) - 1,
                max(0, np.searchsorted(motion_timestamps[left:right], timestamp, side="right") - 1),
            )
            while anchor > 0 and not active[anchor]:
                anchor -= 1
            if not active[anchor]:
                return max(0.0, timestamp - self.serve_lead)

            start_index = anchor
            allowed_quiet_frames = max(1, int(round(self.visual_fps * 0.25)))
            quiet_frames = 0
            for index in range(anchor - 1, -1, -1):
                if active[index]:
                    start_index = index
                    quiet_frames = 0
                else:
                    quiet_frames += 1
                    if quiet_frames > allowed_quiet_frames:
                        break
            motion_start = float(motion_timestamps[left + start_index]) - self.serve_lead
            return max(0.0, max(timestamp - self.max_serve_lookback, motion_start))

        supported: List[Tuple[float, float]] = []
        for peak in hit_peaks:
            peak_time = float(peak)
            motion = local_motion(peak_time)
            if motion >= support_threshold:
                supported.append((peak_time, motion))

        if not supported:
            return []

        groups: List[List[Tuple[float, float]]] = []
        current = [supported[0]]
        for candidate in supported[1:]:
            if candidate[0] - current[-1][0] <= self.max_silence_gap:
                current.append(candidate)
            else:
                groups.append(current)
                current = [candidate]
        groups.append(current)

        rallies: List[Dict[str, Any]] = []
        motion_span = max(0.001, active_motion - support_threshold)
        for group in groups:
            hit_count = len(group)
            first_hit = group[0][0]
            last_hit = group[-1][0]
            start = serve_motion_start(first_hit)
            end = last_hit + self.landing_delay
            duration = end - start
            mean_support = float(np.mean([motion for _, motion in group]))
            normalized_support = max(0.0, min(1.0, (mean_support - support_threshold) / motion_span))

            # One-hit service faults are retained only when court motion is unusually strong.
            if hit_count == 1 and normalized_support < 0.8:
                continue
            if duration < self.min_rally_duration:
                continue

            count_score = min(1.0, hit_count / 6.0)
            confidence = round(min(0.99, 0.48 + 0.30 * count_score + 0.22 * normalized_support), 3)
            rallies.append({
                "start": start,
                "end": end,
                "confidence": confidence,
                "first_hit": first_hit,
                "last_hit": last_hit,
            })

        return rallies

    def _refine_rally_boundaries(
        self,
        video_path: str,
        raw_rallies: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        if not raw_rallies or self.trajectory_refiner is None:
            return raw_rallies
        try:
            refined = self.trajectory_refiner.refine(video_path, raw_rallies, self.court_roi)
            for index, (current, following) in enumerate(zip(refined, refined[1:])):
                original_gap = (
                    float(raw_rallies[index + 1]["start"])
                    - float(raw_rallies[index]["end"])
                )
                if original_gap <= 0.5:
                    continue
                latest_end = float(following["start"]) - 0.6
                if float(current["end"]) > latest_end > float(current["start"]):
                    current["end"] = max(float(current.get("last_hit", current["start"])), latest_end)
            return refined
        except Exception as exc:
            logger.warning("TrackNetV3 boundary refinement skipped: %s", exc)
            return raw_rallies
