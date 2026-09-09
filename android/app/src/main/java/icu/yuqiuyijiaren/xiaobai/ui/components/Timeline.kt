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
import icu.yuqiuyijiaren.xiaobai.ui.theme.Green
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
                .background(Color(0xFF15151B))
                .border(1.dp, Color(0xFF22222A), RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Text("选择视频后显示时间轴", color = Muted, fontSize = 12.sp)
        }
        return
    }

    var zoom by remember { mutableFloatStateOf(2.2f) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF15151B))
            .border(1.dp, Color(0xFF22222A), RoundedCornerShape(12.dp))
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

        fun timeToX(time: Double, widthPx: Float): Float =
            ((time / durationSec).toFloat().coerceIn(0f, 1f) * widthPx)

        fun xToTime(x: Float, widthPx: Float): Double =
            (x / widthPx).coerceIn(0f, 1f) * durationSec

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
                .pointerInput(durationSec, canvasWidth) {
                    detectTapGestures(
                        onDoubleTap = { zoom = 2.2f },
                        onTap = { offset ->
                            val time = xToTime(offset.x, size.width.toFloat())
                            val hit = rallies.indexOfLast { time in it.startSec..it.endSec }
                            if (hit >= 0) {
                                onSelect(hit)
                            }
                            onSeek(time)
                        },
                    )
                }
                .pointerInput(durationSec, canvasWidth) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            onGestureStart()
                            val widthPx = size.width.toFloat()
                            val time = xToTime(offset.x, widthPx)
                            onSeek(time)
                        },
                        onDragEnd = { onGestureEnd() },
                        onDragCancel = { onGestureEnd() },
                        onDrag = { change, _ ->
                            change.consume()
                            val widthPx = size.width.toFloat()
                            val time = xToTime(change.position.x, widthPx)
                            onSeek(time)
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
                    .background(Color(0xFF1C1C24)),
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
            if (markIn != null && markOut != null && markOut > markIn && durationSec > 0.0) {
                val startRatio = (markIn / durationSec).toFloat().coerceIn(0f, 1f)
                val widthRatio = ((markOut - markIn) / durationSec).toFloat().coerceIn(0f, 1f)
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 10.dp)
                        .offset(x = canvasWidth * startRatio)
                        .width(canvasWidth * widthRatio)
                        .height(if (compact) 36.dp else 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(0x3334C759)),
                )
            } else if (markIn != null && markOut == null && durationSec > 0.0) {
                val from = minOf(markIn, playheadSec)
                val to = maxOf(markIn, playheadSec)
                val startRatio = (from / durationSec).toFloat().coerceIn(0f, 1f)
                val widthRatio = ((to - from) / durationSec).toFloat().coerceIn(0f, 1f)
                if (widthRatio > 0.001f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(top = 10.dp)
                            .offset(x = canvasWidth * startRatio)
                            .width(canvasWidth * widthRatio)
                            .height(if (compact) 36.dp else 48.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0x3334C759))
                            .border(1.dp, Color(0xFF34C759), RoundedCornerShape(8.dp)),
                    )
                }
            }

            rallies.forEachIndexed { index, rally ->
                val startRatio = (rally.startSec / durationSec).toFloat().coerceIn(0f, 1f)
                val endRatio = (rally.endSec / durationSec).toFloat().coerceIn(0f, 1f)
                val rawWidth = (endRatio - startRatio).coerceAtLeast(0f)
                val minRatio = (minBlockPx / canvasWidthPx).coerceIn(0.01f, 0.08f)
                val widthRatio = rawWidth.coerceAtLeast(minRatio)
                val color = if (rally.confidence < 0.72) Amber else Green
                val selected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 10.dp)
                        .offset(x = canvasWidth * startRatio)
                        .width(canvasWidth * widthRatio)
                        .height(if (compact) 36.dp else 48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = if (selected) 0.95f else 0.45f))
                        .border(
                            width = if (selected) 2.dp else 1.dp,
                            color = if (selected) Color.White else color,
                            shape = RoundedCornerShape(8.dp),
                        )
                        .pointerInput(index) {
                            detectTapGestures {
                                onSelect(index)
                                onSeek(rally.startSec)
                            }
                        },
                ) {
                    Text(
                        text = "#%02d".format(index + 1),
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        modifier = Modifier.align(Alignment.Center),
                    )
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
                    .background(Color(0xFFFF453A)),
            )

            val ticks = adaptiveTicks(durationSec, canvasWidth.value)
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

private fun adaptiveTicks(durationSec: Double, canvasWidthDp: Float): List<Double> {
    if (durationSec <= 0.0 || canvasWidthDp <= 0f) return emptyList()
    val minIntervalDp = 56f
    val maxLabels = (canvasWidthDp / minIntervalDp).toInt().coerceIn(2, 60)
    val rawStep = durationSec / maxLabels
    val candidates = doubleArrayOf(1.0, 2.0, 5.0, 10.0, 15.0, 30.0, 60.0, 120.0, 300.0, 600.0)
    val step = candidates.firstOrNull { it >= rawStep } ?: 600.0
    val ticks = ArrayList<Double>()
    var t = 0.0
    while (t <= durationSec - step * 0.3) {
        ticks.add(t)
        t += step
    }
    ticks.add(durationSec)
    return ticks
}

private fun formatClock(seconds: Double): String {
    val total = max(0, seconds.roundToInt())
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
