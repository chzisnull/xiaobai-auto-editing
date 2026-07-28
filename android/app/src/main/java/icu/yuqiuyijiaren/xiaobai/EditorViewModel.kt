package icu.yuqiuyijiaren.xiaobai

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import icu.yuqiuyijiaren.xiaobai.analysis.AnalysisUpdate
import icu.yuqiuyijiaren.xiaobai.analysis.HeuristicOnDeviceAnalyzer
import icu.yuqiuyijiaren.xiaobai.analysis.RallyAnalyzer
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisProgress
import icu.yuqiuyijiaren.xiaobai.domain.EditorEvent
import icu.yuqiuyijiaren.xiaobai.domain.EditorUiState
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.export.LocalExporter
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class EditorViewModel(
    application: Application,
    private val analyzer: RallyAnalyzer = HeuristicOnDeviceAnalyzer(),
    private val exporter: LocalExporter = LocalExporter(),
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EditorUiState())
    val uiState: StateFlow<EditorUiState> = _uiState.asStateFlow()

    private var analysisJob: Job? = null

    fun onEvent(event: EditorEvent) {
        when (event) {
            EditorEvent.PickVideo -> Unit // handled in UI
            is EditorEvent.VideoPicked -> {
                analysisJob?.cancel()
                _uiState.value = EditorUiState(
                    source = event.source,
                    playheadSec = 0.0,
                    analysis = AnalysisProgress(phase = AnalysisPhase.Idle),
                )
            }
            EditorEvent.StartAnalysis -> startAnalysis()
            is EditorEvent.SelectRally -> _uiState.update { it.copy(selectedRallyIndex = event.index) }
            is EditorEvent.UpdateRally -> updateRally(event.index, event.startSec, event.endSec)
            is EditorEvent.DeleteRally -> deleteRally(event.index)
            is EditorEvent.AddRallyAt -> addRally(event.timeSec)
            is EditorEvent.SeekTo -> _uiState.update { it.copy(playheadSec = event.timeSec) }
            EditorEvent.Export -> export()
            EditorEvent.ClearError -> _uiState.update { it.copy(errorMessage = null) }
        }
    }

    fun updatePlayhead(timeSec: Double) {
        _uiState.update { it.copy(playheadSec = timeSec.coerceAtLeast(0.0)) }
    }

    private fun startAnalysis() {
        val source = _uiState.value.source ?: return
        if (_uiState.value.isAnalyzing) return
        analysisJob?.cancel()
        analysisJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    rallies = emptyList(),
                    selectedRallyIndex = null,
                    exportPath = null,
                    errorMessage = null,
                    analysis = AnalysisProgress(
                        phase = AnalysisPhase.Preparing,
                        percent = 0.02f,
                        message = "准备端侧分析",
                    ),
                )
            }
            analyzer.analyze(getApplication(), source).collect { update ->
                when (update) {
                    is AnalysisUpdate.Progress -> _uiState.update { it.copy(analysis = update.progress) }
                    is AnalysisUpdate.Result -> _uiState.update {
                        it.copy(
                            rallies = update.rallies,
                            analysis = AnalysisProgress(
                                phase = AnalysisPhase.Done,
                                percent = 1f,
                                message = if (update.rallies.isEmpty()) {
                                    "未识别到有效回合，可手动添加"
                                } else {
                                    "识别完成 · ${update.rallies.size} 个回合"
                                },
                            ),
                            selectedRallyIndex = update.rallies.indices.firstOrNull(),
                        )
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
        _uiState.update { state ->
            if (index !in state.rallies.indices) return@update state
            val next = state.rallies.toMutableList()
            next[index] = next[index].copy(startSec = startSec, endSec = endSec).clamp(duration)
            state.copy(rallies = next)
        }
    }

    private fun deleteRally(index: Int) {
        _uiState.update { state ->
            if (index !in state.rallies.indices) return@update state
            val next = state.rallies.toMutableList().also { it.removeAt(index) }
            val selected = when {
                next.isEmpty() -> null
                state.selectedRallyIndex == null -> null
                state.selectedRallyIndex!! >= next.size -> next.lastIndex
                else -> state.selectedRallyIndex
            }
            state.copy(rallies = next, selectedRallyIndex = selected)
        }
    }

    private fun addRally(timeSec: Double) {
        val duration = _uiState.value.source?.durationSec ?: return
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
            it.copy(rallies = next, selectedRallyIndex = next.indexOf(rally))
        }
    }

    private fun export() {
        val source = _uiState.value.source ?: return
        val rallies = _uiState.value.rallies
        if (rallies.isEmpty() || _uiState.value.isExporting) return
        viewModelScope.launch {
            _uiState.update { it.copy(isExporting = true, errorMessage = null, exportPath = null) }
            try {
                val file = exporter.exportMerged(getApplication(), source, rallies)
                _uiState.update {
                    it.copy(
                        isExporting = false,
                        exportPath = file.absolutePath,
                    )
                }
            } catch (error: Exception) {
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
