package icu.yuqiuyijiaren.xiaobai.analysis.pipeline

import kotlin.math.max
import kotlin.math.min

/**
 * Court-aware hit grouping aligned with desktop CourtAwareRallyDetector strict FSM.
 * Includes motion-walk serve start / landing end (no TrackNet).
 */
class CourtAwareRallyBuilder(
    private val strict: Boolean = true,
    private val motionThreshold: Double = 0.01,
    private val maxSilenceGap: Double = 1.85,
    private val maxBridgeGap: Double = 4.2,
    private val minRallyDuration: Double = 1.35,
    private val serveLead: Double = 0.50,
    private val landingDelay: Double = 0.70,
    private val maxServeLookback: Double = 2.4,
    private val residualMotionWindow: Double = 1.0,
    private val visualFps: Int = 8,
) {
    fun build(hits: DoubleArray, motion: MotionSeries?): List<RallySegment> {
        if (hits.isEmpty()) return emptyList()
        if (motion == null || motion.isEmpty) {
            return buildAudioOnly(hits)
        }
        val motionT = motion.timestamps
        val motionE = motion.energies
        val medianMotion = median(motionE)
        val activeMotion = percentile(motionE, 0.85)
        val motionRange = max(0.0, activeMotion - medianMotion)
        val supportThreshold = max(motionThreshold * 0.6, medianMotion + 0.10 * motionRange)
        val bridgeThreshold = max(motionThreshold * 0.55, medianMotion + 0.08 * motionRange)
        val residualThreshold = max(motionThreshold * 0.45, medianMotion + 0.06 * motionRange)
        val onsetThreshold = max(motionThreshold * 0.50, medianMotion + 0.10 * motionRange)
        val mergeMotionThreshold = max(residualThreshold, medianMotion + 0.14 * motionRange)

        fun localMotion(timestamp: Double, radius: Double = 0.40): Double {
            val left = motionT.leftIndex(timestamp - radius)
            val right = motionT.rightIndex(timestamp + radius)
            if (right <= left) {
                val idx = motionT.leftIndex(timestamp).coerceIn(0, motionE.lastIndex)
                return motionE[idx]
            }
            var maxE = 0.0
            for (i in left until right) maxE = max(maxE, motionE[i])
            return maxE
        }

        fun intervalStats(start: Double, end: Double): Pair<Double, Double> {
            if (end <= start) return 0.0 to 0.0
            val left = motionT.leftIndex(start)
            val right = motionT.rightIndex(end)
            if (right <= left) return 0.0 to 0.0
            var sum = 0.0
            var active = 0
            for (i in left until right) {
                sum += motionE[i]
                if (motionE[i] >= residualThreshold) active++
            }
            val n = right - left
            return (sum / n) to (active.toDouble() / n)
        }

        fun walkMotionStart(timestamp: Double, maxLookback: Double = maxServeLookback): Double {
            val windowStart = max(0.0, timestamp - maxLookback)
            val left = motionT.leftIndex(windowStart)
            val right = motionT.rightIndex(timestamp + 0.15)
            if (right <= left) return max(0.0, timestamp - serveLead)

            val allowedQuiet = max(2, (visualFps * 0.40).toInt())
            var anchor = motionT.leftIndex(timestamp).coerceIn(left, right - 1)
            while (anchor > left && motionE[anchor] < onsetThreshold) anchor--
            if (motionE[anchor] < onsetThreshold) return max(0.0, timestamp - serveLead)

            var startIndex = anchor
            var quiet = 0
            for (i in anchor - 1 downTo left) {
                if (motionE[i] >= onsetThreshold) {
                    startIndex = i
                    quiet = 0
                } else {
                    quiet++
                    if (quiet > allowedQuiet) break
                }
            }
            val motionStart = motionT[startIndex] - serveLead
            return max(0.0, max(windowStart, motionStart))
        }

        fun walkMotionEnd(lastHit: Double, maxLookahead: Double = residualMotionWindow): Double {
            val baseEnd = lastHit + landingDelay
            val lookUntil = lastHit + max(maxLookahead, residualMotionWindow)
            val strongUntil = lastHit + maxLookahead + 1.5
            val left = motionT.leftIndex(lastHit)
            val right = motionT.rightIndex(strongUntil)
            if (right <= left) return baseEnd

            var lastActive = lastHit
            var lastStrong = lastHit
            var quiet = 0
            val allowedQuiet = max(2, (visualFps * 0.45).toInt())
            for (i in left until right) {
                val ts = motionT[i]
                val energy = motionE[i]
                if (energy >= residualThreshold) {
                    lastActive = ts
                    quiet = 0
                    if (energy >= mergeMotionThreshold) lastStrong = ts
                } else {
                    quiet++
                    if (quiet > allowedQuiet && ts >= baseEnd) break
                }
            }
            val residualEnd = min(lookUntil, lastActive + 0.45)
            val strongEnd = min(strongUntil, lastStrong + 0.35)
            return max(baseEnd, max(residualEnd, strongEnd))
        }

        val softHits = ArrayList<Pair<Double, Double>>()
        val supported = ArrayList<Pair<Double, Double>>()
        val courtSupported = HashSet<Double>()
        for (peak in hits) {
            val m = localMotion(peak)
            val inCourt = m >= supportThreshold
            if (inCourt) courtSupported.add(peak)
            if (inCourt || strict) {
                supported.add(peak to m)
            }
            if (m >= bridgeThreshold || inCourt) {
                softHits.add(peak to m)
            }
        }
        // Dead/flat motion (common with keyframe-only sampling) → audio-only, not empty.
        if (supported.isEmpty()) {
            val maxE = motionE.maxOrNull() ?: 0.0
            if (maxE < 0.008) return buildAudioOnly(hits)
            return emptyList()
        }

        fun motionBridges(previousHit: Double, nextHit: Double): Boolean {
            val gap = nextHit - previousHit
            if (gap <= maxSilenceGap) return true
            if (gap > maxBridgeGap) return false
            val (meanEnergy, activeFraction) = intervalStats(previousHit, nextHit)
            val pureMotionCap = if (strict) 2.6 else 4.5
            val pureMotionFrac = 0.55
            if (
                gap <= min(maxBridgeGap, pureMotionCap) &&
                activeFraction >= pureMotionFrac &&
                meanEnergy >= mergeMotionThreshold
            ) {
                return true
            }
            for ((softTime, softMotion) in softHits) {
                if (
                    softTime > previousHit + 0.05 && softTime < nextHit - 0.05 &&
                    softMotion >= bridgeThreshold &&
                    gap <= maxBridgeGap &&
                    activeFraction >= if (strict) 0.32 else 0.30
                ) {
                    return true
                }
            }
            return false
        }

        val groups = ArrayList<MutableList<Pair<Double, Double>>>()
        var current = mutableListOf(supported[0])
        for (i in 1 until supported.size) {
            val candidate = supported[i]
            if (motionBridges(current.last().first, candidate.first)) {
                current.add(candidate)
            } else {
                groups.add(current)
                current = mutableListOf(candidate)
            }
        }
        groups.add(current)

        val rallies = ArrayList<RallySegment>()
        val motionSpan = max(0.001, activeMotion - supportThreshold)
        for (groupIn in groups) {
            var group = groupIn.toMutableList()
            var firstHit = group.first().first
            var lastHit = group.last().first
            val trailCap = if (strict) 0.85 else maxBridgeGap
            for ((softTime, softMotion) in softHits) {
                if (softTime > lastHit && softTime <= lastHit + trailCap) {
                    if (softMotion >= bridgeThreshold && motionBridges(lastHit, softTime)) {
                        lastHit = softTime
                        group.add(softTime to softMotion)
                    }
                }
            }
            for ((softTime, softMotion) in softHits.asReversed()) {
                if (softTime >= firstHit - min(maxBridgeGap, 3.5) && softTime < firstHit) {
                    if (softMotion >= bridgeThreshold && motionBridges(softTime, firstHit)) {
                        firstHit = softTime
                        group.add(0, softTime to softMotion)
                    } else {
                        break
                    }
                }
            }
            if (strict && group.size >= 5) {
                while (group.size >= 4) {
                    val gap = group.last().first - group[group.lastIndex - 1].first
                    if (gap > 1.35 && group.last().second < supportThreshold) {
                        group.removeAt(group.lastIndex)
                    } else {
                        break
                    }
                }
                lastHit = group.last().first
                firstHit = group.first().first
            }
            var hitCount = max(1, group.size)
            val start = walkMotionStart(firstHit)
            // Strict: trim to last hit; do not pad walking residual motion.
            val end = if (strict) lastHit + landingDelay else walkMotionEnd(lastHit)
            val duration = end - start
            val meanSupport = group.map { it.second }.average()
            val normalizedSupport = ((meanSupport - supportThreshold) / motionSpan).coerceIn(0.0, 1.0)
            val minHits = if (strict) 2 else 1
            var strongOne = false
            var serveIsoOne = false
            var longIsoOne = false
            var iso = 0.0
            if (hitCount == 1) {
                strongOne = normalizedSupport >= 0.55 || group.first().second >= supportThreshold
                val prevIdx = supported.indexOfFirst { kotlin.math.abs(it.first - firstHit) < 1e-6 }
                iso = if (prevIdx > 0) firstHit - supported[prevIdx - 1].first else 99.0
                val nextGap = if (prevIdx >= 0 && prevIdx < supported.lastIndex) {
                    supported[prevIdx + 1].first - firstHit
                } else {
                    99.0
                }
                // GT11-style isolated serve. No peak-height channel on device;
                // isolation + next-gap is the gate (weaker 8:04 leftovers are
                // still filtered by highlight far-court-one + duration).
                serveIsoOne = iso >= 3.30 && iso < 5.0 && nextGap >= 3.60
                // GT21: silent serve, only audible contact ~9s after previous point.
                longIsoOne = iso >= 9.0 && nextGap in 1.80..3.50
            }
            if (hitCount < minHits && !strongOne && !serveIsoOne && !longIsoOne) continue
            if (duration < minRallyDuration) {
                if (!(hitCount == 1 && ((strongOne && iso < 3.2) || serveIsoOne || longIsoOne))) continue
            }
            var nCourt = group.count { it.first in courtSupported }
            if (strict && nCourt == 0 && duration > 3.2 && hitCount >= 3) {
                val peeled = peelZeroCourtFarTail(group)
                    ?: continue
                group = peeled.toMutableList()
                firstHit = group.first().first
                lastHit = group.last().first
                hitCount = max(1, group.size)
                nCourt = group.count { it.first in courtSupported }
                val peeledStart = walkMotionStart(firstHit)
                val peeledEnd = lastHit + landingDelay
                val peeledMean = group.map { it.second }.average()
                val peeledNorm = ((peeledMean - supportThreshold) / motionSpan).coerceIn(0.0, 1.0)
                val peeledHitSpan = max(0.25, lastHit - firstHit)
                if (hitCount >= 4 && hitCount / peeledHitSpan < 0.22) continue
                val peeledCountScore = min(1.0, hitCount / 6.0)
                val peeledConf = min(0.99, 0.48 + 0.30 * peeledCountScore + 0.22 * peeledNorm)
                rallies.add(
                    RallySegment(
                        start = peeledStart,
                        end = peeledEnd,
                        confidence = peeledConf,
                        firstHit = firstHit,
                        lastHit = lastHit,
                    ),
                )
                continue
            }
            val hitSpan = max(0.25, lastHit - firstHit)
            if (strict && hitCount >= 4 && hitCount / hitSpan < 0.22) continue
            val countScore = min(1.0, hitCount / 6.0)
            val confidence = min(0.99, 0.48 + 0.30 * countScore + 0.22 * normalizedSupport)
            rallies.add(
                RallySegment(
                    start = start,
                    end = end,
                    confidence = confidence,
                    firstHit = firstHit,
                    lastHit = lastHit,
                ),
            )
        }
        val merged = mergeMotionLinked(rallies, motionT, motionE, mergeMotionThreshold)
        return splitOverlong(merged)
    }

    private fun peelZeroCourtFarTail(
        group: List<Pair<Double, Double>>,
    ): List<Pair<Double, Double>>? {
        // GT23: leftover contact then 1.55–2.15s hole and a compact 2–3 hit tail.
        // Adjacent-court 7:47 is 7 hits with max interior gap 1.44s.
        if (group.size !in 4..6) return null
        val times = group.map { it.first }
        for (i in 0 until times.size - 2) {
            val gap = times[i + 1] - times[i]
            if (gap !in 1.55..2.15) continue
            val tail = group.subList(i + 1, group.size)
            if (tail.size !in 2..3) continue
            val span = tail.last().first - tail.first().first
            if (span in 1.40..2.60) return tail.toList()
        }
        return null
    }

    private fun buildAudioOnly(hits: DoubleArray): List<RallySegment> {
        if (hits.isEmpty()) return emptyList()
        val groups = ArrayList<MutableList<Double>>()
        var current = mutableListOf(hits[0])
        for (i in 1 until hits.size) {
            val hit = hits[i]
            if (hit - current.last() > maxSilenceGap) {
                groups.add(current)
                current = mutableListOf(hit)
            } else {
                current.add(hit)
            }
        }
        groups.add(current)
        val minHits = if (strict) 2 else 2
        return groups.mapNotNull { group ->
            if (group.size < minHits) return@mapNotNull null
            val first = group.first()
            val last = group.last()
            val start = max(0.0, first - serveLead - 0.5)
            val end = last + landingDelay
            if (end - start < minRallyDuration) return@mapNotNull null
            val hitSpan = max(0.25, last - first)
            if (strict && group.size / hitSpan < 0.28 && group.size < 5) return@mapNotNull null
            RallySegment(
                start = start,
                end = end,
                confidence = 0.75,
                firstHit = first,
                lastHit = last,
            )
        }
    }

    private fun splitOverlong(rallies: List<RallySegment>, maxLen: Double = 28.0): List<RallySegment> {
        if (rallies.isEmpty()) return rallies
        val out = ArrayList<RallySegment>()
        for (r in rallies) {
            if (r.duration <= maxLen) {
                out.add(r)
                continue
            }
            // Hard split long mega-clips at midpoint of hit span when possible
            val mid = (r.firstHit + r.lastHit) / 2.0
            if (mid > r.start + 1.0 && mid < r.end - 1.0) {
                out.add(r.copyBounds(end = mid, lastHit = min(r.lastHit, mid)))
                out.add(r.copyBounds(start = mid, firstHit = max(r.firstHit, mid)))
            } else {
                out.add(r)
            }
        }
        return out
    }

    private fun mergeMotionLinked(
        rallies: List<RallySegment>,
        motionT: DoubleArray,
        motionE: DoubleArray,
        mergeMotionThreshold: Double,
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
            // Align with desktop strict merge: tight wall-clock gap OR tight hit gap
            if (projected <= 24.0) {
                if (gap <= 0.40 && hitGap <= 2.2) {
                    should = true
                } else if (hitGap <= 1.15) {
                    should = true
                } else if (hitGap <= maxBridgeGap && gap <= 2.2) {
                    val left = motionT.leftIndex(prev.lastHit)
                    val right = motionT.rightIndex(item.firstHit)
                    if (right > left) {
                        var active = 0
                        for (j in left until right) {
                            if (motionE[j] >= mergeMotionThreshold) active++
                        }
                        if (active.toDouble() / (right - left) >= 0.40) should = true
                    }
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
