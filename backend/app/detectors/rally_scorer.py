"""Free multimodal rally scorer.

Uses only local signals already available in this app:
  - audio hit peaks
  - court-local motion energy
  - TrackNetV3 shuttle trajectory points (bundled open weights)

No cloud LLM, no paid API, no extra training required.

Design goal: keep *in-rally* footage only. Prefer slightly short clips over
gluing dead-ball walking between points into one mega-segment.
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

import numpy as np

from backend.app.detectors.trajectory_series import TrajectorySeries


def _clip01(value: float) -> float:
    return max(0.0, min(1.0, float(value)))


class MultimodalRallyScorer:
    """Rule-based start/end/keep scoring + conservative trajectory merge."""

    def __init__(
        self,
        merge_gap: float = 4.0,
        serve_lookback: float = 2.2,
        land_lookahead: float = 1.8,
        review_threshold: float = 0.58,
        max_rally_duration: float = 28.0,
        min_hit_density: float = 0.18,
    ) -> None:
        self.merge_gap = max(1.0, float(merge_gap))
        self.serve_lookback = max(0.5, float(serve_lookback))
        self.land_lookahead = max(0.5, float(land_lookahead))
        self.review_threshold = _clip01(review_threshold)
        self.max_rally_duration = max(8.0, float(max_rally_duration))
        self.min_hit_density = max(0.05, float(min_hit_density))

    def refine(
        self,
        rallies: List[Dict[str, Any]],
        hit_peaks: Optional[np.ndarray],
        motion_timestamps: Optional[np.ndarray],
        motion_energies: Optional[np.ndarray],
        trajectory: TrajectorySeries,
    ) -> List[Dict[str, Any]]:
        if not rallies:
            return []

        hits = np.asarray(hit_peaks if hit_peaks is not None else [], dtype=float)
        motion_t = motion_timestamps if motion_timestamps is not None else np.asarray([])
        motion_e = motion_energies if motion_energies is not None else np.asarray([])

        expanded = [
            self._expand_with_trajectory(dict(item), trajectory)
            for item in rallies
        ]
        # Prefer tight hit-anchored bounds before merge so dead time is not glued.
        expanded = [self._tighten_to_hits(item, hits) for item in expanded]
        merged = self._merge_by_scores(expanded, hits, motion_t, motion_e, trajectory)
        split = self._split_overlong(merged, hits)
        scored: List[Dict[str, Any]] = []
        for item in split:
            item = self._tighten_to_hits(item, hits)
            scores = self._score_rally(item, hits, motion_t, motion_e, trajectory)
            item["confidence"] = scores["confidence"]
            item["score_start"] = scores["start"]
            item["score_end"] = scores["end"]
            item["score_keep"] = scores["keep"]
            item["needs_review"] = bool(scores["confidence"] < self.review_threshold)
            duration = float(item["end"]) - float(item["start"])
            hit_count = self._hit_count(hits, float(item["start"]), float(item["end"]))
            hit_density = hit_count / max(0.2, duration)
            # Drop sparse pseudo-rallies that are mostly walking/noise.
            if duration < 0.9:
                continue
            if hit_count < 2 and scores["keep"] < 0.45:
                continue
            if duration >= 8.0 and hit_density < self.min_hit_density and scores["keep"] < 0.50:
                continue
            scored.append(item)
        return scored

    def _hit_count(self, hits: np.ndarray, start: float, end: float) -> int:
        if len(hits) == 0:
            return 0
        return int(np.sum((hits >= start - 0.15) & (hits <= end + 0.15)))

    def _tighten_to_hits(self, rally: Dict[str, Any], hits: np.ndarray) -> Dict[str, Any]:
        """Anchor segment to first/last hit so long lead/trail dead time is trimmed."""
        first_hit = float(rally.get("first_hit", rally["start"]))
        last_hit = float(rally.get("last_hit", rally["end"]))
        start = float(rally["start"])
        end = float(rally["end"])

        if len(hits):
            inside = hits[(hits >= start - 0.5) & (hits <= end + 0.5)]
            if len(inside):
                first_hit = float(inside[0])
                last_hit = float(inside[-1])

        # Keep a short serve lead and landing tail only.
        start = max(start, first_hit - min(self.serve_lookback, 2.0))
        end = min(end, last_hit + min(self.land_lookahead, 1.6))
        # Never keep more than ~2.2s before first hit or ~1.8s after last hit.
        start = max(start, first_hit - 2.2)
        end = min(end, last_hit + 1.8)

        rally["first_hit"] = first_hit
        rally["last_hit"] = last_hit
        rally["start"] = max(0.0, start)
        rally["end"] = max(rally["start"] + 0.25, end)
        return rally

    def _expand_with_trajectory(
        self,
        rally: Dict[str, Any],
        trajectory: TrajectorySeries,
    ) -> Dict[str, Any]:
        first_hit = float(rally.get("first_hit", rally["start"]))
        last_hit = float(rally.get("last_hit", rally["end"]))
        start = float(rally["start"])
        end = float(rally["end"])

        serve = trajectory.earliest_flight_start(first_hit, self.serve_lookback)
        if serve is not None and first_hit - serve <= self.serve_lookback:
            start = min(start, max(serve, first_hit - self.serve_lookback))

        land = trajectory.latest_flight_end(last_hit, self.land_lookahead)
        if land is not None and land - last_hit <= self.land_lookahead + 0.4:
            end = max(end, min(land, last_hit + self.land_lookahead + 0.3))

        rally["start"] = max(0.0, start)
        rally["end"] = max(rally["start"] + 0.2, end)
        return rally

    def _merge_by_scores(
        self,
        rallies: List[Dict[str, Any]],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
        trajectory: TrajectorySeries,
    ) -> List[Dict[str, Any]]:
        if len(rallies) <= 1:
            return rallies

        merged: List[Dict[str, Any]] = [dict(rallies[0])]
        for candidate in rallies[1:]:
            previous = merged[-1]
            gap = float(candidate["start"]) - float(previous["end"])
            hit_gap = float(candidate.get("first_hit", candidate["start"])) - float(
                previous.get("last_hit", previous["end"])
            )
            continue_score = self._continue_score(
                previous,
                candidate,
                hits,
                motion_t,
                motion_e,
                trajectory,
            )

            should_merge = False
            # Only auto-merge very tight fragments. Larger gaps need strong
            # evidence that the shuttle is still in play (not multi-court noise).
            if gap <= 0.7 or hit_gap <= 2.2:
                should_merge = True
            elif hit_gap <= self.merge_gap and continue_score >= 0.72:
                # Require actual flight in the hole, not motion alone.
                left = float(previous.get("last_hit", previous["end"]))
                right = float(candidate.get("first_hit", candidate["start"]))
                if trajectory.has_flight(left, right) and self._hit_count(hits, left, right) >= 1:
                    should_merge = True

            # Never merge into a segment that would become a multi-point blob.
            if should_merge:
                projected = max(float(previous["end"]), float(candidate["end"])) - min(
                    float(previous["start"]), float(candidate["start"])
                )
                if projected > self.max_rally_duration and hit_gap > 2.0:
                    should_merge = False

            if should_merge:
                previous["end"] = max(float(previous["end"]), float(candidate["end"]))
                previous["start"] = min(float(previous["start"]), float(candidate["start"]))
                previous["first_hit"] = min(
                    float(previous.get("first_hit", previous["start"])),
                    float(candidate.get("first_hit", candidate["start"])),
                )
                previous["last_hit"] = max(
                    float(previous.get("last_hit", previous["end"])),
                    float(candidate.get("last_hit", candidate["end"])),
                )
                previous["confidence"] = round(
                    min(
                        float(previous.get("confidence", 0.9)),
                        float(candidate.get("confidence", 0.9)),
                    ),
                    3,
                )
            else:
                merged.append(dict(candidate))
        return merged

    def _split_overlong(
        self,
        rallies: List[Dict[str, Any]],
        hits: np.ndarray,
    ) -> List[Dict[str, Any]]:
        """Cut mega-segments back into rally-sized chunks using hit silence."""
        if not rallies:
            return []
        result: List[Dict[str, Any]] = []
        for rally in rallies:
            duration = float(rally["end"]) - float(rally["start"])
            if duration <= self.max_rally_duration or len(hits) == 0:
                result.append(rally)
                continue

            start = float(rally["start"])
            end = float(rally["end"])
            inside = hits[(hits >= start) & (hits <= end)]
            if len(inside) < 4:
                result.append(rally)
                continue

            # Split on the largest silences between hits.
            groups: List[List[float]] = [[float(inside[0])]]
            for hit in inside[1:]:
                hit_f = float(hit)
                if hit_f - groups[-1][-1] >= 2.6:
                    groups.append([hit_f])
                else:
                    groups[-1].append(hit_f)

            if len(groups) == 1:
                # Force-split near the middle largest gap if still too long.
                gaps = [
                    (float(inside[i + 1]) - float(inside[i]), i)
                    for i in range(len(inside) - 1)
                ]
                gaps.sort(reverse=True)
                if gaps and gaps[0][0] >= 2.0:
                    index = gaps[0][1]
                    groups = [
                        [float(x) for x in inside[: index + 1]],
                        [float(x) for x in inside[index + 1 :]],
                    ]
                else:
                    result.append(rally)
                    continue

            for group in groups:
                first_hit = group[0]
                last_hit = group[-1]
                seg = {
                    "start": max(start, first_hit - 1.2),
                    "end": min(end, last_hit + 1.3),
                    "first_hit": first_hit,
                    "last_hit": last_hit,
                    "confidence": float(rally.get("confidence", 0.75)),
                }
                if seg["end"] - seg["start"] >= 0.9:
                    result.append(seg)
        return result

    def _continue_score(
        self,
        previous: Dict[str, Any],
        following: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
        trajectory: TrajectorySeries,
    ) -> float:
        left = float(previous.get("last_hit", previous["end"]))
        right = float(following.get("first_hit", following["start"]))
        if right <= left:
            return 1.0

        flight = trajectory.flight_fraction(left, right)
        dens = _clip01(trajectory.density(left, right) / 6.0)
        has_flight = 1.0 if trajectory.has_flight(left, right) else 0.0

        hit_count = int(np.sum((hits > left) & (hits < right))) if len(hits) else 0
        hit_score = _clip01(hit_count / 3.0)

        motion_score = 0.0
        if len(motion_t) and len(motion_e):
            i0 = int(np.searchsorted(motion_t, left, side="left"))
            i1 = int(np.searchsorted(motion_t, right, side="right"))
            if i1 > i0:
                window = motion_e[i0:i1]
                baseline = float(np.median(motion_e))
                active = float(np.percentile(motion_e, 85))
                thr = baseline + 0.18 * max(0.0, active - baseline)
                motion_score = _clip01(float(np.mean(window >= thr)))

        # Hits + clear flight dominate; bare motion is weak (walking/picking).
        return _clip01(
            0.35 * has_flight
            + 0.20 * flight
            + 0.15 * dens
            + 0.25 * hit_score
            + 0.05 * motion_score
        )

    def _score_rally(
        self,
        rally: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
        trajectory: TrajectorySeries,
    ) -> Dict[str, float]:
        start = float(rally["start"])
        end = float(rally["end"])
        first_hit = float(rally.get("first_hit", start))
        last_hit = float(rally.get("last_hit", end))
        duration = max(0.2, end - start)

        hit_count = self._hit_count(hits, start, end)
        hit_density = hit_count / duration
        silence_after = max(0.0, end - last_hit)

        flight_frac = trajectory.flight_fraction(start, end)
        traj_density = trajectory.density(start, end)
        serve_lead = max(0.0, first_hit - start)
        land_trail = max(0.0, end - last_hit)

        motion_active = 0.0
        motion_decay = 0.5
        if len(motion_t) and len(motion_e):
            i0 = int(np.searchsorted(motion_t, start, side="left"))
            i1 = int(np.searchsorted(motion_t, end, side="right"))
            if i1 > i0:
                window = motion_e[i0:i1]
                baseline = float(np.median(motion_e))
                active = float(np.percentile(motion_e, 85))
                thr = baseline + 0.10 * max(0.0, active - baseline)
                motion_active = _clip01(float(np.mean(window >= thr)))
                mid = (i0 + i1) // 2
                head = float(np.mean(motion_e[i0:max(i0 + 1, mid)]))
                tail = float(np.mean(motion_e[mid:i1]))
                if head > 1e-6:
                    motion_decay = _clip01(1.0 - (tail / head))

        start_score = _clip01(
            0.30 * _clip01(serve_lead / 1.8)
            + 0.35 * (1.0 if trajectory.has_flight(start, first_hit + 0.3) else 0.0)
            + 0.25 * _clip01(1.0 - abs(first_hit - start - 0.6) / 1.5)
            + 0.10 * motion_active
        )
        end_score = _clip01(
            0.30 * _clip01(silence_after / 1.5)
            + 0.30 * (1.0 if land_trail >= 0.35 else _clip01(land_trail / 0.35))
            + 0.20 * motion_decay
            + 0.20 * (1.0 - trajectory.flight_fraction(max(last_hit, end - 0.8), end + 0.05))
        )
        keep_score = _clip01(
            0.30 * flight_frac
            + 0.35 * _clip01(hit_density / 0.7)
            + 0.20 * motion_active
            + 0.10 * _clip01(traj_density / 5.0)
            + 0.05 * _clip01(min(duration, 12.0) / 12.0)
        )
        # Penalize bloated low-density segments.
        if duration > 20.0 and hit_density < 0.25:
            keep_score *= 0.7
        confidence = round(
            _clip01(0.45 * keep_score + 0.25 * start_score + 0.30 * end_score),
            3,
        )
        if hit_count >= 3 and duration >= 2.0 and hit_density >= 0.25:
            confidence = max(confidence, 0.55)
        return {
            "start": round(start_score, 3),
            "end": round(end_score, 3),
            "keep": round(keep_score, 3),
            "confidence": confidence,
        }
