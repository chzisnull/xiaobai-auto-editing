package icu.yuqiuyijiaren.xiaobai.ui.screens

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import icu.yuqiuyijiaren.xiaobai.EditorViewModel
import icu.yuqiuyijiaren.xiaobai.domain.EditorEvent
import icu.yuqiuyijiaren.xiaobai.domain.EditorUiState
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import icu.yuqiuyijiaren.xiaobai.ui.components.CourtRoiOverlay
import icu.yuqiuyijiaren.xiaobai.ui.components.EditorTopBar
import icu.yuqiuyijiaren.xiaobai.ui.components.LocalVideoPlayer
import icu.yuqiuyijiaren.xiaobai.ui.components.RallyInspector
import icu.yuqiuyijiaren.xiaobai.ui.components.ZoomableRallyTimeline
import icu.yuqiuyijiaren.xiaobai.ui.theme.Amber
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Canvas
import icu.yuqiuyijiaren.xiaobai.ui.theme.Ink
import icu.yuqiuyijiaren.xiaobai.ui.theme.Muted
import java.io.File
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun handlePickedUri(uri: Uri?) {
        if (uri == null) return
        // Persist read access when possible (SAF / OpenDocument)
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val source = readVideoSource(context, uri)
        if (source == null) {
            scope.launch {
                snackbar.showSnackbar("无法读取该视频（时长或权限异常）")
            }
        } else {
            viewModel.onEvent(EditorEvent.VideoPicked(source))
        }
    }

    // System photo picker — sometimes empty on emulators after adb push
    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> handlePickedUri(uri) }

    // SAF document picker — can open Download / Movies reliably for test clips
    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> handlePickedUri(uri) }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (!msg.isNullOrBlank()) {
            snackbar.showSnackbar(msg)
            viewModel.onEvent(EditorEvent.ClearError)
        }
    }

    LaunchedEffect(state.exportPath, state.shareRequested, state.exportGalleryName) {
        val path = state.exportPath
        if (path.isNullOrBlank()) return@LaunchedEffect
        if (state.shareRequested) {
            val galleryLabel = state.exportGalleryName
            val savedMsg = if (!galleryLabel.isNullOrBlank()) {
                "已保存到相册 Movies/Xiaobai/$galleryLabel"
            } else {
                "合并完成；相册写入失败，可从分享面板另存"
            }
            snackbar.showSnackbar(savedMsg)
            // Optional share sheet after local save
            shareExportedVideo(context, path)
            viewModel.onEvent(EditorEvent.ClearExportPath)
        }
    }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            EditorTopBar(
                deviceSummary = state.deviceSummary,
                capabilityLine = state.deviceCapabilityLine,
                selectedTier = state.analysisTier,
                recommendedTier = state.recommendedTier,
                configSummary = state.analysisConfigSummary,
                canUndo = state.canUndo,
                roiActive = state.showRoiEditor,
                roiEnabled = state.source != null,
                analyzing = state.isAnalyzing,
                onSelectTier = { viewModel.onEvent(EditorEvent.SetAnalysisTier(it)) },
                onUndo = { viewModel.onEvent(EditorEvent.Undo) },
                onToggleRoi = {
                    if (state.showRoiEditor) viewModel.onEvent(EditorEvent.HideRoiEditor)
                    else viewModel.onEvent(EditorEvent.ShowRoiEditor)
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Export must live in bottomBar so it is never clipped by the middle Column.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xF2F2F2F7)),
            ) {
                ExportButton(
                    state = state,
                    viewModel = viewModel,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                )
                TimelineBar(state, viewModel)
            }
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val landscape = maxWidth > maxHeight
            val onPickGallery: () -> Unit = {
                galleryPicker.launch(
                    androidx.activity.result.PickVisualMediaRequest(
                        ActivityResultContracts.PickVisualMedia.VideoOnly,
                    ),
                )
            }
            val onPickFile: () -> Unit = {
                filePicker.launch(arrayOf("video/*", "video/mp4", "video/3gpp", "video/webm"))
            }
            if (landscape) {
                LandscapeBody(
                    state = state,
                    viewModel = viewModel,
                    onPickGallery = onPickGallery,
                    onPickFile = onPickFile,
                )
            } else {
                PortraitBody(
                    state = state,
                    viewModel = viewModel,
                    onPickGallery = onPickGallery,
                    onPickFile = onPickFile,
                )
            }
        }
    }
}

@Composable
private fun TimelineBar(state: EditorUiState, viewModel: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xF2F2F2F7))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("回合时间轴", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 13.sp)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                MetricChip("原片", formatClock(state.source?.durationSec ?: 0.0))
                MetricChip("成片", formatClock(state.selectedDurationSec))
                MetricChip("精简", "${state.reductionPercent}%")
            }
        }
        ZoomableRallyTimeline(
            durationSec = state.source?.durationSec ?: 0.0,
            rallies = state.rallies,
            playheadSec = state.playheadSec,
            selectedIndex = state.selectedRallyIndex,
            rangeMarkInSec = state.rangeMarkInSec,
            rangeMarkOutSec = state.rangeMarkOutSec,
            onSeek = { viewModel.onEvent(EditorEvent.SeekTo(it)) },
            onSelect = { viewModel.onEvent(EditorEvent.SelectRally(it)) },
            onUpdateRally = { index, start, end ->
                viewModel.onEvent(EditorEvent.UpdateRally(index, start, end))
            },
            onGestureStart = { viewModel.beginGestureUndo() },
            onGestureEnd = { viewModel.endGestureUndo() },
            compact = true,
        )
    }
}

@Composable
private fun PortraitBody(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
) {
    // Full-page scroll so every control is reachable; export stays in bottomBar.
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item { ToolbarRow(state, viewModel, onPickGallery, onPickFile) }
        item { VideoCard(state, viewModel) }
        if (state.isAnalyzing ||
            (state.analysis.message.isNotBlank() &&
                state.analysis.phase != icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase.Idle)
        ) {
            item { AnalysisCard(state, viewModel) }
        }
        item {
            RallyInspector(
                rally = state.selectedRally,
                index = state.selectedRallyIndex,
                total = state.rallies.size,
                playheadSec = state.playheadSec,
                rangeMarkInSec = state.rangeMarkInSec,
                rangeMarkOutSec = state.rangeMarkOutSec,
                onPlaySelected = { viewModel.onEvent(EditorEvent.PlaySelected) },
                onPrev = { viewModel.onEvent(EditorEvent.SelectPrevious) },
                onNext = { viewModel.onEvent(EditorEvent.SelectNext) },
                onSetStartAtPlayhead = { viewModel.onEvent(EditorEvent.SetSelectedStartAtPlayhead) },
                onSetEndAtPlayhead = { viewModel.onEvent(EditorEvent.SetSelectedEndAtPlayhead) },
                onNudgeStart = { viewModel.onEvent(EditorEvent.NudgeSelectedEdge(startDelta = it)) },
                onNudgeEnd = { viewModel.onEvent(EditorEvent.NudgeSelectedEdge(endDelta = it)) },
                onNudgeBoth = { viewModel.onEvent(EditorEvent.NudgeSelected(it)) },
                onMarkIn = { viewModel.onEvent(EditorEvent.MarkRangeStartAtPlayhead) },
                onMarkOut = { viewModel.onEvent(EditorEvent.MarkRangeEndAtPlayhead) },
                onClearMarks = { viewModel.onEvent(EditorEvent.ClearRangeMarks) },
                onSplit = { viewModel.onEvent(EditorEvent.SplitSelectedAtPlayhead) },
                onMergeNext = { viewModel.onEvent(EditorEvent.MergeSelectedWithNext) },
                onDelete = {
                    state.selectedRallyIndex?.let { viewModel.onEvent(EditorEvent.DeleteRally(it)) }
                },
                canMergeNext = state.selectedRallyIndex != null &&
                    (state.selectedRallyIndex ?: 0) < state.rallies.lastIndex,
            )
        }
        item { RallyListHeader(state, viewModel) }
        if (state.rallies.isEmpty()) {
            item {
                Text("暂无回合，请识别或用入点/出点新建", color = Muted, fontSize = 13.sp)
            }
        } else {
            itemsIndexed(state.rallies) { index, rally ->
                RallyListItem(index, rally, state.selectedRallyIndex == index, viewModel)
            }
        }
    }
}

@Composable
private fun LandscapeBody(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(
            modifier = Modifier
                .weight(0.58f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolbarRow(state, viewModel, onPickGallery, onPickFile)
            Box(modifier = Modifier.weight(1f)) {
                VideoCard(state, viewModel, fillHeight = true)
            }
            if (state.isAnalyzing ||
                (state.analysis.message.isNotBlank() &&
                    state.analysis.phase != icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase.Idle)
            ) {
                AnalysisCard(state, viewModel)
            }
            RallyInspector(
                rally = state.selectedRally,
                index = state.selectedRallyIndex,
                total = state.rallies.size,
                playheadSec = state.playheadSec,
                rangeMarkInSec = state.rangeMarkInSec,
                rangeMarkOutSec = state.rangeMarkOutSec,
                onPlaySelected = { viewModel.onEvent(EditorEvent.PlaySelected) },
                onPrev = { viewModel.onEvent(EditorEvent.SelectPrevious) },
                onNext = { viewModel.onEvent(EditorEvent.SelectNext) },
                onSetStartAtPlayhead = { viewModel.onEvent(EditorEvent.SetSelectedStartAtPlayhead) },
                onSetEndAtPlayhead = { viewModel.onEvent(EditorEvent.SetSelectedEndAtPlayhead) },
                onNudgeStart = { viewModel.onEvent(EditorEvent.NudgeSelectedEdge(startDelta = it)) },
                onNudgeEnd = { viewModel.onEvent(EditorEvent.NudgeSelectedEdge(endDelta = it)) },
                onNudgeBoth = { viewModel.onEvent(EditorEvent.NudgeSelected(it)) },
                onMarkIn = { viewModel.onEvent(EditorEvent.MarkRangeStartAtPlayhead) },
                onMarkOut = { viewModel.onEvent(EditorEvent.MarkRangeEndAtPlayhead) },
                onClearMarks = { viewModel.onEvent(EditorEvent.ClearRangeMarks) },
                onSplit = { viewModel.onEvent(EditorEvent.SplitSelectedAtPlayhead) },
                onMergeNext = { viewModel.onEvent(EditorEvent.MergeSelectedWithNext) },
                onDelete = {
                    state.selectedRallyIndex?.let { viewModel.onEvent(EditorEvent.DeleteRally(it)) }
                },
                canMergeNext = state.selectedRallyIndex != null &&
                    (state.selectedRallyIndex ?: 0) < state.rallies.lastIndex,
            )
        }
        Column(
            modifier = Modifier
                .weight(0.42f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            RallyListHeader(state, viewModel)
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                state.rallies.forEachIndexed { index, rally ->
                    RallyListItem(index, rally, state.selectedRallyIndex == index, viewModel)
                }
            }
            ExportButton(state, viewModel)
        }
    }
}

@Composable
private fun ToolbarRow(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickGallery: () -> Unit,
    onPickFile: () -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = onPickFile,
            colors = ButtonDefaults.buttonColors(containerColor = Blue),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Default.FolderOpen, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text("选文件")
        }
        OutlinedButton(
            onClick = onPickGallery,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("相册")
        }
        Button(
            onClick = { viewModel.onEvent(EditorEvent.StartAnalysis) },
            enabled = state.source != null && !state.isAnalyzing,
            colors = ButtonDefaults.buttonColors(containerColor = Blue),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Default.AutoAwesome, contentDescription = null)
            Spacer(Modifier.width(6.dp))
            Text(if (state.isAnalyzing) "分析中" else "开始识别")
        }
        if (state.isAnalyzing) {
            OutlinedButton(
                onClick = { viewModel.onEvent(EditorEvent.CancelAnalysis) },
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(4.dp))
                Text("取消")
            }
        }
        if (state.showRoiEditor) {
            OutlinedButton(
                onClick = { viewModel.onEvent(EditorEvent.ResetCourtRoi) },
                shape = RoundedCornerShape(10.dp),
            ) { Text("重置ROI") }
            OutlinedButton(
                onClick = { viewModel.onEvent(EditorEvent.HideRoiEditor) },
                shape = RoundedCornerShape(10.dp),
            ) { Text("完成标定") }
        }
    }
}

@Composable
private fun VideoCard(state: EditorUiState, viewModel: EditorViewModel, fillHeight: Boolean = false) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f)),
        shape = RoundedCornerShape(14.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier)
            .border(1.dp, Color(0x28767680), RoundedCornerShape(14.dp)),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            if (state.source == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(200.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF111113)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("从相册选择羽毛球比赛视频", color = Color(0xFFAEAEB2))
                }
            } else {
                Box {
                    LocalVideoPlayer(
                        source = state.source,
                        playheadSec = state.playheadSec,
                        onPlayheadChange = viewModel::updatePlayhead,
                        playback = state.playback,
                        onPlaybackConsumed = { viewModel.onEvent(EditorEvent.ClearPlaybackCommand) },
                        fillHeight = fillHeight,
                        modifier = if (fillHeight) Modifier.fillMaxSize() else Modifier,
                    )
                    if (state.showRoiEditor) {
                        CourtRoiOverlay(
                            roi = state.courtRoi,
                            onPointMove = { i, x, y ->
                                viewModel.onEvent(EditorEvent.UpdateCourtRoiPoint(i, x, y))
                            },
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "${state.source!!.displayName} · ${formatClock(state.source!!.durationSec)}",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

@Composable
private fun AnalysisCard(state: EditorUiState, viewModel: EditorViewModel) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0x14007AFF)),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (state.isAnalyzing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp,
                        color = Blue,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Text(
                    state.analysis.message.ifBlank { "准备中" },
                    color = Ink,
                    fontSize = 13.sp,
                    maxLines = 2,
                )
            }
            if (state.isAnalyzing) {
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { state.analysis.percent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Blue,
                    trackColor = Color(0x22007AFF),
                )
            }
        }
    }
}

@Composable
private fun RallyListHeader(state: EditorUiState, viewModel: EditorViewModel) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("回合列表 · ${state.rallies.size}", fontWeight = FontWeight.SemiBold, color = Ink)
        IconButton(
            onClick = { viewModel.onEvent(EditorEvent.AddRallyAt(state.playheadSec)) },
            enabled = state.source != null,
        ) {
            Icon(Icons.Default.Add, contentDescription = "在播头添加", tint = Blue)
        }
    }
}

@Composable
private fun RallyListItem(
    index: Int,
    rally: icu.yuqiuyijiaren.xiaobai.domain.Rally,
    selected: Boolean,
    viewModel: EditorViewModel,
) {
    Card(
        onClick = { viewModel.onEvent(EditorEvent.SelectRally(index)) },
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0x1A007AFF) else Color.White.copy(alpha = 0.9f),
        ),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(if (rally.confidence < 0.72) Amber else Blue),
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "%02d  %s - %s".format(
                        index + 1,
                        formatClock(rally.startSec),
                        formatClock(rally.endSec),
                    ),
                    color = Ink,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    "%.1fs · 置信度 %d%%".format(
                        rally.durationSec,
                        (rally.confidence * 100).roundToInt(),
                    ),
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
            IconButton(onClick = {
                viewModel.onEvent(EditorEvent.SelectRally(index))
                viewModel.onEvent(EditorEvent.PlaySelected)
            }) {
                Icon(Icons.Default.PlayArrow, contentDescription = "播放本段", tint = Blue)
            }
        }
    }
}

@Composable
private fun ExportButton(
    state: EditorUiState,
    viewModel: EditorViewModel,
    modifier: Modifier = Modifier,
) {
    Button(
        onClick = { viewModel.onEvent(EditorEvent.Export) },
        enabled = state.rallies.isNotEmpty() && !state.isExporting && !state.isAnalyzing,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Blue),
    ) {
        if (state.isExporting) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                color = Color.White,
                strokeWidth = 2.dp,
            )
            Spacer(Modifier.width(8.dp))
            Text("正在导出并保存…")
        } else {
            Icon(Icons.Default.IosShare, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text(
                if (state.rallies.isEmpty()) {
                    "导出合并视频到相册"
                } else {
                    "导出到相册 · ${state.rallies.size} 段"
                },
            )
        }
    }
}

@Composable
private fun MetricChip(label: String, value: String) {
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Color.White.copy(alpha = 0.85f))
            .border(1.dp, Color(0x1F767680), RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(value, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
    }
}

private fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}

private fun shareExportedVideo(context: android.content.Context, path: String): Boolean {
    return try {
        val file = File(path)
        if (!file.exists()) return false
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri("video", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "分享羽毛球集锦"))
        true
    } catch (_: Exception) {
        false
    }
}

private fun readVideoSource(context: android.content.Context, uri: Uri): VideoSource? {
    return try {
        var name = "video.mp4"
        context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (cursor.moveToFirst() && index >= 0) {
                name = cursor.getString(index) ?: name
            }
        }
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
                ?: 0L
            if (durationMs <= 0L) return null
            VideoSource(
                uriString = uri.toString(),
                displayName = name,
                durationMs = durationMs,
            )
        } finally {
            retriever.release()
        }
    } catch (_: Exception) {
        null
    }
}
