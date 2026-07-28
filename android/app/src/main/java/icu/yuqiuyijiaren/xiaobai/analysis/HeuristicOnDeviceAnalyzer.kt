package icu.yuqiuyijiaren.xiaobai.analysis

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisProgress
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * First-generation fully on-device analyzer.
 *
 * Pipeline (no network, no Python):
 * 1) Decode audio track with MediaExtractor/MediaCodec
 * 2) Band-emphasize mid/high energy for hit-like bursts
 * 3) Peak pick with refractory period
 * 4) Group peaks into rallies with strict silence / density rules
 *
 * This intentionally mirrors desktop "strict highlight" philosophy so later
 * ONNX trajectory models can plug into the same Rally list contract.
 */
class HeuristicOnDeviceAnalyzer(
    private val maxSilenceSec: Double = 2.5,
    private val minHits: Int = 3,
    private val minDurationSec: Double = 1.5,
    private val maxDurationSec: Double = 22.0,
    private val servePadSec: Double = 0.75,
    private val landPadSec: Double = 0.95,
    private val minHitDensity: Double = 0.28,
) : RallyAnalyzer {

    override fun analyze(context: Context, source: VideoSource): Flow<AnalysisUpdate> = flow {
        try {
            emit(progress(AnalysisPhase.Preparing, 0.05f))
            val uri = Uri.parse(source.uriString)
            emit(progress(AnalysisPhase.ExtractingAudio, 0.15f))
            val envelope = decodeAudioEnvelope(context, uri)
            emit(progress(AnalysisPhase.DetectingHits, 0.55f))
            val hits = detectHits(envelope)
            emit(progress(AnalysisPhase.BuildingRallies, 0.8f))
            val rallies = buildRallies(hits, source.durationSec)
            emit(progress(AnalysisPhase.Done, 1f))
            emit(AnalysisUpdate.Result(rallies))
        } catch (error: Exception) {
            emit(
                AnalysisUpdate.Error(
                    error.message?.takeIf { it.isNotBlank() } ?: "端侧分析失败",
                ),
            )
        }
    }.flowOn(Dispatchers.Default)

    private fun progress(phase: AnalysisPhase, percent: Float) = AnalysisUpdate.Progress(
        AnalysisProgress(phase = phase, percent = percent, message = phase.toMessage()),
    )

    private data class Envelope(
        val sampleRate: Int,
        val hopSec: Double,
        val values: FloatArray,
    )

    private fun decodeAudioEnvelope(context: Context, uri: Uri): Envelope {
        val extractor = MediaExtractor()
        try {
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: extractor.setDataSource(context, uri, null)

            val trackIndex = (0 until extractor.trackCount).firstOrNull { index ->
                extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: throw IllegalStateException("视频没有可用音轨，无法做端侧击球检测")

            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            val mime = format.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalStateException("无法读取音轨格式")
            val sampleRate = if (format.containsKey(MediaFormat.KEY_SAMPLE_RATE)) {
                format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            } else {
                44100
            }
            val channelCount = if (format.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) {
                format.getInteger(MediaFormat.KEY_CHANNEL_COUNT).coerceAtLeast(1)
            } else {
                1
            }

            val codec = MediaCodec.createDecoderByType(mime)
            codec.configure(format, null, null, 0)
            codec.start()

            val hopSamples = max(1, sampleRate / 50) // ~20ms
            val energies = ArrayList<Float>(4096)
            var pending = FloatArray(0)
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val sampleSize = extractor.readSampleData(buffer, 0)
                        if (sampleSize < 0) {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                0,
                                0L,
                                MediaCodec.BUFFER_FLAG_END_OF_STREAM,
                            )
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(
                                inIndex,
                                0,
                                sampleSize,
                                extractor.sampleTime,
                                0,
                            )
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
                            val pcm = ByteArray(info.size)
                            outBuffer.get(pcm)
                            val samples = pcmToMonoFloat(pcm, channelCount)
                            pending = concat(pending, samples)
                            var offset = 0
                            while (offset + hopSamples <= pending.size) {
                                var sum = 0.0
                                var i = 0
                                while (i < hopSamples) {
                                    val s = pending[offset + i]
                                    // Emphasize sharp bursts (hit-like) over low rumble.
                                    val shaped = s * s * (0.35f + abs(s))
                                    sum += shaped
                                    i++
                                }
                                val rms = sqrt(sum / hopSamples).toFloat()
                                energies.add(rms)
                                offset += hopSamples
                            }
                            if (offset > 0) {
                                pending = pending.copyOfRange(offset, pending.size)
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                            outputDone = true
                        }
                    }
                }
            }

            codec.stop()
            codec.release()

            if (energies.isEmpty()) {
                throw IllegalStateException("未能从视频解码出有效音频")
            }
            return Envelope(
                sampleRate = sampleRate,
                hopSec = hopSamples.toDouble() / sampleRate.toDouble(),
                values = energies.toFloatArray(),
            )
        } finally {
            extractor.release()
        }
    }

    private fun pcmToMonoFloat(pcm: ByteArray, channelCount: Int): FloatArray {
        val shortCount = pcm.size / 2
        val buffer = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val mono = FloatArray(shortCount / channelCount)
        var i = 0
        var o = 0
        while (i + channelCount <= shortCount) {
            var acc = 0f
            var c = 0
            while (c < channelCount) {
                acc += buffer.get(i + c) / 32768f
                c++
            }
            mono[o++] = acc / channelCount
            i += channelCount
        }
        return if (o == mono.size) mono else mono.copyOf(o)
    }

    private fun concat(a: FloatArray, b: FloatArray): FloatArray {
        if (a.isEmpty()) return b
        if (b.isEmpty()) return a
        return FloatArray(a.size + b.size).also {
            System.arraycopy(a, 0, it, 0, a.size)
            System.arraycopy(b, 0, it, a.size, b.size)
        }
    }

    private fun detectHits(envelope: Envelope): DoubleArray {
        val values = envelope.values
        if (values.isEmpty()) return doubleArrayOf()

        // Robust threshold: median + k * MAD style approximation via percentiles.
        val sorted = values.sorted()
        val median = percentile(sorted, 0.5)
        val p90 = percentile(sorted, 0.90)
        val p97 = percentile(sorted, 0.97)
        val thr = max(median * 4.5f + 1e-5f, p90 * 1.35f + (p97 - p90) * 0.35f)

        val minGapSec = 0.22
        val minGapHops = max(1, (minGapSec / envelope.hopSec).toInt())
        val hits = ArrayList<Double>()
        var lastHitHop = -minGapHops
        var i = 1
        while (i < values.size - 1) {
            val v = values[i]
            val isPeak = v >= thr && v >= values[i - 1] && v >= values[i + 1]
            if (isPeak && i - lastHitHop >= minGapHops) {
                // Local prominence check reduces steady noise.
                val left = values[max(0, i - 3)]
                val right = values[min(values.lastIndex, i + 3)]
                if (v > left * 1.15f && v > right * 1.15f) {
                    hits.add(i * envelope.hopSec)
                    lastHitHop = i
                }
            }
            i++
        }
        return hits.toDoubleArray()
    }

    private fun percentile(sorted: List<Float>, q: Double): Float {
        if (sorted.isEmpty()) return 0f
        val idx = ((sorted.size - 1) * q).toInt().coerceIn(0, sorted.lastIndex)
        return sorted[idx]
    }

    private fun buildRallies(hits: DoubleArray, durationSec: Double): List<Rally> {
        if (hits.size < minHits) return emptyList()
        val groups = ArrayList<MutableList<Double>>()
        var current = mutableListOf(hits[0])
        for (i in 1 until hits.size) {
            val hit = hits[i]
            if (hit - current.last() > maxSilenceSec) {
                groups.add(current)
                current = mutableListOf(hit)
            } else {
                current.add(hit)
            }
        }
        groups.add(current)

        val now = System.currentTimeMillis()
        val rallies = ArrayList<Rally>()
        groups.forEachIndexed { index, group ->
            if (group.size < minHits) return@forEachIndexed
            val first = group.first()
            val last = group.last()
            val span = max(0.25, last - first)
            val density = group.size / span
            if (density < minHitDensity && group.size < minHits + 2) return@forEachIndexed
            val start = max(0.0, first - servePadSec)
            val end = min(durationSec, last + landPadSec)
            val duration = end - start
            if (duration < minDurationSec || duration > maxDurationSec + 4.0) return@forEachIndexed
            // Confidence from density + hit count.
            val densityScore = (density / 0.8).coerceIn(0.0, 1.0)
            val countScore = (group.size / 8.0).coerceIn(0.0, 1.0)
            val confidence = (0.45 + 0.3 * densityScore + 0.25 * countScore).coerceIn(0.45, 0.99)
            rallies.add(
                Rally(
                    id = now + index,
                    startSec = start,
                    endSec = end,
                    confidence = confidence,
                ).clamp(durationSec),
            )
        }
        return rallies
    }
}
