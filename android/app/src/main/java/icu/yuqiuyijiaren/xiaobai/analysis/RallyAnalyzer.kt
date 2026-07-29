package icu.yuqiuyijiaren.xiaobai.analysis

import android.content.Context
import android.net.Uri
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisConfig
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisProgress
import icu.yuqiuyijiaren.xiaobai.domain.CourtRoi
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import kotlinx.coroutines.flow.Flow

/**
 * On-device rally analyzer contract.
 *
 * Default: [OnDevicePcAnalyzer] (audio hits + court ROI motion + strict highlight).
 * Fallback: [HeuristicOnDeviceAnalyzer]. Future: ONNX TrackNet precise tier.
 */
data class AnalysisRequest(
    val source: VideoSource,
    val courtRoi: CourtRoi = CourtRoi.DEFAULT,
    val config: AnalysisConfig? = null,
)

interface RallyAnalyzer {
    fun analyze(
        context: Context,
        request: AnalysisRequest,
    ): Flow<AnalysisUpdate>
}

sealed interface AnalysisUpdate {
    data class Progress(val progress: AnalysisProgress) : AnalysisUpdate
    data class Result(
        val rallies: List<Rally>,
        val warnings: List<String> = emptyList(),
    ) : AnalysisUpdate
    data class Error(val message: String) : AnalysisUpdate
}

fun AnalysisPhase.toMessage(): String = when (this) {
    AnalysisPhase.Idle -> ""
    AnalysisPhase.Preparing -> "准备视频与元数据"
    AnalysisPhase.ExtractingAudio -> "提取音频特征"
    AnalysisPhase.DetectingHits -> "检测击球峰值"
    AnalysisPhase.BuildingRallies -> "组装严格集锦回合"
    AnalysisPhase.Done -> "分析完成"
    AnalysisPhase.Failed -> "分析失败"
}

fun Uri.safeString(): String = toString()
