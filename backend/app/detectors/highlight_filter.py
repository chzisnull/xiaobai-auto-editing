"""Strict highlight post-filter: keep only in-rally exchanges.

No external model. Uses hit peaks + optional court-motion energy to:
  - anchor segments to first/last hit with short pads
  - split on long hit silence *only when court motion also dies*
  - re-merge fragments that were over-split mid-exchange
  - drop sparse / single-touch / dead-ball segments
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

import numpy as np


class StrictHighlightFilter:
    """Post-process coarse rallies into tight highlight clips."""

    def __init__(
        self,
        serve_pad: float = 0.75,
        land_pad: float = 0.95,
        max_hit_silence: float = 2.5,
        motion_bridge_silence: float = 3.6,
        min_hits: int = 3,
        min_duration: float = 1.5,
        max_duration: float = 22.0,
        min_hit_density: float = 0.30,
        remerge_gap: float = 1.35,
    ) -> None:
        self.serve_pad = max(0.2, float(serve_pad))
        self.land_pad = max(0.2, float(land_pad))
        self.max_hit_silence = max(1.0, float(max_hit_silence))
        self.motion_bridge_silence = max(self.max_hit_silence, float(motion_bridge_silence))
        self.min_hits = max(2, int(min_hits))
        self.min_duration = max(0.6, float(min_duration))
        self.max_duration = max(8.0, float(max_duration))
        self.min_hit_density = max(0.1, float(min_hit_density))
        self.remerge_gap = max(0.3, float(remerge_gap))

    def apply(
        self,
        rallies: List[Dict[str, Any]],
        hit_peaks: Optional[np.ndarray],
        motion_timestamps: Optional[np.ndarray] = None,
        motion_energies: Optional[np.ndarray] = None,
    ) -> List[Dict[str, Any]]:
        hits = np.asarray(hit_peaks if hit_peaks is not None else [], dtype=float)
        motion_t = np.asarray(motion_timestamps if motion_timestamps is not None else [], dtype=float)
        motion_e = np.asarray(motion_energies if motion_energies is not None else [], dtype=float)
        if not rallies:
            return []

        split = self._split_by_hit_silence(rallies, hits, motion_t, motion_e)
        tightened = [self._anchor_to_hits(item, hits, motion_t, motion_e) for item in split]
        merged = self._remerge_over_split(tightened, hits, motion_t, motion_e)
        kept: List[Dict[str, Any]] = []
        for item in merged:
            if self._is_highlight(item, hits, motion_t, motion_e):
                kept.append(item)
        return self._dedupe_overlaps(kept)

    def _hits_in(self, hits: np.ndarray, start: float, end: float) -> np.ndarray:
        if len(hits) == 0:
            return hits
        return hits[(hits >= start - 0.05) & (hits <= end + 0.05)]

    def _motion_active_fraction(self, start: float, end: float, motion_t: np.ndarray, motion_e: np.ndarray) -> float:
        if end <= start or len(motion_t) == 0 or len(motion_e) == 0:
            return 0.0
        left = int(np.searchsorted(motion_t, start, side="left"))
        right = int(np.searchsorted(motion_t, end, side="right"))
        if right <= left:
            return 0.0
        window = motion_e[left:right]
        baseline = float(np.median(motion_e))
        p85 = float(np.percentile(motion_e, 85))
        span = max(0.0, p85 - baseline)
        mean_window = float(np.mean(window))
        if span < 1e-5:
            # Flat energy: elevated plateau is "live"; near-zero plateau is idle.
            if baseline >= 0.012 and mean_window >= baseline * 0.85:
                return 1.0
            thr = max(baseline + 0.004, 0.012)
        else:
            thr = max(baseline + 0.14 * span, 0.008)
        if float(np.max(window)) < thr:
            return 0.0
        return float(np.mean(window >= thr))

    def _gap_still_live(
        self,
        previous_hit: float,
        next_hit: float,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> bool:
        """True when a hit hole is likely still inside a rally (players exchanging)."""
        gap = next_hit - previous_hit
        if gap <= self.max_hit_silence:
            return True
        if gap > self.motion_bridge_silence:
            return False
        if len(motion_t) == 0:
            return False
        active = self._motion_active_fraction(previous_hit, next_hit, motion_t, motion_e)
        # Mid-rally soft exchanges keep elevated court motion; dead ball usually drops.
        return active >= 0.42

    def _anchor_to_hits(
        self,
        rally: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> Dict[str, Any]:
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
        start = max(0.0, first_hit - self.serve_pad)
        end = max(start + 0.25, last_hit + self.land_pad)

        # Trim landing tail if court motion dies immediately after last hit
        # (standing around / walking after the point).
        if len(motion_t):
            tail_active = self._motion_active_fraction(last_hit, last_hit + self.land_pad + 0.4, motion_t, motion_e)
            if tail_active < 0.28:
                end = min(end, last_hit + min(0.55, self.land_pad))
            head_active = self._motion_active_fraction(max(0.0, first_hit - self.serve_pad - 0.2), first_hit, motion_t, motion_e)
            if head_active < 0.25:
                start = max(start, first_hit - min(0.45, self.serve_pad))

        item["start"] = start
        item["end"] = max(start + 0.25, end)
        return item

    def _split_by_hit_silence(
        self,
        rallies: List[Dict[str, Any]],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
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
                prev = groups[-1][-1]
                gap = hit_f - prev
                if gap > self.max_hit_silence and not self._gap_still_live(prev, hit_f, motion_t, motion_e):
                    groups.append([hit_f])
                elif gap > self.motion_bridge_silence:
                    groups.append([hit_f])
                else:
                    groups[-1].append(hit_f)

            # Force-split still-too-long groups only on large *dead* gaps.
            refined_groups: List[List[float]] = []
            for group in groups:
                span = group[-1] - group[0]
                if span <= self.max_duration or len(group) < 5:
                    refined_groups.append(group)
                    continue
                gaps = [
                    (group[index + 1] - group[index], index)
                    for index in range(len(group) - 1)
                ]
                gaps.sort(reverse=True)
                cut_done = False
                for gap_len, index in gaps[:3]:
                    if gap_len < max(2.0, self.max_hit_silence * 0.9):
                        break
                    left_hit = group[index]
                    right_hit = group[index + 1]
                    if not self._gap_still_live(left_hit, right_hit, motion_t, motion_e):
                        refined_groups.append(group[: index + 1])
                        refined_groups.append(group[index + 1 :])
                        cut_done = True
                        break
                if not cut_done:
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

    def _remerge_over_split(
        self,
        rallies: List[Dict[str, Any]],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> List[Dict[str, Any]]:
        """Glue fragments that look like one exchange split by a missed hit."""
        if len(rallies) <= 1:
            return rallies
        ordered = sorted((dict(item) for item in rallies), key=lambda item: float(item["start"]))
        merged: List[Dict[str, Any]] = [ordered[0]]
        for item in ordered[1:]:
            prev = merged[-1]
            gap = float(item["start"]) - float(prev["end"])
            hit_gap = float(item.get("first_hit", item["start"])) - float(prev.get("last_hit", prev["end"]))
            projected = float(item["end"]) - float(prev["start"])

            should = False
            if projected <= self.max_duration + 2.0:
                if gap <= self.remerge_gap and hit_gap <= self.motion_bridge_silence:
                    live = self._gap_still_live(
                        float(prev.get("last_hit", prev["end"])),
                        float(item.get("first_hit", item["start"])),
                        motion_t,
                        motion_e,
                    )
                    # Also remerge very tight fragment pairs (common false mid-cuts).
                    if live or (gap <= 0.55 and hit_gap <= 2.0):
                        should = True
                elif hit_gap <= self.max_hit_silence:
                    should = True

            if should:
                prev["end"] = max(float(prev["end"]), float(item["end"]))
                prev["start"] = min(float(prev["start"]), float(item["start"]))
                prev["first_hit"] = min(
                    float(prev.get("first_hit", prev["start"])),
                    float(item.get("first_hit", item["start"])),
                )
                prev["last_hit"] = max(
                    float(prev.get("last_hit", prev["end"])),
                    float(item.get("last_hit", item["end"])),
                )
                prev["confidence"] = round(
                    min(float(prev.get("confidence", 0.8)), float(item.get("confidence", 0.8))),
                    3,
                )
            else:
                merged.append(item)
        return merged

    def _is_highlight(
        self,
        rally: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> bool:
        start = float(rally["start"])
        end = float(rally["end"])
        duration = end - start
        if duration < self.min_duration:
            return False
        if duration > self.max_duration + 5.0:
            return False

        first_hit = float(rally.get("first_hit", start))
        last_hit = float(rally.get("last_hit", end))
        inside = self._hits_in(hits, first_hit, last_hit)
        hit_count = len(inside)
        if hit_count < self.min_hits:
            return False

        hit_span = max(0.25, last_hit - first_hit)
        density = hit_count / hit_span
        if density < self.min_hit_density and hit_count < self.min_hits + 2:
            return False
        if duration >= 12.0 and density < self.min_hit_density * 0.85:
            return False

        # Reject clips that are mostly inactive court motion (waiting/walking).
        if len(motion_t):
            active = self._motion_active_fraction(first_hit, last_hit, motion_t, motion_e)
            if duration >= 2.5 and active < 0.20:
                return False
            if duration >= 3.0 and active < 0.28 and density < self.min_hit_density * 1.15:
                return False
            # Short low-activity stubs are common false "dead ball" clips.
            if duration <= 5.0 and active < 0.32 and hit_count <= 4:
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
                prev_hits = float(prev.get("last_hit", prev["end"])) - float(prev.get("first_hit", prev["start"]))
                item_hits = float(item.get("last_hit", item["end"])) - float(item.get("first_hit", item["start"]))
                if item_hits > prev_hits:
                    merged[-1] = dict(item)
                continue
            merged.append(dict(item))
        return merged
