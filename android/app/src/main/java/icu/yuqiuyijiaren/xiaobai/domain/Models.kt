package icu.yuqiuyijiaren.xiaobai.domain

/**
 * Shared domain models aligned with desktop/web rally JSON shape.
 * Keep this package free of Android UI so future ONNX/native code can reuse it.
 */
data class Rally(
    val id: Long,
    val startSec: Double,
    val endSec: Double,
    val confidence: Double = 1.0,
) {
    val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)

    fun clamp(duration: Double): Rally {
        val start = startSec.coerceIn(0.0, duration)
        val end = endSec.coerceIn(start + 0.2, duration)
        return copy(startSec = start, endSec = end)
    }
}

data class VideoSource(
    val uriString: String,
    val displayName: String,
    val durationMs: Long,
) {
    val durationSec: Double get() = durationMs / 1000.0
}

enum class AnalysisPhase {
    Idle,
    Preparing,
    ExtractingAudio,
    DetectingHits,
    BuildingRallies,
    Done,
    Failed,
}

data class AnalysisProgress(
    val phase: AnalysisPhase = AnalysisPhase.Idle,
    val percent: Float = 0f,
    val message: String = "",
)

sealed interface EditorEvent {
    data object PickVideo : EditorEvent
    data class VideoPicked(val source: VideoSource) : EditorEvent
    data object StartAnalysis : EditorEvent
    data class SelectRally(val index: Int?) : EditorEvent
    data class UpdateRally(val index: Int, val startSec: Double, val endSec: Double) : EditorEvent
    data class DeleteRally(val index: Int) : EditorEvent
    data class AddRallyAt(val timeSec: Double) : EditorEvent
    data class SeekTo(val timeSec: Double) : EditorEvent
    data object Export : EditorEvent
    data object ClearError : EditorEvent
}

data class EditorUiState(
    val source: VideoSource? = null,
    val rallies: List<Rally> = emptyList(),
    val selectedRallyIndex: Int? = null,
    val playheadSec: Double = 0.0,
    val analysis: AnalysisProgress = AnalysisProgress(),
    val isExporting: Boolean = false,
    val exportPath: String? = null,
    val errorMessage: String? = null,
) {
    val isAnalyzing: Boolean
        get() = analysis.phase != AnalysisPhase.Idle &&
            analysis.phase != AnalysisPhase.Done &&
            analysis.phase != AnalysisPhase.Failed

    val selectedDurationSec: Double
        get() = rallies.sumOf { it.durationSec }

    val reductionPercent: Int
        get() {
            val total = source?.durationSec ?: 0.0
            if (total <= 0.0) return 0
            return ((1.0 - selectedDurationSec / total) * 100.0).toInt().coerceIn(0, 100)
        }
}
