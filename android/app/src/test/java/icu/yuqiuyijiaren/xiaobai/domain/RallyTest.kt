package icu.yuqiuyijiaren.xiaobai.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RallyTest {
    @Test
    fun clampKeepsValidRange() {
        val rally = Rally(id = 1, startSec = -1.0, endSec = 99.0, confidence = 0.9)
        val clamped = rally.clamp(duration = 10.0)
        assertEquals(0.0, clamped.startSec, 1e-6)
        assertEquals(10.0, clamped.endSec, 1e-6)
        assertTrue(clamped.durationSec >= 0.2)
    }

    @Test
    fun clampAtEndOfTimelineDoesNotThrow() {
        val rally = Rally(id = 2, startSec = 9.5, endSec = 10.0)
        val nudged = rally.copy(startSec = 9.8, endSec = 10.3).clamp(10.0)
        assertTrue(nudged.startSec <= 9.8 + 1e-6)
        assertEquals(10.0, nudged.endSec, 1e-6)
        assertTrue(nudged.durationSec >= 0.2 - 1e-6)
    }

    @Test
    fun clampZeroDurationIsSafe() {
        val rally = Rally(id = 3, startSec = 1.0, endSec = 2.0).clamp(0.0)
        assertEquals(0.0, rally.startSec, 1e-6)
        assertEquals(0.0, rally.endSec, 1e-6)
    }

    @Test
    fun mergeOverlappingMergesIntersectingRanges() {
        val r1 = Rally(id = 1, startSec = 1.0, endSec = 5.0, confidence = 0.8)
        val r2 = Rally(id = 2, startSec = 4.0, endSec = 8.0, confidence = 0.95)
        val merged = listOf(r1, r2).mergeOverlapping()

        assertEquals(1, merged.size)
        assertEquals(1.0, merged[0].startSec, 1e-6)
        assertEquals(8.0, merged[0].endSec, 1e-6)
        assertEquals(0.95, merged[0].confidence, 1e-6)
    }

    @Test
    fun mergeOverlappingHandlesContainedAndTouchingRanges() {
        val r1 = Rally(id = 1, startSec = 2.0, endSec = 10.0, reviewStatus = ReviewStatus.Normal)
        val r2 = Rally(id = 2, startSec = 3.0, endSec = 7.0, reviewStatus = ReviewStatus.Approved)
        val r3 = Rally(id = 3, startSec = 10.03, endSec = 12.0, reviewStatus = ReviewStatus.Normal) // within 0.05s margin
        val r4 = Rally(id = 4, startSec = 15.0, endSec = 20.0)

        // Pass out of order to ensure sorting works
        val merged = listOf(r4, r2, r1, r3).mergeOverlapping(marginSec = 0.05)

        assertEquals(2, merged.size)
        assertEquals(2.0, merged[0].startSec, 1e-6)
        assertEquals(12.0, merged[0].endSec, 1e-6)
        assertEquals(ReviewStatus.Approved, merged[0].reviewStatus)

        assertEquals(15.0, merged[1].startSec, 1e-6)
        assertEquals(20.0, merged[1].endSec, 1e-6)
    }

    @Test
    fun mergeOverlappingChainedRallies() {
        val r1 = Rally(id = 1, startSec = 1.0, endSec = 3.0)
        val r2 = Rally(id = 2, startSec = 2.5, endSec = 4.5)
        val r3 = Rally(id = 3, startSec = 4.0, endSec = 6.0)
        val merged = listOf(r1, r2, r3).mergeOverlapping()

        assertEquals(1, merged.size)
        assertEquals(1.0, merged[0].startSec, 1e-6)
        assertEquals(6.0, merged[0].endSec, 1e-6)
    }

    @Test
    fun directMarkingDoesNotDefaultToZero() {
        val markIn = 20.0
        val t = 42.0
        val rally = Rally(
            id = 100,
            startSec = minOf(markIn, t),
            endSec = maxOf(markIn, t),
            confidence = 1.0,
        )
        val rallies = emptyList<Rally>()
        val combined = rallies + rally
        val merged = combined.mergeOverlapping()

        assertEquals(1, merged.size)
        assertEquals(20.0, merged[0].startSec, 1e-6)
        assertEquals(42.0, merged[0].endSec, 1e-6)
    }

    @Test
    fun gapCalculationBetweenRallies() {
        val r1 = Rally(id = 1, startSec = 10.0, endSec = 25.0)
        val r2 = Rally(id = 2, startSec = 40.0, endSec = 55.0)
        val gap = r2.startSec - r1.endSec
        assertEquals(15.0, gap, 1e-6)
    }
}
