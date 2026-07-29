package icu.yuqiuyijiaren.xiaobai.export

import icu.yuqiuyijiaren.xiaobai.domain.Rally
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExportTimelineTest {

    @Test
    fun padsAndClampsToDuration() {
        val rallies = listOf(Rally(id = 1, startSec = 2.0, endSec = 5.0))
        val out = ExportTimeline.prepareSegments(
            rallies = rallies,
            durationSec = 10.0,
            preBuffer = 1.0,
            postBuffer = 1.5,
        )
        assertEquals(1, out.size)
        assertEquals(1.0, out[0].startSec, 1e-6)
        assertEquals(6.5, out[0].endSec, 1e-6)
    }

    @Test
    fun mergesOverlappingAfterPadding() {
        val rallies = listOf(
            Rally(id = 1, startSec = 1.0, endSec = 3.0),
            Rally(id = 2, startSec = 3.2, endSec = 5.0),
        )
        val out = ExportTimeline.prepareSegments(
            rallies = rallies,
            durationSec = 20.0,
            preBuffer = 0.5,
            postBuffer = 0.5,
            mergeGapSec = 0.05,
        )
        assertEquals(1, out.size)
        assertTrue(out[0].startSec < 1.0)
        assertTrue(out[0].endSec > 5.0)
    }

    @Test
    fun keepsSeparatedRallies() {
        val rallies = listOf(
            Rally(id = 1, startSec = 1.0, endSec = 2.0),
            Rally(id = 2, startSec = 10.0, endSec = 12.0),
        )
        val out = ExportTimeline.prepareSegments(
            rallies = rallies,
            durationSec = 30.0,
            preBuffer = 0.2,
            postBuffer = 0.2,
        )
        assertEquals(2, out.size)
    }
}
