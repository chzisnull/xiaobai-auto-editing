package icu.yuqiuyijiaren.xiaobai.analysis.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of desktop `StrictHighlightFilter`.
 * Keep algorithm aligned with backend/app/detectors/highlight_filter.py.
 */
class StrictHighlightFilter(
    servePad: Double = 1.05,
    landPad: Double = 0.60,
    maxHitSilence: Double = 1.85,
    motionBridgeSilence: Double = 4.2,
    minHits: Int = 2,
    minDuration: Double = 1.5,
    maxDuration: Double = 22.0,
    minHitDensity: Double = 0.22,
    remergeGap: Double = 1.15,
) {
    private val servePad = max(0.2, servePad)
    private val landPad = max(0.2, landPad)
    private val maxHitSilence = max(1.0, maxHitSilence)
    private val motionBridgeSilence = max(this.maxHitSilence, motionBridgeSilence)
    private val minHits = max(1, minHits)
    private val minDuration = max(0.6, minDuration)
    private val maxDuration = max(8.0, maxDuration)
    private val minHitDensity = max(0.08, minHitDensity)
    private val remergeGap = max(0.3, remergeGap)

    fun apply(
        rallies: List<RallySegment>,
        hitPeaks: DoubleArray,
        motion: MotionSeries? = null,
    ): List<RallySegment> {
        if (rallies.isEmpty()) return emptyList()
        val motionT = motion?.timestamps ?: DoubleArray(0)
        val motionE = motion?.energies ?: DoubleArray(0)
        val split = splitByHitSilence(rallies, hitPeaks, motionT, motionE)
        val tightened = split.map { anchorToHits(it, hitPeaks, motionT, motionE) }
            .map { trimApplauseTail(it, hitPeaks) }
            .map { trimPostLand(it, hitPeaks, motionT, motionE) }
        val merged = remergeOverSplit(tightened, hitPeaks, motionT, motionE)
        val kept = ArrayList<RallySegment>()
        for (raw in merged) {
            var item = trimApplauseTail(raw, hitPeaks)
            item = anchorToHits(item, hitPeaks, motionT, motionE)
            item = trimPostLand(item, hitPeaks, motionT, motionE)
            val peeledCourt = peelPrimaryCourtFromAdjacentGlue(item, hitPeaks, motionT, motionE)
            if (peeledCourt != null) {
                item = anchorToHits(peeledCourt, hitPeaks, motionT, motionE)
                    .copyBounds(primaryCourtPeel = true)
            }
            if (isHighlight(item, hitPeaks, motionT, motionE)) {
                kept.add(item)
                continue
            }
            val peeledCore = peelShortIsoInRoiCore(item, hitPeaks, motionT, motionE)
            if (peeledCore != null && isHighlight(peeledCore, hitPeaks, motionT, motionE)) {
                kept.add(peeledCore)
                continue
            }
            val peeled = peelSilentPlayServe(item, hitPeaks, motionT, motionE)
            if (peeled != null && isHighlight(peeled, hitPeaks, motionT, motionE)) {
                kept.add(peeled)
            }
        }
        return dedupeOverlaps(kept)
    }

    private fun hitsIn(hits: DoubleArray, start: Double, end: Double): DoubleArray {
        if (hits.isEmpty()) return hits
        return hits.filter { it >= start - 0.05 && it <= end + 0.05 }.toDoubleArray()
    }

    private fun motionActiveFraction(
        start: Double,
        end: Double,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): Double {
        if (end <= start || motionT.isEmpty() || motionE.isEmpty()) return 0.0
        val left = motionT.leftIndex(start)
        val right = motionT.rightIndex(end)
        if (right <= left) return 0.0
        val window = motionE.copyOfRange(left, right)
        val baseline = median(motionE)
        val p85 = percentile(motionE, 0.85)
        val span = max(0.0, p85 - baseline)
        val meanWindow = window.average()
        val thr = if (span < 1e-5) {
            if (baseline >= 0.012 && meanWindow >= baseline * 0.85) return 1.0
            max(baseline + 0.004, 0.012)
        } else {
            max(baseline + 0.14 * span, 0.008)
        }
        if (window.maxOrNull() ?: 0.0 < thr) return 0.0
        return window.count { it >= thr }.toDouble() / window.size
    }

    private fun hasMotionBurst(
        start: Double,
        end: Double,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): Boolean {
        if (end <= start || motionT.isEmpty() || motionE.isEmpty()) return false
        val left = motionT.leftIndex(start)
        val right = motionT.rightIndex(end)
        if (right <= left) return false
        val window = motionE.copyOfRange(left, right)
        val baseline = median(motionE)
        val p85 = percentile(motionE, 0.85)
        val span = max(0.0, p85 - baseline)
        val peak = window.maxOrNull() ?: 0.0
        val burstFloor = max(0.016, baseline + 0.40 * span)
        if (peak >= burstFloor) return true
        val thr = max(0.014, baseline + 0.28 * span)
        var run = 0
        for (energy in window) {
            if (energy >= thr) {
                run++
                if (run >= 3) return true
            } else {
                run = 0
            }
        }
        return false
    }

    private fun walkSilentPlayEnd(
        lastHit: Double,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): Double {
        val default = lastHit + landPad
        if (motionT.isEmpty() || motionE.isEmpty()) return default
        val maxAhead = 3.5
        val baseline = median(motionE)
        val p85 = percentile(motionE, 0.85)
        val span = max(0.0, p85 - baseline)
        val thr = max(0.014, baseline + 0.28 * span)
        val left = motionT.leftIndex(lastHit)
        val right = motionT.rightIndex(lastHit + maxAhead)
        if (right <= left) return default
        var lastActive = lastHit
        var quiet = 0
        for (i in left until right) {
            if (motionE[i] >= thr) {
                lastActive = motionT[i]
                quiet = 0
            } else {
                quiet++
                if (quiet > 3 && motionT[i] >= lastHit + 0.80) break
            }
        }
        return max(default, min(lastHit + maxAhead, lastActive + 0.45))
    }

    private fun peelSilentPlayServe(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): RallySegment? {
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit)
        if (inside.size < 7) return null
        val lastIsi = inside[inside.lastIndex] - inside[inside.lastIndex - 1]
        val nextGap = nextHitGap(hits, inside[inside.lastIndex])
        if (lastIsi !in 1.05..1.40 || nextGap < 5.2) return null
        val serve = inside[inside.lastIndex]
        if (!hasMotionBurst(serve, serve + 2.8, motionT, motionE)) return null
        val start = max(0.0, serve - servePad)
        val end = walkSilentPlayEnd(serve, motionT, motionE)
        return RallySegment(
            start = start,
            end = max(start + 0.25, end),
            confidence = rally.confidence,
            firstHit = serve,
            lastHit = serve,
        )
    }

    private fun hitInCourt(
        timestamp: Double,
        motionT: DoubleArray,
        motionE: DoubleArray,
        radius: Double = 0.40,
    ): Boolean {
        if (motionT.isEmpty() || motionE.isEmpty()) return false
        val baseline = median(motionE)
        val p85 = percentile(motionE, 0.85)
        val span = max(0.0, p85 - baseline)
        val thr = max(0.008, baseline + 0.10 * span)
        val left = motionT.leftIndex(timestamp - radius)
        val right = motionT.rightIndex(timestamp + radius)
        if (right <= left) return motionAt(timestamp, motionT, motionE) >= thr
        var peak = 0.0
        for (i in left until right) peak = max(peak, motionE[i])
        return peak >= thr
    }

    private fun peelPrimaryCourtFromAdjacentGlue(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): RallySegment? {
        if (motionT.isEmpty() || motionE.isEmpty()) return null
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit)
        if (inside.size < 10) return null
        val duration = rally.end - rally.start
        val span = max(0.25, rally.lastHit - rally.firstHit)
        val density = inside.size / span
        if (duration < 6.0 || density <= 1.7) return null
        val isolation = hitIsolation(hits, rally.firstHit)
        if (isolation < 3.20) return null
        val inCourt = BooleanArray(inside.size) { hitInCourt(inside[it], motionT, motionE) }
        var cut = inside.size
        var twoHitNetFault = false
        var index = 0
        while (index < inside.size - 1) {
            val gap = inside[index + 1] - inside[index]
            // Need >=3 hits so a ~1.05s first ISI is not a point boundary.
            if (gap >= 1.00 && (index + 1) >= 3) {
                cut = index + 1
                break
            }
            if (!inCourt[index + 1]) {
                var run = 1
                var cursor = index + 2
                while (cursor < inside.size && !inCourt[cursor]) {
                    run++
                    cursor++
                }
                if (run >= 2) {
                    // Isolated 1-hit in-ROI serve then 2+ out-of-ROI (GT3).
                    if (index == 0 && inCourt[0]) {
                        cut = 2
                        twoHitNetFault = true
                    } else {
                        cut = index + 1
                    }
                    break
                }
                // Single out-of-ROI after a 3+ in-ROI head (euro-long GT5).
                val nIn = inCourt.copyOfRange(0, index + 1).count { it }
                if (nIn >= 3 && (index + 1) >= 3) {
                    cut = index + 1
                    break
                }
            }
            index++
        }
        if (cut >= inside.size) return null
        if (cut < 3 && !twoHitNetFault) return null
        if (cut >= 8) {
            val isis = DoubleArray(cut - 1) { inside[it + 1] - inside[it] }
            if (median(isis) < 0.42) return null
        }
        var leadOut = 0
        for (flag in inCourt.copyOfRange(0, cut)) {
            if (!flag) leadOut++ else break
        }
        if (leadOut >= 2) return null
        if (inCourt.copyOfRange(0, cut).none { it }) return null
        val serve = inside[0]
        val land = inside[cut - 1]
        return RallySegment(
            start = max(0.0, serve - servePad),
            end = land + landPad,
            confidence = rally.confidence,
            firstHit = serve,
            lastHit = land,
            primaryCourtPeel = true,
        )
    }

    private fun peelShortIsoInRoiCore(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): RallySegment? {
        // Euro-long GT6: extra 1:24 sits 2.20s before the in-ROI rally so
        // peel iso is 2.20. User-sample 6:27 is all in-ROI (leadOut=0,
        // n=10 dens=1.94 iso=2.28) and must not peel. Do not lower the
        // iso>=3.20 floor. Cap duration under 6s; do not re-anchor.
        if (motionT.isEmpty() || motionE.isEmpty()) return null
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit)
        if (inside.size < 10) return null
        val duration = rally.end - rally.start
        val span = max(0.25, rally.lastHit - rally.firstHit)
        val density = inside.size / span
        if (duration < 6.0 || density <= 1.7) return null
        val isolation = hitIsolation(hits, rally.firstHit)
        if (isolation >= 3.20) return null
        val inCourt = BooleanArray(inside.size) { hitInCourt(inside[it], motionT, motionE) }
        var leadOut = 0
        for (flag in inCourt) {
            if (!flag) leadOut++ else break
        }
        if (leadOut !in 1..2) return null
        var nInBody = 0
        for (i in leadOut until inCourt.size) if (inCourt[i]) nInBody++
        if (nInBody < 8) return null
        val startIdx = leadOut
        val serve = inside[startIdx]
        var cut = startIdx + 1
        var index = startIdx
        while (index < inside.size - 1) {
            val nxt = inside[index + 1]
            val tentativeStart = max(0.0, serve - servePad)
            val tentativeEnd = nxt + landPad
            if (tentativeEnd - tentativeStart >= 6.0) break
            val gap = nxt - inside[index]
            if (gap >= 1.00 && (index + 1 - startIdx) >= 3) break
            if (!inCourt[index + 1]) {
                var run = 1
                var cursor = index + 2
                while (cursor < inside.size && !inCourt[cursor]) {
                    run++
                    cursor++
                }
                if (run >= 2) break
            }
            cut = index + 2
            index++
        }
        val clusterN = cut - startIdx
        if (clusterN < 3) return null
        if (clusterN >= 8) {
            val isis = DoubleArray(clusterN - 1) { inside[startIdx + it + 1] - inside[startIdx + it] }
            if (median(isis) < 0.42) return null
        }
        val land = inside[cut - 1]
        return RallySegment(
            start = max(0.0, serve - servePad),
            end = land + landPad,
            confidence = rally.confidence,
            firstHit = serve,
            lastHit = land,
        )
    }

    private fun hitIsolation(hits: DoubleArray, firstHit: Double): Double {
        if (hits.isEmpty()) return 99.0
        var prev = Double.NaN
        for (hit in hits) {
            if (hit < firstHit - 0.12) prev = hit else break
        }
        return if (prev.isNaN()) 99.0 else firstHit - prev
    }

    private fun nextHitGap(hits: DoubleArray, lastHit: Double): Double {
        for (hit in hits) {
            if (hit > lastHit + 0.12) return hit - lastHit
        }
        return 99.0
    }

    private fun farCourtCompactThreeShape(
        isolation: Double,
        firstIsi: Double,
        lastIsi: Double,
        nextGap: Double,
        hitCount: Int,
        duration: Double,
    ): Boolean {
        if (hitCount != 3) return false
        if (duration !in 2.0..7.0) return false
        if (isolation !in 1.65..2.50) return false
        if (firstIsi !in 0.40..0.80) return false
        if (lastIsi !in 1.45..2.00) return false
        return nextGap >= 3.20
    }

    private fun isServeLike(
        isolation: Double,
        firstIsi: Double,
        hitCount: Int,
        duration: Double,
    ): Boolean {
        if (isolation < 1.85) return false
        if (hitCount == 1) {
            // Silent-serve 1-hit (iso>=9, GT21) needs a longer lead pad.
            if (isolation in 9.0..30.0) return duration in 1.2..6.8
            return isolation in 3.2..30.0 && duration in 1.2..3.8
        }
        if (firstIsi <= 0.0) return false
        return firstIsi in 0.22..2.55
    }

    private fun isolatedQuietPreHit(
        firstHit: Double,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
        minGap: Double,
        maxGap: Double,
        minIso: Double,
        maxIso: Double,
        maxGapActive: Double = 0.42,
    ): Double? {
        val candidates = hitsIn(hits, firstHit - maxGap, firstHit - minGap)
        if (candidates.isEmpty()) return null
        val candidate = candidates.last()
        val gap = firstHit - candidate
        if (gap <= minGap || gap > maxGap) return null
        val neighborsBefore = hitsIn(hits, candidate - 1.20, candidate - 0.08)
        val neighborsAfter = hitsIn(hits, candidate + 0.08, min(candidate + 1.20, firstHit - 0.12))
        if (neighborsBefore.isNotEmpty() || neighborsAfter.isNotEmpty()) return null
        val gapActive = motionActiveFraction(candidate, firstHit, motionT, motionE)
        val candBurst = hasMotionBurst(max(0.0, candidate - 0.35), candidate + 0.25, motionT, motionE)
        val candIso = hitIsolation(hits, candidate)
        if (candBurst || gapActive >= maxGapActive) return null
        if (candIso !in minIso..maxIso) return null
        return candidate
    }

    private fun gapStillLive(
        previousHit: Double,
        nextHit: Double,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): Boolean {
        val gap = nextHit - previousHit
        if (gap <= maxHitSilence) return true
        if (gap > motionBridgeSilence) return false
        if (motionT.isEmpty()) return false
        val active = motionActiveFraction(previousHit, nextHit, motionT, motionE)
        return if (gap <= 2.8) active >= 0.55 else active >= 0.50
    }

    private fun walkServeStart(
        firstHit: Double,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): Double {
        val default = max(0.0, firstHit - servePad)
        if (motionT.isEmpty() || motionE.isEmpty()) return default
        val maxLook = min(2.4, max(servePad + 1.1, 2.0))
        val windowStart = max(0.0, firstHit - maxLook)
        val left = motionT.leftIndex(windowStart)
        val right = motionT.rightIndex(firstHit + 0.12)
        if (right <= left) return default
        val baseline = median(motionE)
        val p85 = percentile(motionE, 0.85)
        val span = max(0.0, p85 - baseline)
        val onset = max(0.008, baseline + 0.08 * span)
        var anchor = (right - 1).coerceAtLeast(left)
        while (anchor > left && motionE[anchor] < onset) anchor--
        if (motionE[anchor] < onset) return default
        var startIndex = anchor
        var quiet = 0
        for (i in anchor - 1 downTo left) {
            if (motionE[i] >= onset) {
                startIndex = i
                quiet = 0
            } else {
                quiet++
                if (quiet > 3) break
            }
        }
        val motionStart = motionT[startIndex] - 0.30
        val preEnd = motionT[startIndex]
        val preStart = max(0.0, preEnd - 1.4)
        val headActive = motionActiveFraction(preStart, preEnd, motionT, motionE)
        val tossActive = motionActiveFraction(max(0.0, firstHit - 0.7), firstHit + 0.15, motionT, motionE)
        if (headActive >= 0.55 && tossActive < headActive + 0.05 && (firstHit - motionStart) > 1.1) {
            return default
        }
        return max(0.0, min(default, max(windowStart, motionStart)))
    }

    private fun anchorToHits(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): RallySegment {
        var firstHit = rally.firstHit
        var lastHit = rally.lastHit
        val inside = hitsIn(hits, rally.start - 0.25, rally.end + 0.25)
        if (inside.isNotEmpty()) {
            firstHit = inside.first()
            lastHit = inside.last()
            val preFrom = max(rally.start - 0.55, firstHit - 1.6)
            val pre = hitsIn(hits, preFrom, firstHit - 0.12)
            if (pre.size == 1 && firstHit - pre.first() <= 1.6) {
                firstHit = pre.first()
            }
        }
        val clustered = hitsIn(hits, firstHit, lastHit)
        var trimmedWalkHead = false
        if (clustered.size >= 5) {
            val lastIsi = clustered.last() - clustered[clustered.size - 2]
            val isolation = hitIsolation(hits, clustered.first())
            val span = clustered.last() - clustered.first()
            val head = clustered.copyOfRange(0, clustered.size - 2)
            val headIsis = DoubleArray(max(0, head.size - 1)) { i -> head[i + 1] - head[i] }
            val sparseHead = headIsis.isNotEmpty() && (headIsis.minOrNull() ?: 0.0) >= 0.70
            if (lastIsi <= 0.50 && isolation < 2.20 && span >= 3.5 && sparseHead) {
                firstHit = clustered[clustered.size - 2]
                lastHit = clustered.last()
                trimmedWalkHead = true
            }
        }
        // Live court-bound rally: pull a single quiet far-court contact
        // (~1.94s high-clear hole) without a global silence-gap bump.
        if (
            !trimmedWalkHead &&
            clustered.size >= 4 &&
            (clustered.last() - clustered.first()) <= 3.8 &&
            hasMotionBurst(firstHit, lastHit, motionT, motionE)
        ) {
            val candidateHits = hitsIn(hits, firstHit - 2.05, firstHit - 0.12)
            if (candidateHits.isNotEmpty()) {
                val candidate = candidateHits.last()
                val gap = firstHit - candidate
                val neighbors = hitsIn(hits, candidate - 1.20, candidate - 0.08)
                val gapActive = motionActiveFraction(candidate, firstHit, motionT, motionE)
                val candBurst = hasMotionBurst(
                    max(0.0, candidate - 0.35),
                    candidate + 0.25,
                    motionT,
                    motionE,
                )
                val candIso = hitIsolation(hits, candidate)
                if (
                    gap > 1.70 && gap <= 2.05 &&
                    neighbors.isEmpty() &&
                    !candBurst &&
                    gapActive < 0.42 &&
                    candIso in 2.8..8.5
                ) {
                    firstHit = candidate
                }
            }
        }
        // Local start-lead: live rally pulls one isolated quiet serve-like
        // hit across a 2.50-5.75s high-clear hole (GT4/10/20).
        var clusteredLive = hitsIn(hits, firstHit, lastHit)
        if (
            !trimmedWalkHead &&
            clusteredLive.size >= 5 &&
            (clusteredLive.last() - clusteredLive[clusteredLive.size - 2]) >= 0.50 &&
            hasMotionBurst(firstHit, lastHit, motionT, motionE)
        ) {
            val pulled = isolatedQuietPreHit(
                firstHit, hits, motionT, motionE,
                minGap = 2.50, maxGap = 5.75, minIso = 1.50, maxIso = 6.0,
            )
            if (pulled != null) {
                firstHit = pulled
            } else {
                val maxInterior = (0 until clusteredLive.size - 1).maxOf { clusteredLive[it + 1] - clusteredLive[it] }
                if (maxInterior < 2.50) {
                    val tight = isolatedQuietPreHit(
                        firstHit, hits, motionT, motionE,
                        minGap = 2.16, maxGap = 2.50, minIso = 1.50, maxIso = 6.0,
                    )
                    if (tight != null) firstHit = tight
                }
            }
        }
        val clusteredNow = hitsIn(hits, firstHit, lastHit)
        val nHereStart = clusteredNow.size
        val isoNow = hitIsolation(hits, firstHit)
        val nextNow = nextHitGap(hits, lastHit)
        var start = if (
            !trimmedWalkHead &&
            nHereStart == 1 &&
            isoNow in 9.0..30.0 &&
            nextNow in 1.80..3.50
        ) {
            var silentStart = max(0.0, firstHit - 5.60)
            val prevHits = hits.filter { it < firstHit - 0.12 }
            if (prevHits.isNotEmpty()) silentStart = max(silentStart, prevHits.last() + 0.35)
            silentStart
        } else if (trimmedWalkHead) {
            max(0.0, firstHit - servePad)
        } else {
            walkServeStart(firstHit, motionT, motionE)
        }
        var end = max(start + 0.25, lastHit + landPad)
        if (motionT.isNotEmpty()) {
            val tailActive = motionActiveFraction(lastHit, lastHit + landPad + 0.35, motionT, motionE)
            val nHere = hitsIn(hits, firstHit, lastHit).size
            val isolatedOne = nHere == 1 && hitIsolation(hits, firstHit) >= 3.2
            val clusteredNow = hitsIn(hits, firstHit, lastHit)
            val farThree = nHere == 3 && clusteredNow.size == 3 && farCourtCompactThreeShape(
                isolation = hitIsolation(hits, firstHit),
                firstIsi = clusteredNow[1] - clusteredNow[0],
                lastIsi = clusteredNow.last() - clusteredNow[clusteredNow.size - 2],
                nextGap = nextHitGap(hits, lastHit),
                hitCount = 3,
                duration = max(end - start, lastHit + 2.20 - start),
            )
            if (farThree) {
                end = max(end, lastHit + 2.20)
            } else if (tailActive < 0.22 && !isolatedOne) {
                end = min(end, lastHit + min(0.45, landPad))
            }
            // John GT3-like: 4-hit exchange, last ISI is the landing then a
            // 4s+ between-point hole. 0.60s pad leaves iou=0.27 vs a 5s GT.
            // User n=4 TPs have next<2.0 or lastIsi>=2.4.
            if (nHere == 4 && clusteredNow.size == 4) {
                val landLastIsi = clusteredNow.last() - clusteredNow[clusteredNow.size - 2]
                val landNext = nextHitGap(hits, lastHit)
                if (landLastIsi in 1.45..1.80 && landNext >= 4.0 &&
                    hasMotionBurst(firstHit, lastHit, motionT, motionE)
                ) {
                    end = max(end, lastHit + 3.50)
                }
            }
        }
        return rally.copyBounds(
            start = max(0.0, start),
            end = max(max(0.0, start) + 0.25, end),
            firstHit = firstHit,
            lastHit = lastHit,
        )
    }

    private fun trimApplauseTail(rally: RallySegment, hits: DoubleArray): RallySegment {
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit)
        if (inside.size < 7) return rally
        val span = inside.last() - inside.first()
        val tailFrom = inside.first() + 0.55 * span
        var cutAt: Double? = null
        for (index in 0..inside.size - 6) {
            val window = inside.copyOfRange(index, index + 6)
            val intervals = DoubleArray(5) { i -> window[i + 1] - window[i] }
            val sorted = intervals.sorted()
            val medianIsi = sorted[2]
            val maxIsi = intervals.maxOrNull() ?: 0.0
            if (medianIsi < 0.36 && maxIsi < 0.60) {
                val inTail = window[0] >= tailFrom
                val clapDur = window.last() - window[0]
                if ((inTail || clapDur >= 3.0) && index >= 2) {
                    cutAt = inside[index]
                    break
                }
            }
        }
        val cut = cutAt ?: return rally
        if (cut <= rally.firstHit + 0.4) return rally
        return rally.copyBounds(end = min(rally.end, cut + landPad), lastHit = cut)
    }

    private fun motionAt(timestamp: Double, motionT: DoubleArray, motionE: DoubleArray): Double {
        if (motionT.isEmpty() || motionE.isEmpty()) return 0.0
        var index = motionT.leftIndex(timestamp).coerceIn(0, motionE.lastIndex)
        if (index > 0 && kotlin.math.abs(motionT[index - 1] - timestamp) <= kotlin.math.abs(motionT[index] - timestamp)) {
            index -= 1
        }
        return motionE[index]
    }

    private fun trimPostLand(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): RallySegment {
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit).toMutableList()
        if (inside.size < 7 || motionE.isEmpty()) return rally
        var cut: Int? = null
        val startIndex = max(3, (inside.size * 2) / 5)
        for (index in startIndex until (inside.size - 3)) {
            val gap = inside[index + 1] - inside[index]
            val tailN = inside.size - (index + 1)
            if (gap < 1.32 || tailN < 3) continue
            if (gap > maxHitSilence && gapStillLive(inside[index], inside[index + 1], motionT, motionE)) {
                continue
            }
            val tail = inside.subList(index + 1, inside.size)
            if (gap < 1.55 && tail.size >= 2 && (tail.last() - tail[tail.size - 2]) <= 0.55) {
                continue
            }
            cut = index
            break
        }
        if (cut == null) return rally
        val kept = inside.subList(0, cut + 1)
        return rally.copyBounds(
            end = min(rally.end, kept.last() + landPad),
            firstHit = kept.first(),
            lastHit = kept.last(),
        )
    }

    private fun splitByHitSilence(
        rallies: List<RallySegment>,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): List<RallySegment> {
        if (hits.isEmpty()) return rallies.map { it.copy() }
        val result = ArrayList<RallySegment>()
        for (rally in rallies) {
            val inside = hitsIn(hits, rally.start, rally.end)
            if (inside.size < 2) {
                result.add(rally.copy())
                continue
            }
            val groups = ArrayList<MutableList<Double>>()
            groups.add(mutableListOf(inside[0]))
            for (i in 1 until inside.size) {
                val hit = inside[i]
                val prev = groups.last().last()
                val gap = hit - prev
                if (gap > maxHitSilence && !gapStillLive(prev, hit, motionT, motionE)) {
                    groups.add(mutableListOf(hit))
                } else if (gap > motionBridgeSilence) {
                    groups.add(mutableListOf(hit))
                } else {
                    groups.last().add(hit)
                }
            }
            val refined = ArrayList<List<Double>>()
            for (group in groups) {
                val span = group.last() - group.first()
                val longBlob = span > 10.0 && group.size >= 5
                if (span <= maxDuration && !longBlob) {
                    refined.add(group)
                    continue
                }
                if (group.size < 5) {
                    refined.add(group)
                    continue
                }
                val gaps = (0 until group.lastIndex).map { index ->
                    (group[index + 1] - group[index]) to index
                }.sortedByDescending { it.first }
                var cutDone = false
                val minCut = if (longBlob) 1.40 else max(1.85, maxHitSilence * 0.9)
                for ((gapLen, index) in gaps.take(5)) {
                    if (gapLen < minCut) break
                    val leftHit = group[index]
                    val rightHit = group[index + 1]
                    val live = gapStillLive(leftHit, rightHit, motionT, motionE)
                    val active = motionActiveFraction(leftHit, rightHit, motionT, motionE)
                    val walkingGap = longBlob && gapLen >= 1.40 && active >= 0.48
                    if (!live || walkingGap) {
                        refined.add(group.subList(0, index + 1).toList())
                        refined.add(group.subList(index + 1, group.size).toList())
                        cutDone = true
                        break
                    }
                }
                if (!cutDone) refined.add(group)
            }
            for (group in refined) {
                result.add(
                    RallySegment(
                        start = group.first() - servePad,
                        end = group.last() + landPad,
                        confidence = rally.confidence,
                        firstHit = group.first(),
                        lastHit = group.last(),
                    ),
                )
            }
        }
        return result
    }

    internal fun remergeOverSplit(
        rallies: List<RallySegment>,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): List<RallySegment> {
        if (rallies.size <= 1) return rallies
        val ordered = rallies.sortedBy { it.start }.map { it.copy() }.toMutableList()
        val merged = mutableListOf(ordered[0])
        for (i in 1 until ordered.size) {
            val item = ordered[i]
            val prev = merged.last()
            val gap = item.start - prev.end
            val hitGap = item.firstHit - prev.lastHit
            val projected = item.end - prev.start
            val itemHits = hitsIn(hits, item.firstHit, item.lastHit)
            val prevHits = hitsIn(hits, prev.firstHit, prev.lastHit)
            var should = false
            val gapActive = motionActiveFraction(prev.lastHit, item.firstHit, motionT, motionE)
            val walkingRejoin = (
                prevHits.size >= 4 &&
                    (prev.lastHit - prev.firstHit) >= 4.0 &&
                    hitGap >= 1.50 &&
                    gapActive >= 0.48
                )
            val prevBurstStart = if (prev.lastHit - prev.firstHit >= 0.4) prev.lastHit else prev.lastHit + 0.40
            val prevBurst = hasMotionBurst(prev.firstHit, prevBurstStart, motionT, motionE)
            val itemBurstStart = if (item.lastHit - item.firstHit >= 0.4) item.firstHit else item.firstHit - 0.40
            val itemBurstEnd = if (item.lastHit - item.firstHit >= 0.4) item.lastHit else item.lastHit + 0.40
            val itemBurst = hasMotionBurst(itemBurstStart, itemBurstEnd, motionT, motionE)
            if (
                gap < 0.0 &&
                hitGap in -0.05..0.20 &&
                prevHits.size >= 3 &&
                itemHits.size >= 4 &&
                !prevBurst &&
                itemBurst
            ) {
                val latest = item.start - 0.35
                val prevInside = prevHits.filter { it < item.firstHit - 0.12 }
                if (prevInside.isNotEmpty() && latest > prev.start + 0.5) {
                    merged[merged.lastIndex] = prev.copyBounds(
                        end = min(prev.end, min(prevInside.last() + landPad, latest)),
                        lastHit = prevInside.last(),
                    )
                }
                merged.add(item)
                continue
            }
            if (projected <= maxDuration + 2.0 && !walkingRejoin) {
                if (gap <= remergeGap && hitGap <= motionBridgeSilence) {
                    val live = gapStillLive(prev.lastHit, item.firstHit, motionT, motionE)
                    if (live || (gap <= 0.45 && hitGap <= 1.8 && gapActive < 0.62)) {
                        should = true
                    } else if (
                        gap <= 0.60 &&
                        hitGap > maxHitSilence && hitGap <= 2.2 &&
                        gapActive < 0.62 &&
                        projected <= 12.0 &&
                        prevHits.size >= 2 &&
                        itemHits.size >= 3 &&
                        !isWalkIn(prev, hits)
                    ) {
                        should = true
                    }
                } else if (hitGap <= maxHitSilence) {
                    should = true
                } else if (
                    hitGap > maxHitSilence && hitGap <= 4.8 &&
                    projected <= maxDuration &&
                    itemHits.size <= 2 &&
                    prevHits.size >= 2 &&
                    gapActive < 0.45 &&
                    !(itemHits.size == 1 && hitGap >= 3.2)
                ) {
                    should = true
                } else if (
                    hitGap > 3.2 && hitGap <= 5.2 &&
                    projected <= 8.5 &&
                    prevHits.size <= 2 &&
                    itemHits.size <= 2 &&
                    gapActive < 0.90 &&
                    !(
                        prevHits.size == 1 &&
                        hitIsolation(hits, prev.firstHit) in 3.30..<5.0 &&
                        nextHitGap(hits, prev.lastHit) >= 3.60
                    )
                ) {
                    should = true
                } else if (
                    hitGap > 2.8 && hitGap <= 5.3 &&
                    projected <= maxDuration &&
                    prevHits.size <= 2 &&
                    itemHits.size >= 3 &&
                    gapActive < 0.42
                ) {
                    should = true
                } else if (
                    hitGap > maxHitSilence && hitGap <= 2.2 &&
                    projected <= 12.0 &&
                    prevHits.size >= 2 &&
                    itemHits.size >= 3 &&
                    gapActive < 0.55
                ) {
                    should = true
                }
            }
            if (should) {
                merged[merged.lastIndex] = prev.copyBounds(
                    start = min(prev.start, item.start),
                    end = max(prev.end, item.end),
                    confidence = min(prev.confidence, item.confidence),
                    firstHit = min(prev.firstHit, item.firstHit),
                    lastHit = max(prev.lastHit, item.lastHit),
                )
            } else if (
                projected <= maxDuration + 2.0 &&
                    !walkingRejoin &&
                    hitGap > 5.0 && hitGap <= 5.6 &&
                    prevHits.size in 2..5 &&
                    itemHits.size >= 4 &&
                    gapActive < 0.40
            ) {
                val landing = item.firstHit
                merged[merged.lastIndex] = prev.copyBounds(
                    end = max(prev.end, landing + landPad),
                    lastHit = max(prev.lastHit, landing),
                )
                val rest = itemHits.filter { it > landing + maxHitSilence }
                if (rest.size >= 2) {
                    merged.add(
                        item.copyBounds(
                            start = rest.first() - servePad,
                            end = rest.last() + landPad,
                            firstHit = rest.first(),
                            lastHit = rest.last(),
                        ),
                    )
                }
            } else {
                merged.add(item)
            }
        }
        return merged
    }

    private fun isWalkIn(rally: RallySegment, hits: DoubleArray): Boolean {
        if (rally.start > 0.40) return false
        if (rally.firstHit > 0.85) return false
        if (rally.lastHit > 5.5 || rally.duration > 6.0) return false
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit)
        return inside.size >= 4 && rally.lastHit <= 5.5
    }

    internal fun isHighlight(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): Boolean {
        val duration = rally.duration
        if (duration < minDuration) return false
        if (duration > min(maxDuration + 2.0, 16.8)) return false
        if (isWalkIn(rally, hits)) return false
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit)
        val hitCount = inside.size
        val isolation = hitIsolation(hits, rally.firstHit)
        val firstIsi = if (hitCount >= 2) inside[1] - inside[0] else 0.0
        val lastIsi = if (hitCount >= 2) inside[inside.lastIndex] - inside[inside.lastIndex - 1] else 0.0
        val nextGapHl = nextHitGap(hits, rally.lastHit)
        val serveLike = isServeLike(isolation, firstIsi, hitCount, duration)
        val burstStart = if (rally.lastHit - rally.firstHit >= 0.4) rally.firstHit else rally.firstHit - 0.40
        val burstEnd = if (rally.lastHit - rally.firstHit >= 0.4) rally.lastHit else rally.lastHit + 0.40
        val burst = hasMotionBurst(burstStart, burstEnd, motionT, motionE)
        val farCourtThree = !burst && farCourtCompactThreeShape(
            isolation, firstIsi, lastIsi, nextGapHl, hitCount, duration,
        )
        if (hitCount < minHits) {
            val nextGap = nextHitGap(hits, rally.lastHit)
            // Burst needs a between-point hole: adjacent leftovers can burst
            // in-ROI with next-gap ~2.4s (euro-short 0:06). GT11 next>=3.6.
            val farCourtOne = hitCount == 1 && serveLike && (
                (burst && nextGap >= 3.20) ||
                    (isolation >= 9.0 && nextGap in 1.80..3.50) ||
                    (isolation >= 3.30 && isolation < 5.0 && nextGap >= 3.60)
                )
            val silentPlayOne = hitCount == 1 && burst &&
                isolation in 1.05..2.50 && nextGap >= 5.2 && duration <= 5.5
            if (!(minHits <= 2 && (farCourtOne || silentPlayOne))) return false
        }
        val hitSpan = max(0.25, rally.lastHit - rally.firstHit)
        val density = hitCount / hitSpan
        if (hitCount >= 2 && density < minHitDensity && hitCount < minHits + 2) {
            if (!(hitCount <= 2 && duration <= 5.5)) return false
        }
        if (duration >= 12.0 && density < minHitDensity * 0.85) return false
        // Do not loosen globally (user-sample 6:27). Peeled primary-court
        // clusters (euro-long GT4) may still be dense 6s rallies.
        if (duration >= 6.0 && density > 1.7 && hitCount >= 10 && !rally.primaryCourtPeel) return false
        if (hitCount <= 3 && !burst && !serveLike && !farCourtThree) return false
        if (hitCount == 2 && !burst && firstIsi > 1.6) return false
        if (hitCount >= 4 && !burst && motionT.isNotEmpty()) return false
        if (hitCount in 2..3 && !burst && duration >= 2.45 && motionT.isNotEmpty() && !farCourtThree) {
            val activeShort = motionActiveFraction(rally.firstHit, rally.lastHit, motionT, motionE)
            if (activeShort < 0.12) return false
        }
        // Euro-long extra 1:24: 2 out-of-ROI hits, no burst, next-gap 2.20s
        // before GT6. Do not lower duration>=2.45 (protects GT14 n=3 ~2.1s).
        if (hitCount == 2 && !burst && motionT.isNotEmpty() && nextGapHl < 3.20 && !farCourtThree) {
            val nIn = inside.count { hitInCourt(it, motionT, motionE) }
            if (nIn == 0) return false
        }
        if (duration in 6.0..9.5 && hitCount >= 7 && isolation < 2.30) return false
        if (duration in 8.0..11.0 && hitCount in 4..6 && isolation < 1.85) return false
        if (hitCount >= 11 && duration >= 10.0 && isolation < 2.15) return false
        if (motionT.isNotEmpty()) {
            val active = motionActiveFraction(rally.firstHit, rally.lastHit, motionT, motionE)
            if (duration >= 2.5 && active < 0.12 && hitCount >= 4) return false
            if (duration >= 3.0 && active < 0.18 && density < minHitDensity * 1.15 && hitCount >= 4) return false
            if (duration <= 5.0 && active < 0.12 && hitCount >= 5) return false
            // Interview / huddle talking-length: high-motion plateau (mean
            // near p85), not a rally burst-then-quiet. Long huddle n>=15
            // dur>=12s (john 14.8s) vs 9s live exchange. Shorter huddle
            // (9.4s n=9 firstIsi=2.25) needs the first-gap clause.
            if (duration >= 12.0 && hitCount >= 15) {
                val left = motionT.leftIndex(rally.firstHit)
                val right = motionT.rightIndex(rally.lastHit)
                if (right > left) {
                    val meanWindow = motionE.copyOfRange(left, right).average()
                    val p85 = percentile(motionE, 0.85)
                    if (p85 > 1e-5 && meanWindow >= 0.80 * p85) return false
                }
            }
            if (duration >= 8.5 && hitCount >= 8 && firstIsi >= 2.0) {
                val left = motionT.leftIndex(rally.firstHit)
                val right = motionT.rightIndex(rally.lastHit)
                if (right > left) {
                    val meanWindow = motionE.copyOfRange(left, right).average()
                    val p85 = percentile(motionE, 0.85)
                    if (p85 > 1e-5 && meanWindow >= 0.80 * p85) return false
                }
            }
            if (rally.firstHit >= 8.0) {
                val preActive = motionActiveFraction(
                    max(0.0, rally.firstHit - 2.2),
                    max(0.0, rally.firstHit - 0.5),
                    motionT,
                    motionE,
                )
                val highClearOpen = firstIsi >= 4.5
                if (duration >= 8.0 && hitCount >= 6 && !highClearOpen) {
                    if (preActive >= 0.55 && hitCount >= 8 && isolation < 5.5) return false
                    if (preActive >= 0.58 && isolation < 2.4) return false
                }
                if (
                    duration >= 4.5 && hitCount >= 5 && preActive >= 0.55 &&
                        isolation < 2.05 && !highClearOpen
                ) {
                    return false
                }
                if (duration >= 5.8 && hitCount in 4..6 && preActive >= 0.70 && isolation < 5.0) return false
                // 2-hit leftover after walking (2:43, 7:13, 4:13 after GT11).
                // Compact net-faults (GT5 ISI 0.30s) survive; walking ISI ~1.2s.
                // preActive 0.42 catches the GT11-adjacent walk (0.46).
                if (hitCount == 2 && duration <= 4.5 && preActive >= 0.42 && isolation < 4.0 && firstIsi > 0.55) return false
                // Compact 2-hit walking blip (4:53) before a real net-fault.
                // GT5/GT14 are 3-hit; GT14 iso ~3.2 with no pre-motion.
                if (
                    hitCount == 2 && duration <= 2.3 && preActive >= 0.60 &&
                        isolation >= 1.85 && isolation < 2.80 && firstIsi <= 0.55
                ) {
                    return false
                }
            }
            // 4-hit walking cadence (4:03 extra before GT11, 4:18 before GT12).
            // GT17/GT18 are n=4 but lastIsi >= 2.4 or firstIsi >= 3.1 and longer.
            if (hitCount == 4 && duration in 4.2..5.8) {
                val isis = (0 until hitCount - 1).map { inside[it + 1] - inside[it] }
                if (isis.minOrNull()!! >= 0.90 && isis.maxOrNull()!! <= 1.75) return false
            }
            // Adjacent-court compact tail after one isolated contact (2:17 extra).
            // GT10 is n=5 but last-4 span ~2.0s and active ~0.68.
            if (
                hitCount == 5 && duration <= 5.3 && firstIsi >= 1.55 &&
                    inside[inside.lastIndex] - inside[1] <= 1.50 && active < 0.30
            ) {
                return false
            }
            // 4-hit pickup: compact last after a ~1.5s hole (2:56 extra).
            if (hitCount == 4 && duration <= 4.6 && lastIsi <= 0.40) {
                val isis = (0 until hitCount - 1).map { inside[it + 1] - inside[it] }
                if (isis.any { it in 1.45..1.80 }) return false
            }
            // John-carroll remaining extras. Signatures measured on the clip;
            // user-sample / euro-long / kaja TPs do not match.
            val extraIsis = if (hitCount >= 2) {
                (0 until hitCount - 1).map { inside[it + 1] - inside[it] }
            } else {
                emptyList()
            }
            val nInCourt = inside.count { hitInCourt(it, motionT, motionE) }
            val preNow = motionActiveFraction(
                max(0.0, rally.firstHit - 2.2),
                max(0.0, rally.firstHit - 0.5),
                motionT,
                motionE,
            )
            // 1:18 sideline: compact 5-hit high-motion continuation (next 1.89).
            // User GT12 n=5 firstIsi=1.84 dens=1.45 next=4.84 dur=5.78.
            // Euro-long GT1 dens=1.89 next=7.20 max-isi=1.18.
            if (
                hitCount == 5 && duration <= 4.5 && density >= 2.2 &&
                    extraIsis.isNotEmpty() && extraIsis.maxOrNull()!! <= 0.65 &&
                    nextGapHl < 2.5 && active >= 0.80
            ) {
                return false
            }
            // 1:45 reaction + scoreboard: n=6 mixed-court compact ISIs dens=2.01.
            // Kaja n=6 dens=1.47 max-isi=1.71; user GT8 dens=0.97 dur=8s;
            // john GT6 n=6 dur=6.34 dens=1.57.
            if (
                hitCount == 6 && duration <= 5.5 && density >= 1.85 &&
                    extraIsis.isNotEmpty() && extraIsis.maxOrNull()!! <= 0.90 &&
                    nInCourt <= 3
            ) {
                return false
            }
            // 2:14 poster: n=3 talking-gap then compact pair, walking pre-motion.
            // User GT16 firstIsi=3.23 pre=0 act=0.088 lastIsi=1.39.
            if (
                hitCount == 3 && duration in 4.5..6.0 &&
                    firstIsi in 2.00..2.80 && lastIsi <= 0.70 &&
                    preNow >= 0.55 && active >= 0.65
            ) {
                return false
            }
            // 2:44 handshake: n=3 compact clap after a real rally, pre already high.
            // User GT5 firstIsi=0.80 iso=1.36 next=4.21; GT14 burst=False nIn=0.
            if (
                hitCount == 3 && duration <= 2.8 && firstIsi <= 0.35 &&
                    lastIsi <= 0.65 && isolation >= 3.5 && nextGapHl < 3.0 &&
                    preNow >= 0.85 && burst
            ) {
                return false
            }
        }
        return true
    }

    private fun dedupeOverlaps(rallies: List<RallySegment>): List<RallySegment> {
        if (rallies.size <= 1) return rallies
        val ordered = rallies.sortedBy { it.start }
        val merged = mutableListOf(ordered[0].copy())
        for (i in 1 until ordered.size) {
            val item = ordered[i]
            val prev = merged.last()
            if (item.start <= prev.end + 0.15) {
                val prevHits = prev.lastHit - prev.firstHit
                val itemHits = item.lastHit - item.firstHit
                if (itemHits > prevHits) merged[merged.lastIndex] = item.copy()
                continue
            }
            merged.add(item.copy())
        }
        return merged
    }
}

internal fun median(values: DoubleArray): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 0) {
        (sorted[mid - 1] + sorted[mid]) / 2.0
    } else {
        sorted[mid]
    }
}

internal fun percentile(values: DoubleArray, q: Double): Double {
    if (values.isEmpty()) return 0.0
    val sorted = values.sorted()
    val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)
    return sorted[idx]
}

private fun DoubleArray.leftIndex(value: Double): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid] < value) lo = mid + 1 else hi = mid
    }
    return lo
}

private fun DoubleArray.rightIndex(value: Double): Int {
    var lo = 0
    var hi = size
    while (lo < hi) {
        val mid = (lo + hi) ushr 1
        if (this[mid] <= value) lo = mid + 1 else hi = mid
    }
    return lo
}
