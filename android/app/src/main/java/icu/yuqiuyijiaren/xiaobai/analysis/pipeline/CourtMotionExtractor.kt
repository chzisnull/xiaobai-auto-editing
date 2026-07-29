package icu.yuqiuyijiaren.xiaobai.analysis.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Court-ROI frame-difference motion energy.
 * Aligned closer to desktop CourtAware: ~8 fps, thr≈18, light blur, closest frame when possible.
 */
class CourtMotionExtractor(
    private val visualFps: Int = 8,
    private val width: Int = 320,
    private val height: Int = 240,
    private val diffThreshold: Int = 18,
    private val useClosestFrame: Boolean = true,
    private val maxSamplesCap: Int = 5400,
    private val courtRoi: List<Pair<Float, Float>> = DEFAULT_ROI,
) {
    fun extract(context: Context, uri: Uri, durationSec: Double): MotionSeries {
        if (durationSec <= 0.0) return MotionSeries(DoubleArray(0), DoubleArray(0))
        val retriever = MediaMetadataRetriever()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                retriever.setDataSource(pfd.fileDescriptor)
            } ?: retriever.setDataSource(context, uri)

            val mask = buildCourtMask(width, height, courtRoi)
            // Long videos: lower motion fps to keep emulator/low-RAM devices alive
            val adaptiveFps = when {
                durationSec > 20 * 60 -> min(visualFps, 4)
                durationSec > 10 * 60 -> min(visualFps, 5)
                durationSec > 6 * 60 -> min(visualFps, max(4, visualFps - 1))
                else -> visualFps
            }.coerceAtLeast(2)
            val stepSec = 1.0 / adaptiveFps.toDouble()
            val timestamps = ArrayList<Double>()
            val energies = ArrayList<Double>()
            var prevGray: IntArray? = null
            var t = 0.0
            val maxSamples = (durationSec / stepSec).toInt().coerceAtMost(maxSamplesCap.coerceAtLeast(600))
            var sample = 0
            while (t <= durationSec + 1e-3 && sample < maxSamples) {
                val us = (t * 1_000_000.0).toLong().coerceAtLeast(0L)
                val frame = getFrame(retriever, us)
                if (frame != null) {
                    val gray = toGrayBlurred(frame, width, height)
                    frame.recycle()
                    val prev = prevGray
                    if (prev != null) {
                        var changed = 0
                        var total = 0
                        var i = 0
                        while (i < gray.size) {
                            if (mask[i]) {
                                total++
                                if (abs(gray[i] - prev[i]) > diffThreshold) changed++
                            }
                            i++
                        }
                        val energy = if (total > 0) changed.toDouble() / total else 0.0
                        timestamps.add(t)
                        energies.add(energy)
                    }
                    prevGray = gray
                }
                t += stepSec
                sample++
            }
            return MotionSeries(
                timestamps = timestamps.toDoubleArray(),
                energies = energies.toDoubleArray(),
            )
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun getFrame(retriever: MediaMetadataRetriever, us: Long): Bitmap? {
        return try {
            if (useClosestFrame) {
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST)
                    ?: retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            } else {
                retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        } catch (_: Exception) {
            retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }

    private fun toGrayBlurred(src: Bitmap, w: Int, h: Int): IntArray {
        val scaled = if (src.width == w && src.height == h) {
            src
        } else {
            Bitmap.createScaledBitmap(src, w, h, true)
        }
        val pixels = IntArray(w * h)
        scaled.getPixels(pixels, 0, w, 0, 0, w, h)
        if (scaled !== src) scaled.recycle()
        val gray = IntArray(pixels.size)
        for (i in pixels.indices) {
            val c = pixels[i]
            gray[i] = ((Color.red(c) * 30 + Color.green(c) * 59 + Color.blue(c) * 11) / 100)
        }
        // 3x3 box blur (1-pass) to match desktop Gaussian-ish denoise cheaply
        return boxBlur3x3(gray, w, h)
    }

    private fun boxBlur3x3(src: IntArray, w: Int, h: Int): IntArray {
        val out = IntArray(src.size)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var sum = 0
                var count = 0
                for (dy in -1..1) {
                    val yy = y + dy
                    if (yy < 0 || yy >= h) continue
                    for (dx in -1..1) {
                        val xx = x + dx
                        if (xx < 0 || xx >= w) continue
                        sum += src[yy * w + xx]
                        count++
                    }
                }
                out[y * w + x] = sum / count.coerceAtLeast(1)
            }
        }
        return out
    }

    companion object {
        val DEFAULT_ROI: List<Pair<Float, Float>> = listOf(
            0.20f to 0.34f,
            0.82f to 0.34f,
            0.98f to 0.96f,
            0.02f to 0.96f,
        )

        fun buildCourtMask(
            width: Int,
            height: Int,
            roi: List<Pair<Float, Float>>,
        ): BooleanArray {
            val poly = roi.map { (nx, ny) ->
                (nx.coerceIn(0f, 1f) * (width - 1)).roundToInt() to
                    (ny.coerceIn(0f, 1f) * (height - 1)).roundToInt()
            }
            val mask = BooleanArray(width * height)
            var minX = width
            var maxX = 0
            var minY = height
            var maxY = 0
            for ((x, y) in poly) {
                minX = min(minX, x)
                maxX = max(maxX, x)
                minY = min(minY, y)
                maxY = max(maxY, y)
            }
            minX = minX.coerceIn(0, width - 1)
            maxX = maxX.coerceIn(0, width - 1)
            minY = minY.coerceIn(0, height - 1)
            maxY = maxY.coerceIn(0, height - 1)
            for (y in minY..maxY) {
                for (x in minX..maxX) {
                    if (pointInPolygon(x, y, poly)) {
                        mask[y * width + x] = true
                    }
                }
            }
            if (mask.none { it }) {
                val y0 = (height * 0.34).toInt()
                for (y in y0 until height) {
                    for (x in 0 until width) mask[y * width + x] = true
                }
            }
            return mask
        }

        private fun pointInPolygon(x: Int, y: Int, poly: List<Pair<Int, Int>>): Boolean {
            var inside = false
            var j = poly.lastIndex
            for (i in poly.indices) {
                val xi = poly[i].first
                val yi = poly[i].second
                val xj = poly[j].first
                val yj = poly[j].second
                val intersect = ((yi > y) != (yj > y)) &&
                    (x < (xj - xi).toDouble() * (y - yi) / (yj - yi + 1e-9) + xi)
                if (intersect) inside = !inside
                j = i
            }
            return inside
        }
    }
}
