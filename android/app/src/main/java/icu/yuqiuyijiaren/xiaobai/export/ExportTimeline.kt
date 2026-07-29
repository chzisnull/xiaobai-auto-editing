package icu.yuqiuyijiaren.xiaobai.export

import icu.yuqiuyijiaren.xiaobai.domain.Rally
import kotlin.math.max
import kotlin.math.min

/**
 * Pure export timeline helpers (JVM-testable, no Android framework).
 * Aligns with desktop buffer padding + overlap merge before FFmpeg.
 */
object ExportTimeline {

    /**
     * Expand each rally by [preBuffer]/[postBuffer], clamp to [0, duration],
     * then merge overlapping / nearly-adjacent segments.
     */
    fun prepareSegments(
        rallies: List<Rally>,
        durationSec: Double,
        preBuffer: Double = 0.0,
        postBuffer: Double = 0.0,
        mergeGapSec: Double = 0.05,
    ): List<Rally> {
        if (rallies.isEmpty() || durationSec <= 0.0) return emptyList()
        val padded = rallies
            .map { rally ->
                val start = max(0.0, rally.startSec - preBuffer.coerceAtLeast(0.0))
                val end = min(durationSec, rally.endSec + postBuffer.coerceAtLeast(0.0))
                rally.copy(startSec = start, endSec = max(start + 0.2, end)).clamp(durationSec)
            }
            .sortedBy { it.startSec }
        return mergeOverlapping(padded, durationSec, mergeGapSec)
    }

    fun mergeOverlapping(
        rallies: List<Rally>,
        durationSec: Double,
        mergeGapSec: Double = 0.05,
    ): List<Rally> {
        if (rallies.isEmpty()) return emptyList()
        val ordered = rallies.sortedBy { it.startSec }
        val out = ArrayList<Rally>(ordered.size)
        var current = ordered.first().clamp(durationSec)
        for (i in 1 until ordered.size) {
            val next = ordered[i].clamp(durationSec)
            if (next.startSec <= current.endSec + mergeGapSec) {
                current = current.copy(
                    endSec = max(current.endSec, next.endSec),
                    confidence = min(current.confidence, next.confidence),
                ).clamp(durationSec)
            } else {
                out.add(current)
                current = next
            }
        }
        out.add(current)
        return out
    }
}
