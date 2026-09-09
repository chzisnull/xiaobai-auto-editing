package icu.yuqiuyijiaren.xiaobai.analysis

import android.content.Context
import android.net.Uri
import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.AudioHitDetector
import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.CourtAwareRallyBuilder
import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.CourtMotionExtractor
import icu.yuqiuyijiaren.xiaobai.analysis.pipeline.StrictHighlightFilter
import icu.yuqiuyijiaren.xiaobai.device.DeviceProfiler
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisConfig
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisProgress
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn

/**
 * On-device pipeline: audio hits + sequential motion + strict highlight.
 * Motion uses MediaCodec sequential decode (not full-film random seeks).
 */
class OnDevicePcAnalyzer : RallyAnalyzer {

    override fun analyze(context: Context, request: AnalysisRequest): Flow<AnalysisUpdate> = flow {
        try {
            val source = request.source
            val device = DeviceProfiler.probe(context)
            val config = request.config
                ?: AnalysisConfig.forTier(AnalysisTier.Standard, device)

            val motionFps = config.effectiveMotionFps(source.durationSec)
            val motionCap = config.effectiveMaxSamples(source.durationSec)

            emit(
                progress(
                    AnalysisPhase.Preparing,
                    0.03f,
                    "准备 ${config.tier.label} 档 · 运动${motionFps}fps · 最多${motionCap}点 · ${device.classLabel}",
                ),
            )
            val uri = Uri.parse(source.uriString)
            val warnings = ArrayList<String>()

            val audioHits = AudioHitDetector(
                maxOutputSamples = config.maxAudioMinutes * 60 * 16_000,
                softProminence = config.softProminence,
                hitMadK = config.hitMadK,
            )
            val builder = CourtAwareRallyBuilder(visualFps = motionFps)
            val highlightFilter = StrictHighlightFilter(
                servePad = 1.15,
                landPad = 0.85,
                maxHitSilence = 2.45,
                motionBridgeSilence = 3.3,
                minHits = 3,
                minDuration = 1.6,
                minHitDensity = 0.32,
                remergeGap = 1.15,
            )

            emit(progress(AnalysisPhase.ExtractingAudio, 0.08f, "解码音轨并检测击球（${config.tier.label}）"))
            android.util.Log.i("Xiaobai", "Starting audioHits.detect for uri=$uri")
            val hitResult = try {
                audioHits.detect(context, uri)
            } catch (error: OutOfMemoryError) {
                android.util.Log.e("Xiaobai", "Audio detection OOM", error)
                emit(AnalysisUpdate.Error("内存不足，请改用「快速」档或缩短视频后重试"))
                return@flow
            } catch (error: Exception) {
                android.util.Log.e("Xiaobai", "Audio detection failed", error)
                emit(
                    AnalysisUpdate.Error(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "音轨解码失败，无法做端侧击球检测",
                    ),
                )
                return@flow
            }
            android.util.Log.i("Xiaobai", "Audio hits detected: count=${hitResult.hitsSec.size}")
            if (hitResult.hitsSec.isEmpty()) {
                warnings += "未检测到击球峰值，请检查音轨或手动标注"
            }

            emit(
                progress(
                    AnalysisPhase.DetectingHits,
                    0.28f,
                    "顺序解码提取运动能量 · ${motionFps}fps · ≤${motionCap}点",
                ),
            )
            val motionExtractor = CourtMotionExtractor(
                visualFps = motionFps,
                diffThreshold = config.motionDiffThreshold,
                useClosestFrame = false,
                maxSamplesCap = motionCap,
                applyBlur = config.applyMotionBlur,
                courtRoi = request.courtRoi.asPairs(),
            )
            val motion = try {
                android.util.Log.i("Xiaobai", "Starting motionExtractor.extract motionFps=$motionFps, motionCap=$motionCap")
                motionExtractor.extract(context, uri, source.durationSec, onProgress = null)
            } catch (error: OutOfMemoryError) {
                android.util.Log.e("Xiaobai", "Motion extraction OOM", error)
                warnings += "运动分析内存不足，已退化为仅音频"
                null
            } catch (error: Exception) {
                android.util.Log.e("Xiaobai", "Motion extraction failed", error)
                null
            }
            if (motion == null || motion.isEmpty) {
                android.util.Log.w("Xiaobai", "Motion extraction empty or null")
                warnings += "球场运动提取失败/为空，已退化为仅音频分组"
            } else {
                android.util.Log.i("Xiaobai", "Motion extracted: timestamps=${motion.timestamps.size}")
                emit(
                    progress(
                        AnalysisPhase.DetectingHits,
                        0.68f,
                        "运动采样完成 · ${motion.timestamps.size} 点",
                    ),
                )
            }

            emit(progress(AnalysisPhase.BuildingRallies, 0.78f, "组装回合并应用高光过滤"))
            val coarse = builder.build(hitResult.hitsSec, motion)
            android.util.Log.i("Xiaobai", "Coarse rallies built: count=${coarse.size}")
            val filtered = highlightFilter.apply(coarse, hitResult.hitsSec, motion)
            val refined = if (config.doubleHighlightPass) {
                highlightFilter.apply(filtered, hitResult.hitsSec, motion)
            } else {
                filtered
            }
            android.util.Log.i("Xiaobai", "Refined rallies: count=${refined.size}")

            val duration = source.durationSec
            val baseId = System.currentTimeMillis()
            val rallies = refined
                .sortedBy { it.start }
                .mapIndexed { index, seg ->
                    Rally(
                        id = baseId + index,
                        startSec = seg.start.coerceAtLeast(0.0),
                        endSec = seg.end.coerceAtMost(duration),
                        confidence = seg.confidence.coerceIn(0.0, 1.0),
                    ).clamp(duration)
                }
                .filter { it.durationSec >= 0.5 }
            android.util.Log.i("Xiaobai", "Final rallies: count=${rallies.size}")

            val doneMsg = when {
                rallies.isEmpty() -> "未识别到有效回合，可用「打点」或手动添加"
                else -> "识别完成 · ${rallies.size} 个回合 · ${config.tier.label}档 · ${motionFps}fps"
            }
            emit(progress(AnalysisPhase.Done, 1f, doneMsg))
            emit(AnalysisUpdate.Result(rallies, warnings))
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: Exception) {
            emit(
                AnalysisUpdate.Error(
                    error.message?.takeIf { it.isNotBlank() } ?: "端侧 PC 管线分析失败",
                ),
            )
        }
    }.flowOn(Dispatchers.Default)

    private fun progress(phase: AnalysisPhase, percent: Float, message: String) =
        AnalysisUpdate.Progress(
            AnalysisProgress(phase = phase, percent = percent, message = message),
        )
}
