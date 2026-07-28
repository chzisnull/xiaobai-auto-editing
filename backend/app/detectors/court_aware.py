import logging
import os
from typing import Any, Dict, List, Optional, Sequence, Tuple

import numpy as np

from backend.app.detectors.audio_visual import AudioVisualRallyDetector
from backend.app.detectors.highlight_filter import StrictHighlightFilter
from backend.app.detectors.rally_scorer import MultimodalRallyScorer
from backend.app.detectors.tracknet_v3 import TrackNetV3BoundaryRefiner
from backend.app.detectors.trajectory_series import TrajectorySeries


NormalizedPoint = Tuple[float, float]
logger = logging.getLogger(__name__)
_UNSET = object()


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
        landing_delay: float = 1.0,
        serve_lead: float = 0.35,
        max_serve_lookback: float = 2.0,
        max_bridge_gap: float = 3.2,
        residual_motion_window: float = 1.4,
        max_rally_duration: float = 20.0,
        highlight_mode: Optional[str] = None,
        trajectory_refiner: Optional[TrackNetV3BoundaryRefiner] = None,
        rally_scorer: Optional[MultimodalRallyScorer] = None,
        highlight_filter: Any = _UNSET,
        **kwargs: Any,
    ) -> None:
        # strict (default): highlight-only clips. standard: more recall, more padding.
        mode = (highlight_mode or os.getenv("RALLY_MODE", "strict")).strip().lower()
        self.highlight_mode = mode if mode in {"strict", "standard"} else "strict"
        self.strict = self.highlight_mode == "strict"

        kwargs.setdefault("visual_fps", 8)
        kwargs.setdefault("motion_threshold", 0.01)
        if self.strict:
            kwargs.setdefault("max_silence_gap", 2.2)
            kwargs.setdefault("min_rally_duration", 1.4)
            landing_delay = min(landing_delay, 1.0)
            serve_lead = min(serve_lead, 0.4)
            max_serve_lookback = min(max_serve_lookback, 2.0)
            max_bridge_gap = min(max_bridge_gap, 3.2)
            residual_motion_window = min(residual_motion_window, 1.4)
            max_rally_duration = min(max_rally_duration, 20.0)
        else:
            kwargs.setdefault("max_silence_gap", 3.0)
            kwargs.setdefault("min_rally_duration", 1.2)
        super().__init__(**kwargs)
        self.court_roi = self._normalize_court_roi(court_roi)
        self.landing_delay = max(0.2, min(float(landing_delay), 2.0))
        self.serve_lead = max(0.0, min(float(serve_lead), 1.2))
        self.max_serve_lookback = max(self.serve_lead, min(float(max_serve_lookback), 6.0))
        self.max_bridge_gap = max(self.max_silence_gap, min(float(max_bridge_gap), 8.0))
        self.residual_motion_window = max(0.5, min(float(residual_motion_window), 6.0))
        self.max_rally_duration = max(8.0, min(float(max_rally_duration), 60.0))
        if trajectory_refiner is not None:
            self.trajectory_refiner = trajectory_refiner
        else:
            enabled = os.getenv("TRACKNET_ENABLED", "true").lower() in ("1", "true", "yes")
            self.trajectory_refiner = TrackNetV3BoundaryRefiner() if enabled else None
        # Free local scorer: audio + motion + trajectory weights. No cloud model.
        scorer_enabled = os.getenv("MULTIMODAL_SCORER", "true").lower() in ("1", "true", "yes")
        self.rally_scorer = rally_scorer if rally_scorer is not None else (
            MultimodalRallyScorer(
                merge_gap=min(self.max_bridge_gap, 3.5 if self.strict else 4.0),
                serve_lookback=1.2 if self.strict else 2.2,
                land_lookahead=1.2 if self.strict else 1.8,
                max_rally_duration=self.max_rally_duration,
                min_hit_density=0.32 if self.strict else 0.18,
            ) if scorer_enabled else None
        )
        if highlight_filter is not _UNSET:
            # Explicit None disables the filter (used by unit tests).
            self.highlight_filter = highlight_filter
        elif self.strict:
            self.highlight_filter = StrictHighlightFilter(
                serve_pad=0.85,
                land_pad=1.0,
                max_hit_silence=2.2,
                min_hits=3,
                min_duration=1.4,
                max_duration=self.max_rally_duration,
                min_hit_density=0.32,
            )
        else:
            self.highlight_filter = StrictHighlightFilter(
                serve_pad=1.2,
                land_pad=1.4,
                max_hit_silence=2.8,
                min_hits=2,
                min_duration=1.2,
                max_duration=max(self.max_rally_duration, 28.0),
                min_hit_density=0.18,
            )

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

        timestamps_arr = np.asarray(timestamps)
        energies_arr = np.asarray(energies)
        self._last_motion_timestamps = timestamps_arr
        self._last_motion_energies = energies_arr
        return timestamps_arr, energies_arr

    def _run_rally_fsm(
        self,
        hit_peaks: np.ndarray,
        motion_timestamps: Optional[np.ndarray] = None,
        motion_energies: Optional[np.ndarray] = None,
    ) -> List[Dict[str, Any]]:
        """Fuse sound candidates with court-local motion before temporal grouping.

        Failure modes this path prioritises:
        1. Late serve cut-in — walk start back along continuous court motion.
        2. Mid-rally hard cuts — bridge hit holes with motion / soft audio peaks.
        3. Early landing cut-off — extend end while residual motion remains.
        """
        if len(hit_peaks) == 0:
            return []
        if motion_timestamps is None or motion_energies is None or len(motion_timestamps) == 0:
            return super()._run_rally_fsm(hit_peaks, motion_timestamps, motion_energies)

        median_motion = float(np.median(motion_energies))
        active_motion = float(np.percentile(motion_energies, 85))
        motion_range = max(0.0, active_motion - median_motion)
        # Primary gate: hit must coincide with court activity.
        support_threshold = max(
            self.motion_threshold,
            median_motion + 0.20 * motion_range,
        )
        # Soft gate: quieter mid-rally exchanges still glue fragments.
        bridge_threshold = max(
            self.motion_threshold * 0.55,
            median_motion + 0.08 * motion_range,
        )
        residual_threshold = max(
            self.motion_threshold * 0.45,
            median_motion + 0.06 * motion_range,
        )
        # Between-point walking is usually weaker than live exchanges.
        merge_motion_threshold = max(
            residual_threshold,
            median_motion + 0.14 * motion_range,
        )
        onset_threshold = max(
            self.motion_threshold * 0.50,
            median_motion + 0.08 * motion_range,
        )

        def local_motion(timestamp: float, radius: float = 0.40) -> float:
            left = np.searchsorted(motion_timestamps, timestamp - radius, side="left")
            right = np.searchsorted(motion_timestamps, timestamp + radius, side="right")
            if right <= left:
                index = min(np.searchsorted(motion_timestamps, timestamp), len(motion_energies) - 1)
                return float(motion_energies[index])
            return float(np.max(motion_energies[left:right]))

        def interval_motion_stats(start: float, end: float) -> Tuple[float, float]:
            if end <= start:
                return 0.0, 0.0
            left = np.searchsorted(motion_timestamps, start, side="left")
            right = np.searchsorted(motion_timestamps, end, side="right")
            if right <= left:
                return 0.0, 0.0
            window = motion_energies[left:right]
            mean_energy = float(np.mean(window))
            active_fraction = float(np.mean(window >= residual_threshold))
            return mean_energy, active_fraction

        def motion_bridges(previous_hit: float, next_hit: float) -> bool:
            gap = next_hit - previous_hit
            if gap <= self.max_silence_gap:
                return True
            if gap > self.max_bridge_gap:
                return False

            mean_energy, active_fraction = interval_motion_stats(previous_hit, next_hit)
            # Dense continuous motion can bridge a short hole (missed soft hit).
            # Cap the pure-motion bridge so between-point walking is not glued.
            pure_motion_cap = 3.2 if self.strict else 4.5
            pure_motion_frac = 0.62 if self.strict else 0.55
            if (
                gap <= min(self.max_bridge_gap, pure_motion_cap)
                and active_fraction >= pure_motion_frac
                and mean_energy >= merge_motion_threshold
            ):
                return True

            # Motion-supported soft hits may bridge slightly longer holes.
            for soft_time, soft_motion in soft_hits:
                if (
                    previous_hit + 0.05 < soft_time < next_hit - 0.05
                    and soft_motion >= bridge_threshold
                    and gap <= self.max_bridge_gap
                    and active_fraction >= (0.38 if self.strict else 0.30)
                ):
                    return True
            return False

        def walk_motion_start(timestamp: float, max_lookback: float) -> float:
            """Walk back along active court motion preceding a hit / rally start."""
            left = np.searchsorted(
                motion_timestamps,
                max(0.0, timestamp - max_lookback),
                side="left",
            )
            right = np.searchsorted(motion_timestamps, timestamp + 0.15, side="right")
            if right <= left:
                return max(0.0, timestamp - self.serve_lead)

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
            allowed_quiet_frames = max(2, int(round(self.visual_fps * 0.40)))
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
            return max(0.0, max(timestamp - max_lookback, motion_start))

        def walk_motion_end(last_hit: float, max_lookahead: float) -> float:
            """Keep landing / late exchanges while court motion lingers."""
            end = last_hit + self.landing_delay
            # Allow a little extra when motion stays clearly active (not just
            # residual footwork), which recovers late smashes after a quiet hit.
            look_until = last_hit + max(max_lookahead, self.residual_motion_window)
            strong_look_until = last_hit + max_lookahead + 1.5
            left = np.searchsorted(motion_timestamps, last_hit, side="left")
            right = np.searchsorted(motion_timestamps, strong_look_until, side="right")
            if right <= left:
                return end

            last_active_time = last_hit
            last_strong_time = last_hit
            quiet_frames = 0
            allowed_quiet = max(2, int(round(self.visual_fps * 0.45)))
            for index in range(left, right):
                timestamp = float(motion_timestamps[index])
                energy = float(motion_energies[index])
                if energy >= residual_threshold:
                    last_active_time = timestamp
                    quiet_frames = 0
                    if energy >= merge_motion_threshold:
                        last_strong_time = timestamp
                else:
                    quiet_frames += 1
                    if quiet_frames > allowed_quiet and timestamp >= end:
                        break
            residual_end = min(look_until, last_active_time + 0.45)
            strong_end = min(strong_look_until, last_strong_time + 0.35)
            return max(end, residual_end, strong_end)

        supported: List[Tuple[float, float]] = []
        soft_hits: List[Tuple[float, float]] = []
        for peak in hit_peaks:
            peak_time = float(peak)
            motion = local_motion(peak_time)
            if motion >= support_threshold:
                supported.append((peak_time, motion))
                soft_hits.append((peak_time, motion))
            elif motion >= bridge_threshold:
                soft_hits.append((peak_time, motion))

        if not supported:
            return []

        groups: List[List[Tuple[float, float]]] = []
        current = [supported[0]]
        for candidate in supported[1:]:
            if motion_bridges(current[-1][0], candidate[0]):
                current.append(candidate)
            else:
                groups.append(current)
                current = [candidate]
        groups.append(current)

        rallies: List[Dict[str, Any]] = []
        motion_span = max(0.001, active_motion - support_threshold)
        for group in groups:
            first_hit = group[0][0]
            last_hit = group[-1][0]
            # Absorb motion-supported soft peaks after the last strong hit so a
            # quiet final exchange is not chopped off.
            for soft_time, soft_motion in soft_hits:
                if last_hit < soft_time <= last_hit + self.max_bridge_gap:
                    if soft_motion >= bridge_threshold and motion_bridges(last_hit, soft_time):
                        last_hit = soft_time
                        group = group + [(soft_time, soft_motion)]

            # Also absorb soft peaks slightly before the first strong hit
            # (serve contact sometimes weak on phone mics).
            for soft_time, soft_motion in reversed(soft_hits):
                if first_hit - min(self.max_bridge_gap, 3.5) <= soft_time < first_hit:
                    if soft_motion >= bridge_threshold and motion_bridges(soft_time, first_hit):
                        first_hit = soft_time
                        group = [(soft_time, soft_motion)] + group
                    else:
                        break

            hit_count = max(1, len(group))
            # Strict highlight mode: keep clips tight around audible hits.
            if self.strict:
                start = max(0.0, first_hit - self.serve_lead - 0.5)
                end = last_hit + self.landing_delay
            else:
                start = walk_motion_start(first_hit, self.max_serve_lookback)
                end = walk_motion_end(last_hit, self.residual_motion_window)
            duration = end - start
            mean_support = float(np.mean([motion for _, motion in group]))
            normalized_support = max(0.0, min(1.0, (mean_support - support_threshold) / motion_span))

            # One-hit service faults are retained only when court motion is unusually strong.
            min_hits = 3 if self.strict else 1
            if hit_count < min_hits:
                if not (hit_count == 1 and normalized_support >= 0.85 and not self.strict):
                    continue
            if duration < self.min_rally_duration:
                continue
            hit_span = max(0.25, last_hit - first_hit)
            if self.strict and hit_count / hit_span < 0.28 and hit_count < 5:
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

        if not self.strict:
            rallies = self._expand_rallies_with_motion(
                rallies,
                motion_timestamps,
                motion_energies,
                residual_threshold,
                onset_threshold,
            )
        rallies = self._merge_motion_linked_rallies(
            rallies,
            motion_timestamps,
            motion_energies,
            merge_motion_threshold,
        )
        # Always run highlight filter when we still have hit peaks available.
        hit_peaks_arr = np.asarray(hit_peaks, dtype=float)
        if self.highlight_filter is not None and len(hit_peaks_arr):
            rallies = self.highlight_filter.apply(rallies, hit_peaks_arr)
        return rallies

    def _expand_rallies_with_motion(
        self,
        rallies: List[Dict[str, Any]],
        motion_timestamps: np.ndarray,
        motion_energies: np.ndarray,
        residual_threshold: float,
        onset_threshold: float,
    ) -> List[Dict[str, Any]]:
        """Second-pass start/end expansion using continuous court activity."""
        if not rallies:
            return rallies

        expanded: List[Dict[str, Any]] = []
        for index, rally in enumerate(rallies):
            item = dict(rally)
            prev_end = float(expanded[-1]["end"]) if expanded else 0.0
            next_start = float(rallies[index + 1]["start"]) if index + 1 < len(rallies) else None

            # Walk start earlier while motion stays active, without invading the previous rally.
            earliest = max(prev_end + 0.35, float(item["start"]) - self.max_serve_lookback - 1.5)
            left = np.searchsorted(motion_timestamps, earliest, side="left")
            right = np.searchsorted(motion_timestamps, float(item["first_hit"]) + 0.1, side="right")
            if right > left:
                active = motion_energies[left:right] >= onset_threshold
                if np.any(active):
                    anchor = len(active) - 1
                    while anchor > 0 and not active[anchor]:
                        anchor -= 1
                    start_index = anchor
                    quiet = 0
                    allowed = max(2, int(round(self.visual_fps * 0.40)))
                    for cursor in range(anchor - 1, -1, -1):
                        if active[cursor]:
                            start_index = cursor
                            quiet = 0
                        else:
                            quiet += 1
                            if quiet > allowed:
                                break
                    candidate_start = max(earliest, float(motion_timestamps[left + start_index]) - self.serve_lead)
                    item["start"] = min(float(item["start"]), candidate_start)

            # Walk end later while residual motion remains, without invading the next rally.
            latest = float(item["last_hit"]) + self.residual_motion_window + 1.0
            if next_start is not None:
                latest = min(latest, next_start - 0.35)
            left = np.searchsorted(motion_timestamps, float(item["last_hit"]), side="left")
            right = np.searchsorted(motion_timestamps, latest, side="right")
            if right > left:
                last_active = float(item["last_hit"])
                quiet = 0
                allowed = max(2, int(round(self.visual_fps * 0.45)))
                floor_end = float(item["last_hit"]) + self.landing_delay
                for cursor in range(left, right):
                    timestamp = float(motion_timestamps[cursor])
                    if float(motion_energies[cursor]) >= residual_threshold:
                        last_active = timestamp
                        quiet = 0
                    else:
                        quiet += 1
                        if quiet > allowed and timestamp >= floor_end:
                            break
                item["end"] = max(float(item["end"]), min(latest, last_active + 0.45))

            if float(item["end"]) > float(item["start"]):
                expanded.append(item)
        return expanded

    def _merge_motion_linked_rallies(
        self,
        rallies: List[Dict[str, Any]],
        motion_timestamps: np.ndarray,
        motion_energies: np.ndarray,
        merge_motion_threshold: float,
    ) -> List[Dict[str, Any]]:
        """Merge adjacent fragments that still look like one continuous rally."""
        if len(rallies) <= 1:
            return rallies

        merged: List[Dict[str, Any]] = [dict(rallies[0])]
        for candidate in rallies[1:]:
            previous = merged[-1]
            gap = float(candidate["start"]) - float(previous["end"])
            hit_gap = float(candidate.get("first_hit", candidate["start"])) - float(
                previous.get("last_hit", previous["end"])
            )

            should_merge = False
            tight_gap = 0.55 if self.strict else 0.8
            tight_hit_gap = 1.8 if self.strict else min(2.4, self.max_silence_gap)
            if gap <= tight_gap or hit_gap <= tight_hit_gap:
                should_merge = True
            elif hit_gap <= self.max_bridge_gap and not self.strict:
                span_start = float(previous.get("last_hit", previous["end"]))
                span_end = float(candidate.get("first_hit", candidate["start"]))
                left = np.searchsorted(motion_timestamps, span_start, side="left")
                right = np.searchsorted(motion_timestamps, span_end, side="right")
                if right > left:
                    window = motion_energies[left:right]
                    active_fraction = float(np.mean(window >= merge_motion_threshold))
                    mean_energy = float(np.mean(window))
                    if active_fraction >= 0.48 and mean_energy >= merge_motion_threshold:
                        should_merge = True

            if should_merge:
                projected = max(float(previous["end"]), float(candidate["end"])) - min(
                    float(previous["start"]), float(candidate["start"])
                )
                if projected > self.max_rally_duration and hit_gap > (1.6 if self.strict else 2.0):
                    should_merge = False

            if should_merge:
                previous["end"] = max(float(previous["end"]), float(candidate["end"]))
                previous["last_hit"] = max(
                    float(previous.get("last_hit", previous["end"])),
                    float(candidate.get("last_hit", candidate["end"])),
                )
                previous["first_hit"] = min(
                    float(previous.get("first_hit", previous["start"])),
                    float(candidate.get("first_hit", candidate["start"])),
                )
                previous["start"] = min(float(previous["start"]), float(candidate["start"]))
                previous["confidence"] = round(
                    min(float(previous.get("confidence", 0.9)), float(candidate.get("confidence", 0.9))),
                    3,
                )
            else:
                merged.append(dict(candidate))

        return self._split_overlong_rallies(merged)

    def _split_overlong_rallies(self, rallies: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        """Break multi-point blobs using first/last hit spacing."""
        if not rallies:
            return rallies
        result: List[Dict[str, Any]] = []
        for rally in rallies:
            duration = float(rally["end"]) - float(rally["start"])
            first_hit = float(rally.get("first_hit", rally["start"]))
            last_hit = float(rally.get("last_hit", rally["end"]))
            if duration <= self.max_rally_duration or last_hit - first_hit <= self.max_rally_duration:
                # Also tighten long lead/trail padding.
                rally = dict(rally)
                rally["start"] = max(float(rally["start"]), first_hit - min(self.max_serve_lookback, 2.2))
                rally["end"] = min(float(rally["end"]), last_hit + min(self.residual_motion_window, 1.8))
                if float(rally["end"]) > float(rally["start"]):
                    result.append(rally)
                continue
            # Without per-hit list here, keep the segment but clamp padding hard.
            clamped = dict(rally)
            clamped["start"] = max(float(rally["start"]), first_hit - 1.5)
            clamped["end"] = min(float(rally["end"]), last_hit + 1.5)
            # If still absurdly long, leave as-is for scorer split (when enabled).
            result.append(clamped)
        return result

    def _rally_merge_gap_threshold(self) -> float:
        # Keep post-format merge tight so dead-ball gaps are not re-glued.
        return 0.45 if self.strict else 0.9

    def _detect_audio_hits(self, audio: np.ndarray, sr: int) -> np.ndarray:
        hits = super()._detect_audio_hits(audio, sr)
        self._last_hit_peaks = hits
        return hits

    @staticmethod
    def _clamp_adjacent_overlaps(
        rallies: List[Dict[str, Any]],
        *,
        min_gap: float = 0.35,
        only_when_original_gap_gt: Optional[float] = None,
        original_rallies: Optional[List[Dict[str, Any]]] = None,
    ) -> List[Dict[str, Any]]:
        if len(rallies) <= 1:
            return rallies
        clamped = [dict(item) for item in rallies]
        for index in range(len(clamped) - 1):
            if only_when_original_gap_gt is not None and original_rallies is not None:
                original_gap = (
                    float(original_rallies[index + 1]["start"])
                    - float(original_rallies[index]["end"])
                )
                if original_gap <= only_when_original_gap_gt:
                    continue
            current = clamped[index]
            following = clamped[index + 1]
            latest_end = float(following["start"]) - min_gap
            if float(current["end"]) > latest_end > float(current["start"]):
                current["end"] = max(float(current.get("last_hit", current["start"])), latest_end)
        return clamped

    def _refine_rally_boundaries(
        self,
        video_path: str,
        raw_rallies: List[Dict[str, Any]],
    ) -> List[Dict[str, Any]]:
        """Candidate-span TrackNet + free multimodal scoring, with boundary fallback."""
        if not raw_rallies:
            return raw_rallies

        hit_peaks = getattr(self, "_last_hit_peaks", np.asarray([]))
        motion_timestamps = getattr(self, "_last_motion_timestamps", None)
        motion_energies = getattr(self, "_last_motion_energies", None)

        if (
            self.trajectory_refiner is not None
            and getattr(self.trajectory_refiner, "available", False)
            and self.rally_scorer is not None
            and hasattr(self.trajectory_refiner, "track_points")
        ):
            try:
                fps, points = self.trajectory_refiner.track_points(
                    video_path,
                    raw_rallies,
                    self.court_roi,
                )
                series = TrajectorySeries.from_points(points, fps=fps)
                if len(series) > 0:
                    refined = self.rally_scorer.refine(
                        raw_rallies,
                        hit_peaks=hit_peaks,
                        motion_timestamps=motion_timestamps,
                        motion_energies=motion_energies,
                        trajectory=series,
                    )
                    if self.highlight_filter is not None:
                        refined = self.highlight_filter.apply(refined, hit_peaks)
                    return self._clamp_adjacent_overlaps(refined)
                logger.info("TrackNet produced no points; falling back to boundary refine")
            except Exception as exc:
                logger.warning("Multimodal trajectory scoring skipped: %s", exc)

        # Even without trajectory points, re-apply strict highlight filter.
        if self.highlight_filter is not None and len(np.asarray(hit_peaks)):
            raw_rallies = self.highlight_filter.apply(raw_rallies, np.asarray(hit_peaks, dtype=float))

        if self.trajectory_refiner is None:
            return raw_rallies
        try:
            refined = self.trajectory_refiner.refine(video_path, raw_rallies, self.court_roi)
            if self.highlight_filter is not None and len(np.asarray(hit_peaks)):
                refined = self.highlight_filter.apply(refined, np.asarray(hit_peaks, dtype=float))
            return self._clamp_adjacent_overlaps(
                refined,
                min_gap=0.45,
                only_when_original_gap_gt=0.5,
                original_rallies=raw_rallies,
            )
        except Exception as exc:
            logger.warning("TrackNetV3 boundary refinement skipped: %s", exc)
            return raw_rallies
