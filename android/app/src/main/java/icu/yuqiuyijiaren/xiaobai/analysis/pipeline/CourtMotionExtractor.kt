package icu.yuqiuyijiaren.xiaobai.analysis.pipeline

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.Image
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Court-ROI frame-difference motion energy.
 *
 * Primary path: **sequential MediaCodec** hardware decode + subsample by PTS.
 * This avoids thousands of random [MediaMetadataRetriever.getFrameAtTime] seeks
 * that made 10+ minute videos take many minutes even on flagship SoCs.
 *
 * Fallback: keyframe SYNC retriever sampling if codec path fails.
 */
class CourtMotionExtractor(
    private val visualFps: Int = 4,
    private val width: Int = 320,
    private val height: Int = 240,
    private val diffThreshold: Int = 18,
    private val useClosestFrame: Boolean = false,
    private val maxSamplesCap: Int = 1200,
    private val applyBlur: Boolean = false,
    private val courtRoi: List<Pair<Float, Float>> = DEFAULT_ROI,
) {
    /**
     * @param onProgress 0f..1f within motion extraction stage
     */
    fun extract(
        context: Context,
        uri: Uri,
        durationSec: Double,
        onProgress: ((Float) -> Unit)? = null,
    ): MotionSeries {
        if (durationSec <= 0.0) return MotionSeries(DoubleArray(0), DoubleArray(0))
        val fps = visualFps.coerceAtLeast(2)
        val maxSamples = maxSamplesCap.coerceAtLeast(400)

        return try {
            extractSequentialCodec(context, uri, durationSec, fps, maxSamples, onProgress)
        } catch (_: Exception) {
            extractRetrieverFallback(context, uri, durationSec, fps, maxSamples, onProgress)
        }
    }

    // -------------------------------------------------------------------------
    // Fast path: sequential MediaCodec + Y-plane downsample
    // -------------------------------------------------------------------------

    private fun extractSequentialCodec(
        context: Context,
        uri: Uri,
        durationSec: Double,
        fps: Int,
        maxSamples: Int,
        onProgress: ((Float) -> Unit)?,
    ): MotionSeries {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            icu.yuqiuyijiaren.xiaobai.domain.MediaSourceHelper.setExtractorDataSource(extractor, context, uri)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            } ?: throw IllegalStateException("no video track")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("no mime")

            codec = MediaCodec.createDecoderByType(mime)
            // Prefer flexible YUV so we can read Image without a Surface
            try {
                format.setInteger(
                    MediaFormat.KEY_COLOR_FORMAT,
                    android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
                )
            } catch (_: Exception) {
            }
            codec.configure(format, null, null, 0)
            codec.start()

            val mask = buildCourtMask(width, height, courtRoi)
            val sampleIntervalUs = (1_000_000.0 / fps).toLong().coerceAtLeast(1L)
            val timestamps = ArrayList<Double>(min(maxSamples, 512))
            val energies = ArrayList<Double>(min(maxSamples, 512))
            var prevGray: IntArray? = null
            var nextSampleUs = 0L
            var inputDone = false
            var outputDone = false
            val info = MediaCodec.BufferInfo()
            val endUs = (durationSec * 1_000_000.0).toLong()
            var lastProgressEmit = -1
            var sampledAttempts = 0
            var imageHits = 0

            while (!outputDone && timestamps.size < maxSamples) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(8_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex, 0, 0, 0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            val pts = extractor.sampleTime.coerceAtLeast(0L)
                            codec.queueInputBuffer(inIndex, 0, sampleSize, pts, 0)
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, 8_000)
                when {
                    outIndex >= 0 -> {
                        val pts = info.presentationTimeUs
                        val shouldSample =
                            info.size > 0 &&
                                pts >= nextSampleUs &&
                                (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0

                        if (shouldSample) {
                            sampledAttempts++
                            val image = try {
                                codec.getOutputImage(outIndex)
                            } catch (_: Exception) {
                                null
                            }
                            if (image != null) {
                                imageHits++
                                try {
                                    val gray = yPlaneToGray(image, width, height, applyBlur)
                                    val prev = prevGray
                                    if (prev != null) {
                                        timestamps.add(pts / 1_000_000.0)
                                        energies.add(frameDiffEnergy(prev, gray, mask, diffThreshold))
                                    }
                                    prevGray = gray
                                    nextSampleUs = pts + sampleIntervalUs
                                } finally {
                                    image.close()
                                }
                            } else if (sampledAttempts > 40 && imageHits == 0) {
                                codec.releaseOutputBuffer(outIndex, false)
                                throw IllegalStateException("decoder does not expose Image frames")
                            }
                            val pct = ((pts.toDouble() / endUs.coerceAtLeast(1L)).toFloat())
                                .coerceIn(0f, 0.99f)
                            val bucket = (pct * 20).toInt()
                            if (bucket != lastProgressEmit) {
                                lastProgressEmit = bucket
                                onProgress?.invoke(pct)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                        if (pts > endUs && timestamps.size >= 8) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> Unit
                    outIndex == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                }
            }
            onProgress?.invoke(1f)
            if (timestamps.size < 2) {
                throw IllegalStateException("codec path produced too few samples")
            }
            return MotionSeries(timestamps.toDoubleArray(), energies.toDoubleArray())
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    /**
     * Fast Y-plane → downsampled gray (optional light blur).
     */
    private fun yPlaneToGray(image: Image, outW: Int, outH: Int, blur: Boolean): IntArray {
        val plane = image.planes[0]
        val buffer = plane.buffer.duplicate()
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        val srcW = image.width.coerceAtLeast(1)
        val srcH = image.height.coerceAtLeast(1)
        val gray = IntArray(outW * outH)
        for (y in 0 until outH) {
            val sy = (y * srcH) / outH
            val rowStart = sy * rowStride
            for (x in 0 until outW) {
                val sx = (x * srcW) / outW
                val idx = rowStart + sx * pixelStride
                val v = if (idx < buffer.limit()) {
                    buffer.get(idx).toInt() and 0xFF
                } else {
                    0
                }
                gray[y * outW + x] = v
            }
        }
        return if (blur) boxBlur3x3(gray, outW, outH) else gray
    }

    // -------------------------------------------------------------------------
    // Fallback: keyframe SYNC retriever (still no CLOSEST spam)
    // -------------------------------------------------------------------------

    private fun extractRetrieverFallback(
        context: Context,
        uri: Uri,
        durationSec: Double,
        fps: Int,
        maxSamples: Int,
        onProgress: ((Float) -> Unit)?,
    ): MotionSeries {
        val retriever = MediaMetadataRetriever()
        try {
            icu.yuqiuyijiaren.xiaobai.domain.MediaSourceHelper.setRetrieverDataSource(retriever, context, uri)

            val mask = buildCourtMask(width, height, courtRoi)
            // SYNC keyframes only — never OPTION_CLOSEST on full film
            val stepSec = 1.0 / fps.toDouble()
            val timestamps = ArrayList<Double>()
            val energies = ArrayList<Double>()
            var prevGray: IntArray? = null
            var t = 0.0
            var sample = 0
            val totalPlan = min(maxSamples, (durationSec / stepSec).toInt().coerceAtLeast(1))
            while (t <= durationSec + 1e-3 && sample < maxSamples) {
                val us = (t * 1_000_000.0).toLong().coerceAtLeast(0L)
                val frame = try {
                    retriever.getFrameAtTime(us, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                } catch (_: Exception) {
                    null
                }
                if (frame != null) {
                    val gray = bitmapToGray(frame, width, height, applyBlur)
                    frame.recycle()
                    val prev = prevGray
                    if (prev != null) {
                        timestamps.add(t)
                        energies.add(frameDiffEnergy(prev, gray, mask, diffThreshold))
                    }
                    prevGray = gray
                }
                t += stepSec
                sample++
                if (sample % 20 == 0) {
                    onProgress?.invoke((sample.toFloat() / totalPlan).coerceIn(0f, 0.99f))
                }
            }
            onProgress?.invoke(1f)
            return MotionSeries(timestamps.toDoubleArray(), energies.toDoubleArray())
        } finally {
            try {
                retriever.release()
            } catch (_: Exception) {
            }
        }
    }

    private fun bitmapToGray(src: Bitmap, w: Int, h: Int, blur: Boolean): IntArray {
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
        return if (blur) boxBlur3x3(gray, w, h) else gray
    }

    private fun frameDiffEnergy(
        prev: IntArray,
        gray: IntArray,
        mask: BooleanArray,
        thr: Int,
    ): Double {
        var changed = 0
        var total = 0
        val n = min(prev.size, min(gray.size, mask.size))
        var i = 0
        while (i < n) {
            if (mask[i]) {
                total++
                if (abs(gray[i] - prev[i]) > thr) changed++
            }
            i++
        }
        return if (total > 0) changed.toDouble() / total else 0.0
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
