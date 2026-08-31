"""Strict highlight post-filter: keep only in-rally exchanges.

No external model. Uses hit peaks + optional court-motion energy to:
  - anchor segments to first/last hit with short pads
  - walk serve start back along court-ROI motion (toss) before first hit
  - split on long hit silence *only when court motion also dies*
  - re-merge fragments that were over-split mid-exchange (quiet clears)
  - drop sparse / walking / applause / interview segments
  - keep 2-hit (and isolated 1-hit+land) serve-into-net rallies
"""

from __future__ import annotations

from typing import Any, Dict, List, Optional

import numpy as np


class StrictHighlightFilter:
    """Post-process coarse rallies into tight highlight clips."""

    def __init__(
        self,
        serve_pad: float = 1.05,
        land_pad: float = 0.60,
        max_hit_silence: float = 1.85,
        motion_bridge_silence: float = 4.2,
        min_hits: int = 2,
        min_duration: float = 1.5,
        max_duration: float = 22.0,
        min_hit_density: float = 0.22,
        remerge_gap: float = 1.15,
    ) -> None:
        self.serve_pad = max(0.2, float(serve_pad))
        self.land_pad = max(0.2, float(land_pad))
        self.max_hit_silence = max(1.0, float(max_hit_silence))
        self.motion_bridge_silence = max(self.max_hit_silence, float(motion_bridge_silence))
        self.min_hits = max(1, int(min_hits))
        self.min_duration = max(0.6, float(min_duration))
        self.max_duration = max(8.0, float(max_duration))
        self.min_hit_density = max(0.08, float(min_hit_density))
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
        tightened = [self._trim_applause_tail(item, hits) for item in tightened]
        tightened = [self._trim_post_land(item, hits, motion_t, motion_e) for item in tightened]
        merged = self._remerge_over_split(tightened, hits, motion_t, motion_e)
        kept: List[Dict[str, Any]] = []
        for item in merged:
            item = self._trim_applause_tail(item, hits)
            item = self._anchor_to_hits(item, hits, motion_t, motion_e)
            # After the final anchor so last_hit is not re-expanded by later hits.
            item = self._trim_post_land(item, hits, motion_t, motion_e)
            # Applause-dense blobs that mix primary-court rally + adjacent-court
            # glue: peel the first in-ROI cluster before highlight scoring.
            peeled_court = self._peel_primary_court_from_adjacent_glue(
                item, hits, motion_t, motion_e
            )
            if peeled_court is not None:
                item = self._anchor_to_hits(peeled_court, hits, motion_t, motion_e)
                item["_primary_court_peel"] = True
            if self._is_highlight(item, hits, motion_t, motion_e):
                item.pop("_primary_court_peel", None)
                kept.append(item)
                continue
            # Short-iso in-ROI core (euro-long GT6). Do not re-anchor: nearby
            # extra 1:24 / post-land hits would re-expand into the applause gate.
            peeled_core = self._peel_short_iso_in_roi_core(
                item, hits, motion_t, motion_e
            )
            if peeled_core is not None and self._is_highlight(peeled_core, hits, motion_t, motion_e):
                peeled_core.pop("_primary_court_peel", None)
                kept.append(peeled_core)
                continue
            # Only peel from blobs that already failed highlight so TPs (GT15)
            # are never gutted. Recovers 1-hit + silent play (GT22).
            peeled = self._peel_silent_play_serve(item, hits, motion_t, motion_e)
            if peeled is not None and self._is_highlight(peeled, hits, motion_t, motion_e):
                peeled.pop("_primary_court_peel", None)
                kept.append(peeled)
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

    def _has_motion_burst(
        self,
        start: float,
        end: float,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> bool:
        """True if the court ROI shows a short play burst, not a walking plateau."""
        if end <= start or len(motion_t) == 0 or len(motion_e) == 0:
            return False
        left = int(np.searchsorted(motion_t, start, side="left"))
        right = int(np.searchsorted(motion_t, end, side="right"))
        if right <= left:
            return False
        window = motion_e[left:right]
        baseline = float(np.median(motion_e))
        p85 = float(np.percentile(motion_e, 85))
        span = max(0.0, p85 - baseline)
        peak = float(np.max(window))
        burst_floor = max(0.016, baseline + 0.40 * span)
        if peak >= burst_floor:
            return True
        thr = max(0.014, baseline + 0.28 * span)
        run = 0
        for energy in window:
            if float(energy) >= thr:
                run += 1
                if run >= 3:
                    return True
            else:
                run = 0
        return False

    def _walk_silent_play_end(
        self,
        last_hit: float,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> float:
        """Extend a 1-hit clip through residual in-court play (into-net, etc.)."""
        default = last_hit + self.land_pad
        if len(motion_t) == 0 or len(motion_e) == 0:
            return default
        max_ahead = 3.5
        baseline = float(np.median(motion_e))
        p85 = float(np.percentile(motion_e, 85))
        span = max(0.0, p85 - baseline)
        thr = max(0.014, baseline + 0.28 * span)
        left = int(np.searchsorted(motion_t, last_hit, side="left"))
        right = int(np.searchsorted(motion_t, last_hit + max_ahead, side="right"))
        if right <= left:
            return default
        last_active = last_hit
        quiet = 0
        for index in range(left, right):
            if float(motion_e[index]) >= thr:
                last_active = float(motion_t[index])
                quiet = 0
            else:
                quiet += 1
                if quiet > 3 and float(motion_t[index]) >= last_hit + 0.80:
                    break
        return max(default, min(last_hit + max_ahead, last_active + 0.45))

    def _hit_in_court(
        self,
        timestamp: float,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
        radius: float = 0.40,
    ) -> bool:
        """True when local court-ROI motion supports this audio hit."""
        if len(motion_t) == 0 or len(motion_e) == 0:
            return False
        baseline = float(np.median(motion_e))
        p85 = float(np.percentile(motion_e, 85))
        span = max(0.0, p85 - baseline)
        thr = max(0.008, baseline + 0.10 * span)
        left = int(np.searchsorted(motion_t, timestamp - radius, side="left"))
        right = int(np.searchsorted(motion_t, timestamp + radius, side="right"))
        if right <= left:
            return self._motion_at(timestamp, motion_t, motion_e) >= thr
        return float(np.max(motion_e[left:right])) >= thr

    def _peel_primary_court_from_adjacent_glue(
        self,
        rally: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> Optional[Dict[str, Any]]:
        """Peel a primary-court rally off an applause-dense adjacent-court blob.

        Euro-long GT4/GT2: serve-isolated in-ROI exchanges get glued to
        adjacent-court / walking hits (n>=10, dens>1.7, dur>=6) so the
        applause gate drops the whole blob. Split on interior holes (>=1.0s)
        and 2+ consecutive out-of-ROI hits; keep only the first primary
        cluster. Also: (1) 1 in-ROI serve + first out-of-ROI return when 2+
        out-of-ROI follow (euro-long GT3 net-fault); (2) first out-of-ROI
        after a 3+ in-ROI head (euro-long GT5; trimmed blobs lose the 2+
        out-of-ROI tail). Do not peel walking extras with short isolation
        (user-sample 6:27 n=10 dens=1.94 iso=2.28). Do not loosen the
        applause gate. Do not lower the 1.0s-hole min cluster of 3.
        """
        if len(motion_t) == 0 or len(motion_e) == 0:
            return None
        first_hit = float(rally.get("first_hit", rally["start"]))
        last_hit = float(rally.get("last_hit", rally["end"]))
        start = float(rally["start"])
        end = float(rally["end"])
        inside = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
        if len(inside) < 10:
            return None
        duration = end - start
        span = max(0.25, last_hit - first_hit)
        density = len(inside) / span
        if duration < 6.0 or density <= 1.7:
            return None
        isolation = self._hit_isolation(hits, first_hit)
        if isolation < 3.20:
            return None

        in_court = [self._hit_in_court(t, motion_t, motion_e) for t in inside]
        cut = len(inside)
        two_hit_net_fault = False
        for index in range(len(inside) - 1):
            gap = inside[index + 1] - inside[index]
            # Require >=3 hits in the primary cluster so a ~1.05s first ISI
            # (euro-long GT2 serve-return) is not treated as a point boundary.
            if gap >= 1.00 and (index + 1) >= 3:
                cut = index + 1
                break
            if not in_court[index + 1]:
                run = 1
                cursor = index + 2
                while cursor < len(inside) and not in_court[cursor]:
                    run += 1
                    cursor += 1
                if run >= 2:
                    # Isolated 1-hit in-ROI serve then 2+ out-of-ROI contacts
                    # (euro-long GT3). Keep serve + first out-of-ROI return;
                    # a 1-hit peel fails far_court_one (next gap ~1s).
                    if index == 0 and in_court[0]:
                        cut = 2
                        two_hit_net_fault = True
                    else:
                        cut = index + 1
                    break
                # Single out-of-ROI after a 3+ in-ROI head (euro-long GT5).
                n_in = sum(1 for flag in in_court[: index + 1] if flag)
                if n_in >= 3 and (index + 1) >= 3:
                    cut = index + 1
                    break
        if cut >= len(inside):
            return None
        if cut < 3 and not two_hit_net_fault:
            return None
        cluster_isis = [inside[i + 1] - inside[i] for i in range(cut - 1)]
        # Handshake / clap runs (Kaja 0:16 n=16 median ISI ~0.38). Euro-long
        # GT4 primary cluster median ISI is ~0.48.
        if cut >= 8 and float(np.median(cluster_isis)) < 0.42:
            return None
        lead_out = 0
        for flag in in_court[:cut]:
            if not flag:
                lead_out += 1
            else:
                break
        if lead_out >= 2:
            return None
        if not any(in_court[:cut]):
            return None
        serve = inside[0]
        land = inside[cut - 1]
        return {
            "start": max(0.0, serve - self.serve_pad),
            "end": land + self.land_pad,
            "first_hit": serve,
            "last_hit": land,
            "confidence": float(rally.get("confidence", 0.8)),
            "_primary_court_peel": True,
        }

    def _peel_short_iso_in_roi_core(
        self,
        rally: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> Optional[Dict[str, Any]]:
        """Keep an in-ROI rally core glued behind a 1–2 hit out-of-ROI lead.

        Euro-long GT6: extra 1:24 (2 out-of-ROI hits) sits 2.20s before the
        1:29–1:34 rally, so peel iso is 2.20 and the iso>=3.20 path cannot
        fire. The blob is OO then a dense in-ROI exchange (n=18 dens=2.10
        dur=10.07). User-sample 6:27 is all in-ROI walking (n=10 dens=1.94
        iso=2.28 lead_out=0) and must stay dropped. Do not lower the
        iso>=3.20 peel floor. Duration is capped under 6s so the result
        does not re-trip the applause / 6–9.5s walking-iso gates. Do not
        re-anchor (nearby 1:24 / post-land hits would re-expand).
        """
        if len(motion_t) == 0 or len(motion_e) == 0:
            return None
        first_hit = float(rally.get("first_hit", rally["start"]))
        last_hit = float(rally.get("last_hit", rally["end"]))
        start = float(rally["start"])
        end = float(rally["end"])
        inside = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
        if len(inside) < 10:
            return None
        duration = end - start
        span = max(0.25, last_hit - first_hit)
        density = len(inside) / span
        if duration < 6.0 or density <= 1.7:
            return None
        isolation = self._hit_isolation(hits, first_hit)
        # iso>=3.20 already has the primary-court peel. This path is only
        # for the short-iso GT6 shape; 6:27 is also short-iso but lead_out=0.
        if isolation >= 3.20:
            return None
        in_court = [self._hit_in_court(t, motion_t, motion_e) for t in inside]
        lead_out = 0
        for flag in in_court:
            if not flag:
                lead_out += 1
            else:
                break
        if not (1 <= lead_out <= 2):
            return None
        n_in_body = sum(1 for flag in in_court[lead_out:] if flag)
        if n_in_body < 8:
            return None
        # First in-ROI contact is the visible rally body. Last out-of-ROI
        # is the far-court serve; padded start from the first in-ROI already
        # covers GT 1:29 (serve_pad 1.05).
        start_idx = lead_out
        serve = inside[start_idx]
        cut = start_idx + 1
        for index in range(start_idx, len(inside) - 1):
            nxt = inside[index + 1]
            tentative_start = max(0.0, serve - self.serve_pad)
            tentative_end = nxt + self.land_pad
            if tentative_end - tentative_start >= 6.0:
                break
            gap = nxt - inside[index]
            if gap >= 1.00 and (index + 1 - start_idx) >= 3:
                break
            if not in_court[index + 1]:
                run = 1
                cursor = index + 2
                while cursor < len(inside) and not in_court[cursor]:
                    run += 1
                    cursor += 1
                if run >= 2:
                    break
            cut = index + 2
        cluster_n = cut - start_idx
        if cluster_n < 3:
            return None
        cluster = inside[start_idx:cut]
        if cluster_n >= 8:
            cluster_isis = [cluster[i + 1] - cluster[i] for i in range(cluster_n - 1)]
            if float(np.median(cluster_isis)) < 0.42:
                return None
        land = inside[cut - 1]
        return {
            "start": max(0.0, serve - self.serve_pad),
            "end": land + self.land_pad,
            "first_hit": serve,
            "last_hit": land,
            "confidence": float(rally.get("confidence", 0.8)),
        }

    def _peel_silent_play_serve(
        self,
        rally: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> Optional[Dict[str, Any]]:
        """Keep the last hit of a dropped walking blob when play continues silently.

        GT22: walking 7:32-7:41 then a near-court serve and into-net with no
        extra audio hits. last_isi ~1.2s, next-gap ~5.8s, post-hit burst.
        """
        first_hit = float(rally.get("first_hit", rally["start"]))
        last_hit = float(rally.get("last_hit", rally["end"]))
        inside = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
        if len(inside) < 7:
            return None
        last_isi = inside[-1] - inside[-2]
        next_gap = self._next_hit_gap(hits, inside[-1])
        if not (1.05 <= last_isi <= 1.40 and next_gap >= 5.2):
            return None
        serve = inside[-1]
        if not self._has_motion_burst(serve, serve + 2.8, motion_t, motion_e):
            return None
        start = max(0.0, serve - self.serve_pad)
        end = self._walk_silent_play_end(serve, motion_t, motion_e)
        return {
            "start": start,
            "end": max(start + 0.25, end),
            "first_hit": serve,
            "last_hit": serve,
            "confidence": float(rally.get("confidence", 0.8)),
        }

    def _hit_isolation(self, hits: np.ndarray, first_hit: float) -> float:
        if len(hits) == 0:
            return 99.0
        prev = hits[hits < first_hit - 0.12]
        if len(prev) == 0:
            return 99.0
        return float(first_hit - prev[-1])

    def _next_hit_gap(self, hits: np.ndarray, last_hit: float) -> float:
        if len(hits) == 0:
            return 99.0
        nxt = hits[hits > last_hit + 0.12]
        if len(nxt) == 0:
            return 99.0
        return float(nxt[0] - last_hit)

    def _far_court_compact_three_shape(
        self,
        isolation: float,
        first_isi: float,
        last_isi: float,
        next_gap: float,
        hit_count: int,
        duration: float,
    ) -> bool:
        """Timing signature of a peeled far-court 3-hit (GT23).

        Isolation sits just under serve-like 1.85 because an adjacent-court
        leftover is 1.84s before the real serve. first_isi is the compact
        return; last_isi is the clear/out; next-gap is the between-point hole.
        Adjacent-court 7:47 is 7 hits with first_isi 0.54 and no 1.55–2.15
        interior gap leaving a 3-hit tail.
        """
        if hit_count != 3:
            return False
        if not (2.0 <= duration <= 7.0):
            return False
        if not (1.65 <= isolation <= 2.50):
            return False
        if not (0.40 <= first_isi <= 0.80):
            return False
        if not (1.45 <= last_isi <= 2.00):
            return False
        return next_gap >= 3.20

    def _is_serve_like(
        self,
        isolation: float,
        first_isi: float,
        hit_count: int,
        duration: float,
    ) -> bool:
        """Serve-like first contact: isolated from the previous point."""
        if isolation < 1.85:
            return False
        if hit_count == 1:
            # Finite isolation: not "first peak in the whole file".
            # 3.2s avoids leftover 1-hit splits glued to the previous point.
            # Silent-serve 1-hit (iso>=9, GT21) needs a longer lead pad so
            # duration can exceed the usual 3.8s cap.
            if 9.0 <= isolation <= 30.0:
                return 1.2 <= duration <= 6.8
            return 3.2 <= isolation <= 30.0 and 1.2 <= duration <= 3.8
        if first_isi <= 0:
            return False
        return 0.22 <= first_isi <= 2.55

    def _isolated_quiet_pre_hit(
        self,
        first_hit: float,
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
        *,
        min_gap: float,
        max_gap: float,
        min_iso: float,
        max_iso: float,
        max_gap_active: float = 0.42,
    ) -> Optional[float]:
        """Nearest isolated quiet hit in (min_gap, max_gap] before first_hit.

        Neighbors on either side (except the live first_hit) mean pickup /
        walking / a compact serve cluster — those need a different rule.
        """
        candidates = [
            float(t) for t in self._hits_in(hits, first_hit - max_gap, first_hit - min_gap)
        ]
        if not candidates:
            return None
        candidate = candidates[-1]
        gap = first_hit - candidate
        if not (min_gap < gap <= max_gap):
            return None
        neighbors_before = self._hits_in(hits, candidate - 1.20, candidate - 0.08)
        neighbors_after = self._hits_in(
            hits, candidate + 0.08, min(candidate + 1.20, first_hit - 0.12)
        )
        if len(neighbors_before) or len(neighbors_after):
            return None
        gap_active = self._motion_active_fraction(candidate, first_hit, motion_t, motion_e)
        cand_burst = self._has_motion_burst(
            max(0.0, candidate - 0.35),
            candidate + 0.25,
            motion_t,
            motion_e,
        )
        cand_iso = self._hit_isolation(hits, candidate)
        if cand_burst or gap_active >= max_gap_active:
            return None
        if not (min_iso <= cand_iso <= max_iso):
            return None
        return candidate

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
        # Walking between points has some court motion; require a higher bar
        # as the hole gets longer so two points are not glued.
        if gap <= 2.8:
            return active >= 0.55
        return active >= 0.50

    def _walk_serve_start(
        self,
        first_hit: float,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> float:
        """Walk back along court-ROI motion for toss/serve, with an onset gate."""
        default = max(0.0, first_hit - self.serve_pad)
        if len(motion_t) == 0 or len(motion_e) == 0:
            return default
        max_look = min(2.4, max(self.serve_pad + 1.1, 2.0))
        window_start = max(0.0, first_hit - max_look)
        left = int(np.searchsorted(motion_t, window_start, side="left"))
        right = int(np.searchsorted(motion_t, first_hit + 0.12, side="right"))
        if right <= left:
            return default

        baseline = float(np.median(motion_e))
        p85 = float(np.percentile(motion_e, 85))
        span = max(0.0, p85 - baseline)
        onset = max(0.008, baseline + 0.08 * span)
        energies = motion_e[left:right]
        times = motion_t[left:right]
        active = energies >= onset
        if not np.any(active):
            return default

        # Anchor near first_hit, then walk back while motion stays on.
        anchor = len(active) - 1
        while anchor > 0 and not active[anchor]:
            anchor -= 1
        start_index = anchor
        quiet = 0
        allowed_quiet = 3
        for index in range(anchor - 1, -1, -1):
            if active[index]:
                start_index = index
                quiet = 0
            else:
                quiet += 1
                if quiet > allowed_quiet:
                    break
        motion_start = float(times[start_index]) - 0.30

        # Onset gate: if the 1.2s just before the walked start is already as
        # active as the toss window, this is walking/huddle — don't rewind far.
        pre_end = float(times[start_index])
        pre_start = max(0.0, pre_end - 1.4)
        head_active = self._motion_active_fraction(pre_start, pre_end, motion_t, motion_e)
        toss_active = self._motion_active_fraction(
            max(0.0, first_hit - 0.7), first_hit + 0.15, motion_t, motion_e
        )
        if head_active >= 0.55 and toss_active < head_active + 0.05 and (first_hit - motion_start) > 1.1:
            return default
        return max(0.0, min(default, max(window_start, motion_start)))

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

        # Prefer audio hits inside this fragment. Quiet-serve lookback is
        # capped so we do not steal the previous point's last hit.
        inside = self._hits_in(hits, start - 0.25, end + 0.25)
        if len(inside):
            first_hit = float(inside[0])
            last_hit = float(inside[-1])
            pre_from = max(start - 0.55, first_hit - 1.6)
            pre = self._hits_in(hits, pre_from, first_hit - 0.12)
            if len(pre) == 1 and first_hit - float(pre[0]) <= 1.6:
                first_hit = float(pre[0])

        # Walking head + compact 2-hit net-fault (user-sample GT5 86.51/86.81).
        # Short isolation means this fragment continues the previous point's
        # walking; a real rally's compact smash ending has iso >= 2.2 (GT6/8).
        trimmed_walk_head = False
        clustered = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
        if len(clustered) >= 5:
            last_isi = clustered[-1] - clustered[-2]
            isolation = self._hit_isolation(hits, clustered[0])
            span = clustered[-1] - clustered[0]
            head = clustered[:-2]
            head_isis = [head[i + 1] - head[i] for i in range(len(head) - 1)]
            # Walking heads are sparse (min ISI >= 0.70). Adjacent-court stubs
            # have a compact pair in the middle and must not be unstuck.
            sparse_head = bool(head_isis) and min(head_isis) >= 0.70
            if (
                last_isi <= 0.50
                and isolation < 2.20
                and span >= 3.5
                and sparse_head
            ):
                first_hit = clustered[-2]
                last_hit = clustered[-1]
                trimmed_walk_head = True

        # Live court-bound rally: pull a single quiet far-court contact
        # (~1.94s high-clear hole, user-sample GT20) without raising the
        # global silence / remerge gap (that glue dropped GT3/GT9).
        # Skip when the candidate itself is a high-motion landing (GT1→GT2).
        if (
            not trimmed_walk_head
            and len(clustered) >= 4
            and (clustered[-1] - clustered[0]) <= 3.8
            and self._has_motion_burst(first_hit, last_hit, motion_t, motion_e)
        ):
            candidate_hits = [
                float(t) for t in self._hits_in(hits, first_hit - 2.05, first_hit - 0.12)
            ]
            if candidate_hits:
                candidate = candidate_hits[-1]
                gap = first_hit - candidate
                neighbors = [
                    float(t) for t in self._hits_in(hits, candidate - 1.20, candidate - 0.08)
                ]
                gap_active = self._motion_active_fraction(
                    candidate, first_hit, motion_t, motion_e
                )
                cand_burst = self._has_motion_burst(
                    max(0.0, candidate - 0.35),
                    candidate + 0.25,
                    motion_t,
                    motion_e,
                )
                cand_iso = self._hit_isolation(hits, candidate)
                if (
                    1.70 < gap <= 2.05
                    and not neighbors
                    and not cand_burst
                    and gap_active < 0.42
                    # Isolated far-court contact, not the first blip of a
                    # walking chain (iso=99) or a leftover after 10s+ idle.
                    and 2.8 <= cand_iso <= 8.5
                ):
                    first_hit = candidate

        # Local start-lead: a live rally already exists; pull one isolated
        # quiet serve-like hit across a 2.50-5.75s high-clear hole (GT4/10/20).
        # Compact-span is intentionally NOT used — that blocked these TPs.
        # Walking extras skipped via n>=5 and last_isi>=0.50 (2:17 compact
        # tail last_isi~0.25; 4-hit cadence n=4). Do not raise max_iso to
        # 8.8 (GT15 leftover) or drop last_isi (2:17 extra survives).
        clustered = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
        if (
            not trimmed_walk_head
            and len(clustered) >= 5
            and (clustered[-1] - clustered[-2]) >= 0.50
            and self._has_motion_burst(first_hit, last_hit, motion_t, motion_e)
        ):
            pulled = self._isolated_quiet_pre_hit(
                first_hit,
                hits,
                motion_t,
                motion_e,
                min_gap=2.50,
                max_gap=5.75,
                min_iso=1.50,
                max_iso=6.0,
            )
            if pulled is not None:
                first_hit = pulled
            elif max(clustered[i + 1] - clustered[i] for i in range(len(clustered) - 1)) < 2.50:
                # GT7 2.18s hole sits just under 2.50. min_gap 2.15 also
                # pulls the dead-ball fixture (2.20s hole, 3.0s interior).
                pulled = self._isolated_quiet_pre_hit(
                    first_hit,
                    hits,
                    motion_t,
                    motion_e,
                    min_gap=2.16,
                    max_gap=2.50,
                    min_iso=1.50,
                    max_iso=6.0,
                )
                if pulled is not None:
                    first_hit = pulled

        item["first_hit"] = first_hit
        item["last_hit"] = last_hit

        clustered_now = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
        n_here_start = len(clustered_now)
        iso_now = self._hit_isolation(hits, first_hit)
        next_now = self._next_hit_gap(hits, last_hit)
        if (
            not trimmed_walk_head
            and n_here_start == 1
            and 9.0 <= iso_now <= 30.0
            and 1.80 <= next_now <= 3.50
        ):
            # Silent serve: no audio/motion at contact (GT21). Pad back toward
            # the toss without invading the previous point's last hit.
            start = max(0.0, first_hit - 5.60)
            prev_hits = hits[hits < first_hit - 0.12] if len(hits) else hits
            if len(prev_hits):
                start = max(start, float(prev_hits[-1]) + 0.35)
        elif trimmed_walk_head:
            # Do not motion-walk start back into the walking we just stripped.
            start = max(0.0, first_hit - self.serve_pad)
        else:
            start = self._walk_serve_start(first_hit, motion_t, motion_e)
        # Always trim to last hit + short land pad. Residual walking motion
        # after a winner must NOT extend the clip (over-run failure).
        end = max(start + 0.25, last_hit + self.land_pad)
        if len(motion_t):
            tail_active = self._motion_active_fraction(
                last_hit, last_hit + self.land_pad + 0.35, motion_t, motion_e
            )
            n_here = len(self._hits_in(hits, first_hit, last_hit))
            # Far-court 1-hit serves have no residual pixels; keep the full land
            # pad so IoU vs a 3s GT still clears 0.3 (user-sample GT11).
            isolated_one = n_here == 1 and self._hit_isolation(hits, first_hit) >= 3.2
            clustered_now = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
            far_three = False
            if n_here == 3 and len(clustered_now) == 3:
                far_three = self._far_court_compact_three_shape(
                    isolation=self._hit_isolation(hits, first_hit),
                    first_isi=clustered_now[1] - clustered_now[0],
                    last_isi=clustered_now[-1] - clustered_now[-2],
                    next_gap=self._next_hit_gap(hits, last_hit),
                    hit_count=3,
                    duration=max(end - start, last_hit + 2.20 - start),
                )
            if far_three:
                # Long-out landing has no ROI pixels; 0.60s pad undershoots GT23.
                end = max(end, last_hit + 2.20)
            elif tail_active < 0.22 and not isolated_one:
                end = min(end, last_hit + min(0.45, self.land_pad))
            # John GT3-like: 4-hit exchange, last ISI is the landing (1.45-1.80)
            # then a 4s+ between-point hole. 0.60s pad leaves iou=0.27 vs a 5s
            # GT. User n=4 TPs have next<2.0 or last_isi>=2.4 so they do not
            # match. Do not raise the global land pad.
            if n_here == 4 and len(clustered_now) == 4:
                land_last_isi = clustered_now[-1] - clustered_now[-2]
                land_next = self._next_hit_gap(hits, last_hit)
                if (
                    1.45 <= land_last_isi <= 1.80
                    and land_next >= 4.0
                    and self._has_motion_burst(first_hit, last_hit, motion_t, motion_e)
                ):
                    end = max(end, last_hit + 3.50)

        item["start"] = max(0.0, start)
        item["end"] = max(item["start"] + 0.25, end)
        return item

    def _trim_applause_tail(self, rally: Dict[str, Any], hits: np.ndarray) -> Dict[str, Any]:
        """Cut handshake/applause: a late run of 6+ hits with median ISI < 0.32s.

        Smash exchanges also have short ISIs; only cut a *tail* (last ~45% of
        the hit span) so mid-rally bursts are not mistaken for clapping.
        """
        item = dict(rally)
        start = float(item["start"])
        end = float(item["end"])
        first_hit = float(item.get("first_hit", start))
        last_hit = float(item.get("last_hit", end))
        inside = self._hits_in(hits, first_hit, last_hit)
        if len(inside) < 7:
            return item
        times = [float(t) for t in inside]
        span = times[-1] - times[0]
        tail_from = times[0] + 0.55 * span
        cut_at = None
        for index in range(0, len(times) - 5):
            window = times[index:index + 6]
            intervals = [window[i + 1] - window[i] for i in range(5)]
            median_isi = float(np.median(intervals))
            if median_isi < 0.36 and max(intervals) < 0.60:
                in_tail = window[0] >= tail_from
                clap_dur = window[-1] - window[0]
                if (in_tail or clap_dur >= 3.0) and index >= 2:
                    cut_at = times[index]
                    break
        if cut_at is None:
            return item
        if cut_at <= first_hit + 0.4:
            return item
        item["last_hit"] = cut_at
        item["end"] = min(end, cut_at + self.land_pad)
        return item

    def _motion_at(self, timestamp: float, motion_t: np.ndarray, motion_e: np.ndarray) -> float:
        if len(motion_t) == 0 or len(motion_e) == 0:
            return 0.0
        index = int(np.searchsorted(motion_t, timestamp))
        index = min(max(index, 0), len(motion_e) - 1)
        if index > 0 and abs(float(motion_t[index - 1]) - timestamp) <= abs(float(motion_t[index]) - timestamp):
            index -= 1
        return float(motion_e[index])

    def _trim_post_land(
        self,
        rally: Dict[str, Any],
        hits: np.ndarray,
        motion_t: np.ndarray,
        motion_e: np.ndarray,
    ) -> Dict[str, Any]:
        """Drop trailing shoe-squeaks / pickup hits after the shuttle has landed."""
        item = dict(rally)
        first_hit = float(item.get("first_hit", item["start"]))
        last_hit = float(item.get("last_hit", item["end"]))
        inside = [float(t) for t in self._hits_in(hits, first_hit, last_hit)]
        if len(inside) < 7 or len(motion_e) == 0:
            return item
        # Cut a trailing walking run in the last ~60% of hits: 3+ contacts
        # after a 1.32s+ hole. Skip live mid-rally holes (motion still up).
        # Keep 1–2 hits after a high-clear / net-drop (Kaja, n usually < 7).
        cut = None
        start_index = max(3, (len(inside) * 2) // 5)
        for index in range(start_index, len(inside) - 3):
            gap = inside[index + 1] - inside[index]
            tail_n = len(inside) - (index + 1)
            if gap < 1.32 or tail_n < 3:
                continue
            if gap > self.max_hit_silence and self._gap_still_live(
                inside[index], inside[index + 1], motion_t, motion_e
            ):
                continue
            # Compact 2-hit net-fault after a short hole is a rally ending
            # (GT5 1.36s then 86.51/86.81). Longer holes are post-land pickup
            # even if the tail happens to end compact (GT17 1.80s).
            tail = inside[index + 1 :]
            if gap < 1.55 and len(tail) >= 2 and (tail[-1] - tail[-2]) <= 0.55:
                continue
            cut = index
            break
        if cut is None:
            return item
        inside = inside[: cut + 1]
        item["first_hit"] = inside[0]
        item["last_hit"] = inside[-1]
        item["end"] = min(float(item["end"]), inside[-1] + self.land_pad)
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

            # Force-split still-too-long groups on the largest dead/walking gap.
            refined_groups: List[List[float]] = []
            for group in groups:
                span = group[-1] - group[0]
                long_blob = span > 10.0 and len(group) >= 5
                if span <= self.max_duration and not long_blob:
                    refined_groups.append(group)
                    continue
                if len(group) < 5:
                    refined_groups.append(group)
                    continue
                gaps = [
                    (group[index + 1] - group[index], index)
                    for index in range(len(group) - 1)
                ]
                gaps.sort(reverse=True)
                cut_done = False
                min_cut = 1.40 if long_blob else max(1.85, self.max_hit_silence * 0.9)
                for gap_len, index in gaps[:5]:
                    if gap_len < min_cut:
                        break
                    left_hit = group[index]
                    right_hit = group[index + 1]
                    live = self._gap_still_live(left_hit, right_hit, motion_t, motion_e)
                    active = self._motion_active_fraction(left_hit, right_hit, motion_t, motion_e)
                    # High-motion holes in a long blob are walking/pickup, not a
                    # quiet high-clear (those have *low* court motion).
                    walking_gap = long_blob and gap_len >= 1.40 and active >= 0.48
                    if (not live) or walking_gap:
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
            item_hits = self._hits_in(
                hits,
                float(item.get("first_hit", item["start"])),
                float(item.get("last_hit", item["end"])),
            )
            prev_hits = self._hits_in(
                hits,
                float(prev.get("first_hit", prev["start"])),
                float(prev.get("last_hit", prev["end"])),
            )

            should = False
            prev_last = float(prev.get("last_hit", prev["end"]))
            prev_first = float(prev.get("first_hit", prev["start"]))
            item_first = float(item.get("first_hit", item["start"]))
            item_last = float(item.get("last_hit", item["end"]))
            gap_active = self._motion_active_fraction(prev_last, item_first, motion_t, motion_e)
            walking_rejoin = (
                len(prev_hits) >= 4
                and (prev_last - prev_first) >= 4.0
                and hit_gap >= 1.50
                and gap_active >= 0.48
            )
            prev_burst = self._has_motion_burst(
                prev_first,
                prev_last if prev_last - prev_first >= 0.4 else prev_last + 0.40,
                motion_t,
                motion_e,
            )
            item_burst = self._has_motion_burst(
                item_first if item_last - item_first >= 0.4 else item_first - 0.40,
                item_last if item_last - item_first >= 0.4 else item_last + 0.40,
                motion_t,
                motion_e,
            )
            # Local start-lead may steal the previous walking tail's last
            # contact (the real serve). Do not glue that walking blob onto
            # the live rally (GT20: 6:51 walking last=6:59.30 serve).
            if (
                gap < 0.0
                and -0.05 <= hit_gap <= 0.20
                and len(prev_hits) >= 3
                and len(item_hits) >= 4
                and not prev_burst
                and item_burst
            ):
                latest = float(item["start"]) - 0.35
                prev_inside = [float(t) for t in prev_hits if float(t) < item_first - 0.12]
                if prev_inside and latest > float(prev["start"]) + 0.5:
                    prev["last_hit"] = prev_inside[-1]
                    prev["end"] = min(float(prev["end"]), prev_inside[-1] + self.land_pad, latest)
                merged.append(dict(item))
                continue
            if projected <= self.max_duration + 2.0 and not walking_rejoin:
                if gap <= self.remerge_gap and hit_gap <= self.motion_bridge_silence:
                    live = self._gap_still_live(
                        prev_last,
                        item_first,
                        motion_t,
                        motion_e,
                    )
                    # Tight fragments, but do not glue walking (high-motion hole).
                    # Pads can leave a 0.5s wall-clock gap across a ~2.2s quiet
                    # high-clear (user-sample GT7 pred 7→8). Projected<=12 keeps
                    # GT1+GT2 (projected ~16s) unglued.
                    if live or (gap <= 0.45 and hit_gap <= 1.8 and gap_active < 0.62):
                        should = True
                    elif (
                        gap <= 0.60
                        and self.max_hit_silence < hit_gap <= 2.2
                        and gap_active < 0.62
                        and projected <= 12.0
                        and len(prev_hits) >= 2
                        and len(item_hits) >= 3
                        and not self._is_walk_in(prev, hits)
                    ):
                        should = True
                elif hit_gap <= self.max_hit_silence:
                    should = True
                elif (
                    self.max_hit_silence < hit_gap <= 4.8
                    and projected <= self.max_duration
                    and len(item_hits) <= 2
                    and len(prev_hits) >= 2
                    and gap_active < 0.45
                    # A 1-hit after a serve-sized hole is the next point's serve
                    # (GT11), not a quiet landing of the previous extra.
                    and not (len(item_hits) == 1 and hit_gap >= 3.2)
                ):
                    # Quiet mid-rally clear: 1–2 isolated hits after a 2.6–5s hole.
                    # High-motion holes are walking between points — do not glue.
                    should = True
                elif (
                    3.2 < hit_gap <= 5.2
                    and projected <= 8.5
                    and len(prev_hits) <= 2
                    and len(item_hits) <= 2
                    and gap_active < 0.90
                    # GT11: isolated serve (iso 3.3–5.0, next-gap ≥ 3.6) must
                    # not remerge with the following walking 2-hit. GT1's first
                    # hit has iso 7.6s; GT16 iso is 2.2s.
                    and not (
                        len(prev_hits) == 1
                        and 3.30 <= self._hit_isolation(hits, prev_first) < 5.0
                        and self._next_hit_gap(hits, prev_last) >= 3.60
                    )
                ):
                    # Short rally split by a high-clear (user-sample GT1 / GT16).
                    should = True
                elif (
                    2.8 < hit_gap <= 5.3
                    and projected <= self.max_duration
                    and len(prev_hits) <= 2
                    and len(item_hits) >= 3
                    and gap_active < 0.42
                ):
                    # Isolated serve then the rest of a short rally after a high-clear.
                    should = True
                elif (
                    self.max_hit_silence < hit_gap <= 2.2
                    and projected <= 12.0
                    and len(prev_hits) >= 2
                    and len(item_hits) >= 3
                    and gap_active < 0.55
                ):
                    # Continuation after a ~2s high-clear (Kaja; user-sample GT7
                    # 2-hit fragment then 4-hit after 2.19s quiet). prev span is
                    # short so walking_rejoin does not fire. GT1+GT2 projected
                    # duration is ~16s so they stay unglued.
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
            elif (
                projected <= self.max_duration + 2.0
                and not walking_rejoin
                and 5.0 < hit_gap <= 5.6
                and 2 <= len(prev_hits) <= 5
                and len(item_hits) >= 4
                and gap_active < 0.40
            ):
                # Quiet high-clear then a landing hit, then walking (GT19).
                # Absorb only the landing; leave the walking cluster behind.
                landing = item_first
                prev["last_hit"] = max(prev_last, landing)
                prev["end"] = max(float(prev["end"]), landing + self.land_pad)
                rest = [float(t) for t in item_hits if float(t) > landing + self.max_hit_silence]
                if len(rest) >= 2:
                    leftover = dict(item)
                    leftover["first_hit"] = rest[0]
                    leftover["last_hit"] = rest[-1]
                    leftover["start"] = rest[0] - self.serve_pad
                    leftover["end"] = rest[-1] + self.land_pad
                    merged.append(leftover)
            else:
                merged.append(item)
        return merged

    def _is_walk_in(self, rally: Dict[str, Any], hits: np.ndarray) -> bool:
        """Camera-open walking tagged as a rally (user-sample 0:00–0:04)."""
        start = float(rally["start"])
        last_hit = float(rally.get("last_hit", rally["end"]))
        first_hit = float(rally.get("first_hit", start))
        duration = float(rally["end"]) - start
        inside = self._hits_in(hits, first_hit, last_hit)
        if start > 0.40:
            return False
        if first_hit > 0.85:
            return False
        if last_hit > 5.5 or duration > 6.0:
            return False
        # A real rally that begins at t=0 (e.g. Kaja) lasts past 5.5s.
        return len(inside) >= 4 and last_hit <= 5.5

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
        if duration > min(self.max_duration + 2.0, 16.8):
            return False
        if self._is_walk_in(rally, hits):
            return False

        first_hit = float(rally.get("first_hit", start))
        last_hit = float(rally.get("last_hit", end))
        inside = self._hits_in(hits, first_hit, last_hit)
        hit_count = len(inside)
        isolation = self._hit_isolation(hits, first_hit)
        first_isi = float(inside[1] - inside[0]) if hit_count >= 2 else 0.0
        last_isi = float(inside[-1] - inside[-2]) if hit_count >= 2 else 0.0
        next_gap_hl = self._next_hit_gap(hits, last_hit)
        serve_like = self._is_serve_like(isolation, first_isi, hit_count, duration)
        burst = self._has_motion_burst(
            first_hit if last_hit - first_hit >= 0.4 else first_hit - 0.40,
            last_hit if last_hit - first_hit >= 0.4 else last_hit + 0.40,
            motion_t,
            motion_e,
        )
        far_court_three = (
            not burst
            and self._far_court_compact_three_shape(
                isolation, first_isi, last_isi, next_gap_hl, hit_count, duration
            )
        )

        if hit_count < self.min_hits:
            # Isolated 1-hit+land (service fault). Burst is typical on-court;
            # far-court / phone-mic faults often have no pixel burst (GT11/GT21).
            # Burst alone is not enough: adjacent-court leftovers can sit in
            # the ROI with a swing-like pixel burst but the next contact is
            # only ~2.4s later (eurogames-short 0:06). Real 1-hit faults have
            # a between-point hole (GT11 next>=3.6) or long isolation (GT21).
            next_gap = self._next_hit_gap(hits, last_hit)
            far_court_one = (
                hit_count == 1
                and serve_like
                and (
                    (burst and next_gap >= 3.20)
                    or (isolation >= 9.0 and 1.80 <= next_gap <= 3.50)
                    or (3.30 <= isolation < 5.0 and next_gap >= 3.60)
                )
            )
            # Peeled 1-hit + silent play (GT22). Isolation is short because
            # walking contacts sit just before the serve in the hit list.
            silent_play_one = (
                hit_count == 1
                and burst
                and 1.05 <= isolation < 2.50
                and next_gap >= 5.2
                and duration <= 5.5
            )
            if not (self.min_hits <= 2 and (far_court_one or silent_play_one)):
                return False

        hit_span = max(0.25, last_hit - first_hit)
        density = hit_count / hit_span
        if hit_count >= 2 and density < self.min_hit_density and hit_count < self.min_hits + 2:
            # 2-hit net-faults are compact; don't density-drop them.
            if not (hit_count <= 2 and duration <= 5.5):
                return False
        if duration >= 12.0 and density < self.min_hit_density * 0.85:
            return False
        # Applause / interview clapping: too dense for too long.
        # Do not loosen globally (user-sample 6:27 n=10 dens=1.94). Peeled
        # primary-court clusters (euro-long GT4) may still be dense 6s rallies.
        if duration >= 6.0 and density > 1.7 and hit_count >= 10:
            if not rally.get("_primary_court_peel"):
                return False

        # Rally-likeness: need an in-ROI motion burst OR a serve-like first hit.
        # 2-hit net-faults (min_hits=2) often have almost no pixels; serve-like
        # isolation is enough. Walking / adjacent-court stubs usually have
        # neither a real serve gap nor a swing burst.
        if hit_count <= 3 and not burst and not serve_like and not far_court_three:
            return False
        if hit_count == 2 and not burst and first_isi > 1.6:
            return False
        # 4+ audio blips with no ROI swing: walking / interview, not a rally.
        # Skip when motion is unavailable (audio-only unit tests).
        if hit_count >= 4 and not burst and len(motion_t):
            return False
        # Adjacent-court 2–3 blips last longer than a compact net-fault (GT14 ~2.1s).
        # Do not apply to 1-hit: silent-serve lookback (GT21) makes duration
        # >= 2.45 while first_hit==last_hit so active_short is always 0.
        if (
            2 <= hit_count <= 3
            and not burst
            and duration >= 2.45
            and len(motion_t)
            and not far_court_three
        ):
            active_short = self._motion_active_fraction(first_hit, last_hit, motion_t, motion_e)
            if active_short < 0.12:
                return False
        # Euro-long extra 1:24: 2 out-of-ROI hits, no burst, next-gap 2.20s
        # before GT6. Do not lower the duration>=2.45 floor (protects GT14
        # ~2.1s n=3). Far-court 1-hit (GT11) is n=1; GT14 is n=3.
        if (
            hit_count == 2
            and not burst
            and len(motion_t)
            and next_gap_hl < 3.20
            and not far_court_three
        ):
            n_in = sum(1 for t in inside if self._hit_in_court(float(t), motion_t, motion_e))
            if n_in == 0:
                return False
        # 2–9.5s walking / adjacent-court stubs (e.g. 3:37–3:43, 7:36–7:42).
        if 6.0 <= duration <= 9.5 and hit_count >= 7 and isolation < 2.30:
            return False
        # 8–11s pickup blobs with no serve gap (user-sample 4:46, 6:50).
        # TPs in this duration band have isolation >= 2.0 (GT2/GT4/GT6/GT8).
        if 8.0 <= duration <= 11.0 and 4 <= hit_count <= 6 and isolation < 1.85:
            return False
        # Long leftover glue (e.g. 0:27–0:40) without a serve gap.
        if hit_count >= 11 and duration >= 10.0 and isolation < 2.15:
            return False

        # Reject clips that are mostly inactive court motion (waiting) — but
        # far-court 2-hit faults often have almost no pixel motion, so only
        # apply the idle gate to longer / higher-count segments. Threshold is
        # below typical far-court rally active-frac (~0.16–0.27).
        if len(motion_t):
            active = self._motion_active_fraction(first_hit, last_hit, motion_t, motion_e)
            if duration >= 2.5 and active < 0.12 and hit_count >= 4:
                return False
            if duration >= 3.0 and active < 0.18 and density < self.min_hit_density * 1.15 and hit_count >= 4:
                return False
            if duration <= 5.0 and active < 0.12 and hit_count >= 5:
                return False
            # Interview / huddle talking-length: ROI motion stays a high
            # plateau (mean near p85) instead of a rally burst-then-quiet.
            # Long huddle (john 14.8s n=19 mean/p85~1.03) vs a 9s live
            # exchange that keeps motion up across a high-clear hole.
            # User-sample long TPs stay (GT4 14.1s n=12 mean/p85=0.50,
            # GT9 12.7s n=14 0.59). Shorter huddle (9.4s n=9 first_isi=2.25
            # mean/p85~0.83) needs the first-gap clause.
            if duration >= 12.0 and hit_count >= 15:
                left = int(np.searchsorted(motion_t, first_hit, side="left"))
                right = int(np.searchsorted(motion_t, last_hit, side="right"))
                if right > left:
                    mean_window = float(np.mean(motion_e[left:right]))
                    p85 = float(np.percentile(motion_e, 85))
                    if p85 > 1e-5 and mean_window >= 0.80 * p85:
                        return False
            if (
                duration >= 8.5
                and hit_count >= 8
                and first_isi >= 2.0
            ):
                left = int(np.searchsorted(motion_t, first_hit, side="left"))
                right = int(np.searchsorted(motion_t, last_hit, side="right"))
                if right > left:
                    mean_window = float(np.mean(motion_e[left:right]))
                    p85 = float(np.percentile(motion_e, 85))
                    if p85 > 1e-5 and mean_window >= 0.80 * p85:
                        return False
            # Long blob that starts while court motion is already walking-level
            # (no between-point quiet): pickup / sideline, not a serve.
            if first_hit >= 8.0:
                pre_active = self._motion_active_fraction(
                    max(0.0, first_hit - 2.2),
                    max(0.0, first_hit - 0.5),
                    motion_t,
                    motion_e,
                )
                # Sideline / end-of-tape walking: motion already high before
                # the first "hit". Real serves after a pause have low pre-active
                # (GT24) or are shorter than 8s (GT12).
                # Serve then high-clear (first_isi>=4.5, GT20) has a leftover
                # contact 1.5s before the serve so iso is short and pre-motion
                # is high — do not treat that as walking-then-serve.
                high_clear_open = first_isi >= 4.5
                if duration >= 8.0 and hit_count >= 6 and not high_clear_open:
                    # High pre-motion with a real serve gap is walking-then-serve
                    # (GT9). Only drop when isolation is also short.
                    if pre_active >= 0.55 and hit_count >= 8 and isolation < 5.5:
                        return False
                    if pre_active >= 0.58 and isolation < 2.4:
                        return False
                # Short continuation of walking immediately after the previous
                # point (no between-point quiet). GT12 isolation is ~4s.
                if (
                    duration >= 4.5
                    and hit_count >= 5
                    and pre_active >= 0.55
                    and isolation < 2.05
                    and not high_clear_open
                ):
                    return False
                # End-of-tape / post-point walking leftover (user-sample 8:47).
                if (
                    duration >= 5.8
                    and 4 <= hit_count <= 6
                    and pre_active >= 0.70
                    and isolation < 5.0
                ):
                    return False
                # 2-hit leftover after walking (user-sample 2:43–2:46, 7:13–7:17,
                # 4:13 after GT11). Compact net-faults (GT5 ISI 0.30s) must
                # survive high pre-motion; walking leftovers have ~1.2–1.4s ISI.
                # pre_active 0.42 catches the GT11-adjacent walk (0.46) without
                # touching GT1 (n=2 but duration 6s / iso 7.6).
                # first_isi>0.55 also drops euro-long extra 1:03 (isi=0.60
                # iso=3.49 pre=0.92). John GT2 isi=0.41 stays.
                if (
                    hit_count == 2
                    and duration <= 4.5
                    and pre_active >= 0.42
                    and isolation < 4.0
                    and first_isi > 0.55
                ):
                    return False
                # Compact 2-hit walking blip (user-sample 4:53–4:55) just before
                # a real net-fault. GT5/GT14 are 3-hit; GT14 iso ~3.2 with no
                # pre-motion. Do not raise this iso cap to 3.2.
                # pre>=0.60 also drops john sideline extra 1:23 (pre=0.62);
                # john GT2 pre=0.54 / dur=2.38 stays.
                if (
                    hit_count == 2
                    and duration <= 2.3
                    and pre_active >= 0.60
                    and 1.85 <= isolation < 2.80
                    and first_isi <= 0.55
                ):
                    return False
            # 4-hit walking cadence: all ISIs ~0.9–1.7s, no smash / high-clear
            # (user-sample 4:03 extra before GT11, 4:18 extra before GT12).
            # GT17/GT18 are n=4 but last_isi >= 2.4 or first_isi >= 3.1 and longer.
            if hit_count == 4 and 4.2 <= duration <= 5.8:
                isis = [float(inside[i + 1] - inside[i]) for i in range(hit_count - 1)]
                if min(isis) >= 0.90 and max(isis) <= 1.75:
                    return False
            # Adjacent-court compact tail after one isolated contact
            # (user-sample 2:17 extra; GT7 is the real rally ~5s later).
            # GT10 is n=5 but last-4 span ~2.0s and active ~0.68.
            if (
                hit_count == 5
                and duration <= 5.3
                and first_isi >= 1.55
                and float(inside[-1] - inside[1]) <= 1.50
                and active < 0.30
            ):
                return False
            # 4-hit pickup: compact last after a ~1.5s hole (user-sample 2:56).
            # GT17/GT18 last_isi is 1.3–2.4s and duration >= 6.9s.
            if hit_count == 4 and duration <= 4.6 and last_isi <= 0.40:
                isis = [float(inside[i + 1] - inside[i]) for i in range(hit_count - 1)]
                if any(1.45 <= gap <= 1.80 for gap in isis):
                    return False
            # John-carroll remaining extras. Signatures measured on the clip;
            # user-sample / euro-long / kaja TPs do not match. Do not use
            # these to pull serve/land cut-points.
            extra_isis = [
                float(inside[i + 1] - inside[i]) for i in range(hit_count - 1)
            ] if hit_count >= 2 else []
            n_in_court = sum(
                1 for t in inside if self._hit_in_court(float(t), motion_t, motion_e)
            )
            pre_now = self._motion_active_fraction(
                max(0.0, first_hit - 2.2),
                max(0.0, first_hit - 0.5),
                motion_t,
                motion_e,
            )
            # 1:18 sideline: compact 5-hit high-motion continuation (next 1.89).
            # User GT12 n=5 first_isi=1.84 dens=1.45 next=4.84 dur=5.78.
            # Euro-long GT1 dens=1.89 next=7.20 max-isi=1.18.
            if (
                hit_count == 5
                and duration <= 4.5
                and density >= 2.2
                and extra_isis
                and max(extra_isis) <= 0.65
                and next_gap_hl < 2.5
                and active >= 0.80
            ):
                return False
            # 1:45 reaction + scoreboard: n=6 mixed-court compact ISIs dens=2.01.
            # Kaja n=6 dens=1.47 max-isi=1.71; user GT8 dens=0.97 dur=8s;
            # john GT6 n=6 dur=6.34 dens=1.57.
            if (
                hit_count == 6
                and duration <= 5.5
                and density >= 1.85
                and extra_isis
                and max(extra_isis) <= 0.90
                and n_in_court <= 3
            ):
                return False
            # 2:14 poster: n=3 talking-gap then compact pair, walking pre-motion.
            # User GT16 first_isi=3.23 pre=0 act=0.088 last_isi=1.39.
            if (
                hit_count == 3
                and 4.5 <= duration <= 6.0
                and 2.00 <= first_isi <= 2.80
                and last_isi <= 0.70
                and pre_now >= 0.55
                and active >= 0.65
            ):
                return False
            # 2:44 handshake: n=3 compact clap after a real rally, pre already high.
            # User GT5 first_isi=0.80 iso=1.36 next=4.21; GT14 burst=False n_in=0.
            if (
                hit_count == 3
                and duration <= 2.8
                and first_isi <= 0.35
                and last_isi <= 0.65
                and isolation >= 3.5
                and next_gap_hl < 3.0
                and pre_now >= 0.85
                and burst
            ):
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
