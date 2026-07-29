package icu.yuqiuyijiaren.xiaobai.analysis

import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.MotionSeries
import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.RallySegment
import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.StrictHighlightFilter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StrictHighlightFilterTest {

    @Test
    fun keepsDenseRallyAndDropsSparseStub() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(10.0, 10.8, 11.5, 12.2, 13.0, 30.0, 30.5)
        val rallies = listOf(
            RallySegment(start = 9.0, end = 14.0, confidence = 0.9, firstHit = 10.0, lastHit = 13.0),
            RallySegment(start = 29.5, end = 31.2, confidence = 0.7, firstHit = 30.0, lastHit = 30.5),
        )
        // Flat elevated motion on first segment only
        val motionT = DoubleArray(40) { it * 0.5 }
        val motionE = DoubleArray(40) { t ->
            val time = t * 0.5
            if (time in 9.5..13.5) 0.08 else 0.002
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isNotEmpty())
        assertTrue(out.any { it.firstHit in 9.0..14.0 })
        // sparse two-hit stub should be dropped by minHits=3
        assertTrue(out.none { it.firstHit >= 29.0 })
    }

    @Test
    fun remergesTightFragments() {
        val filter = StrictHighlightFilter(remergeGap = 1.35)
        val hits = doubleArrayOf(5.0, 5.7, 6.4, 7.1, 7.8, 8.5)
        val rallies = listOf(
            RallySegment(5.0, 6.5, 0.9, 5.0, 6.4),
            RallySegment(6.8, 9.0, 0.9, 7.1, 8.5),
        )
        val motionT = DoubleArray(30) { it * 0.4 }
        val motionE = DoubleArray(30) { 0.06 }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(out[0].duration > 3.0)
    }
}
