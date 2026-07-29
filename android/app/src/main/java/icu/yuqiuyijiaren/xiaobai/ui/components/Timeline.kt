package icu.yuqiuyijiaren.xiaobai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.ui.theme.Amber
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Ink
import icu.yuqiuyijiaren.xiaobai.ui.theme.Muted
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ZoomableRallyTimeline(
    durationSec: Double,
    rallies: List<Rally>,
    playheadSec: Double,
    selectedIndex: Int?,
    rangeMarkInSec: Double? = null,
    rangeMarkOutSec: Double? = null,
    onSeek: (Double) -> Unit,
    onSelect: (Int) -> Unit,
    onUpdateRally: (index: Int, startSec: Double, endSec: Double) -> Unit,
    onGestureStart: () -> Unit = {},
    onGestureEnd: () -> Unit = {},
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val trackHeight = if (compact) 96.dp else 132.dp
    if (durationSec <= 0.0) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14767680)),
            contentAlignment = Alignment.Center,
        ) {
            Text("选择视频后显示时间轴", color = Muted, fontSize = 12.sp)
        }
        return
    }

    var zoom by remember { mutableFloatStateOf(2.2f) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current
    var dragMode by remember { mutableStateOf<DragMode?>(null) }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x0F767680))
            .border(1.dp, Color(0x28767680), RoundedCornerShape(12.dp))
            .pointerInput(durationSec) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 32f)
                }
            },
    ) {
        val viewportWidth = maxWidth
        val canvasWidth = viewportWidth * zoom
        val canvasWidthPx = with(density) { canvasWidth.toPx() }
        val minBlockPx = with(density) { 40.dp.toPx() }
        val handlePx = with(density) { 36.dp.toPx() }
        val snapPx = with(density) { 10.dp.toPx() }

        fun timeToX(time: Double, widthPx: Float): Float =
            ((time / durationSec).toFloat().coerceIn(0f, 1f) * widthPx)

        fun xToTime(x: Float, widthPx: Float): Double =
            (x / widthPx).coerceIn(0f, 1f) * durationSec

        fun snapTime(time: Double, widthPx: Float): Double {
            val playX = timeToX(playheadSec, widthPx)
            val tX = timeToX(time, widthPx)
            return if (abs(playX - tX) <= snapPx) playheadSec else time
        }

        LaunchedEffect(playheadSec, zoom, durationSec) {
            val x = ((playheadSec / durationSec).toFloat() * canvasWidthPx)
            val view = with(density) { viewportWidth.toPx() }
            val target = (x - view * 0.35f).toInt().coerceAtLeast(0)
            if (abs(scroll.value - target) > view * 0.35f) {
                scroll.animateScrollTo(target)
            }
        }

        Box(
            modifier = Modifier
                .horizontalScroll(scroll)
                .width(canvasWidth)
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .pointerInput(durationSec, canvasWidth, dragMode) {
                    detectTapGestures(
                        onDoubleTap = { zoom = 2.2f },
                        onTap = { offset ->
                            if (dragMode != null) return@detectTapGestures
                            // Prefer selecting a rally under tap
                            val time = xToTime(offset.x, size.width.toFloat())
                            val hit = rallies.indexOfLast { time in it.startSec..it.endSec }
                            if (hit >= 0) {
                                onSelect(hit)
                                onSeek(time)
                            } else {
                                onSeek(time)
                            }
                        },
                    )
                }
                .pointerInput(durationSec, canvasWidth, selectedIndex, rallies) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onGestureStart()
                            val selected = selectedIndex?.takeIf { it in rallies.indices }?.let { rallies[it] }
                            dragMode = if (selected != null) {
                                val startX = timeToX(selected.startSec, size.width.toFloat())
                                val endX = timeToX(selected.endSec, size.width.toFloat())
                                when {
                                    abs(offset.x - startX) <= handlePx ->
                                        DragMode.TrimStart(selectedIndex!!)
                                    abs(offset.x - endX) <= handlePx ->
                                        DragMode.TrimEnd(selectedIndex!!)
                                    offset.x in startX..endX ->
                                        DragMode.MoveBody(
                                            index = selectedIndex!!,
                                            grabTime = xToTime(offset.x, size.width.toFloat()),
                                            originStart = selected.startSec,
                                            originEnd = selected.endSec,
                                        )
                                    else -> DragMode.Scrub
                                }
                            } else {
                                DragMode.Scrub
                            }
                        },
                        onDragEnd = {
                            dragMode = null
                            onGestureEnd()
                        },
                        onDragCancel = {
                            dragMode = null
                            onGestureEnd()
                        },
                        onDrag = { change, _ ->
                            change.consume()
                            val widthPx = size.width.toFloat()
                            val time = xToTime(change.position.x, widthPx)
                            when (val mode = dragMode) {
                                is DragMode.Scrub, null -> onSeek(time)
                                is DragMode.TrimStart -> {
                                    val rally = rallies.getOrNull(mode.index) ?: return@detectDragGestures
                                    val start = snapTime(time, widthPx).coerceIn(0.0, rally.endSec - 0.25)
                                    onUpdateRally(mode.index, start, rally.endSec)
                                    onSeek(start)
                                }
                                is DragMode.TrimEnd -> {
                                    val rally = rallies.getOrNull(mode.index) ?: return@detectDragGestures
                                    val end = snapTime(time, widthPx).coerceIn(rally.startSec + 0.25, durationSec)
                                    onUpdateRally(mode.index, rally.startSec, end)
                                    onSeek(end)
                                }
                                is DragMode.MoveBody -> {
                                    val delta = time - mode.grabTime
                                    val len = mode.originEnd - mode.originStart
                                    var start = mode.originStart + delta
                                    var end = mode.originEnd + delta
                                    if (start < 0.0) {
                                        start = 0.0
                                        end = len
                                    }
                                    if (end > durationSec) {
                                        end = durationSec
                                        start = (durationSec - len).coerceAtLeast(0.0)
                                    }
                                    onUpdateRally(mode.index, start, end)
                                    onSeek(start)
                                }
                            }
                        },
                    )
                },
        ) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(if (compact) 52.dp else 68.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1A767680)),
            )

            // Range mark preview
            val markIn = rangeMarkInSec
            val markOut = rangeMarkOutSec
            if (markIn != null) {
                val x = canvasWidth * (markIn / durationSec).toFloat()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 6.dp)
                        .offset(x = x - 1.dp)
                        .width(2.dp)
                        .height(if (compact) 50.dp else 64.dp)
                        .background(Color(0xFF34C759)),
                )
            }
            if (markOut != null) {
                val x = canvasWidth * (markOut / durationSec).toFloat()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 6.dp)
                        .offset(x = x - 1.dp)
                        .width(2.dp)
                        .height(if (compact) 50.dp else 64.dp)
                        .background(Color(0xFFFF3B30)),
                )
            }
            if (markIn != null && markOut != null && markOut > markIn) {
                val startRatio = (markIn / durationSec).toFloat()
                val widthRatio = ((markOut - markIn) / durationSec).toFloat()
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 12.dp)
                        .offset(x = canvasWidth * startRatio)
                        .width(canvasWidth * widthRatio)
                        .height(if (compact) 36.dp else 44.dp)
                        .background(Color(0x3334C759)),
                )
            }

            rallies.forEachIndexed { index, rally ->
                val startRatio = (rally.startSec / durationSec).toFloat().coerceIn(0f, 1f)
                val endRatio = (rally.endSec / durationSec).toFloat().coerceIn(0f, 1f)
                val rawWidth = (endRatio - startRatio).coerceAtLeast(0f)
                val minRatio = (minBlockPx / canvasWidthPx).coerceIn(0.01f, 0.08f)
                val widthRatio = rawWidth.coerceAtLeast(minRatio)
                val color = if (rally.confidence < 0.72) Amber else Blue
                val selected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 10.dp)
                        .offset(x = canvasWidth * startRatio)
                        .width(canvasWidth * widthRatio)
                        .height(if (compact) 36.dp else 48.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(color.copy(alpha = if (selected) 1f else 0.82f))
                        .then(
                            if (selected) Modifier.border(2.dp, Ink, RoundedCornerShape(10.dp))
                            else Modifier,
                        )
                        .pointerInput(index) {
                            detectTapGestures {
                                onSelect(index)
                                onSeek(rally.startSec)
                            }
                        },
                ) {
                    Text(
                        text = "%02d".format(index + 1),
                        color = Color.White,
                        fontSize = 12.sp,
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.align(Alignment.Center),
                    )
                    if (selected) {
                        HandleBar(Alignment.CenterStart)
                        HandleBar(Alignment.CenterEnd)
                    }
                }
            }

            val playRatio = (playheadSec / durationSec).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 2.dp)
                    .offset(x = canvasWidth * playRatio - 1.5.dp)
                    .width(3.dp)
                    .height(if (compact) 56.dp else 72.dp)
                    .background(Ink),
            )

            val ticks = adaptiveTicks(durationSec, zoom)
            ticks.forEach { t ->
                Text(
                    text = formatClock(t),
                    color = Muted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = canvasWidth * (t / durationSec).toFloat())
                        .padding(bottom = 2.dp),
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.HandleBar(align: Alignment) {
    Box(
        modifier = Modifier
            .align(align)
            .fillMaxHeight()
            .width(14.dp)
            .background(Color.White.copy(alpha = 0.92f)),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .width(3.dp)
                .height(22.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Ink.copy(alpha = 0.75f)),
        )
    }
}

private sealed interface DragMode {
    data object Scrub : DragMode
    data class TrimStart(val index: Int) : DragMode
    data class TrimEnd(val index: Int) : DragMode
    data class MoveBody(
        val index: Int,
        val grabTime: Double,
        val originStart: Double,
        val originEnd: Double,
    ) : DragMode
}

private fun adaptiveTicks(durationSec: Double, zoom: Float): List<Double> {
    val visible = durationSec / zoom.coerceAtLeast(1f)
    val step = when {
        visible <= 15 -> 1.0
        visible <= 30 -> 2.0
        visible <= 90 -> 5.0
        visible <= 180 -> 15.0
        visible <= 600 -> 30.0
        else -> 60.0
    }
    val ticks = ArrayList<Double>()
    var t = 0.0
    while (t <= durationSec + 1e-6) {
        ticks.add(t)
        t += step
    }
    if (ticks.lastOrNull() != durationSec) ticks.add(durationSec)
    return ticks
}

private fun formatClock(seconds: Double): String {
    val total = max(0, seconds.roundToInt())
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
