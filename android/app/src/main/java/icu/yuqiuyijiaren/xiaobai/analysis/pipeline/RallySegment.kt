package icu.yuqiuyijiaren.xiaobai.analysis.pipeline

/**
 * Intermediate rally representation aligned with desktop detector dicts.
 */
data class RallySegment(
    val start: Double,
    val end: Double,
    val confidence: Double = 0.8,
    val firstHit: Double = start,
    val lastHit: Double = end,
    val primaryCourtPeel: Boolean = false,
) {
    val duration: Double get() = (end - start).coerceAtLeast(0.0)

    fun copyBounds(
        start: Double = this.start,
        end: Double = this.end,
        confidence: Double = this.confidence,
        firstHit: Double = this.firstHit,
        lastHit: Double = this.lastHit,
        primaryCourtPeel: Boolean = this.primaryCourtPeel,
    ): RallySegment = RallySegment(
        start = start,
        end = end.coerceAtLeast(start + 0.25),
        confidence = confidence,
        firstHit = firstHit,
        lastHit = lastHit,
        primaryCourtPeel = primaryCourtPeel,
    )
}

data class MotionSeries(
    val timestamps: DoubleArray,
    val energies: DoubleArray,
) {
    val isEmpty: Boolean get() = timestamps.isEmpty() || energies.isEmpty()
}
