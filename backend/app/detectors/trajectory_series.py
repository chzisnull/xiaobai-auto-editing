"""In-memory shuttle trajectory helpers for multimodal rally scoring.

No cloud API and no extra trained model: this only aggregates TrackNet points
already produced on-device by the bundled TrackNetV3 weights.
"""

from __future__ import annotations

from dataclasses import dataclass
import math
from typing import List, Optional, Sequence, Tuple

from backend.app.detectors.tracknet_v3 import TrajectoryPoint


@dataclass(frozen=True)
class TrajectorySeries:
    """Sorted shuttle detections used by the free multimodal scorer."""

    points: Tuple[TrajectoryPoint, ...]
    fps: float = 30.0

    @classmethod
    def from_points(
        cls,
        points: Sequence[TrajectoryPoint],
        fps: float = 30.0,
    ) -> "TrajectorySeries":
        ordered = tuple(sorted(points, key=lambda item: item.time))
        return cls(points=ordered, fps=float(fps) if fps > 0 else 30.0)

    def __len__(self) -> int:
        return len(self.points)

    def between(self, start: float, end: float) -> List[TrajectoryPoint]:
        if end < start or not self.points:
            return []
        return [point for point in self.points if start <= point.time <= end]

    def density(self, start: float, end: float) -> float:
        duration = max(0.05, end - start)
        return len(self.between(start, end)) / duration

    def clusters(
        self,
        start: float,
        end: float,
        max_gap: float = 0.25,
    ) -> List[List[TrajectoryPoint]]:
        points = self.between(start, end)
        if not points:
            return []
        groups: List[List[TrajectoryPoint]] = [[points[0]]]
        for point in points[1:]:
            if point.time - groups[-1][-1].time <= max_gap:
                groups[-1].append(point)
            else:
                groups.append([point])
        return groups

    @staticmethod
    def is_moving(cluster: Sequence[TrajectoryPoint]) -> bool:
        if len(cluster) < 3:
            return False
        path_length = sum(
            math.hypot(current.x - previous.x, current.y - previous.y)
            for previous, current in zip(cluster, cluster[1:])
        )
        displacement = math.hypot(
            cluster[-1].x - cluster[0].x,
            cluster[-1].y - cluster[0].y,
        )
        return path_length >= 0.025 and (displacement >= 0.012 or path_length >= 0.06)

    def flight_segments(
        self,
        start: float,
        end: float,
        max_gap: float = 0.25,
    ) -> List[Tuple[float, float]]:
        segments: List[Tuple[float, float]] = []
        for cluster in self.clusters(start, end, max_gap=max_gap):
            if self.is_moving(cluster):
                segments.append((cluster[0].time, cluster[-1].time))
        return segments

    def flight_fraction(self, start: float, end: float) -> float:
        duration = max(0.05, end - start)
        covered = 0.0
        for seg_start, seg_end in self.flight_segments(start, end):
            covered += max(0.0, min(end, seg_end) - max(start, seg_start))
        return max(0.0, min(1.0, covered / duration))

    def has_flight(self, start: float, end: float, min_points: int = 3) -> bool:
        for cluster in self.clusters(start, end):
            if len(cluster) >= min_points and self.is_moving(cluster):
                return True
        return False

    def earliest_flight_start(
        self,
        anchor: float,
        lookback: float,
        join_slack: float = 0.4,
    ) -> Optional[float]:
        """First moving trajectory that ends near the anchor (serve lead-in)."""
        segments = self.flight_segments(max(0.0, anchor - lookback), anchor + 0.25)
        candidates = [
            seg_start
            for seg_start, seg_end in segments
            if seg_end >= anchor - join_slack
        ]
        if not candidates:
            return None
        return max(0.0, min(candidates) - 0.25)

    def latest_flight_end(
        self,
        anchor: float,
        lookahead: float,
        join_slack: float = 0.45,
    ) -> Optional[float]:
        """Last moving trajectory that starts near the anchor (landing trail)."""
        segments = self.flight_segments(anchor - 0.15, anchor + lookahead)
        candidates = [
            seg_end
            for seg_start, seg_end in segments
            if seg_start <= anchor + join_slack
        ]
        if not candidates:
            return None
        return max(candidates) + 0.25
