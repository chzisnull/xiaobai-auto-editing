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
        val hits = doubleArrayOf(10.0, 10.8, 11.5, 12.2, 13.0, 30.0)
        val rallies = listOf(
            RallySegment(start = 9.0, end = 14.0, confidence = 0.9, firstHit = 10.0, lastHit = 13.0),
            RallySegment(start = 29.5, end = 31.2, confidence = 0.7, firstHit = 30.0, lastHit = 30.0),
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
        // isolated 1-hit stub should be dropped (2-hit net-faults are kept)
        assertTrue(out.none { it.firstHit >= 29.0 })
    }

    @Test
    fun keepsTwoHitNetFault() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(86.5, 86.8)
        val rallies = listOf(
            RallySegment(start = 86.0, end = 89.5, confidence = 0.8, firstHit = 86.5, lastHit = 86.8),
        )
        val motionT = DoubleArray(150) { 80.0 + it * 0.1 }
        val motionE = DoubleArray(150) { idx ->
            val t = motionT[idx]
            if (t in 86.0..89.0) 0.025 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(out[0].firstHit <= 86.6)
        assertTrue(out[0].duration < 5.0)
    }

    @Test
    fun dropsCameraOpenWalkIn() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(0.6, 1.45, 1.9, 2.15, 3.14, 3.82, 4.08)
        val rallies = listOf(
            RallySegment(start = 0.0, end = 4.6, confidence = 0.99, firstHit = 0.6, lastHit = 4.08),
        )
        val motionT = DoubleArray(80) { it * 0.1 }
        val motionE = DoubleArray(80) { idx ->
            val t = motionT[idx]
            if (t <= 4.5) 0.06 else 0.003
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isEmpty())
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

    @Test
    fun dropsAdjacentCourtStubWithoutServeIsolation() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(216.2, 218.14, 219.40, 219.70, 220.72, 221.82, 222.95, 223.31)
        val rallies = listOf(
            RallySegment(start = 217.0, end = 224.0, confidence = 0.88, firstHit = 218.14, lastHit = 223.31),
        )
        val motionT = DoubleArray(200) { 210.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            if (t in 217.5..223.5) 0.022 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isEmpty())
    }

    @Test
    fun keepsFarCourtServeLikeRally() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(100.0, 113.0, 114.3, 115.8, 118.5, 120.4, 120.7, 123.5)
        val rallies = listOf(
            RallySegment(start = 112.0, end = 124.0, confidence = 0.8, firstHit = 113.0, lastHit = 123.5),
        )
        val motionT = DoubleArray(400) { 90.0 + it * 0.1 }
        val motionE = DoubleArray(400) { idx ->
            val t = motionT[idx]
            when {
                t in 120.2..124.0 -> 0.030
                t in 112.5..124.0 -> 0.012
                else -> 0.004
            }
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isNotEmpty())
        assertTrue(out.any { it.firstHit <= 113.2 && it.lastHit >= 118.0 })
    }

    @Test
    fun dropsNoBurstWalkingCluster() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(80.0, 91.0, 92.1, 93.4, 94.0, 95.2, 110.0)
        val rallies = listOf(
            RallySegment(start = 90.0, end = 96.0, confidence = 0.7, firstHit = 91.0, lastHit = 95.2),
        )
        val motionT = DoubleArray(500) { 70.0 + it * 0.1 }
        val motionE = DoubleArray(500) { 0.004 }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isEmpty())
    }

    @Test
    fun remergesTwoHitThenContinuation() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(142.175, 148.304, 149.314, 151.508, 153.063, 154.528, 156.160)
        val rallies = listOf(
            RallySegment(147.254, 149.914, 0.6, 148.304, 149.314),
            RallySegment(150.458, 156.760, 0.9, 151.508, 156.160),
        )
        val motionT = DoubleArray(200) { 140.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            if (t in 151.3..156.4) 0.030 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(out[0].firstHit <= 148.5)
        assertTrue(out[0].lastHit >= 154.0)
    }

    @Test
    fun dropsTwoHitWalkingStub() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(161.62, 164.35, 165.77, 180.0)
        val rallies = listOf(
            RallySegment(163.3, 166.4, 0.8, 164.35, 165.77),
        )
        val motionT = DoubleArray(200) { 155.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            if (t in 161.5..166.0) 0.040 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isEmpty())
    }

    @Test
    fun unsticksGt5StyleNetFault() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(72.66, 79.16, 80.13, 82.10, 83.27, 84.35, 85.71, 86.51, 86.81, 91.03)
        val rallies = listOf(
            RallySegment(71.6, 80.73, 0.99, 72.66, 80.13),
            RallySegment(81.05, 87.41, 0.99, 82.10, 86.81),
        )
        val motionT = DoubleArray(250) { 70.0 + it * 0.1 }
        val motionE = DoubleArray(250) { idx ->
            val t = motionT[idx]
            when {
                t in 76.2..80.0 -> 0.032
                t in 82.0..87.0 -> 0.040
                else -> 0.004
            }
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.any { kotlin.math.abs(it.lastHit - 80.13) < 0.3 })
        val net = out.filter { it.firstHit in 85.4..87.0 }
        assertEquals(1, net.size)
        val gs = 86.0
        val ge = 89.0
        val inter = maxOf(0.0, minOf(ge, net[0].end) - maxOf(gs, net[0].start))
        val union = (ge - gs) + net[0].duration - inter
        assertTrue(inter / union >= 0.3)
    }

    @Test
    fun absorbsLandingAfterQuietHighClear() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            377.97, 378.64, 378.90, 380.88, 386.15,
            387.56, 389.84, 390.18, 390.56, 391.63, 392.57, 393.97, 394.45, 395.00,
        )
        val rallies = listOf(
            RallySegment(376.92, 381.48, 0.9, 377.97, 380.88),
            RallySegment(385.10, 395.60, 0.99, 386.15, 395.00),
        )
        val motionT = DoubleArray(300) { 370.0 + it * 0.1 }
        val motionE = DoubleArray(300) { idx ->
            val t = motionT[idx]
            when {
                t in 377.5..381.2 -> 0.028
                t in 386.0..386.5 -> 0.016
                t in 387.3..395.2 -> 0.040
                else -> 0.003
            }
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        val gs = 379.0
        val ge = 387.0
        val tps = out.filter {
            val inter = maxOf(0.0, minOf(ge, it.end) - maxOf(gs, it.start))
            val union = (ge - gs) + it.duration - inter
            union > 0 && inter / union >= 0.3
        }
        assertEquals(1, tps.size)
        assertTrue(tps[0].firstHit <= 378.2)
        assertTrue(tps[0].lastHit in 385.5..387.8)
    }

    @Test
    fun keepsIsolatedFarCourtOneHit() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(248.21, 251.57, 255.30, 256.52)
        val rallies = listOf(
            RallySegment(250.52, 252.17, 0.6, 251.57, 251.57),
        )
        val motionT = DoubleArray(200) { 240.0 + it * 0.1 }
        val motionE = DoubleArray(200) { 0.003 }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(kotlin.math.abs(out[0].firstHit - 251.57) < 0.15)
    }

    @Test
    fun keepsLongIsolationOneHit() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(437.08, 446.56, 448.75)
        val rallies = listOf(
            RallySegment(445.51, 447.16, 0.55, 446.56, 446.56),
        )
        val motionT = DoubleArray(250) { 430.0 + it * 0.1 }
        val motionE = DoubleArray(250) { 0.004 }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(kotlin.math.abs(out[0].firstHit - 446.56) < 0.15)
    }

    @Test
    fun pullsQuietFarCourtStartOnLiveRally() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            417.76, 419.30, 424.933, 426.871, 427.271, 427.527, 428.170, 428.434, 429.532,
        )
        val rallies = listOf(
            RallySegment(425.82, 430.13, 0.99, 426.871, 429.532),
        )
        val motionT = DoubleArray(200) { 415.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            if (t in 426.5..429.8) 0.035 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isNotEmpty())
        val main = out.first { it.lastHit >= 428.0 }
        assertTrue(main.firstHit <= 419.5)
        val gs = 419.0
        val ge = 429.0
        val inter = maxOf(0.0, minOf(ge, main.end) - maxOf(gs, main.start))
        val union = (ge - gs) + main.duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }

    @Test
    fun doesNotGlueIsolatedServeToPrevExtra() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(244.35, 245.40, 247.29, 248.21, 251.57, 255.30, 256.52)
        val rallies = listOf(
            RallySegment(243.30, 248.81, 0.76, 244.35, 248.21),
            RallySegment(250.52, 252.17, 0.53, 251.57, 251.57),
            RallySegment(253.83, 257.12, 0.80, 255.30, 256.52),
        )
        val motionT = DoubleArray(200) { 240.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            when {
                t in 244.0..248.5 -> 0.022
                t in 254.0..257.0 -> 0.035
                else -> 0.004
            }
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        val ones = out.filter { kotlin.math.abs(it.firstHit - 251.57) < 0.2 }
        assertEquals(1, ones.size)
        assertTrue(ones[0].lastHit < 253.0)
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 255.30) < 0.2 })
        val gs = 251.0
        val ge = 254.0
        val inter = maxOf(0.0, minOf(ge, ones[0].end) - maxOf(gs, ones[0].start))
        val union = (ge - gs) + ones[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }

    @Test
    fun keepsLongIsoOneHitAfterServeLookback() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(437.08, 446.56, 448.75)
        val rallies = listOf(
            RallySegment(444.50, 447.16, 0.55, 446.56, 446.56),
        )
        val motionT = DoubleArray(250) { 430.0 + it * 0.1 }
        val motionE = DoubleArray(250) { 0.004 }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(kotlin.math.abs(out[0].firstHit - 446.56) < 0.15)
        val gs = 441.0
        val ge = 447.0
        val inter = maxOf(0.0, minOf(ge, out[0].end) - maxOf(gs, out[0].start))
        val union = (ge - gs) + out[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }

    @Test
    fun peelsSilentPlayServeFromWalkingBlob() {
        val filter = StrictHighlightFilter()
        val serve = 461.88
        val hits = doubleArrayOf(
            455.34, 457.50, 457.97, 458.25, 458.66, 459.14, 459.82, 460.30, 460.68, serve, 467.73,
        )
        val rallies = listOf(
            RallySegment(456.45, 462.48, 0.85, 457.50, serve),
        )
        val motionT = DoubleArray(200) { 450.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            when {
                t in 457.3..460.9 -> 0.018
                t in 461.5..465.0 -> 0.055
                else -> 0.004
            }
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        val peeled = out.filter { kotlin.math.abs(it.firstHit - serve) < 0.2 }
        assertEquals(1, peeled.size)
        assertTrue(kotlin.math.abs(peeled[0].lastHit - serve) < 0.15)
        assertTrue(peeled[0].end >= serve + 2.0)
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 457.50) < 0.2 })
        val gs = 461.0
        val ge = 466.0
        val inter = maxOf(0.0, minOf(ge, peeled[0].end) - maxOf(gs, peeled[0].start))
        val union = (ge - gs) + peeled[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }


    @Test
    fun keepsGt23FarCourtThree() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(473.88, 476.25, 478.10, 478.65, 480.35, 484.19)
        val rallies = listOf(
            RallySegment(477.05, 480.95, 0.6, 478.10, 480.35),
        )
        val motionT = DoubleArray(200) { 470.0 + it * 0.1 }
        val motionE = DoubleArray(200) { 0.003 }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        val kept = out.filter { kotlin.math.abs(it.firstHit - 478.10) < 0.2 }
        assertEquals(1, kept.size)
        assertTrue(kotlin.math.abs(kept[0].lastHit - 480.35) < 0.15)
        val gs = 479.0
        val ge = 483.0
        val inter = maxOf(0.0, minOf(ge, kept[0].end) - maxOf(gs, kept[0].start))
        val union = (ge - gs) + kept[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
        assertTrue(kept[0].end >= 480.35 + 2.0)
    }

    @Test
    fun dropsFourHitWalkingCadenceButKeepsGt17Style() {
        val filter = StrictHighlightFilter()
        val walkHits = doubleArrayOf(240.00, 244.35, 246.02, 247.29, 248.21, 251.57)
        val walk = listOf(RallySegment(243.30, 248.81, 0.76, 244.35, 248.21))
        val motionT = DoubleArray(1300) { 230.0 + it * 0.1 }
        val motionE = DoubleArray(1300) { idx ->
            val t = motionT[idx]
            when {
                t in 244.0..248.5 -> 0.024
                t in 336.0..341.0 -> 0.032
                else -> 0.004
            }
        }
        val outWalk = filter.apply(walk, walkHits, MotionSeries(motionT, motionE))
        assertTrue(outWalk.none { it.firstHit in 243.0..249.0 })
        val gt17Hits = doubleArrayOf(330.00, 336.22, 337.20, 338.09, 340.53, 348.00)
        val gt17 = listOf(RallySegment(334.07, 340.98, 0.99, 336.22, 340.53))
        val outGt = filter.apply(gt17, gt17Hits, MotionSeries(motionT, motionE))
        assertTrue(outGt.any { kotlin.math.abs(it.firstHit - 336.22) < 0.3 })
    }

    @Test
    fun dropsGt11AdjacentTwoHitWalk() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(248.21, 251.57, 255.30, 256.52)
        val rallies = listOf(
            RallySegment(250.52, 252.17, 0.53, 251.57, 251.57),
            RallySegment(253.83, 257.12, 0.80, 255.30, 256.52),
        )
        val motionT = DoubleArray(200) { 240.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            if (t in 254.0..257.0) 0.035 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.any { kotlin.math.abs(it.firstHit - 251.57) < 0.2 })
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 255.30) < 0.2 })
    }

    @Test
    fun dropsCompactTwoHitWalkingBlipButKeepsGt14() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(292.53, 294.74, 295.17, 298.34, 298.61, 298.92)
        val rallies = listOf(
            RallySegment(293.69, 295.62, 0.85, 294.74, 295.17),
            RallySegment(297.29, 299.37, 0.63, 298.34, 298.92),
        )
        val motionT = DoubleArray(170) { 288.0 + it * 0.1 }
        val motionE = DoubleArray(170) { idx ->
            val t = motionT[idx]
            if (t in 290.5..295.3) 0.028 else 0.004
        }
        val outBlip = filter.apply(listOf(rallies[0]), hits, MotionSeries(motionT, motionE))
        assertTrue(outBlip.none { kotlin.math.abs(it.firstHit - 294.74) < 0.2 })
        val outGt = filter.apply(listOf(rallies[1]), hits, MotionSeries(motionT, motionE))
        val kept = outGt.filter { kotlin.math.abs(it.firstHit - 298.34) < 0.2 }
        assertEquals(1, kept.size)
        val gs = 297.0
        val ge = 300.0
        val inter = maxOf(0.0, minOf(ge, kept[0].end) - maxOf(gs, kept[0].start))
        val union = (ge - gs) + kept[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }

    @Test
    fun dropsAdjacentCourtFiveHitTailButKeepsGt10Style() {
        val filter = StrictHighlightFilter()
        val extraHits = doubleArrayOf(136.29, 139.24, 141.04, 141.58, 142.18, 142.43, 146.12)
        val extra = listOf(RallySegment(137.97, 142.88, 0.95, 139.24, 142.43))
        val motionT = DoubleArray(1150) { 130.0 + it * 0.1 }
        val motionE = DoubleArray(1150) { idx ->
            val t = motionT[idx]
            when {
                t in 138.6..139.8 -> 0.040
                t in 228.5..233.5 -> 0.032
                else -> 0.004
            }
        }
        val outX = filter.apply(extra, extraHits, MotionSeries(motionT, motionE))
        assertTrue(outX.none { kotlin.math.abs(it.firstHit - 139.24) < 0.2 })
        val gt10Hits = doubleArrayOf(225.00, 228.80, 230.06, 230.63, 231.36, 232.05, 240.00)
        val gt10 = listOf(RallySegment(228.23, 233.82, 0.86, 228.80, 232.05))
        val outTp = filter.apply(gt10, gt10Hits, MotionSeries(motionT, motionE))
        assertTrue(outTp.any { kotlin.math.abs(it.firstHit - 228.80) < 0.3 })
    }

    @Test
    fun dropsFourHitPickupHole() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(175.02, 177.76, 178.57, 180.14, 180.44, 191.00)
        val rallies = listOf(RallySegment(176.71, 181.04, 0.79, 177.76, 180.44))
        val motionT = DoubleArray(250) { 170.0 + it * 0.1 }
        val motionE = DoubleArray(250) { idx ->
            val t = motionT[idx]
            if (t in 177.5..180.8) 0.026 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 177.76) < 0.2 })
    }


    @Test
    fun pullsIsolatedQuietServeAcrossHighClear() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            65.56, 67.74, 72.66, 73.65, 74.79, 75.48, 75.75, 76.44, 76.71, 76.97, 77.79, 79.16, 80.13,
        )
        val rallies = listOf(RallySegment(71.61, 80.73, 0.99, 72.66, 80.13))
        val motionT = DoubleArray(250) { 60.0 + it * 0.1 }
        val motionE = DoubleArray(250) { idx ->
            val t = motionT[idx]
            if (t in 72.5..80.2) 0.035 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isNotEmpty())
        val main = out.minBy { kotlin.math.abs(it.lastHit - 80.13) }
        assertTrue(main.firstHit <= 68.0)
        val gs = 67.0
        val ge = 79.0
        val inter = maxOf(0.0, minOf(ge, main.end) - maxOf(gs, main.start))
        val union = (ge - gs) + main.duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }

    @Test
    fun silentServeOneHitPadsTowardToss() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(437.08, 446.56, 448.75)
        val rallies = listOf(RallySegment(444.50, 447.16, 0.55, 446.56, 446.56))
        val motionT = DoubleArray(250) { 430.0 + it * 0.1 }
        val motionE = DoubleArray(250) { 0.004 }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(kotlin.math.abs(out[0].firstHit - 446.56) < 0.15)
        assertTrue(out[0].start <= 441.5)
        val gs = 441.0
        val ge = 447.0
        val inter = maxOf(0.0, minOf(ge, out[0].end) - maxOf(gs, out[0].start))
        val union = (ge - gs) + out[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }

    @Test
    fun pullsGt7StyleTightHighClearHole() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(142.425, 146.120, 148.304, 149.314, 151.508, 153.063, 154.528)
        val rallies = listOf(RallySegment(147.254, 155.128, 0.60, 148.304, 154.528))
        val motionT = DoubleArray(180) { 140.0 + it * 0.1 }
        val motionE = DoubleArray(180) { idx ->
            val t = motionT[idx]
            if (t in 148.2..155.0) 0.035 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isNotEmpty())
        val main = out.minBy { kotlin.math.abs(it.lastHit - 154.528) }
        assertTrue(main.firstHit <= 146.20)
        val gs = 142.0
        val ge = 153.0
        val inter = maxOf(0.0, minOf(ge, main.end) - maxOf(gs, main.start))
        val union = (ge - gs) + main.duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
    }

    @Test
    fun tightHoleSkipsDeadBallInterior() {
        val filter = StrictHighlightFilter(maxHitSilence = 2.0, motionBridgeSilence = 4.2)
        val hits = doubleArrayOf(11.0, 12.0, 13.0, 16.2, 18.4, 19.6, 22.6, 23.2, 24.0)
        val rallies = listOf(RallySegment(10.0, 28.0, 0.9, 11.0, 25.0))
        val motionT = DoubleArray(220) { 8.0 + it * 0.1 }
        val motionE = DoubleArray(220) { idx ->
            val t = motionT[idx]
            when {
                t in 10.5..13.4 -> 0.04
                t in 22.2..24.5 -> 0.04
                else -> 0.003
            }
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.size >= 2)
        assertTrue(out[0].lastHit <= 16.5)
        assertTrue(out.last().firstHit >= 18.0)
    }

    @Test
    fun dropsOneHitBurstWithShortNextGap() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(2.41, 6.49, 8.93)
        val rallies = listOf(RallySegment(4.63, 7.09, 0.75, 6.49, 6.49))
        val motionT = DoubleArray(120) { it * 0.1 }
        val motionE = DoubleArray(120) { idx ->
            val t = motionT[idx]
            if (t in 6.10..6.90) 0.030 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertTrue(out.isEmpty())
    }

    @Test
    fun keepsOneHitBurstWithBetweenPointHole() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(1.00, 6.50, 10.00)
        val rallies = listOf(RallySegment(5.45, 7.10, 0.75, 6.50, 6.50))
        val motionT = DoubleArray(120) { it * 0.1 }
        val motionE = DoubleArray(120) { idx ->
            val t = motionT[idx]
            if (t in 6.10..6.90) 0.030 else 0.004
        }
        val out = filter.apply(rallies, hits, MotionSeries(motionT, motionE))
        assertEquals(1, out.size)
        assertTrue(kotlin.math.abs(out[0].firstHit - 6.50) < 0.15)
    }

    @Test
    fun peelsPrimaryCourtFromAdjacentGlue() {
        val filter = StrictHighlightFilter()
        val gt4Hits = doubleArrayOf(
            42.00,
            45.56, 46.20, 46.58, 47.06, 47.45, 47.73, 48.14, 48.73, 49.40, 50.24, 50.85,
            51.93, 52.95, 53.48, 53.94, 54.31, 54.67, 55.03,
        )
        val gt4 = RallySegment(44.51, 55.63, 0.99, 45.56, 55.03)
        val motionT = DoubleArray(180) { 40.0 + it * 0.1 }
        val motionE = DoubleArray(180) { idx ->
            val t = motionT[idx]
            when {
                t in 45.9..51.2 -> 0.040
                t in 51.7..55.2 -> 0.045
                else -> 0.010
            }
        }
        val out = filter.apply(listOf(gt4), gt4Hits, MotionSeries(motionT, motionE))
        val kept = out.filter { it.firstHit in 45.0..47.0 }
        assertEquals(1, kept.size)
        assertTrue(kept[0].lastHit <= 51.2)
        val gs = 45.0
        val ge = 51.0
        val inter = maxOf(0.0, minOf(ge, kept[0].end) - maxOf(gs, kept[0].start))
        val union = (ge - gs) + kept[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
        assertTrue(out.none { it.firstHit in 51.5..55.5 })

        val walkHits = doubleArrayOf(
            386.15, 387.56,
            389.84, 390.18, 390.56, 391.63, 392.57, 392.83, 393.97, 394.45, 394.72, 395.00,
        )
        val walk = RallySegment(387.69, 395.60, 0.80, 389.84, 395.00)
        val motionT2 = DoubleArray(200) { 380.0 + it * 0.1 }
        val motionE2 = DoubleArray(200) { idx ->
            val t = motionT2[idx]
            if (t in 389.5..395.3) 0.050 else 0.010
        }
        val outWalk = filter.apply(listOf(walk), walkHits, MotionSeries(motionT2, motionE2))
        assertTrue(outWalk.none { it.firstHit in 389.0..396.0 })
    }

    @Test
    fun peelsGt2StyleInteriorHole() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            11.642,
            19.225, 20.271, 20.631, 21.159,
            23.770, 24.116, 24.464, 24.895, 25.250, 25.613, 25.980, 26.364, 26.806, 27.127, 27.751,
        )
        val blob = RallySegment(18.05, 28.35, 0.78, 19.225, 27.751)
        val motionT = DoubleArray(200) { 10.0 + it * 0.1 }
        val motionE = DoubleArray(200) { idx ->
            val t = motionT[idx]
            if (t in 19.0..28.0) 0.035 else 0.010
        }
        val out = filter.apply(listOf(blob), hits, MotionSeries(motionT, motionE))
        val kept = out.filter { kotlin.math.abs(it.firstHit - 19.225) < 0.2 }
        assertEquals(1, kept.size)
        assertTrue(kept[0].lastHit <= 21.5)
        val gs = 18.0
        val ge = 21.0
        val inter = maxOf(0.0, minOf(ge, kept[0].end) - maxOf(gs, kept[0].start))
        val union = (ge - gs) + kept[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
        assertTrue(out.none { it.firstHit in 23.5..28.0 })
    }

    @Test
    fun dropsKajaHandshakeApplauseBlob() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            8.89, 12.14,
            16.17, 17.81, 19.32, 19.63, 19.88, 20.28, 20.69, 21.07, 21.42, 21.77,
            22.16, 22.54, 22.92, 23.29, 23.64, 24.02, 24.27, 24.75, 25.12, 25.46,
            25.82, 26.18, 26.57, 26.85,
        )
        val blob = RallySegment(15.12, 27.45, 0.97, 16.17, 26.85)
        val motionT = DoubleArray(220) { 8.0 + it * 0.1 }
        val motionE = DoubleArray(220) { idx ->
            val t = motionT[idx]
            if (t in 16.0..27.0) 0.040 else 0.010
        }
        val out = filter.apply(listOf(blob), hits, MotionSeries(motionT, motionE))
        assertTrue(out.none { it.firstHit in 15.0..27.0 })
    }

    @Test
    fun peelsGt3StyleTwoHitNetFault() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            29.687,
            35.772, 36.781, 37.910, 38.586, 38.949, 39.354, 39.802, 40.281, 40.912, 41.220, 41.747, 41.999,
        )
        val blob = RallySegment(34.72, 42.45, 0.78, 35.772, 41.999)
        val motionT = DoubleArray(160) { 28.0 + it * 0.1 }
        val motionE = DoubleArray(160) { idx ->
            val t = motionT[idx]
            when {
                t in 35.4..36.2 -> 0.040
                t in 39.2..42.1 -> 0.028
                else -> 0.010
            }
        }
        val out = filter.apply(listOf(blob), hits, MotionSeries(motionT, motionE))
        val kept = out.filter { kotlin.math.abs(it.firstHit - 35.772) < 0.2 }
        assertEquals(1, kept.size)
        assertTrue(kept[0].lastHit <= 37.2)
        val gs = 34.0
        val ge = 36.0
        val inter = maxOf(0.0, minOf(ge, kept[0].end) - maxOf(gs, kept[0].start))
        val union = (ge - gs) + kept[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
        assertTrue(out.none { it.firstHit in 37.5..42.5 })
    }

    @Test
    fun peelsGt5StyleSingleOutAfterInRoiHead() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            65.096,
            73.429, 74.127, 74.400, 75.047, 75.413, 76.211, 76.689, 77.564, 77.936, 78.208,
        )
        val blob = RallySegment(72.38, 78.66, 0.84, 73.429, 78.208)
        val motionT = DoubleArray(170) { 64.0 + it * 0.1 }
        val motionE = DoubleArray(170) { idx ->
            val t = motionT[idx]
            when {
                t in 73.2..76.9 -> 0.040
                t in 77.0..77.75 -> 0.008
                t in 78.05..78.5 -> 0.032
                else -> 0.010
            }
        }
        val out = filter.apply(listOf(blob), hits, MotionSeries(motionT, motionE))
        val kept = out.filter { kotlin.math.abs(it.firstHit - 73.429) < 0.2 }
        assertEquals(1, kept.size)
        assertTrue(kept[0].lastHit <= 77.70)
        val gs = 73.0
        val ge = 75.0
        val inter = maxOf(0.0, minOf(ge, kept[0].end) - maxOf(gs, kept[0].start))
        val union = (ge - gs) + kept[0].duration - inter
        assertTrue(union > 0 && inter / union >= 0.3)
        assertTrue(out.none { it.firstHit in 77.4..79.0 })
    }

    @Test
    fun peelsGt6StyleShortIsoInRoiCore() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(
            80.170,
            85.621, 86.007,
            88.209, 89.452,
            90.007, 90.436, 90.870, 91.160, 91.529, 92.044, 92.580, 92.834,
            93.548, 94.228, 94.494, 95.069, 95.527, 96.261, 96.530, 96.780,
        )
        val extra = RallySegment(84.57, 86.46, 0.58, 85.621, 86.007)
        val blob = RallySegment(87.16, 97.23, 0.85, 88.209, 96.780)
        val motionT = DoubleArray(210) { 78.0 + it * 0.1 }
        val motionE = DoubleArray(210) { idx ->
            val t = motionT[idx]
            when {
                t in 89.9..91.8 -> 0.040
                t in 91.85..92.25 -> 0.008
                t in 92.3..96.9 -> 0.035
                else -> 0.010
            }
        }
        val out = filter.apply(listOf(extra, blob), hits, MotionSeries(motionT, motionE))
        val gs = 89.0
        val ge = 94.0
        val matching = out.filter {
            val inter = maxOf(0.0, minOf(ge, it.end) - maxOf(gs, it.start))
            val union = (ge - gs) + it.duration - inter
            union > 0 && inter / union >= 0.3
        }
        assertEquals(1, matching.size)
        assertTrue(matching[0].firstHit >= 89.5)
        assertTrue(matching[0].lastHit <= 95.0)
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 85.621) < 0.2 })

        val walkHits = doubleArrayOf(
            386.15, 387.56,
            389.84, 390.18, 390.56, 391.63, 392.57, 392.83, 393.97, 394.45, 394.72, 395.00,
        )
        val walk = RallySegment(387.69, 395.60, 0.80, 389.84, 395.00)
        val motionT2 = DoubleArray(200) { 380.0 + it * 0.1 }
        val motionE2 = DoubleArray(200) { idx ->
            val t = motionT2[idx]
            if (t in 389.5..395.3) 0.050 else 0.010
        }
        val outWalk = filter.apply(listOf(walk), walkHits, MotionSeries(motionT2, motionE2))
        assertTrue(outWalk.none { it.firstHit in 389.0..396.0 })
    }

    @Test
    fun dropsEuroLong1m03TwoHitWalk() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(60.671, 60.999, 64.493, 65.096, 73.429)
        val extra = RallySegment(63.44, 65.69, 0.70, 64.493, 65.096)
        val motionT = DoubleArray(180) { 58.0 + it * 0.1 }
        val motionE = DoubleArray(180) { idx ->
            val t = motionT[idx]
            when {
                t in 62.2..64.0 -> 0.040
                t in 64.4..65.3 -> 0.032
                else -> 0.010
            }
        }
        val out = filter.apply(listOf(extra), hits, MotionSeries(motionT, motionE))
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 64.493) < 0.2 })
    }

    @Test
    fun dropsHuddleTalkingPlateau() {
        val filter = StrictHighlightFilter()
        val huddleHits = doubleArrayOf(
            8.99, 9.45, 9.88, 10.92, 11.21, 13.59, 14.71, 15.23, 15.81,
            16.82, 17.32, 19.31, 19.62, 20.06, 20.49, 21.39, 21.77,
        )
        val huddle = RallySegment(7.44, 22.22, 0.99, 8.99, 21.77)
        val motionT = DoubleArray(400) { it * 0.1 }
        val motionE = DoubleArray(400) { idx ->
            val t = motionT[idx]
            if (t in 8.5..22.0) 0.330 else 0.110
        }
        val out = filter.apply(listOf(huddle), huddleHits, MotionSeries(motionT, motionE))
        assertTrue(out.none { it.firstHit in 7.0..23.0 })

        val shortHits = doubleArrayOf(29.42, 31.67, 33.70, 33.99, 34.29, 35.16, 35.45, 36.23, 37.16, 40.0)
        val shortHuddle = RallySegment(28.37, 37.76, 0.99, 29.42, 37.16)
        val motionT3 = DoubleArray(300) { 20.0 + it * 0.1 }
        val motionE3 = DoubleArray(300) { idx ->
            val t = motionT3[idx]
            if (t in 29.0..37.5) 0.330 else 0.110
        }
        val outShort = filter.apply(listOf(shortHuddle), shortHits, MotionSeries(motionT3, motionE3))
        assertTrue(outShort.none { it.firstHit in 28.0..38.0 })

        val gt4Hits = doubleArrayOf(
            64.50,
            67.68, 72.61, 73.60, 74.74, 75.43, 75.70, 76.39, 76.66, 76.92, 77.74, 79.12, 80.08,
        )
        val gt4 = RallySegment(66.63, 80.73, 0.99, 67.68, 80.08)
        val motionT2 = DoubleArray(400) { 50.0 + it * 0.1 }
        val motionE2 = DoubleArray(400) { idx ->
            val t = motionT2[idx]
            if (t in 72.4..80.2) 0.028 else 0.010
        }
        val outGt = filter.apply(listOf(gt4), gt4Hits, MotionSeries(motionT2, motionE2))
        assertTrue(outGt.any { kotlin.math.abs(it.firstHit - 67.68) < 0.3 })
    }

    @Test
    fun keepsJohnGt2TwoHit() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(73.743, 74.402, 76.636, 77.043, 80.002)
        val rally = RallySegment(75.11, 77.49, 0.91, 76.636, 77.043)
        val motionT = DoubleArray(150) { 70.0 + it * 0.1 }
        val motionE = DoubleArray(150) { idx ->
            val t = motionT[idx]
            when {
                t in 76.4..77.2 -> 0.160
                else -> 0.004
            }
        }
        val out = filter.apply(listOf(rally), hits, MotionSeries(motionT, motionE))
        assertTrue(out.any { kotlin.math.abs(it.firstHit - 76.636) < 0.2 })
    }

    private fun motionSeries(t0: Double, t1: Double, baseline: Double = 0.020, spikes: List<Triple<Double, Double, Double>>): MotionSeries {
        val n = ((t1 - t0) / 0.1).toInt()
        val motionT = DoubleArray(n) { t0 + it * 0.1 }
        val motionE = DoubleArray(n) { idx ->
            val t = motionT[idx]
            spikes.firstOrNull { t in it.first..it.second }?.third ?: baseline
        }
        return MotionSeries(motionT, motionE)
    }

    @Test
    fun dropsJohnSideline1m18() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(77.043, 80.002, 80.556, 81.154, 81.467, 81.935, 83.822)
        val rally = RallySegment(78.58, 82.53, 0.63, 80.002, 81.935)
        val motion = motionSeries(70.0, 90.0, spikes = listOf(Triple(80.0, 82.1, 0.16)))
        val out = filter.apply(listOf(rally), hits, motion)
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 80.002) < 0.2 })
    }

    @Test
    fun dropsJohnReaction1m45() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(103.552, 107.672, 108.368, 109.176, 109.667, 110.295, 110.657, 112.62)
        val rally = RallySegment(105.94, 111.11, 0.84, 107.672, 110.657)
        val motion = motionSeries(100.0, 120.0, baseline = 0.004, spikes = listOf(Triple(107.4, 108.6, 0.16)))
        val out = filter.apply(listOf(rally), hits, motion)
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 107.672) < 0.2 })
    }

    @Test
    fun dropsJohnPoster2m14() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(133.407, 136.419, 138.744, 139.334, 142.455)
        val rally = RallySegment(134.63, 139.78, 0.77, 136.419, 139.334)
        val motion = motionSeries(128.0, 145.0, spikes = listOf(Triple(134.2, 139.5, 0.16)))
        val out = filter.apply(listOf(rally), hits, motion)
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 136.419) < 0.2 })
    }

    @Test
    fun dropsJohnHandshake2m44() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(161.06, 165.324, 165.589, 166.167, 168.436)
        val rally = RallySegment(164.27, 166.77, 0.74, 165.324, 166.167)
        val motion = motionSeries(155.0, 175.0, spikes = listOf(Triple(163.0, 166.4, 0.16)))
        val out = filter.apply(listOf(rally), hits, motion)
        assertTrue(out.none { kotlin.math.abs(it.firstHit - 165.324) < 0.2 })
    }

    @Test
    fun keepsUserGt5ThreeHit() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(83.30, 85.66, 86.46, 86.76, 90.97)
        val rally = RallySegment(84.66, 87.41, 0.99, 85.66, 86.76)
        val motion = motionSeries(80.0, 95.0, baseline = 0.08, spikes = listOf(Triple(84.4, 87.2, 0.16)))
        val out = filter.apply(listOf(rally), hits, motion)
        assertTrue(out.any { kotlin.math.abs(it.firstHit - 85.66) < 0.2 })
    }

    @Test
    fun keepsEuroLongGt1FiveHit() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(1.79, 2.48, 2.85, 4.03, 4.43, 11.63)
        val rally = RallySegment(0.74, 5.04, 0.93, 1.79, 4.43)
        val motion = motionSeries(0.0, 15.0, spikes = listOf(Triple(1.5, 4.6, 0.16)))
        val out = filter.apply(listOf(rally), hits, motion)
        assertTrue(out.any { kotlin.math.abs(it.firstHit - 1.79) < 0.2 })
    }

    @Test
    fun keepsKajaSixHit() {
        val filter = StrictHighlightFilter()
        val hits = doubleArrayOf(2.0, 4.80, 5.14, 5.52, 5.89, 7.60, 8.89, 12.14)
        val rally = RallySegment(3.75, 9.34, 0.88, 4.80, 8.89)
        val motion = motionSeries(0.0, 16.0, spikes = listOf(Triple(4.5, 9.0, 0.16)))
        val out = filter.apply(listOf(rally), hits, motion)
        assertTrue(out.any { kotlin.math.abs(it.firstHit - 4.80) < 0.2 })
    }
}
