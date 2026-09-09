package icu.yuqiuyijiaren.xiaobai.domain

/**
 * Shared domain models aligned with desktop/web rally JSON shape.
 * Keep this package free of Android UI so future ONNX/native code can reuse it.
 */
enum class ReviewStatus {
    Normal,
    Approved,
    Flagged,
    Rejected,
}

data class Rally(
    val id: Long,
    val startSec: Double,
    val endSec: Double,
    val confidence: Double = 1.0,
    val reviewStatus: ReviewStatus = ReviewStatus.Normal,
) {
    val durationSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)

    fun clamp(duration: Double): Rally {
        if (duration <= 0.0) return copy(startSec = 0.0, endSec = 0.0)
        val minLen = minOf(0.2, duration)
        val maxStart = (duration - minLen).coerceAtLeast(0.0)
        val start = startSec.coerceIn(0.0, maxStart)
        val end = endSec.coerceIn(start + minLen, duration)
        return copy(startSec = start, endSec = end)
    }
}

/**
 * Automatically merges overlapping or touching rallies into single continuous rallies.
 */
fun List<Rally>.mergeOverlapping(marginSec: Double = 0.05): List<Rally> {
    if (size <= 1) return this
    val sorted = sortedBy { it.startSec }
    val result = ArrayList<Rally>(sorted.size)
    var current = sorted[0]

    for (i in 1 until sorted.size) {
        val next = sorted[i]
        if (next.startSec <= current.endSec + marginSec) {
            val mergedEnd = maxOf(current.endSec, next.endSec)
            val mergedConfidence = maxOf(current.confidence, next.confidence)
            val mergedStatus = when {
                current.reviewStatus == ReviewStatus.Approved || next.reviewStatus == ReviewStatus.Approved -> ReviewStatus.Approved
                current.reviewStatus == ReviewStatus.Flagged || next.reviewStatus == ReviewStatus.Flagged -> ReviewStatus.Flagged
                current.reviewStatus == ReviewStatus.Rejected && next.reviewStatus == ReviewStatus.Rejected -> ReviewStatus.Rejected
                else -> current.reviewStatus
            }
            current = current.copy(
                endSec = mergedEnd,
                confidence = mergedConfidence,
                reviewStatus = mergedStatus,
            )
        } else {
            result.add(current)
            current = next
        }
    }
    result.add(current)
    return result
}

data class VideoSource(
    val uriString: String,
    val displayName: String,
    val durationMs: Long,
) {
    val durationSec: Double get() = durationMs / 1000.0
}

/**
 * Four normalized corner points of the court ROI (x,y in 0..1),
 * order: TL, TR, BR, BL — same convention as desktop H5.
 */
data class CourtRoi(
    val points: List<Pair<Float, Float>>,
) {
    init {
        require(points.size == 4) { "Court ROI needs exactly 4 points" }
    }

    fun asPairs(): List<Pair<Float, Float>> = points.map { (x, y) ->
        x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
    }

    companion object {
        val DEFAULT = CourtRoi(
            listOf(
                0.20f to 0.34f,
                0.82f to 0.34f,
                0.98f to 0.96f,
                0.02f to 0.96f,
            ),
        )
    }
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

// AnalysisTier + AnalysisConfig live in AnalysisConfig.kt

/** One-shot playback instruction for the video player. */
data class PlaybackCommand(
    val token: Long,
    val seekSec: Double? = null,
    val playUntilSec: Double? = null,
    val autoPlay: Boolean = false,
)

sealed interface EditorEvent {
    data object PickVideo : EditorEvent
    data class VideoPicked(val source: VideoSource) : EditorEvent
    data object StartAnalysis : EditorEvent
    data object CancelAnalysis : EditorEvent
    data class SelectRally(val index: Int?) : EditorEvent
    data class UpdateRally(val index: Int, val startSec: Double, val endSec: Double) : EditorEvent
    data class DeleteRally(val index: Int) : EditorEvent
    data class AddRallyAt(val timeSec: Double) : EditorEvent
    data class SeekTo(val timeSec: Double) : EditorEvent
    data object MergeSelectedWithNext : EditorEvent
    data object SplitSelectedAtPlayhead : EditorEvent
    data class NudgeSelected(val deltaSec: Double) : EditorEvent
    /** Nudge only start or only end of selected rally. */
    data class NudgeSelectedEdge(val startDelta: Double = 0.0, val endDelta: Double = 0.0) : EditorEvent
    data object SetSelectedStartAtPlayhead : EditorEvent
    data object SetSelectedEndAtPlayhead : EditorEvent
    data object MarkRangeStartAtPlayhead : EditorEvent
    data object MarkRangeEndAtPlayhead : EditorEvent
    data object ClearRangeMarks : EditorEvent
    data object PlaySelected : EditorEvent
    data object SelectPrevious : EditorEvent
    data object SelectNext : EditorEvent
    data object Undo : EditorEvent
    data object ShowRoiEditor : EditorEvent
    data object HideRoiEditor : EditorEvent
    data class UpdateCourtRoiPoint(val index: Int, val x: Float, val y: Float) : EditorEvent
    data object ResetCourtRoi : EditorEvent
    data class SetAnalysisTier(val tier: AnalysisTier) : EditorEvent
    data class SetRallyStatus(val index: Int, val status: ReviewStatus) : EditorEvent
    data object MergeAdjacentRallies : EditorEvent
    data object ToggleSmartSkip : EditorEvent
    data object ToggleLoopRally : EditorEvent
    data object Export : EditorEvent
    data object ClearError : EditorEvent
    data object ClearExportPath : EditorEvent
    data object ClearPlaybackCommand : EditorEvent
    data class SetIsPlaying(val isPlaying: Boolean) : EditorEvent
    data object PausePlayback : EditorEvent
    data object TogglePlayPause : EditorEvent
    data class FastForward(val deltaSec: Double = 1.5) : EditorEvent
    data object StartDirectEdit : EditorEvent
    data object ClearAllRallies : EditorEvent
}

data class EditorUiState(
    val source: VideoSource? = null,
    val rallies: List<Rally> = emptyList(),
    val selectedRallyIndex: Int? = null,
    val playheadSec: Double = 0.0,
    val isPlaying: Boolean = false,
    val analysis: AnalysisProgress = AnalysisProgress(),
    val isExporting: Boolean = false,
    val exportPath: String? = null,
    /** Display name written into Movies/Xiaobai after a successful gallery save. */
    val exportGalleryName: String? = null,
    val shareRequested: Boolean = false,
    val errorMessage: String? = null,
    val courtRoi: CourtRoi = CourtRoi.DEFAULT,
    val showRoiEditor: Boolean = false,
    val analysisWarnings: List<String> = emptyList(),
    /** Mark-in for creating a new range (null when unset). */
    val rangeMarkInSec: Double? = null,
    /** Mark-out for creating a new range. */
    val rangeMarkOutSec: Double? = null,
    val canUndo: Boolean = false,
    val playback: PlaybackCommand? = null,
    val smartSkip: Boolean = false,
    val loopRally: Boolean = false,
    val deviceSummary: String = "",
    val deviceCapabilityLine: String = "",
    val analysisTier: AnalysisTier = AnalysisTier.Standard,
    val analysisConfigSummary: String = "",
    val recommendedTier: AnalysisTier = AnalysisTier.Standard,
) {
    val isAnalyzing: Boolean
        get() = analysis.phase != AnalysisPhase.Idle &&
            analysis.phase != AnalysisPhase.Done &&
            analysis.phase != AnalysisPhase.Failed

    val selectedDurationSec: Double
        get() = rallies.sumOf { it.durationSec }

    val selectedRally: Rally?
        get() = selectedRallyIndex?.let { rallies.getOrNull(it) }

    val reductionPercent: Int
        get() {
            val total = source?.durationSec ?: 0.0
            if (total <= 0.0) return 0
            return ((1.0 - selectedDurationSec / total) * 100.0).toInt().coerceIn(0, 100)
        }
}
