package icu.yuqiuyijiaren.xiaobai.ui.screens

import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import icu.yuqiuyijiaren.xiaobai.EditorViewModel
import icu.yuqiuyijiaren.xiaobai.domain.EditorEvent
import icu.yuqiuyijiaren.xiaobai.domain.VideoSource
import icu.yuqiuyijiaren.xiaobai.ui.components.LocalVideoPlayer
import icu.yuqiuyijiaren.xiaobai.ui.components.ZoomableRallyTimeline
import icu.yuqiuyijiaren.xiaobai.ui.theme.Amber
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Canvas
import icu.yuqiuyijiaren.xiaobai.ui.theme.Ink
import icu.yuqiuyijiaren.xiaobai.ui.theme.Muted
import kotlin.math.roundToInt

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditorScreen(viewModel: EditorViewModel) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        val source = readVideoSource(context, uri)
        if (source == null) {
            viewModel.onEvent(EditorEvent.ClearError)
        } else {
            viewModel.onEvent(EditorEvent.VideoPicked(source))
        }
    }

    LaunchedEffect(state.errorMessage) {
        val msg = state.errorMessage
        if (!msg.isNullOrBlank()) {
            snackbar.showSnackbar(msg)
            viewModel.onEvent(EditorEvent.ClearError)
        }
    }

    LaunchedEffect(state.exportPath) {
        val path = state.exportPath
        if (!path.isNullOrBlank()) {
            snackbar.showSnackbar("已导出到缓存：$path")
        }
    }

    Scaffold(
        containerColor = Canvas,
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("小白自动剪辑", fontWeight = FontWeight.SemiBold, color = Ink)
                        Text("Android 端侧 · 本地计算", fontSize = 12.sp, color = Muted)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xCCF2F2F7)),
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = PaddingValues(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(
                        onClick = { picker.launch("video/*") },
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("选择录像")
                    }
                    Button(
                        onClick = { viewModel.onEvent(EditorEvent.StartAnalysis) },
                        enabled = state.source != null && !state.isAnalyzing,
                        colors = ButtonDefaults.buttonColors(containerColor = Blue),
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (state.isAnalyzing) "分析中" else "开始识别")
                    }
                }
            }

            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color.White.copy(alpha = 0.82f)),
                    shape = RoundedCornerShape(14.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color(0x28767680), RoundedCornerShape(14.dp)),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
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
                            LocalVideoPlayer(
                                source = state.source,
                                playheadSec = state.playheadSec,
                                onPlayheadChange = viewModel::updatePlayhead,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "${state.source!!.displayName} · ${formatClock(state.source!!.durationSec)}",
                                color = Muted,
                                fontSize = 12.sp,
                            )
                        }
                    }
                }
            }

            if (state.isAnalyzing || state.analysis.message.isNotBlank()) {
                item {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = Color(0x14007AFF)),
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (state.isAnalyzing) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(16.dp),
                                        strokeWidth = 2.dp,
                                        color = Blue,
                                    )
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(state.analysis.message.ifBlank { "准备中" }, color = Ink, fontSize = 13.sp)
                            }
                            if (state.isAnalyzing) {
                                Spacer(Modifier.height(8.dp))
                                LinearProgressIndicator(
                                    progress = state.analysis.percent.coerceIn(0f, 1f),
                                    modifier = Modifier.fillMaxWidth(),
                                    color = Blue,
                                    trackColor = Color(0x22007AFF),
                                )
                            }
                        }
                    }
                }
            }

            item {
                Text("回合时间轴", fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(4.dp))
                Text("双指缩放 · 左右滑动 · 点选回合", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.height(8.dp))
                ZoomableRallyTimeline(
                    durationSec = state.source?.durationSec ?: 0.0,
                    rallies = state.rallies,
                    playheadSec = state.playheadSec,
                    selectedIndex = state.selectedRallyIndex,
                    onSeek = { viewModel.onEvent(EditorEvent.SeekTo(it)) },
                    onSelect = { viewModel.onEvent(EditorEvent.SelectRally(it)) },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    MetricChip("原片", formatClock(state.source?.durationSec ?: 0.0))
                    MetricChip("成片", formatClock(state.selectedDurationSec))
                    MetricChip("精简", "${state.reductionPercent}%")
                }
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("回合列表", fontWeight = FontWeight.SemiBold, color = Ink)
                    Row {
                        IconButton(
                            onClick = {
                                viewModel.onEvent(EditorEvent.AddRallyAt(state.playheadSec))
                            },
                            enabled = state.source != null,
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "添加回合", tint = Blue)
                        }
                        IconButton(
                            onClick = {
                                state.selectedRallyIndex?.let {
                                    viewModel.onEvent(EditorEvent.DeleteRally(it))
                                }
                            },
                            enabled = state.selectedRallyIndex != null,
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "删除选中", tint = Muted)
                        }
                    }
                }
            }

            if (state.rallies.isEmpty()) {
                item {
                    Text("暂无回合，请先识别或手动添加", color = Muted, fontSize = 13.sp)
                }
            } else {
                itemsIndexed(state.rallies) { index, rally ->
                    val selected = state.selectedRallyIndex == index
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
                                viewModel.onEvent(EditorEvent.SeekTo(rally.startSec))
                            }) {
                                Icon(Icons.Default.PlayArrow, contentDescription = "定位播放", tint = Blue)
                            }
                        }
                    }
                }
            }

            item {
                Button(
                    onClick = { viewModel.onEvent(EditorEvent.Export) },
                    enabled = state.rallies.isNotEmpty() && !state.isExporting && !state.isAnalyzing,
                    modifier = Modifier.fillMaxWidth(),
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
                        Text("正在本地导出…")
                    } else {
                        Icon(Icons.Default.IosShare, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("导出合并视频（端侧）")
                    }
                }
            }
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
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(label, color = Muted, fontSize = 11.sp)
        Text(value, color = Ink, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
    }
}

private fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
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
        retriever.setDataSource(context, uri)
        val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()
            ?: 0L
        retriever.release()
        if (durationMs <= 0L) return null
        VideoSource(
            uriString = uri.toString(),
            displayName = name,
            durationMs = durationMs,
        )
    } catch (_: Exception) {
        null
    }
}
