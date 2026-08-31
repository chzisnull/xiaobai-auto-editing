package icu.yuqiuyijiaren.xiaobai.analysis

import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.CourtAwareRallyBuilder
import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.MotionSeries
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CourtAwareRallyBuilderTest {

    private val builder = CourtAwareRallyBuilder(strict = true, visualFps = 8)

    @Test
    fun buildsDenseRallyFromSupportedHitsAndMotion() {
        val hits = doubleArrayOf(10.0, 10.7, 11.4, 12.1, 12.9, 13.6)
        val motion = flatMotion(from = 9.0, to = 15.0, energy = 0.08, duration = 40.0)
        val rallies = builder.build(hits, motion)
        assertEquals(1, rallies.size)
        assertTrue(rallies[0].start < 10.0)
        assertTrue(rallies[0].end > 13.6)
        assertTrue(rallies[0].confidence > 0.5)
    }

    @Test
    fun keepsTwoHitNetFaultInStrictMode() {
        val hits = doubleArrayOf(5.0, 5.5)
        val motion = flatMotion(from = 4.0, to = 7.0, energy = 0.08, duration = 20.0)
        val rallies = builder.build(hits, motion)
        assertEquals(1, rallies.size)
        assertTrue(rallies[0].firstHit <= 5.05)
        assertTrue(rallies[0].lastHit >= 5.45)
    }

    @Test
    fun dropsIsolatedOneHitWithoutCourtSupport() {
        val hits = doubleArrayOf(5.0)
        val motion = flatMotion(from = 0.0, to = 1.0, energy = 0.002, duration = 20.0)
        val rallies = builder.build(hits, motion)
        assertTrue(rallies.isEmpty())
    }

    @Test
    fun audioOnlyGroupsBySilenceGap() {
        val hits = doubleArrayOf(
            1.0, 1.6, 2.2, 2.8,
            20.0, 20.6, 21.2, 21.8,
        )
        val rallies = builder.build(hits, motion = null)
        assertEquals(2, rallies.size)
        assertTrue(rallies[0].lastHit < 5.0)
        assertTrue(rallies[1].firstHit > 15.0)
    }

    @Test
    fun emptyHitsYieldEmpty() {
        assertTrue(builder.build(doubleArrayOf(), null).isEmpty())
    }

    @Test
    fun strictModeDoesNotWalkStartBackThroughDeadTime() {
        // Elevated motion begins ~1.2s before first supported hit (walking onto court)
        val hits = doubleArrayOf(10.0, 10.7, 11.4, 12.1, 12.8)
        val motionT = DoubleArray(80) { it * 0.2 }
        val motionE = DoubleArray(80) { idx ->
            val t = motionT[idx]
            when {
                t in 8.5..13.5 -> 0.09
                else -> 0.002
            }
        }
        val rallies = builder.build(hits, MotionSeries(motionT, motionE))
        assertTrue(rallies.isNotEmpty())
        // Strict keeps ~1.1s serve lead, not 1.5s+ of walking onto court.
        assertTrue(rallies[0].start >= 8.6)
        assertTrue(rallies[0].start <= 10.0)
        assertTrue(rallies[0].end > 12.8)
    }

    private fun flatMotion(
        from: Double,
        to: Double,
        energy: Double,
        duration: Double,
        step: Double = 0.2,
    ): MotionSeries {
        val n = ((duration / step).toInt() + 1)
        val t = DoubleArray(n) { it * step }
        val e = DoubleArray(n) { idx ->
            val time = t[idx]
            if (time in from..to) energy else 0.002
        }
        return MotionSeries(t, e)
    }

    @Test
    fun keepsGt11StyleIsolatedServe() {
        val hits = doubleArrayOf(248.21, 251.57, 255.30, 256.52)
        val motionT = DoubleArray(160) { 240.0 + it * 0.125 }
        val motionE = DoubleArray(160) { idx ->
            val t = motionT[idx]
            if (t in 255.0..257.2) 0.032 else 0.003
        }
        val rallies = builder.build(hits, MotionSeries(motionT, motionE))
        assertTrue(rallies.any { kotlin.math.abs(it.firstHit - 251.57) < 0.25 })
    }

    @Test
    fun keepsGt21StyleLongIsoOneHit() {
        val hits = doubleArrayOf(437.08, 446.56, 448.75)
        val motionT = DoubleArray(200) { 430.0 + it * 0.125 }
        val motionE = DoubleArray(200) { 0.004 }
        val rallies = builder.build(hits, MotionSeries(motionT, motionE))
        assertTrue(rallies.any { kotlin.math.abs(it.firstHit - 446.56) < 0.25 })
    }

    @Test
    fun peelsGt23StyleZeroCourtTail() {
        val hits = doubleArrayOf(465.00, 473.88, 476.25, 478.096, 478.65, 480.35, 484.19)
        val motionT = DoubleArray(160) { 470.0 + it * 0.125 }
        val motionE = DoubleArray(160) { 0.003 }
        val rallies = builder.build(hits, MotionSeries(motionT, motionE))
        assertTrue(rallies.any { kotlin.math.abs(it.firstHit - 478.096) < 0.25 })
        assertTrue(rallies.none { kotlin.math.abs(it.firstHit - 476.25) < 0.25 })
    }

    @Test
    fun dropsAdjacentCourtZeroCourtBlob() {
        val hits = doubleArrayOf(461.88, 467.73, 468.26, 469.51, 470.95, 471.68, 472.90, 473.88, 476.25)
        val motionT = DoubleArray(200) { 455.0 + it * 0.125 }
        val motionE = DoubleArray(200) { 0.003 }
        val rallies = builder.build(hits, MotionSeries(motionT, motionE))
        assertTrue(rallies.none { it.firstHit in 467.0..474.0 })
    }
}
