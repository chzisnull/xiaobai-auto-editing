package icu.yuqiuyijiaren.xiaobai.ui.screens

import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.Flag
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.SportsTennis
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import icu.yuqiuyijiaren.xiaobai.EditorViewModel
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisPhase
import icu.yuqiuyijiaren.xiaobai.domain.EditorEvent
import icu.yuqiuyijiaren.xiaobai.domain.EditorUiState
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.domain.ReviewStatus
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import icu.yuqiuyijiaren.xiaobai.ui.components.CourtRoiOverlay
import icu.yuqiuyijiaren.xiaobai.ui.components.EditorTopBar
import icu.yuqiuyijiaren.xiaobai.ui.components.LocalVideoPlayer
import icu.yuqiuyijiaren.xiaobai.ui.components.ZoomableRallyTimeline
import icu.yuqiuyijiaren.xiaobai.ui.theme.Amber
import icu.yuqiuyijiaren.xiaobai.ui.theme.BorderSubtle
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Canvas
import icu.yuqiuyijiaren.xiaobai.ui.theme.Green
import icu.yuqiuyijiaren.xiaobai.ui.theme.Ink
import icu.yuqiuyijiaren.xiaobai.ui.theme.Muted
import icu.yuqiuyijiaren.xiaobai.ui.theme.Red
import icu.yuqiuyijiaren.xiaobai.ui.theme.StudioElevated
import icu.yuqiuyijiaren.xiaobai.ui.theme.SurfaceGlass
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var showRalliesSheet by remember { mutableStateOf(false) }

    fun handlePickedUri(uri: Uri?) {
        if (uri == null) return
        runCatching {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        val source = readVideoSource(context, uri)
        if (source == null) {
            scope.launch { snackbar.showSnackbar("无法读取该视频（时长或权限异常）") }
        } else {
            viewModel.onEvent(EditorEvent.VideoPicked(source))
        }
    }

    val galleryPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> handlePickedUri(uri) }

    val filePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri -> handlePickedUri(uri) }

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

    val onLoadSample: () -> Unit = {
        val candidates = listOf(
            File(context.getExternalFilesDir(null), "badminton_match.mp4"),
            File("/sdcard/Android/data/icu.yuqiuyijiaren.xiaobai.debug/files/badminton_match.mp4"),
            File("/sdcard/Download/badminton_match.mp4"),
            File("/sdcard/Movies/badminton_match.mp4"),
            File("/sdcard/Download/badminton_sample.mp4"),
        )
        val rawFile = candidates.firstOrNull { it.exists() && it.canRead() }
            ?: candidates.firstOrNull { it.exists() }
        if (rawFile != null) {
            val targetFile = File(context.getExternalFilesDir(null) ?: context.filesDir, rawFile.name).let { dest ->
                if (!dest.exists() || dest.length() != rawFile.length()) {
                    try { rawFile.copyTo(dest, overwrite = true) } catch (_: Exception) {}
                }
                if (dest.exists() && dest.canRead()) dest else rawFile
            }
            val sampleUri = Uri.fromFile(targetFile)
            val source = readVideoSource(context, sampleUri) ?: VideoSource(
                uriString = sampleUri.toString(),
                displayName = targetFile.name,
                durationMs = 451033L,
            )
            viewModel.onEvent(EditorEvent.VideoPicked(source))
            scope.launch { snackbar.showSnackbar("已载入测试比赛视频（${formatClock(source.durationSec)}）") }
        } else {
            scope.launch { snackbar.showSnackbar("请点击「选文件」或「相册」导入比赛视频") }
        }
    }

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
            shareExportedVideo(context, path)
            viewModel.onEvent(EditorEvent.ClearExportPath)
        }
    }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            EditorTopBar(
                tier = state.analysisTier,
                canUndo = state.canUndo,
                roiActive = state.showRoiEditor,
                roiEnabled = state.source != null,
                onUndo = { viewModel.onEvent(EditorEvent.Undo) },
                onToggleRoi = {
                    if (state.showRoiEditor) viewModel.onEvent(EditorEvent.HideRoiEditor)
                    else viewModel.onEvent(EditorEvent.ShowRoiEditor)
                },
                onPickFile = onPickFile,
                onOpenTierSelector = {
                    // Cycle through tiers: Fast -> Standard -> Precise -> Fast
                    val nextTier = when (state.analysisTier) {
                        icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier.Fast -> icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier.Standard
                        icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier.Standard -> icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier.Precise
                        icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier.Precise -> icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier.Fast
                    }
                    viewModel.onEvent(EditorEvent.SetAnalysisTier(nextTier))
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            // Sticky CapCut-style Thumb Action Zone
            StickyThumbActionZone(
                state = state,
                viewModel = viewModel,
                onOpenRalliesSheet = { showRalliesSheet = true },
            )
        },
    ) { padding ->
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            val landscape = maxWidth > maxHeight
            if (landscape) {
                LandscapeLayout(
                    state = state,
                    viewModel = viewModel,
                    onPickFile = onPickFile,
                    onPickGallery = onPickGallery,
                    onLoadSample = onLoadSample,
                )
            } else {
                PortraitLayout(
                    state = state,
                    viewModel = viewModel,
                    onPickFile = onPickFile,
                    onPickGallery = onPickGallery,
                    onLoadSample = onLoadSample,
                )
            }
        }
    }

    // Modal Bottom Sheet for Rallies List
    if (showRalliesSheet) {
        ModalBottomSheet(
            onDismissRequest = { showRalliesSheet = false },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = SurfaceGlass,
            dragHandle = {
                Box(
                    modifier = Modifier
                        .padding(top = 10.dp, bottom = 6.dp)
                        .width(36.dp)
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0x44FFFFFF)),
                )
            },
        ) {
            RalliesBottomSheetContent(
                state = state,
                viewModel = viewModel,
                onClose = { showRalliesSheet = false },
            )
        }
    }
}

/**
 * Portrait Layout:
 * 1. Video Player Studio Container
 * 2. Match Metrics & AI Analysis Bar
 * 3. Precision Zoomable Timeline
 * 4. Fast Rally Trim Strip
 */
@Composable
private fun PortraitLayout(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickFile: () -> Unit,
    onPickGallery: () -> Unit,
    onLoadSample: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // 1. Video Container
        VideoPlayerSection(
            state = state,
            viewModel = viewModel,
            onPickFile = onPickFile,
            onPickGallery = onPickGallery,
            onLoadSample = onLoadSample,
        )

        // 2. Metrics & AI Analysis Banner
        StatusAndAnalysisBanner(state = state, viewModel = viewModel)

        // 3. Precision Timeline
        TimelineSection(state = state, viewModel = viewModel)

        // 4. Quick Rally Switcher Strip (1-tap fast switching between all rallies)
        if (state.rallies.isNotEmpty()) {
            RallyQuickSwitchStrip(
                rallies = state.rallies,
                selectedIndex = state.selectedRallyIndex,
                onSelectRally = { viewModel.onEvent(EditorEvent.SelectRally(it)) },
                onSelectPrevious = { viewModel.onEvent(EditorEvent.SelectPrevious) },
                onSelectNext = { viewModel.onEvent(EditorEvent.SelectNext) },
            )
        }

        // 5. Fast Rally Trim Zone (directly accessible above sticky bottom bar)
        FastRallyTrimZone(state = state, viewModel = viewModel)
    }
}

/**
 * Landscape Layout: Left video player, right timeline and quick trim
 */
@Composable
private fun LandscapeLayout(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickFile: () -> Unit,
    onPickGallery: () -> Unit,
    onLoadSample: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight(),
        ) {
            VideoPlayerSection(
                state = state,
                viewModel = viewModel,
                onPickFile = onPickFile,
                onPickGallery = onPickGallery,
                onLoadSample = onLoadSample,
                fillHeight = true,
            )
        }

        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            StatusAndAnalysisBanner(state = state, viewModel = viewModel)
            TimelineSection(state = state, viewModel = viewModel)
            if (state.rallies.isNotEmpty()) {
                RallyQuickSwitchStrip(
                    rallies = state.rallies,
                    selectedIndex = state.selectedRallyIndex,
                    onSelectRally = { viewModel.onEvent(EditorEvent.SelectRally(it)) },
                    onSelectPrevious = { viewModel.onEvent(EditorEvent.SelectPrevious) },
                    onSelectNext = { viewModel.onEvent(EditorEvent.SelectNext) },
                )
            }
            FastRallyTrimZone(state = state, viewModel = viewModel)
        }
    }
}

/**
 * Video Player Section: Contains Custom VideoPlayer or Sleek Empty State
 */
@Composable
private fun VideoPlayerSection(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onPickFile: () -> Unit,
    onPickGallery: () -> Unit,
    onLoadSample: () -> Unit,
    fillHeight: Boolean = false,
) {
    if (state.source == null) {
        // Empty State Dark Studio Card
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.height(200.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(SurfaceGlass)
                .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(16.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(StudioElevated),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Blue,
                        modifier = Modifier.size(26.dp),
                    )
                }

                Text(
                    text = "导入羽毛球比赛视频开始智能剪辑",
                    color = Ink,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                )

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = onPickFile,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("选文件", fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = onPickGallery,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, BorderSubtle),
                    ) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = Ink, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("相册", color = Ink, fontSize = 13.sp)
                    }

                    OutlinedButton(
                        onClick = onLoadSample,
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Blue.copy(alpha = 0.5f)),
                    ) {
                        Text("测试视频", color = Blue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.fillMaxHeight() else Modifier.aspectRatio(16f / 9f)),
        ) {
            LocalVideoPlayer(
                source = state.source,
                playheadSec = state.playheadSec,
                onPlayheadChange = viewModel::updatePlayhead,
                onIsPlayingChange = { viewModel.onEvent(EditorEvent.SetIsPlaying(it)) },
                playback = state.playback,
                onPlaybackConsumed = { viewModel.onEvent(EditorEvent.ClearPlaybackCommand) },
                rallies = state.rallies,
                selectedRally = state.selectedRally,
                smartSkip = state.smartSkip,
                onToggleSmartSkip = { viewModel.onEvent(EditorEvent.ToggleSmartSkip) },
                loopRally = state.loopRally,
                onToggleLoop = { viewModel.onEvent(EditorEvent.ToggleLoopRally) },
                fillHeight = fillHeight,
                modifier = Modifier.fillMaxSize(),
            )

            if (state.showRoiEditor) {
                CourtRoiOverlay(
                    roi = state.courtRoi,
                    onPointMove = { i, x, y ->
                        viewModel.onEvent(EditorEvent.UpdateCourtRoiPoint(i, x, y))
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

/**
 * Match Metrics & AI Status Strip
 */
@Composable
private fun StatusAndAnalysisBanner(state: EditorUiState, viewModel: EditorViewModel) {
    if (state.isAnalyzing) {
        // AI Analyzing Active Banner
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Blue.copy(alpha = 0.12f))
                .border(1.dp, Blue.copy(alpha = 0.35f), RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Blue,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = state.analysis.message.ifBlank { "AI正在分析比赛回合..." },
                            color = Ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x33FFFFFF))
                            .clickable { viewModel.onEvent(EditorEvent.CancelAnalysis) }
                            .padding(horizontal = 8.dp, vertical = 2.dp),
                    ) {
                        Text("取消", color = Muted, fontSize = 11.sp)
                    }
                }

                LinearProgressIndicator(
                    progress = { state.analysis.percent.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth(),
                    color = Blue,
                    trackColor = Color(0x33007AFF),
                )
            }
        }
    } else if (state.source != null && state.rallies.isEmpty()) {
        // Direct Edit & AI Analysis Call to Action
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(SurfaceGlass)
                .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f, fill = false)) {
                    Text("准备剪辑", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text("免识别直接剪辑，或用 AI 智能识别", color = Muted, fontSize = 11.sp)
                }

                Spacer(Modifier.width(8.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 直接剪辑按钮 (无需识别)
                    OutlinedButton(
                        onClick = { viewModel.onEvent(EditorEvent.StartDirectEdit) },
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, tint = Ink, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("直接剪辑", color = Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }

                    // 智能识别按钮
                    Button(
                        onClick = { viewModel.onEvent(EditorEvent.StartAnalysis) },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("智能识别", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    } else if (state.rallies.isNotEmpty()) {
        // Metric Chips + AI Option
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MetricPill("原片", formatClock(state.source?.durationSec ?: 0.0), modifier = Modifier.weight(1f))
            MetricPill("成片", formatClock(state.selectedDurationSec), highlight = Green, modifier = Modifier.weight(1f))
            MetricPill("精简", "-${state.reductionPercent}%", highlight = Amber, modifier = Modifier.weight(1f))
            MetricPill("回合", "${state.rallies.size}段", modifier = Modifier.weight(1f))

            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1F007AFF))
                    .border(1.dp, Color(0x44007AFF), RoundedCornerShape(10.dp))
                    .clickable { viewModel.onEvent(EditorEvent.StartAnalysis) }
                    .padding(horizontal = 8.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = Blue, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("AI识别", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Precision Timeline Section
 */
@Composable
private fun TimelineSection(state: EditorUiState, viewModel: EditorViewModel) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "时间轴",
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    "播头 ${formatClock(state.playheadSec)}",
                    color = Blue,
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
            }

            if (state.rallies.size >= 2) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(StudioElevated)
                        .clickable { viewModel.onEvent(EditorEvent.MergeAdjacentRallies) }
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    Text("合并碎片", color = Blue, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Spacer(Modifier.height(6.dp))

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

/**
 * Horizontal Quick Rally Switcher Strip (CapCut / Premiere style)
 * Allows 1-tap fast switching between all rallies with auto-scroll and prev/next steppers.
 */
@Composable
private fun RallyQuickSwitchStrip(
    rallies: List<Rally>,
    selectedIndex: Int?,
    onSelectRally: (Int) -> Unit,
    onSelectPrevious: () -> Unit,
    onSelectNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    // Auto-scroll so selected rally is always centered in the strip
    LaunchedEffect(selectedIndex) {
        if (selectedIndex != null && selectedIndex in rallies.indices) {
            val target = (selectedIndex - 1).coerceAtLeast(0)
            listState.animateScrollToItem(target)
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(12.dp))
            .padding(horizontal = 6.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Prev button
        val canPrev = selectedIndex != null && selectedIndex > 0
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (canPrev) StudioElevated else Color(0x10FFFFFF))
                .clickable(enabled = canPrev) { onSelectPrevious() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ChevronLeft,
                contentDescription = "上一回合",
                tint = if (canPrev) Ink else Muted.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }

        Spacer(Modifier.width(6.dp))

        // Horizontal Carousel of Rally Pills
        LazyRow(
            state = listState,
            modifier = Modifier.weight(1f),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            itemsIndexed(rallies) { idx, r ->
                val isSelected = idx == selectedIndex
                val bg = if (isSelected) Blue.copy(alpha = 0.25f) else Color(0x1AFFFFFF)
                val borderCol = if (isSelected) Blue else Color(0x22FFFFFF)
                val textCol = if (isSelected) Color.White else Ink.copy(alpha = 0.85f)
                val dotCol = if (r.confidence >= 0.7) Green else Amber

                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(bg)
                        .border(1.dp, borderCol, RoundedCornerShape(8.dp))
                        .clickable { onSelectRally(idx) }
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(dotCol),
                    )
                    Spacer(Modifier.width(5.dp))
                    Text(
                        text = "#%02d".format(idx + 1),
                        color = if (isSelected) Blue else textCol,
                        fontSize = 11.sp,
                        fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "%.1fs".format(r.durationSec),
                        color = if (isSelected) Ink else Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }

        Spacer(Modifier.width(6.dp))

        // Next button
        val canNext = selectedIndex != null && selectedIndex < rallies.lastIndex
        Box(
            modifier = Modifier
                .size(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (canNext) StudioElevated else Color(0x10FFFFFF))
                .clickable(enabled = canNext) { onSelectNext() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "下一回合",
                tint = if (canNext) Ink else Muted.copy(alpha = 0.35f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/**
 * Fast Rally Trim Zone:
 * Provides non-restarting edge nudge buttons, Play Selected, and head/tail sets.
 */
@Composable
private fun FastRallyTrimZone(state: EditorUiState, viewModel: EditorViewModel) {
    val rally = state.selectedRally
    val index = state.selectedRallyIndex
    var dragAccumulator by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(14.dp))
            .pointerInput(state.rallies.size, index) {
                detectHorizontalDragGestures(
                    onDragStart = { dragAccumulator = 0f },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        dragAccumulator += dragAmount
                    },
                    onDragEnd = {
                        if (dragAccumulator > 50f) {
                            viewModel.onEvent(EditorEvent.SelectPrevious)
                        } else if (dragAccumulator < -50f) {
                            viewModel.onEvent(EditorEvent.SelectNext)
                        }
                        dragAccumulator = 0f
                    },
                )
            }
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        if (rally != null && index != null) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Header of selected rally with direct [◀] [▶] Steppers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // Prev Stepper
                        val canPrev = index > 0
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (canPrev) Color(0x33FFFFFF) else Color(0x0EFFFFFF))
                                .clickable(enabled = canPrev) {
                                    viewModel.onEvent(EditorEvent.SelectPrevious)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.ChevronLeft,
                                contentDescription = "上一回合",
                                tint = if (canPrev) Ink else Muted.copy(alpha = 0.3f),
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Round index indicator
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Blue.copy(alpha = 0.2f))
                                .border(1.dp, Blue.copy(alpha = 0.45f), RoundedCornerShape(6.dp))
                                .padding(horizontal = 6.dp, vertical = 2.dp),
                        ) {
                            Text(
                                "第 %02d / %02d 回合".format(index + 1, state.rallies.size),
                                color = Blue,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }

                        Spacer(Modifier.width(6.dp))

                        // Next Stepper
                        val canNext = index < state.rallies.lastIndex
                        Box(
                            modifier = Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(if (canNext) Color(0x33FFFFFF) else Color(0x0EFFFFFF))
                                .clickable(enabled = canNext) {
                                    viewModel.onEvent(EditorEvent.SelectNext)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                Icons.Default.ChevronRight,
                                contentDescription = "下一回合",
                                tint = if (canNext) Ink else Muted.copy(alpha = 0.3f),
                                modifier = Modifier.size(16.dp),
                            )
                        }

                        Spacer(Modifier.width(8.dp))

                        Text(
                            "%s - %s (%.1fs)".format(
                                formatClock(rally.startSec),
                                formatClock(rally.endSec),
                                rally.durationSec,
                            ),
                            color = Ink,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    Text(
                        "置信度 %d%%".format((rally.confidence * 100).roundToInt()),
                        color = Muted,
                        fontSize = 10.sp,
                    )
                }

                // Main Trim Control Bar: [起点 -0.5s / +0.5s]  [▶ 播本段]  [终点 -0.5s / +0.5s]
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Nudge Start
                    TrimStepButton("起点 -0.5s", modifier = Modifier.weight(1f)) {
                        viewModel.onEvent(EditorEvent.NudgeSelectedEdge(startDelta = -0.5))
                    }
                    TrimStepButton("起点 +0.5s", modifier = Modifier.weight(1f)) {
                        viewModel.onEvent(EditorEvent.NudgeSelectedEdge(startDelta = 0.5))
                    }

                    // Play / Pause this rally
                    val isPlaying = state.isPlaying
                    Button(
                        onClick = {
                            if (isPlaying) {
                                viewModel.onEvent(EditorEvent.PausePlayback)
                            } else {
                                viewModel.onEvent(EditorEvent.PlaySelected)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = if (isPlaying) Amber else Green),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp),
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "暂停" else "播放",
                            tint = Color.Black,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(2.dp))
                        Text(if (isPlaying) "暂停" else "播放", color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }

                    // Nudge End (Dynamically extends playback if currently playing!)
                    TrimStepButton("终点 -0.5s", modifier = Modifier.weight(1f)) {
                        viewModel.onEvent(EditorEvent.NudgeSelectedEdge(endDelta = -0.5))
                    }
                    TrimStepButton("终点 +0.5s", modifier = Modifier.weight(1f)) {
                        viewModel.onEvent(EditorEvent.NudgeSelectedEdge(endDelta = 0.5))
                    }
                }

                // Secondary Set at Playhead shortcuts
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    QuickActionButton("以播头设为起点", modifier = Modifier.weight(1f)) {
                        viewModel.onEvent(EditorEvent.SetSelectedStartAtPlayhead)
                    }
                    QuickActionButton("以播头设为终点", modifier = Modifier.weight(1f)) {
                        viewModel.onEvent(EditorEvent.SetSelectedEndAtPlayhead)
                    }
                }
            }
        } else if (state.rangeMarkInSec != null) {
            // Active marking zone
            val markIn = state.rangeMarkInSec
            val currentLen = (state.playheadSec - markIn).coerceAtLeast(0.0)
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF34C759)),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "正在标记回合: 起点 %s · 长度 %.1fs".format(formatClock(markIn), currentLen),
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Text(
                        text = "取消标记",
                        color = Red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { viewModel.onEvent(EditorEvent.ClearRangeMarks) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    // Play/Pause
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x22FFFFFF))
                            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                            .clickable { viewModel.onEvent(EditorEvent.TogglePlayPause) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            if (state.isPlaying) "暂停" else "播放",
                            color = Ink,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }

                    // Fast Forward +1.5s
                    Box(
                        modifier = Modifier
                            .weight(1.3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x2E34C759))
                            .border(1.dp, Color(0xFF34C759), RoundedCornerShape(8.dp))
                            .clickable { viewModel.onEvent(EditorEvent.FastForward(1.5)) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FastForward,
                                contentDescription = null,
                                tint = Color(0xFF34C759),
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "快进 1.5s",
                                color = Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    // Mark End
                    Box(
                        modifier = Modifier
                            .weight(1.3f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFF34C759))
                            .clickable { viewModel.onEvent(EditorEvent.MarkRangeEndAtPlayhead) }
                            .padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color.Black,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                "标结束",
                                color = Color.Black,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        } else if (state.source != null && state.rallies.isEmpty()) {
            // Direct editing entry point when empty
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "尚未划分回合，可导入整片直接剪辑，或用下方按钮标记",
                    color = Muted,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x22FFFFFF))
                        .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
                        .clickable { viewModel.onEvent(EditorEvent.StartDirectEdit) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.ContentCut, contentDescription = null, tint = Ink, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("全片直接剪", color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            // Hint when no rally is selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "轻触时间轴上的绿色回合块进行精准剪辑",
                    color = Muted,
                    fontSize = 12.sp,
                )
            }
        }
    }
}

/**
 * Sticky Bottom Thumb Action Zone (CapCut Style):
 * Thumb-friendly icons for high frequency mobile operation.
 */
@Composable
private fun StickyThumbActionZone(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onOpenRalliesSheet: () -> Unit,
) {
    val selectedIndex = state.selectedRallyIndex
    val currentRallyAtPlayhead = state.rallies.firstOrNull { state.playheadSec in it.startSec..it.endSec }
    val canSplit = state.source != null && (selectedIndex != null || currentRallyAtPlayhead != null)
    val canDelete = selectedIndex != null || currentRallyAtPlayhead != null

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(0.dp)),
    ) {
        if (state.rangeMarkInSec != null) {
            val markIn = state.rangeMarkInSec
            val currentLen = (state.playheadSec - markIn).coerceAtLeast(0.0)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x2E34C759))
                    .padding(horizontal = 12.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF34C759)),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = "已定起点 %s · 长度 %.1fs".format(formatClock(markIn), currentLen),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF34C759).copy(alpha = 0.25f))
                            .border(1.dp, Color(0xFF34C759), RoundedCornerShape(4.dp))
                            .clickable { viewModel.onEvent(EditorEvent.FastForward(1.5)) }
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.FastForward,
                                contentDescription = null,
                                tint = Color(0xFF34C759),
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(Modifier.width(2.dp))
                            Text(
                                "快进 1.5s",
                                color = Color.White,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }

                    Text(
                        text = "取消",
                        color = Red,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .clickable { viewModel.onEvent(EditorEvent.ClearRangeMarks) }
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceAround,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 1. 标开始 (常驻按钮)
            ThumbActionButton(
                icon = Icons.Default.Flag,
                label = if (state.rangeMarkInSec != null) "已标起点" else "标开始",
                tint = if (state.rangeMarkInSec != null) Color(0xFF34C759) else Blue,
                badge = if (state.rangeMarkInSec != null) "●" else null,
                enabled = state.source != null,
                onClick = { viewModel.onEvent(EditorEvent.MarkRangeStartAtPlayhead) },
            )

            // 2. 快进 1.5s (常驻按钮)
            ThumbActionButton(
                icon = Icons.Default.FastForward,
                label = "+1.5s",
                tint = if (state.rangeMarkInSec != null) Color(0xFF34C759) else Ink,
                enabled = state.source != null,
                onClick = { viewModel.onEvent(EditorEvent.FastForward(1.5)) },
            )

            // 3. 标结束 (常驻按钮)
            ThumbActionButton(
                icon = Icons.Default.CheckCircle,
                label = "标结束",
                tint = if (state.rangeMarkInSec != null) Color(0xFF34C759) else Ink,
                enabled = state.source != null,
                onClick = { viewModel.onEvent(EditorEvent.MarkRangeEndAtPlayhead) },
            )

            // 4. 拆分
            ThumbActionButton(
                icon = Icons.Default.ContentCut,
                label = "拆分",
                enabled = canSplit,
                onClick = { viewModel.onEvent(EditorEvent.SplitSelectedAtPlayhead) },
            )

            // 5. 删除
            ThumbActionButton(
                icon = Icons.Default.Delete,
                label = "删除",
                tint = if (canDelete) Red else Muted,
                enabled = canDelete,
                onClick = {
                    val idx = selectedIndex ?: state.rallies.indexOfFirst { state.playheadSec in it.startSec..it.endSec }.takeIf { it >= 0 }
                    idx?.let { viewModel.onEvent(EditorEvent.DeleteRally(it)) }
                },
            )

            // 5. 回合表
            ThumbActionButton(
                icon = Icons.AutoMirrored.Filled.FormatListBulleted,
                label = "回合表",
                badge = if (state.rallies.isNotEmpty()) "${state.rallies.size}" else null,
                onClick = onOpenRalliesSheet,
            )

            // 6. 导出 (Glowing Primary Button)
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (state.rallies.isNotEmpty() && !state.isExporting) Blue else StudioElevated)
                    .clickable(enabled = state.rallies.isNotEmpty() && !state.isExporting) {
                        viewModel.onEvent(EditorEvent.Export)
                    }
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isExporting) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.IosShare, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("导出", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

/**
 * Bottom Sheet Content for "回合表":
 * Detailed list of all rallies with status toggle, delete, and confidence score.
 */
@Composable
private fun RalliesBottomSheetContent(
    state: EditorUiState,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "全部回合 (${state.rallies.size})",
                color = Ink,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (state.rallies.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0x1FFF3B30))
                            .clickable { viewModel.onEvent(EditorEvent.ClearAllRallies) }
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text("清空", color = Red, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    }
                }

                if (state.rallies.size >= 2) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(StudioElevated)
                            .clickable { viewModel.onEvent(EditorEvent.MergeAdjacentRallies) }
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    ) {
                        Text("合并临近碎片", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                }

                IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "关闭", tint = Muted)
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        if (state.rallies.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("暂无回合片段", color = Muted, fontSize = 13.sp)
                    if (state.source != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(8.dp))
                                .background(Blue.copy(alpha = 0.2f))
                                .border(1.dp, Blue, RoundedCornerShape(8.dp))
                                .clickable {
                                    viewModel.onEvent(EditorEvent.StartDirectEdit)
                                    onClose()
                                }
                                .padding(horizontal = 14.dp, vertical = 8.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.ContentCut, contentDescription = null, tint = Blue, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("导入全片直接剪辑", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(340.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.rallies) { index, rally ->
                    val isSelected = state.selectedRallyIndex == index
                    RallySheetItem(
                        index = index,
                        rally = rally,
                        isSelected = isSelected,
                        onSelect = {
                            viewModel.onEvent(EditorEvent.SelectRally(index))
                            viewModel.onEvent(EditorEvent.PlaySelected)
                        },
                        onDelete = {
                            viewModel.onEvent(EditorEvent.DeleteRally(index))
                        },
                        onSetStatus = { status ->
                            viewModel.onEvent(EditorEvent.SetRallyStatus(index, status))
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun RallySheetItem(
    index: Int,
    rally: Rally,
    isSelected: Boolean,
    onSelect: () -> Unit,
    onDelete: () -> Unit,
    onSetStatus: (ReviewStatus) -> Unit,
) {
    val statusColor = when (rally.reviewStatus) {
        ReviewStatus.Approved -> Green
        ReviewStatus.Flagged -> Amber
        ReviewStatus.Rejected -> Red
        ReviewStatus.Normal -> Blue
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (isSelected) StudioElevated else Canvas)
            .border(1.dp, if (isSelected) Blue else BorderSubtle, RoundedCornerShape(10.dp))
            .padding(10.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(statusColor),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "%02d  %s - %s".format(index + 1, formatClock(rally.startSec), formatClock(rally.endSec)),
                        color = Ink,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "%.1fs".format(rally.durationSec),
                        color = Muted,
                        fontSize = 12.sp,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(StudioElevated)
                            .clickable { onSelect() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = Blue, modifier = Modifier.size(16.dp))
                    }

                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(StudioElevated)
                            .clickable { onDelete() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Icons.Default.Delete, contentDescription = null, tint = Red, modifier = Modifier.size(16.dp))
                    }
                }
            }

            // Review status chips
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                StatusTag("正常", rally.reviewStatus == ReviewStatus.Normal, Blue) { onSetStatus(ReviewStatus.Normal) }
                StatusTag("已通过", rally.reviewStatus == ReviewStatus.Approved, Green) { onSetStatus(ReviewStatus.Approved) }
                StatusTag("待复核", rally.reviewStatus == ReviewStatus.Flagged, Amber) { onSetStatus(ReviewStatus.Flagged) }
                StatusTag("弃用", rally.reviewStatus == ReviewStatus.Rejected, Red) { onSetStatus(ReviewStatus.Rejected) }
            }
        }
    }
}

@Composable
private fun StatusTag(label: String, selected: Boolean, color: Color, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) color.copy(alpha = 0.2f) else StudioElevated)
            .border(1.dp, if (selected) color else Color.Transparent, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            color = if (selected) color else Muted,
            fontSize = 10.sp,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
    }
}

/**
 * Thumb Action Item (Icon + label)
 */
@Composable
private fun ThumbActionButton(
    icon: ImageVector,
    label: String,
    enabled: Boolean = true,
    tint: Color = Ink,
    badge: String? = null,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(enabled = enabled) { onClick() }
            .padding(horizontal = 5.dp, vertical = 4.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (enabled) tint else Muted.copy(alpha = 0.4f),
                    modifier = Modifier.size(20.dp),
                )
                if (badge != null) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 6.dp, y = (-4).dp)
                            .clip(CircleShape)
                            .background(Blue)
                            .padding(horizontal = 4.dp, vertical = 1.dp),
                    ) {
                        Text(badge, color = Color.White, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(2.dp))
            Text(
                text = label,
                color = if (enabled) Ink else Muted.copy(alpha = 0.4f),
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}

@Composable
private fun TrimStepButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(34.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(StudioElevated)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Ink, fontSize = 11.sp, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun QuickActionButton(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Box(
        modifier = modifier
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(StudioElevated.copy(alpha = 0.6f))
            .border(1.dp, BorderSubtle, RoundedCornerShape(6.dp))
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, color = Muted, fontSize = 11.sp)
    }
}

@Composable
private fun MetricPill(
    label: String,
    value: String,
    highlight: Color? = null,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(SurfaceGlass)
            .border(1.dp, BorderSubtle, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(label, color = Muted, fontSize = 10.sp)
        Text(
            value,
            color = highlight ?: Ink,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Monospace,
        )
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
        try {
            context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (cursor.moveToFirst() && index >= 0) {
                    name = cursor.getString(index) ?: name
                }
            }
        } catch (_: Exception) {
        }
        if (name == "video.mp4" && uri.path != null) {
            val f = java.io.File(uri.path!!)
            if (f.exists()) name = f.name
        }
        val retriever = MediaMetadataRetriever()
        try {
            icu.yuqiuyijiaren.xiaobai.domain.MediaSourceHelper.setRetrieverDataSource(retriever, context, uri)
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

