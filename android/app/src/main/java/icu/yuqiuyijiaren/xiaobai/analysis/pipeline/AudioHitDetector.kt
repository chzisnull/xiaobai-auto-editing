package icu.yuqiuyijiaren.xiaobai.analysis.pipeline

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * On-device audio hit detector approximating desktop AudioVisualRallyDetector:
 * band-emphasize 2–6 kHz, envelope, MAD threshold, peak pick with refractory.
 *
 * Memory-safe: primitive buffers only (no ArrayList&lt;Float&gt; boxing),
 * and resamples to 16 kHz while decoding so peak RAM stays low.
 */
class AudioHitDetector(
    private val targetSampleRate: Int = 16_000,
    private val minHitIntervalSec: Double = 0.25,
    /** Hard cap (~20 min @ 16 kHz) to avoid OOM on very long matches. */
    private val maxOutputSamples: Int = 16_000 * 60 * 20,
    private val softProminence: Float = 1.04f,
    /** Envelope MAD multiplier. Higher = fewer gym-noise / adjacent-court hits. */
    private val hitMadK: Float = 6.6f,
) {
    data class Result(
        val hitsSec: DoubleArray,
        val sampleRate: Int,
    )

    fun detect(context: Context, uri: Uri): Result {
        val mono16k = decodeMonoResampled(context, uri, targetSampleRate)
        if (mono16k.size < targetSampleRate / 10) {
            return Result(DoubleArray(0), targetSampleRate)
        }
        // In-place-ish pipeline: bandpass returns new array; envelope overwrites abs path carefully
        val filtered = bandpassApprox(mono16k, targetSampleRate, 2000.0, 6000.0)
        // free original early for GC
        // (mono16k may still be referenced until function ends — filtered is enough for peaks)
        val envelope = smoothEnvelopeInPlace(filtered, targetSampleRate)
        val hits = findPeaks(envelope, targetSampleRate)
        return Result(hits, targetSampleRate)
    }

    private fun decodeMonoResampled(context: Context, uri: Uri, outSr: Int): FloatArray {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("视频没有可用音轨")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("无法读取音轨格式")
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44_100
            }
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }
            codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            // Decode PCM chunks → primitive mono samples → online 16 kHz resampler (no boxing).
            val resampler = OnlineResampler(sampleRate, outSr, maxOutputSamples)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            val pcmScratch = ByteArray(256 * 1024)

            while (!outputDone && resampler.size < maxOutputSamples) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                            extractor.advance()
                        }
                    }
                }
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                when {
                    outIndex >= 0 -> {
                        val outBuffer = codec.getOutputBuffer(outIndex)
                        if (outBuffer != null && info.size > 0) {
                            outBuffer.position(info.offset)
                            outBuffer.limit(info.offset + info.size)
                            val size = info.size
                            val bytes = if (size <= pcmScratch.size) {
                                outBuffer.get(pcmScratch, 0, size)
                                pcmScratch
                            } else {
                                val tmp = ByteArray(size)
                                outBuffer.get(tmp)
                                tmp
                            }
                            feedPcmToResampler(bytes, size, channelCount, resampler)
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        // ignore
                    }
                }
            }
            return resampler.toArray()
        } finally {
            try {
                codec?.stop()
                codec?.release()
            } catch (_: Exception) {
            }
            extractor.release()
        }
    }

    private fun feedPcmToResampler(
        pcm: ByteArray,
        size: Int,
        channelCount: Int,
        resampler: OnlineResampler,
    ) {
        val shortCount = size / 2
        if (shortCount <= 0) return
        val buffer = ByteBuffer.wrap(pcm, 0, size).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        var i = 0
        while (i + channelCount <= shortCount && !resampler.isFull) {
            var acc = 0f
            var c = 0
            while (c < channelCount) {
                acc += buffer.get(i + c) / 32768f
                c++
            }
            resampler.offer(acc / channelCount)
            i += channelCount
        }
    }

    /**
     * Online linear resampler writing into a growable primitive FloatArray.
     */
    private class OnlineResampler(
        private val inSr: Int,
        private val outSr: Int,
        private val maxSamples: Int,
    ) {
        private var data = FloatArray(min(maxSamples, outSr * 60))
        var size: Int = 0
            private set
        private var prev = 0f
        private var havePrev = false
        private var phase = 0.0 // position in input samples relative to prev..current interval
        private val step = inSr.toDouble() / outSr.toDouble()
        val isFull: Boolean get() = size >= maxSamples

        fun offer(sample: Float) {
            if (isFull) return
            if (!havePrev) {
                prev = sample
                havePrev = true
                // emit first sample
                append(sample)
                phase = step
                return
            }
            // Walk phase across the prev->sample segment [0,1)
            while (phase < 1.0 && !isFull) {
                val frac = phase.toFloat()
                append(prev * (1f - frac) + sample * frac)
                phase += step
            }
            phase -= 1.0
            prev = sample
        }

        private fun append(v: Float) {
            if (size >= maxSamples) return
            if (size >= data.size) {
                val next = min(maxSamples, max(data.size * 2, data.size + outSr * 30))
                data = data.copyOf(next)
            }
            data[size++] = v
        }

        fun toArray(): FloatArray = if (size == data.size) data else data.copyOf(size)
    }

    private fun bandpassApprox(input: FloatArray, sr: Int, lowHz: Double, highHz: Double): FloatArray {
        val hp = highPassInPlace(input.copyOf(), sr, lowHz)
        return lowPassInPlace(hp, sr, highHz)
    }

    private fun highPassInPlace(data: FloatArray, sr: Int, cutoffHz: Double): FloatArray {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sr
        val alpha = (rc / (rc + dt)).toFloat()
        fun pass() {
            var prevIn = 0f
            var prevOut = 0f
            for (i in data.indices) {
                val x = data[i]
                val y = alpha * (prevOut + x - prevIn)
                data[i] = y
                prevIn = x
                prevOut = y
            }
        }
        pass()
        pass()
        return data
    }

    private fun lowPassInPlace(data: FloatArray, sr: Int, cutoffHz: Double): FloatArray {
        val rc = 1.0 / (2.0 * PI * cutoffHz)
        val dt = 1.0 / sr
        val alpha = (dt / (rc + dt)).toFloat()
        fun pass() {
            var prev = 0f
            for (i in data.indices) {
                prev += alpha * (data[i] - prev)
                data[i] = prev
            }
        }
        pass()
        pass()
        return data
    }

    /** |x| + 5ms MA written back into [signal] buffer. */
    private fun smoothEnvelopeInPlace(signal: FloatArray, sr: Int): FloatArray {
        val win = max(1, (0.005 * sr).toInt())
        // First convert to abs into same array
        for (i in signal.indices) signal[i] = abs(signal[i])
        // Prefix-sum style moving average using small ring
        val out = FloatArray(signal.size)
        var sum = 0.0
        for (i in signal.indices) {
            sum += signal[i]
            if (i >= win) sum -= signal[i - win]
            val n = min(i + 1, win)
            out[i] = (sum / n).toFloat()
        }
        return out
    }

    private fun findPeaks(envelope: FloatArray, sr: Int): DoubleArray {
        if (envelope.isEmpty()) return DoubleArray(0)
        // Approximate median without full sort of millions of samples: subsample
        val step = max(1, envelope.size / 50_000)
        val sample = ArrayList<Float>(min(50_000, envelope.size / step + 1))
        var i = 0
        while (i < envelope.size) {
            sample.add(envelope[i])
            i += step
        }
        sample.sort()
        val med = percentileSorted(sample, 0.5)
        val deviations = ArrayList<Float>(sample.size)
        for (v in sample) deviations.add(abs(v - med))
        deviations.sort()
        val mad = percentileSorted(deviations, 0.5)
        val normalMad = mad * 1.4826f
        val thr = med + hitMadK * normalMad

        val minGap = max(1, (minHitIntervalSec * sr).toInt())
        val peaks = ArrayList<Double>(256)
        var last = -minGap
        i = 1
        val promWin = max(1, sr / 200)
        while (i < envelope.size - 1) {
            val v = envelope[i]
            if (v >= thr && v >= envelope[i - 1] && v >= envelope[i + 1] && i - last >= minGap) {
                val left = envelope[max(0, i - promWin)]
                val right = envelope[min(envelope.lastIndex, i + promWin)]
                if (v > left * softProminence && v > right * softProminence) {
                    peaks.add(i.toDouble() / sr)
                    last = i
                }
            }
            i++
        }
        return peaks.toDoubleArray()
    }

    private fun percentileSorted(sorted: List<Float>, q: Double): Float {
        if (sorted.isEmpty()) return 0f
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }
}
