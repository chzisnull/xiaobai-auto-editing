package icu.yuqiuyijiaren.xiaobai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import kotlin.math.max
import kotlin.math.roundToInt

@Composable
fun ZoomableRallyTimeline(
    durationSec: Double,
    rallies: List<Rally>,
    playheadSec: Double,
    selectedIndex: Int?,
    onSeek: (Double) -> Unit,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (durationSec <= 0.0) {
        Box(
            modifier = modifier
                .fillMaxWidth()
                .height(96.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14767680)),
            contentAlignment = Alignment.Center,
        ) {
            Text("选择视频后显示时间轴", color = Muted, fontSize = 12.sp)
        }
        return
    }

    var zoom by remember { mutableFloatStateOf(1f) }
    val scroll = rememberScrollState()
    val density = LocalDensity.current

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0x0F767680))
            .border(1.dp, Color(0x28767680), RoundedCornerShape(12.dp))
            .pointerInput(durationSec) {
                detectTransformGestures { _, _, zoomChange, _ ->
                    zoom = (zoom * zoomChange).coerceIn(1f, 18f)
                }
            },
    ) {
        val viewportWidth = maxWidth
        val canvasWidth = viewportWidth * zoom
        val canvasWidthPx = with(density) { canvasWidth.toPx() }

        LaunchedEffect(playheadSec, zoom, durationSec) {
            // Keep playhead roughly visible when zoomed.
            val x = ((playheadSec / durationSec).toFloat() * canvasWidthPx)
            val view = with(density) { viewportWidth.toPx() }
            val target = (x - view * 0.35f).toInt().coerceAtLeast(0)
            if (kotlin.math.abs(scroll.value - target) > view * 0.4f) {
                scroll.animateScrollTo(target)
            }
        }

        Box(
            modifier = Modifier
                .horizontalScroll(scroll)
                .width(canvasWidth)
                .fillMaxHeight()
                .padding(horizontal = 12.dp, vertical = 10.dp)
                .pointerInput(durationSec, canvasWidth) {
                    detectTapGestures { offset ->
                        val ratio = (offset.x / size.width).coerceIn(0f, 1f)
                        onSeek(ratio * durationSec)
                    }
                },
        ) {
            // Track background
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x1A767680)),
            )

            rallies.forEachIndexed { index, rally ->
                val startRatio = (rally.startSec / durationSec).toFloat().coerceIn(0f, 1f)
                val endRatio = (rally.endSec / durationSec).toFloat().coerceIn(0f, 1f)
                val widthRatio = (endRatio - startRatio).coerceAtLeast(0.004f)
                val color = if (rally.confidence < 0.72) Amber else Blue
                val selected = selectedIndex == index
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(top = 8.dp)
                        .offset(x = canvasWidth * startRatio)
                        .width(canvasWidth * widthRatio)
                        .height(36.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color)
                        .then(
                            if (selected) Modifier.border(2.dp, Ink, RoundedCornerShape(8.dp))
                            else Modifier,
                        )
                        .pointerInput(index) {
                            detectTapGestures(
                                onTap = {
                                    onSelect(index)
                                    onSeek(rally.startSec)
                                },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (widthRatio * zoom > 0.04f) {
                        Text(
                            text = "%02d".format(index + 1),
                            color = Color.White,
                            fontSize = 11.sp,
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }

            // Playhead
            val playRatio = (playheadSec / durationSec).toFloat().coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(top = 2.dp)
                    .offset(x = canvasWidth * playRatio - 1.dp)
                    .width(2.dp)
                    .height(56.dp)
                    .background(Ink),
            )

            // Ruler labels
            val ticks = listOf(0.0, 0.25, 0.5, 0.75, 1.0)
            ticks.forEach { t ->
                val label = formatClock(durationSec * t)
                Text(
                    text = label,
                    color = Muted,
                    fontSize = 10.sp,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .offset(x = canvasWidth * t.toFloat())
                        .padding(bottom = 2.dp),
                )
            }
        }
    }
}

private fun formatClock(seconds: Double): String {
    val total = max(0, seconds.roundToInt())
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
