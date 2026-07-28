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
}
