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
 * On-device pipeline mirroring desktop CourtAware + StrictHighlight.
 * Intensity is driven by [AnalysisConfig] (device-recommended or user-selected).
 */
class OnDevicePcAnalyzer : RallyAnalyzer {

    override fun analyze(context: Context, request: AnalysisRequest): Flow<AnalysisUpdate> = flow {
        try {
            val source = request.source
            val device = DeviceProfiler.probe(context)
            val config = request.config
                ?: AnalysisConfig.forTier(AnalysisTier.Standard, device)

            emit(
                progress(
                    AnalysisPhase.Preparing,
                    0.04f,
                    "准备 ${config.tier.label} 档 · ${config.visualFps}fps · ${device.classLabel}",
                ),
            )
            val uri = Uri.parse(source.uriString)
            val warnings = ArrayList<String>()

            val audioHits = AudioHitDetector(
                maxOutputSamples = config.maxAudioMinutes * 60 * 16_000,
                softProminence = config.softProminence,
            )
            val builder = CourtAwareRallyBuilder(visualFps = config.visualFps)
            val highlightFilter = StrictHighlightFilter()

            emit(progress(AnalysisPhase.ExtractingAudio, 0.12f, "解码音轨并检测击球（${config.tier.label}）"))
            val hitResult = try {
                System.gc()
                audioHits.detect(context, uri)
            } catch (error: OutOfMemoryError) {
                emit(AnalysisUpdate.Error("内存不足，请改用「快速」档或缩短视频后重试"))
                return@flow
            } catch (error: Exception) {
                emit(
                    AnalysisUpdate.Error(
                        error.message?.takeIf { it.isNotBlank() }
                            ?: "音轨解码失败，无法做端侧击球检测",
                    ),
                )
                return@flow
            }
            if (hitResult.hitsSec.isEmpty()) {
                warnings += "未检测到击球峰值，请检查音轨或手动标注"
            }

            emit(progress(AnalysisPhase.DetectingHits, 0.35f, "提取球场区域运动能量 ${config.visualFps}fps"))
            val motionExtractor = CourtMotionExtractor(
                visualFps = config.visualFps,
                diffThreshold = config.motionDiffThreshold,
                useClosestFrame = config.useClosestFrame,
                maxSamplesCap = config.motionMaxSamples,
                courtRoi = request.courtRoi.asPairs(),
            )
            val motion = try {
                System.gc()
                motionExtractor.extract(context, uri, source.durationSec)
            } catch (_: OutOfMemoryError) {
                warnings += "运动分析内存不足，已退化为仅音频"
                null
            } catch (_: Exception) {
                null
            }
            if (motion == null || motion.isEmpty) {
                warnings += "球场运动提取失败/为空，已退化为仅音频分组（建议标定 ROI 或提高识别强度）"
            }

            emit(progress(AnalysisPhase.BuildingRallies, 0.72f, "组装回合并应用高光过滤"))
            val coarse = builder.build(hitResult.hitsSec, motion)
            val filtered = highlightFilter.apply(coarse, hitResult.hitsSec, motion)
            val refined = if (config.doubleHighlightPass) {
                highlightFilter.apply(filtered, hitResult.hitsSec, motion)
            } else {
                filtered
            }

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

            val doneMsg = when {
                rallies.isEmpty() -> "未识别到有效回合，可用「打点」或手动添加"
                warnings.isNotEmpty() -> "识别完成 · ${rallies.size} 个回合 · ${config.tier.label}档"
                else -> "识别完成 · ${rallies.size} 个回合 · ${config.tier.label}档"
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
