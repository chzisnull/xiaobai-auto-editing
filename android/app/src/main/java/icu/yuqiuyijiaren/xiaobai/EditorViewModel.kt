package icu.yuqiuyijiaren.xiaobai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import icu.yuqiuyijiaren.xiaobai.analysis.AnalysisRequest
import icu.yuqiuyijiaren.xiaobai.analysis.AnalysisUpdate
import icu.yuqiuyijiaren.xiaobai.analysis.OnDevicePcAnalyzer
import icu.yuqiuyijiaren.xiaobai.analysis.RallyAnalyzer
import icu.yuqiuyijiaren.xiaobai.device.DeviceProfile
import icu.yuqiuyijiaren.xiaobai.device.DeviceProfiler
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisConfig
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisProgress
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier
import icu.yuqiuyijiaren.xiaobai.domain.CourtRoi
import icu.yuqiuyijiaren.xiaobai.domain.EditorEvent
import icu.yuqiuyijiaren.xiaobai.domain.EditorUiState
import icu.yuqiuyijiaren.xiaobai.domain.PlaybackCommand
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.domain.ReviewStatus
import icu.yuqiuyijiaren.xiaobai.export.GallerySaver
import icu.yuqiuyijiaren.xiaobai.export.LocalExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min

class EditorViewModel(
    application: Application,
    private val analyzer: RallyAnalyzer = OnDevicePcAnalyzer(),
    private val exporter: LocalExporter = LocalExporter(),
) : AndroidViewModel(application) {

    private val deviceProfile: DeviceProfile = DeviceProfiler.probe(application)
    private var analysisConfig: AnalysisConfig =
        AnalysisConfig.forTier(deviceProfile.recommendedTier, deviceProfile)

    private val _uiState = MutableStateFlow(
        EditorUiState(
            deviceSummary = deviceProfile.shortSummary,
            deviceCapabilityLine = deviceProfile.capabilityLine,
            analysisTier = analysisConfig.tier,
            analysisConfigSummary = analysisConfig.summary,
            recommendedTier = deviceProfile.recommendedTier,
        ),
    )
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null
    private var exportJob: Job? = null
    private var sessionToken: Long = 0L
    private var playbackToken: Long = 0L
    private val undoStack = ArrayDeque<UndoSnapshot>()

    private data class UndoSnapshot(
        val rallies: List<Rally>,
        val selectedRallyIndex: Int?,
        val rangeMarkInSec: Double?,
        val rangeMarkOutSec: Double?,
    )

    fun onEvent(event: EditorEvent) {
        when (event) {
            EditorEvent.PickVideo -> Unit
            is EditorEvent.VideoPicked -> {
                analysisJob?.cancel()
                exportJob?.cancel()
                analysisJob = null
                exportJob = null
                sessionToken += 1L
                undoStack.clear()
                _uiState.value = baseState().copy(
                    source = event.source,
                    playheadSec = 0.0,
                    analysis = AnalysisProgress(phase = AnalysisPhase.Idle),
                    courtRoi = CourtRoi.DEFAULT,
                )
            }
            EditorEvent.StartAnalysis -> {
                android.util.Log.i("Xiaobai", "EditorEvent.StartAnalysis received! source=${_uiState.value.source?.displayName}, isAnalyzing=${_uiState.value.isAnalyzing}")
                startAnalysis()
            }
            EditorEvent.CancelAnalysis -> cancelAnalysis()
            is EditorEvent.SelectRally -> selectRally(event.index)
            is EditorEvent.UpdateRally -> updateRally(event.index, event.startSec, event.endSec)
            is EditorEvent.DeleteRally -> deleteRally(event.index)
            is EditorEvent.AddRallyAt -> addRally(event.timeSec)
            is EditorEvent.SeekTo -> _uiState.update { it.copy(playheadSec = event.timeSec) }
            EditorEvent.MergeSelectedWithNext -> mergeSelectedWithNext()
            EditorEvent.SplitSelectedAtPlayhead -> splitSelectedAtPlayhead()
            is EditorEvent.NudgeSelected -> nudgeSelected(event.deltaSec)
            is EditorEvent.NudgeSelectedEdge -> nudgeSelectedEdge(event.startDelta, event.endDelta)
            EditorEvent.SetSelectedStartAtPlayhead -> setSelectedStartAtPlayhead()
            EditorEvent.SetSelectedEndAtPlayhead -> setSelectedEndAtPlayhead()
            EditorEvent.MarkRangeStartAtPlayhead -> markRangeStart()
            EditorEvent.MarkRangeEndAtPlayhead -> markRangeEnd()
            EditorEvent.ClearRangeMarks -> _uiState.update {
                it.copy(rangeMarkInSec = null, rangeMarkOutSec = null)
            }
            EditorEvent.PlaySelected -> playSelected()
            EditorEvent.SelectPrevious -> selectRelative(-1)
            EditorEvent.SelectNext -> selectRelative(1)
            EditorEvent.Undo -> undo()
            EditorEvent.ShowRoiEditor -> _uiState.update { it.copy(showRoiEditor = true) }
            EditorEvent.HideRoiEditor -> _uiState.update { it.copy(showRoiEditor = false) }
            is EditorEvent.UpdateCourtRoiPoint -> updateRoiPoint(event.index, event.x, event.y)
            EditorEvent.ResetCourtRoi -> _uiState.update { it.copy(courtRoi = CourtRoi.DEFAULT) }
            is EditorEvent.SetAnalysisTier -> setTier(event.tier)
            is EditorEvent.SetRallyStatus -> setRallyStatus(event.index, event.status)
            EditorEvent.MergeAdjacentRallies -> mergeAdjacentRallies()
            EditorEvent.ToggleSmartSkip -> toggleSmartSkip()
            EditorEvent.ToggleLoopRally -> toggleLoopRally()
            EditorEvent.Export -> export()
            EditorEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
            EditorEvent.ClearExportPath -> _uiState.update {
                it.copy(exportPath = null, exportGalleryName = null, shareRequested = false)
            }
            EditorEvent.ClearPlaybackCommand -> _uiState.update { it.copy(playback = null) }
            is EditorEvent.SetIsPlaying -> _uiState.update { it.copy(isPlaying = event.isPlaying) }
            EditorEvent.PausePlayback -> pausePlayback()
        }
    }

    private fun baseState(): EditorUiState = EditorUiState(
        deviceSummary = deviceProfile.shortSummary,
        deviceCapabilityLine = deviceProfile.capabilityLine,
        analysisTier = analysisConfig.tier,
        analysisConfigSummary = analysisConfig.summary,
        recommendedTier = deviceProfile.recommendedTier,
    )

    private fun setTier(tier: AnalysisTier) {
        if (_uiState.value.isAnalyzing) return
        analysisConfig = AnalysisConfig.forTier(tier, deviceProfile)
        _uiState.update {
            it.copy(
                analysisTier = analysisConfig.tier,
                analysisConfigSummary = analysisConfig.summary,
            )
        }
    }

    private fun cancelAnalysis() {
        analysisJob?.cancel()
        analysisJob = null
        _uiState.update {
            it.copy(
                analysis = AnalysisProgress(
                    phase = AnalysisPhase.Idle,
                    percent = 0f,
                    message = "已取消分析",
                ),
            )
        }
    }

    fun updatePlayhead(timeSec: Double) {
        _uiState.update { it.copy(playheadSec = timeSec.coerceAtLeast(0.0)) }
    }

    private fun pushUndo() {
        val state = _uiState.value
        undoStack.addLast(
            UndoSnapshot(
                rallies = state.rallies,
                selectedRallyIndex = state.selectedRallyIndex,
                rangeMarkInSec = state.rangeMarkInSec,
                rangeMarkOutSec = state.rangeMarkOutSec,
            ),
        )
        while (undoStack.size > 40) undoStack.removeFirst()
        _uiState.update { it.copy(canUndo = undoStack.isNotEmpty()) }
    }

    private fun undo() {
        val snap = undoStack.removeLastOrNull() ?: return
        _uiState.update {
            it.copy(
                rallies = snap.rallies,
                selectedRallyIndex = snap.selectedRallyIndex,
                rangeMarkInSec = snap.rangeMarkInSec,
                rangeMarkOutSec = snap.rangeMarkOutSec,
                canUndo = undoStack.isNotEmpty(),
            )
        }
    }

    private var gestureUndoPushed = false

    fun beginGestureUndo() {
        if (!gestureUndoPushed) {
            pushUndo()
            gestureUndoPushed = true
        }
    }

    fun endGestureUndo() {
        gestureUndoPushed = false
    }

    private fun startAnalysis() {
        val source = _uiState.value.source
        android.util.Log.i("Xiaobai", "startAnalysis entry: source=$source, isAnalyzing=${_uiState.value.isAnalyzing}")
        if (source == null) return
        if (_uiState.value.isAnalyzing) return
        val token = sessionToken
        val roi = _uiState.value.courtRoi
        val config = analysisConfig
        android.util.Log.i("Xiaobai", "startAnalysis launching job for token=$token, config=$config")
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    rallies = emptyList(),
                    selectedRallyIndex = null,
                    exportPath = null,
                    exportGalleryName = null,
                    shareRequested = false,
                    errorMessage = null,
                    analysisWarnings = emptyList(),
                    showRoiEditor = false,
                    analysis = AnalysisProgress(
                        phase = AnalysisPhase.Preparing,
                        percent = 0.02f,
                        message = "准备 ${config.tier.label} 档分析",
                    ),
                )
            }
            analyzer.analyze(
                getApplication(),
                AnalysisRequest(source = source, courtRoi = roi, config = config),
            ).collect { update ->
                if (token != sessionToken) return@collect
                when (update) {
                    is AnalysisUpdate.Progress -> _uiState.update { it.copy(analysis = update.progress) }
                    is AnalysisUpdate.Result -> {
                        undoStack.clear()
                        _uiState.update {
                            it.copy(
                                rallies = update.rallies,
                                analysis = AnalysisProgress(
                                    phase = AnalysisPhase.Done,
                                    percent = 1f,
                                    message = if (update.rallies.isEmpty()) {
                                        "未识别到有效回合，可打点或手动添加"
                                    } else {
                                        "识别完成 · ${update.rallies.size} 个回合 · ${config.tier.label}档"
                                    },
                                ),
                                selectedRallyIndex = update.rallies.indices.firstOrNull(),
                                analysisWarnings = update.warnings,
                                canUndo = false,
                            )
                        }
                    }
                    is AnalysisUpdate.Error -> _uiState.update {
                        it.copy(
                            analysis = AnalysisProgress(
                                phase = AnalysisPhase.Failed,
                                percent = 0f,
                                message = update.message,
                            ),
                            errorMessage = update.message,
                        )
                    }
                }
            }
        }
    }

    private fun updateRally(index: Int, startSec: Double, endSec: Double) {
        val duration = _uiState.value.source?.durationSec ?: return
        if (index !in _uiState.value.rallies.indices) return
        beginGestureUndo()
        _uiState.update { state ->
            if (index !in state.rallies.indices) return@update state
            val id = state.rallies[index].id
            val edited = state.rallies[index].copy(startSec = startSec, endSec = endSec).clamp(duration)
            val next = state.rallies.toMutableList().also { it[index] = edited }.sortedBy { it.startSec }
            state.copy(
                rallies = next,
                selectedRallyIndex = next.indexOfFirst { it.id == id }.takeIf { it >= 0 },
            )
        }
    }

    private fun deleteRally(index: Int) {
        pushUndo()
        _uiState.update { state ->
            if (index !in state.rallies.indices) return@update state
            val next = state.rallies.toMutableList().also { it.removeAt(index) }
            val selected = when {
                next.isEmpty() -> null
                state.selectedRallyIndex == null -> null
                state.selectedRallyIndex >= next.size -> next.lastIndex
                else -> state.selectedRallyIndex
            }
            state.copy(rallies = next, selectedRallyIndex = selected)
        }
    }

    private fun addRally(timeSec: Double) {
        val duration = _uiState.value.source?.durationSec ?: return
        pushUndo()
        val start = timeSec.coerceIn(0.0, (duration - 0.5).coerceAtLeast(0.0))
        val end = (start + 4.0).coerceAtMost(duration)
        val rally = Rally(
            id = System.currentTimeMillis(),
            startSec = start,
            endSec = end,
            confidence = 1.0,
        )
        _uiState.update {
            val next = (it.rallies + rally).sortedBy { r -> r.startSec }
            it.copy(rallies = next, selectedRallyIndex = next.indexOfFirst { r -> r.id == rally.id })
        }
    }

    private fun mergeSelectedWithNext() {
        val duration = _uiState.value.source?.durationSec ?: return
        val index = _uiState.value.selectedRallyIndex ?: return
        val rallies = _uiState.value.rallies
        if (index !in rallies.indices || index >= rallies.lastIndex) return
        pushUndo()
        _uiState.update { state ->
            val a = state.rallies[index]
            val b = state.rallies[index + 1]
            val merged = Rally(
                id = a.id,
                startSec = min(a.startSec, b.startSec),
                endSec = max(a.endSec, b.endSec),
                confidence = min(a.confidence, b.confidence),
            ).clamp(duration)
            val next = state.rallies.toMutableList()
            next[index] = merged
            next.removeAt(index + 1)
            state.copy(rallies = next, selectedRallyIndex = index)
        }
    }

    private fun splitSelectedAtPlayhead() {
        val duration = _uiState.value.source?.durationSec ?: return
        val index = _uiState.value.selectedRallyIndex ?: return
        val rally = _uiState.value.rallies.getOrNull(index) ?: return
        val cut = _uiState.value.playheadSec
        if (cut <= rally.startSec + 0.3 || cut >= rally.endSec - 0.3) return
        pushUndo()
        _uiState.update { state ->
            val left = rally.copy(endSec = cut).clamp(duration)
            val right = Rally(
                id = System.currentTimeMillis(),
                startSec = cut,
                endSec = rally.endSec,
                confidence = rally.confidence,
            ).clamp(duration)
            val next = state.rallies.toMutableList()
            next[index] = left
            next.add(index + 1, right)
            state.copy(rallies = next, selectedRallyIndex = index)
        }
    }

    private fun nudgeSelected(deltaSec: Double) {
        nudgeSelectedEdge(startDelta = deltaSec, endDelta = deltaSec)
    }

    private fun nudgeSelectedEdge(startDelta: Double, endDelta: Double) {
        val duration = _uiState.value.source?.durationSec ?: return
        val index = _uiState.value.selectedRallyIndex ?: return
        if (index !in _uiState.value.rallies.indices) return
        pushUndo()
        _uiState.update { state ->
            val rally = state.rallies[index]
            val next = state.rallies.toMutableList()
            val newStart = if (startDelta != 0.0) {
                (rally.startSec + startDelta).coerceIn(0.0, rally.endSec - 0.2)
            } else rally.startSec
            val newEnd = if (endDelta != 0.0) {
                (rally.endSec + endDelta).coerceIn(newStart + 0.2, duration)
            } else rally.endSec

            val updatedRally = rally.copy(
                startSec = newStart,
                endSec = newEnd,
            ).clamp(duration)
            next[index] = updatedRally

            playbackToken += 1L
            val nextPlayback: PlaybackCommand?
            val nextPlayhead: Double

            if (startDelta != 0.0) {
                // User requirement: "如果起点-0.5s则从起点-0.5s重新开始播放"
                // Seek to the new start and start playing until endSec
                nextPlayhead = updatedRally.startSec
                nextPlayback = PlaybackCommand(
                    token = playbackToken,
                    seekSec = updatedRally.startSec,
                    playUntilSec = updatedRally.endSec,
                    autoPlay = true,
                )
            } else if (endDelta > 0.0) {
                // User requirement: "比如我正在播放第二片段点击终点+0.5s 那应该继续播放第二片段直到终点"
                val isPlaying = state.isPlaying
                val currentPlayhead = state.playheadSec

                if (isPlaying) {
                    // Actively playing: do NOT seek (prevent stutter/frame drop), smoothly continue to new endSec
                    nextPlayhead = currentPlayhead
                    nextPlayback = PlaybackCommand(
                        token = playbackToken,
                        seekSec = null,
                        playUntilSec = updatedRally.endSec,
                        autoPlay = true,
                    )
                } else {
                    // Paused at or near the end (or within rally): resume forward to new endSec
                    val resumeSec = if (currentPlayhead >= rally.endSec - 0.25) {
                        (rally.endSec - 0.15).coerceAtLeast(updatedRally.startSec)
                    } else if (currentPlayhead >= updatedRally.startSec) {
                        currentPlayhead
                    } else {
                        (rally.endSec - 0.5).coerceAtLeast(updatedRally.startSec)
                    }
                    nextPlayhead = resumeSec
                    nextPlayback = PlaybackCommand(
                        token = playbackToken,
                        seekSec = resumeSec,
                        playUntilSec = updatedRally.endSec,
                        autoPlay = true,
                    )
                }
            } else if (endDelta < 0.0) {
                // Shortened end
                val isPlaying = state.isPlaying
                val currentPlayhead = state.playheadSec
                if (currentPlayhead >= updatedRally.endSec - 0.05) {
                    nextPlayhead = updatedRally.endSec
                    nextPlayback = PlaybackCommand(
                        token = playbackToken,
                        seekSec = updatedRally.endSec,
                        playUntilSec = updatedRally.endSec,
                        autoPlay = false,
                    )
                } else if (isPlaying) {
                    nextPlayhead = currentPlayhead
                    nextPlayback = PlaybackCommand(
                        token = playbackToken,
                        seekSec = null,
                        playUntilSec = updatedRally.endSec,
                        autoPlay = true,
                    )
                } else {
                    nextPlayhead = currentPlayhead
                    nextPlayback = null
                }
            } else {
                nextPlayhead = state.playheadSec
                nextPlayback = null
            }

            state.copy(
                rallies = next,
                playheadSec = nextPlayhead,
                playback = nextPlayback,
            )
        }
    }

    private fun setSelectedStartAtPlayhead() {
        val duration = _uiState.value.source?.durationSec ?: return
        val index = _uiState.value.selectedRallyIndex ?: return
        val rally = _uiState.value.rallies.getOrNull(index) ?: return
        val t = _uiState.value.playheadSec
        if (t >= rally.endSec - 0.2) return
        pushUndo()
        _uiState.update { state ->
            val next = state.rallies.toMutableList()
            next[index] = rally.copy(startSec = t).clamp(duration)
            state.copy(rallies = next)
        }
    }

    private fun setSelectedEndAtPlayhead() {
        val duration = _uiState.value.source?.durationSec ?: return
        val index = _uiState.value.selectedRallyIndex ?: return
        val rally = _uiState.value.rallies.getOrNull(index) ?: return
        val t = _uiState.value.playheadSec
        if (t <= rally.startSec + 0.2) return
        pushUndo()
        _uiState.update { state ->
            val next = state.rallies.toMutableList()
            next[index] = rally.copy(endSec = t).clamp(duration)
            state.copy(rallies = next)
        }
    }

    private fun setRallyStatus(index: Int, status: ReviewStatus) {
        val count = _uiState.value.rallies.size
        if (index !in 0 until count) return
        pushUndo()
        _uiState.update { state ->
            val next = state.rallies.toMutableList()
            next[index] = next[index].copy(reviewStatus = status)
            state.copy(rallies = next)
        }
    }

    private fun mergeAdjacentRallies() {
        val current = _uiState.value.rallies
        if (current.size < 2) return
        pushUndo()
        val sorted = current.sortedBy { it.startSec }
        val merged = mutableListOf(sorted.first())
        for (i in 1 until sorted.size) {
            val prev = merged.last()
            val curr = sorted[i]
            if (curr.startSec - prev.endSec <= 1.0) {
                merged[merged.lastIndex] = prev.copy(
                    endSec = max(prev.endSec, curr.endSec),
                    confidence = min(prev.confidence, curr.confidence),
                )
            } else {
                merged.add(curr)
            }
        }
        _uiState.update { it.copy(rallies = merged, selectedRallyIndex = null) }
    }

    private fun toggleSmartSkip() {
        _uiState.update { it.copy(smartSkip = !it.smartSkip) }
    }

    private fun toggleLoopRally() {
        _uiState.update { it.copy(loopRally = !it.loopRally) }
    }

    private fun markRangeStart() {
        val t = _uiState.value.playheadSec
        val out = _uiState.value.rangeMarkOutSec
        if (out != null && out > t + 0.3) {
            val duration = _uiState.value.source?.durationSec ?: return
            pushUndo()
            val rally = Rally(
                id = System.currentTimeMillis(),
                startSec = t,
                endSec = out,
                confidence = 1.0,
            ).clamp(duration)
            _uiState.update { state ->
                val next = (state.rallies + rally).sortedBy { it.startSec }
                state.copy(
                    rallies = next,
                    selectedRallyIndex = next.indexOfFirst { it.id == rally.id },
                    rangeMarkInSec = null,
                    rangeMarkOutSec = null,
                )
            }
        } else {
            _uiState.update { it.copy(rangeMarkInSec = t, rangeMarkOutSec = null) }
        }
    }

    private fun markRangeEnd() {
        val t = _uiState.value.playheadSec
        val markIn = _uiState.value.rangeMarkInSec
        if (markIn == null) {
            _uiState.update { it.copy(rangeMarkOutSec = t) }
            return
        }
        if (t <= markIn + 0.3) {
            _uiState.update { it.copy(errorMessage = "出点需晚于入点至少 0.3 秒") }
            return
        }
        val duration = _uiState.value.source?.durationSec ?: return
        pushUndo()
        val rally = Rally(
            id = System.currentTimeMillis(),
            startSec = markIn,
            endSec = t,
            confidence = 1.0,
        ).clamp(duration)
        _uiState.update { state ->
            val next = (state.rallies + rally).sortedBy { it.startSec }
            state.copy(
                rallies = next,
                selectedRallyIndex = next.indexOfFirst { it.id == rally.id },
                rangeMarkInSec = null,
                rangeMarkOutSec = null,
            )
        }
    }

    private fun pausePlayback() {
        playbackToken += 1L
        _uiState.update {
            it.copy(
                isPlaying = false,
                playback = PlaybackCommand(
                    token = playbackToken,
                    seekSec = null,
                    autoPlay = false,
                ),
            )
        }
    }

    private fun playSelected() {
        val rally = _uiState.value.selectedRally ?: return
        playbackToken += 1L
        val currentPlayhead = _uiState.value.playheadSec
        val startFrom = if (currentPlayhead >= rally.startSec && currentPlayhead < rally.endSec - 0.2) {
            currentPlayhead
        } else {
            rally.startSec
        }
        _uiState.update {
            it.copy(
                playheadSec = startFrom,
                playback = PlaybackCommand(
                    token = playbackToken,
                    seekSec = startFrom,
                    playUntilSec = rally.endSec,
                    autoPlay = true,
                ),
            )
        }
    }

    private fun selectRally(index: Int?) {
        if (index == null || index !in _uiState.value.rallies.indices) {
            _uiState.update { it.copy(selectedRallyIndex = null) }
            return
        }
        val rally = _uiState.value.rallies[index]
        playbackToken += 1L
        _uiState.update {
            it.copy(
                selectedRallyIndex = index,
                playheadSec = rally.startSec,
                playback = PlaybackCommand(
                    token = playbackToken,
                    seekSec = rally.startSec,
                    autoPlay = false,
                ),
            )
        }
    }

    private fun selectRelative(delta: Int) {
        val state = _uiState.value
        if (state.rallies.isEmpty()) return
        val current = state.selectedRallyIndex ?: if (delta > 0) -1 else state.rallies.size
        val next = (current + delta).coerceIn(0, state.rallies.lastIndex)
        selectRally(next)
    }

    private fun updateRoiPoint(index: Int, x: Float, y: Float) {
        if (index !in 0..3) return
        _uiState.update { state ->
            val pts = state.courtRoi.points.toMutableList()
            pts[index] = x.coerceIn(0f, 1f) to y.coerceIn(0f, 1f)
            state.copy(courtRoi = CourtRoi(pts))
        }
    }

    private fun export() {
        val source = _uiState.value.source ?: return
        val rallies = _uiState.value.rallies
        if (rallies.isEmpty() || _uiState.value.isExporting) return
        val token = sessionToken
        exportJob?.cancel()
        exportJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isExporting = true,
                    errorMessage = null,
                    exportPath = null,
                    exportGalleryName = null,
                    shareRequested = false,
                )
            }
            try {
                val app = getApplication<Application>()
                val file = exporter.exportMerged(app, source, rallies)
                if (token != sessionToken) return@launch
                val displayName = "xiaobai_${System.currentTimeMillis()}.mp4"
                val galleryName = try {
                    GallerySaver.saveVideoToGallery(app, file, displayName).displayName
                } catch (galleryError: Exception) {
                    if (galleryError is kotlinx.coroutines.CancellationException) throw galleryError
                    null
                }
                if (token != sessionToken) return@launch
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportPath = file.absolutePath,
                        exportGalleryName = galleryName,
                        shareRequested = true,
                    )
                }
            } catch (error: Exception) {
                if (token != sessionToken) return@launch
                if (error is kotlinx.coroutines.CancellationException) throw error
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        errorMessage = error.message ?: "导出失败",
                    )
                }
            }
        }
    }
}
