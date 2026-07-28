"""Strict highlight post-filter: keep only in-rally exchanges.

No external model. Uses hit peaks (and optional trajectory density later) to:
  - anchor segments to first/last hit with short pads
  - split on long hit silence
  - drop sparse / single-touch / bloated dead-ball segments
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

import numpy as np


class StrictHighlightFilter:
    """Post-process coarse rallies into tight highlight clips."""

    def __init__(
        self,
        serve_pad: float = 0.85,
        land_pad: float = 1.0,
        max_hit_silence: float = 2.2,
        min_hits: int = 3,
        min_duration: float = 1.4,
        max_duration: float = 20.0,
        min_hit_density: float = 0.32,
    ) -> None:
        self.serve_pad = max(0.2, float(serve_pad))
        self.land_pad = max(0.2, float(land_pad))
        self.max_hit_silence = max(1.0, float(max_hit_silence))
        self.min_hits = max(2, int(min_hits))
        self.min_duration = max(0.6, float(min_duration))
        self.max_duration = max(8.0, float(max_duration))
        self.min_hit_density = max(0.1, float(min_hit_density))

    def apply(
        self,
        rallies: List[Dict[str, Any]],
        hit_peaks: Optional[np.ndarray],
    ) -> List[Dict[str, Any]]:
        hits = np.asarray(hit_peaks if hit_peaks is not None else [], dtype=float)
        if not rallies:
            return []

        split = self._split_by_hit_silence(rallies, hits)
        tightened = [self._anchor_to_hits(item, hits) for item in split]
        kept: List[Dict[str, Any]] = []
        for item in tightened:
            if self._is_highlight(item, hits):
                kept.append(item)
        return self._dedupe_overlaps(kept)

    def _hits_in(self, hits: np.ndarray, start: float, end: float) -> np.ndarray:
        if len(hits) == 0:
            return hits
        return hits[(hits >= start - 0.05) & (hits <= end + 0.05)]

    def _anchor_to_hits(self, rally: Dict[str, Any], hits: np.ndarray) -> Dict[str, Any]:
        item = dict(rally)
        start = float(item["start"])
        end = float(item["end"])
        first_hit = float(item.get("first_hit", start))
        last_hit = float(item.get("last_hit", end))

        inside = self._hits_in(hits, start - 0.3, end + 0.3)
        if len(inside):
            first_hit = float(inside[0])
            last_hit = float(inside[-1])

        item["first_hit"] = first_hit
        item["last_hit"] = last_hit
        item["start"] = max(0.0, first_hit - self.serve_pad)
        item["end"] = max(item["start"] + 0.25, last_hit + self.land_pad)
        return item

    def _split_by_hit_silence(
        self,
        rallies: List[Dict[str, Any]],
        hits: np.ndarray,
    ) -> List[Dict[str, Any]]:
        if len(hits) == 0:
            return [dict(item) for item in rallies]

        result: List[Dict[str, Any]] = []
        for rally in rallies:
            start = float(rally["start"])
            end = float(rally["end"])
            inside = self._hits_in(hits, start, end)
            if len(inside) < 2:
                result.append(dict(rally))
                continue

            groups: List[List[float]] = [[float(inside[0])]]
            for hit in inside[1:]:
                hit_f = float(hit)
                if hit_f - groups[-1][-1] > self.max_hit_silence:
                    groups.append([hit_f])
                else:
                    groups[-1].append(hit_f)

            # Further force-split still-too-long groups near the largest gap.
            refined_groups: List[List[float]] = []
            for group in groups:
                span = group[-1] - group[0]
                if span <= self.max_duration or len(group) < 4:
                    refined_groups.append(group)
                    continue
                gaps = [
                    (group[index + 1] - group[index], index)
                    for index in range(len(group) - 1)
                ]
                gaps.sort(reverse=True)
                if gaps and gaps[0][0] >= max(1.6, self.max_hit_silence * 0.75):
                    cut = gaps[0][1]
                    refined_groups.append(group[: cut + 1])
                    refined_groups.append(group[cut + 1 :])
                else:
                    refined_groups.append(group)

            for group in refined_groups:
                conf = float(rally.get("confidence", 0.8))
                result.append({
                    "start": group[0] - self.serve_pad,
                    "end": group[-1] + self.land_pad,
                    "first_hit": group[0],
                    "last_hit": group[-1],
                    "confidence": conf,
                })
        return result

    def _is_highlight(self, rally: Dict[str, Any], hits: np.ndarray) -> bool:
        start = float(rally["start"])
        end = float(rally["end"])
        duration = end - start
        if duration < self.min_duration or duration > self.max_duration + 4.0:
            # Allow a little slack after pads; hard drop extreme blobs.
            if duration > self.max_duration + 4.0:
                return False
            if duration < self.min_duration:
                return False

        inside = self._hits_in(hits, float(rally.get("first_hit", start)), float(rally.get("last_hit", end)))
        hit_count = len(inside)
        if hit_count < self.min_hits:
            return False

        hit_span = max(0.25, float(rally.get("last_hit", end)) - float(rally.get("first_hit", start)))
        density = hit_count / hit_span
        if density < self.min_hit_density and hit_count < self.min_hits + 2:
            return False
        # Very long segment with sparse hits is almost always dead-ball padding.
        if duration >= 12.0 and density < self.min_hit_density * 0.9:
            return False
        return True

    @staticmethod
    def _dedupe_overlaps(rallies: List[Dict[str, Any]]) -> List[Dict[str, Any]]:
        if len(rallies) <= 1:
            return rallies
        ordered = sorted(rallies, key=lambda item: float(item["start"]))
        merged: List[Dict[str, Any]] = [dict(ordered[0])]
        for item in ordered[1:]:
            prev = merged[-1]
            if float(item["start"]) <= float(prev["end"]) + 0.15:
                # Prefer the denser / longer exchange when overlapping.
                prev_hits = float(prev.get("last_hit", prev["end"])) - float(prev.get("first_hit", prev["start"]))
                item_hits = float(item.get("last_hit", item["end"])) - float(item.get("first_hit", item["start"]))
                if item_hits > prev_hits:
                    merged[-1] = dict(item)
                continue
            merged.append(dict(item))
        return merged
