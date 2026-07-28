package icu.yuqiuyijiaren.xiaobai.export

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaMuxer
import android.net.Uri
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Lightweight first-pass exporter: remux selected time ranges into one MP4
 * without re-encoding when possible (fast, on-device).
 *
 * Later: FFmpeg-kit / MediaCodec re-encode for frame-accurate cuts + compression.
 */
class LocalExporter {
    suspend fun exportMerged(
        context: Context,
        source: VideoSource,
        rallies: List<Rally>,
    ): File = withContext(Dispatchers.IO) {
        require(rallies.isNotEmpty()) { "没有可导出的回合" }
        val outDir = File(context.cacheDir, "exports").apply { mkdirs() }
        val outFile = File(outDir, "xiaobai_${System.currentTimeMillis()}.mp4")
        if (outFile.exists()) outFile.delete()

        val extractor = MediaExtractor()
        try {
            val uri = Uri.parse(source.uriString)
            context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                extractor.setDataSource(pfd.fileDescriptor)
            } ?: extractor.setDataSource(context, uri, null)

            val muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            val trackMap = HashMap<Int, Int>()
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("video/") || mime.startsWith("audio/")) {
                    trackMap[i] = muxer.addTrack(format)
                }
            }
            if (trackMap.isEmpty()) {
                muxer.release()
                throw IllegalStateException("无法读取视频/音频轨道")
            }
            muxer.start()

            val buffer = ByteBuffer.allocate(1 shl 20)
            val info = MediaCodec.BufferInfo()
            var writePtsUs = 0L

            for (rally in rallies.sortedBy { it.startSec }) {
                val startUs = (rally.startSec * 1_000_000).toLong()
                val endUs = (rally.endSec * 1_000_000).toLong()
                var segmentBase = -1L

                for ((sourceTrack, destTrack) in trackMap) {
                    extractor.selectTrack(sourceTrack)
                    extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                    while (true) {
                        info.offset = 0
                        info.size = extractor.readSampleData(buffer, 0)
                        if (info.size < 0) break
                        val sampleTime = extractor.sampleTime
                        if (sampleTime < 0 || sampleTime > endUs) break
                        if (sampleTime >= startUs) {
                            if (segmentBase < 0L) segmentBase = sampleTime
                            info.presentationTimeUs = writePtsUs + (sampleTime - segmentBase)
                            info.flags = extractor.sampleFlags
                            muxer.writeSampleData(destTrack, buffer, info)
                        }
                        if (!extractor.advance()) break
                    }
                    extractor.unselectTrack(sourceTrack)
                }
                // Advance global timeline by rally duration (approx).
                writePtsUs += ((rally.endSec - rally.startSec) * 1_000_000).toLong()
            }

            muxer.stop()
            muxer.release()
            outFile
        } finally {
            extractor.release()
        }
    }
}
