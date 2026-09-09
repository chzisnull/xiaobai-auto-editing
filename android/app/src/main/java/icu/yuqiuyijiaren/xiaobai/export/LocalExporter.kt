package icu.yuqiuyijiaren.xiaobai.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * On-device remux exporter: copy compressed samples for selected ranges into one MP4.
 *
 * Stream-copy cannot cut mid-GOP cleanly, so each track writes from the previous
 * keyframe (SEEK_TO_PREVIOUS_SYNC) through endUs. That may include short pre-roll.
 * Frame-accurate trims need FFmpeg-kit / re-encode later.
 */
class LocalExporter(
    private val preBufferSec: Double = 0.35,
    private val postBufferSec: Double = 0.35,
) {
    suspend fun exportMerged(
        context: Context,
        source: VideoSource,
        rallies: List<Rally>,
    ): File = withContext(Dispatchers.IO) {
        require(rallies.isNotEmpty()) { "没有可导出的回合" }
        val prepared = ExportTimeline.prepareSegments(
            rallies = rallies,
            durationSec = source.durationSec,
            preBuffer = preBufferSec,
            postBuffer = postBufferSec,
        )
        require(prepared.isNotEmpty()) { "缓冲合并后没有可导出的片段" }

        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "xiaobai_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            val uri = Uri.parse(source.uriString)
            icu.yuqiuyijiaren.xiaobai.domain.MediaSourceHelper.setExtractorDataSource(extractor, context, uri)

            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = LinkedHashMap<Int, Int>()
            var maxInput = 1 shl 20
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[i] = muxer.addTrack(format)
                    if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                        maxInput = max(maxInput, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                    }
                }
            }
            if (trackMap.isEmpty()) {
                throw IllegalStateException("无法读取视频/音频轨道")
            }
            muxer.start()

            val buffer = ByteBuffer.allocate(maxInput.coerceAtLeast(1 shl 20))
            val info = MediaCodec.BufferInfo()
            var writePtsUs = 0L

            for (rally in prepared) {
                val startUs = (rally.startSec * 1_000_000.0).toLong()
                val endUs = (rally.endSec * 1_000_000.0).toLong()
                var segmentDurationUs = 0L

                for ((sourceTrack, destTrack) in trackMap) {
                    extractor.selectTrack(sourceTrack)
                    // Include previous keyframe so the GOP is decodable (may pre-roll before startUs).
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    var trackBase = -1L
                    while (true) {
                        info.offset = 0
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0 || sampleTime > endUs) break
                        if (trackBase < 0L) trackBase = sampleTime
                        val relative = sampleTime - trackBase
                        info.presentationTimeUs = writePtsUs + relative
                        info.flags = extractor.sampleFlags
                        muxer.writeSampleData(destTrack, buffer, info)
                        segmentDurationUs = max(segmentDurationUs, relative)
                        if (!extractor.advance()) break
                    }
                    extractor.unselectTrack(sourceTrack)
                }

                val fallbackUs = ((rally.endSec - rally.startSec) * 1_000_000.0)
                    .toLong()
                    .coerceAtLeast(200_000L)
                writePtsUs += max(segmentDurationUs, fallbackUs) + 10_000L
            }

            try {
                muxer.stop()
            } catch (_: Exception) {
                // stop can throw if no samples written
            }
            muxer.release()
            muxer = null

            if (!outFile.exists() || outFile.length() < 64) {
                outFile.delete()
                throw IllegalStateException("导出文件为空，请检查视频编码是否可 remux")
            }
            outFile
        } catch (error: Exception) {
            outFile.delete()
            throw error
        } finally {
            runCatching {
                muxer?.release()
            }
            extractor.release()
        }
    }
}
