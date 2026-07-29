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
}
