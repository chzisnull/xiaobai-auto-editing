package icu.yuqiuyijiaren.xiaobai.analysis.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Kotlin port of desktop `StrictHighlightFilter`.
 * Keep algorithm aligned with backend/app/detectors/highlight_filter.py.
 */
class StrictHighlightFilter(
    servePad: Double = 0.75,
    landPad: Double = 0.95,
    maxHitSilence: Double = 2.5,
    motionBridgeSilence: Double = 3.6,
    minHits: Int = 3,
    minDuration: Double = 1.5,
    maxDuration: Double = 22.0,
    minHitDensity: Double = 0.30,
    remergeGap: Double = 1.35,
) {
    private val servePad = max(0.2, servePad)
    private val landPad = max(0.2, landPad)
    private val maxHitSilence = max(1.0, maxHitSilence)
    private val motionBridgeSilence = max(this.maxHitSilence, motionBridgeSilence)
    private val minHits = max(2, minHits)
    private val minDuration = max(0.6, minDuration)
    private val maxDuration = max(8.0, maxDuration)
    private val minHitDensity = max(0.1, minHitDensity)
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
        val merged = remergeOverSplit(tightened, hitPeaks, motionT, motionE)
        val kept = merged.filter { isHighlight(it, hitPeaks, motionT, motionE) }
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
        return motionActiveFraction(previousHit, nextHit, motionT, motionE) >= 0.42
    }

    private fun anchorToHits(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): RallySegment {
        var firstHit = rally.firstHit
        var lastHit = rally.lastHit
        val inside = hitsIn(hits, rally.start - 0.3, rally.end + 0.3)
        if (inside.isNotEmpty()) {
            firstHit = inside.first()
            lastHit = inside.last()
        }
        var start = max(0.0, firstHit - servePad)
        var end = max(start + 0.25, lastHit + landPad)
        if (motionT.isNotEmpty()) {
            val tailActive = motionActiveFraction(lastHit, lastHit + landPad + 0.4, motionT, motionE)
            if (tailActive < 0.28) {
                end = min(end, lastHit + min(0.55, landPad))
            }
            val headActive = motionActiveFraction(max(0.0, firstHit - servePad - 0.2), firstHit, motionT, motionE)
            if (headActive < 0.25) {
                start = max(start, firstHit - min(0.45, servePad))
            }
        }
        return rally.copyBounds(
            start = start,
            end = max(start + 0.25, end),
            firstHit = firstHit,
            lastHit = lastHit,
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
                if (span <= maxDuration || group.size < 5) {
                    refined.add(group)
                    continue
                }
                val gaps = (0 until group.lastIndex).map { index ->
                    (group[index + 1] - group[index]) to index
                }.sortedByDescending { it.first }
                var cutDone = false
                for ((gapLen, index) in gaps.take(3)) {
                    if (gapLen < max(2.0, maxHitSilence * 0.9)) break
                    val leftHit = group[index]
                    val rightHit = group[index + 1]
                    if (!gapStillLive(leftHit, rightHit, motionT, motionE)) {
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

    private fun remergeOverSplit(
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
            var should = false
            if (projected <= maxDuration + 2.0) {
                if (gap <= remergeGap && hitGap <= motionBridgeSilence) {
                    val live = gapStillLive(prev.lastHit, item.firstHit, motionT, motionE)
                    if (live || (gap <= 0.55 && hitGap <= 2.0)) should = true
                } else if (hitGap <= maxHitSilence) {
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
            } else {
                merged.add(item)
            }
        }
        return merged
    }

    private fun isHighlight(
        rally: RallySegment,
        hits: DoubleArray,
        motionT: DoubleArray,
        motionE: DoubleArray,
    ): Boolean {
        val duration = rally.duration
        if (duration < minDuration) return false
        if (duration > maxDuration + 5.0) return false
        val inside = hitsIn(hits, rally.firstHit, rally.lastHit)
        val hitCount = inside.size
        if (hitCount < minHits) return false
        val hitSpan = max(0.25, rally.lastHit - rally.firstHit)
        val density = hitCount / hitSpan
        if (density < minHitDensity && hitCount < minHits + 2) return false
        if (duration >= 12.0 && density < minHitDensity * 0.85) return false
        if (motionT.isNotEmpty()) {
            val active = motionActiveFraction(rally.firstHit, rally.lastHit, motionT, motionE)
            if (duration >= 2.5 && active < 0.20) return false
            if (duration >= 3.0 && active < 0.28 && density < minHitDensity * 1.15) return false
            if (duration <= 5.0 && active < 0.32 && hitCount <= 4) return false
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
