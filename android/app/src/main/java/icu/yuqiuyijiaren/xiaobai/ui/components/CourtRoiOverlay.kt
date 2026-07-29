package icu.yuqiuyijiaren.xiaobai.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.yuqiuyijiaren.xiaobai.domain.CourtRoi
import kotlin.math.hypot

@Composable
fun CourtRoiOverlay(
    roi: CourtRoi,
    onPointMove: (index: Int, x: Float, y: Float) -> Unit,
    modifier: Modifier = Modifier,
) {
    var activeCorner by remember { mutableIntStateOf(-1) }
    val labels = listOf("左上", "右上", "右下", "左下")

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(roi) {
                    detectDragGestures(
                        onDragStart = { offset ->
                            val pts = roi.asPairs().map { (nx, ny) ->
                                Offset(nx * size.width, ny * size.height)
                            }
                            val hitR = 48f
                            var best = -1
                            var bestD = Float.MAX_VALUE
                            pts.forEachIndexed { i, p ->
                                val d = hypot(offset.x - p.x, offset.y - p.y)
                                if (d < hitR && d < bestD) {
                                    best = i
                                    bestD = d
                                }
                            }
                            activeCorner = best
                        },
                        onDragEnd = { activeCorner = -1 },
                        onDragCancel = { activeCorner = -1 },
                        onDrag = { change, _ ->
                            val i = activeCorner
                            if (i < 0) return@detectDragGestures
                            change.consume()
                            val nx = (change.position.x / size.width).coerceIn(0f, 1f)
                            val ny = (change.position.y / size.height).coerceIn(0f, 1f)
                            onPointMove(i, nx, ny)
                        },
                    )
                },
        ) {
            val pts = roi.asPairs().map { (nx, ny) -> Offset(nx * size.width, ny * size.height) }
            if (pts.size == 4) {
                val path = Path().apply {
                    moveTo(pts[0].x, pts[0].y)
                    lineTo(pts[1].x, pts[1].y)
                    lineTo(pts[2].x, pts[2].y)
                    lineTo(pts[3].x, pts[3].y)
                    close()
                }
                drawPath(path, Color(0x33007AFF))
                drawPath(path, Color(0xFF007AFF), style = Stroke(width = 3f))
                pts.forEachIndexed { index, p ->
                    val selected = index == activeCorner
                    drawCircle(
                        color = if (selected) Color(0xFFFF9500) else Color.White,
                        radius = if (selected) 16f else 13f,
                        center = p,
                    )
                    drawCircle(
                        color = Color(0xFF007AFF),
                        radius = if (selected) 8f else 6f,
                        center = p,
                    )
                }
            }
        }
        Text(
            text = "拖动四角标定目标球场 · ${labels.joinToString(" / ")}",
            color = Color.White,
            fontSize = 11.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(8.dp),
        )
    }
}
