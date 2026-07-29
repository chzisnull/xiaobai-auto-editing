package icu.yuqiuyijiaren.xiaobai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.yuqiuyijiaren.xiaobai.domain.Rally
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Ink
import icu.yuqiuyijiaren.xiaobai.ui.theme.Muted
import kotlin.math.roundToInt

/**
 * Full trim controls. Parent should scroll so nothing is clipped on short screens.
 */
@Composable
fun RallyInspector(
    rally: Rally?,
    index: Int?,
    total: Int,
    playheadSec: Double,
    rangeMarkInSec: Double?,
    rangeMarkOutSec: Double?,
    onPlaySelected: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    onSetStartAtPlayhead: () -> Unit,
    onSetEndAtPlayhead: () -> Unit,
    onNudgeStart: (Double) -> Unit,
    onNudgeEnd: (Double) -> Unit,
    onNudgeBoth: (Double) -> Unit,
    onMarkIn: () -> Unit,
    onMarkOut: () -> Unit,
    onClearMarks: () -> Unit,
    onSplit: () -> Unit,
    onMergeNext: () -> Unit,
    onDelete: () -> Unit,
    canMergeNext: Boolean,
    modifier: Modifier = Modifier,
    expanded: Boolean = false,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.White.copy(alpha = 0.96f))
            .border(1.dp, Color(0x28767680), RoundedCornerShape(14.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text("精剪审核", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
        Spacer(Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPrev,
                enabled = index != null && index > 0,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "上一段")
            }
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (rally != null && index != null) {
                    Text(
                        "第 %02d / %02d".format(index + 1, total.coerceAtLeast(1)),
                        fontWeight = FontWeight.SemiBold,
                        color = Ink,
                        fontSize = 15.sp,
                    )
                    Text(
                        "${formatClock(rally.startSec)} – ${formatClock(rally.endSec)} · ${"%.1f".format(rally.durationSec)}s",
                        color = Muted,
                        fontSize = 12.sp,
                    )
                    Text(
                        "播头 ${formatClock(playheadSec)}",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                } else {
                    Text("未选中回合", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 15.sp)
                    Text("播头 ${formatClock(playheadSec)} · 可打点新建", color = Muted, fontSize = 12.sp)
                }
            }
            IconButton(
                onClick = onNext,
                enabled = index != null && index < total - 1,
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一段")
            }
        }

        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Button(
                onClick = onPlaySelected,
                enabled = rally != null,
                modifier = Modifier.weight(1f),
                colors = ButtonDefaults.buttonColors(containerColor = Blue),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(Icons.Default.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(4.dp))
                Text("播放本段")
            }
            OutlinedButton(
                onClick = onDelete,
                enabled = rally != null,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = if (rally != null) Color(0xFFFF3B30) else Muted,
                )
                Spacer(Modifier.width(4.dp))
                Text("删除本段", color = if (rally != null) Color(0xFFFF3B30) else Muted)
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("以播头设置", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedButton(
                onClick = onSetStartAtPlayhead,
                enabled = rally != null,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text("设为起点") }
            OutlinedButton(
                onClick = onSetEndAtPlayhead,
                enabled = rally != null,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) { Text("设为终点") }
        }

        Spacer(Modifier.height(10.dp))
        Text("微调 / 结构", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        // Wrap chips so nothing is hidden off-screen
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                ActionChip("入-0.2", enabled = rally != null) { onNudgeStart(-0.2) }
                ActionChip("入+0.2", enabled = rally != null) { onNudgeStart(0.2) }
                ActionChip("出-0.2", enabled = rally != null) { onNudgeEnd(-0.2) }
                ActionChip("出+0.2", enabled = rally != null) { onNudgeEnd(0.2) }
                ActionChip("整体-0.2", enabled = rally != null) { onNudgeBoth(-0.2) }
                ActionChip("整体+0.2", enabled = rally != null) { onNudgeBoth(0.2) }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onSplit,
                    enabled = rally != null,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("播头拆分") }
                OutlinedButton(
                    onClick = onMergeNext,
                    enabled = canMergeNext,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(10.dp),
                ) { Text("合并下段") }
            }
        }

        Spacer(Modifier.height(10.dp))
        Text("新建片段", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = onMarkIn,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (rangeMarkInSec != null) "入点 ${formatClock(rangeMarkInSec)}" else "打入点")
            }
            OutlinedButton(
                onClick = onMarkOut,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp),
            ) {
                Text(if (rangeMarkOutSec != null) "出点 ${formatClock(rangeMarkOutSec)}" else "打出点")
            }
            if (rangeMarkInSec != null || rangeMarkOutSec != null) {
                OutlinedButton(
                    onClick = onClearMarks,
                    shape = RoundedCornerShape(10.dp),
                ) { Text("清除") }
            }
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) {
        Text(
            label,
            color = if (enabled) Blue else Muted,
            fontSize = 12.sp,
            maxLines = 1,
        )
    }
}

private fun formatClock(seconds: Double): String {
    val total = seconds.coerceAtLeast(0.0).roundToInt()
    val m = total / 60
    val s = total % 60
    return "%02d:%02d".format(m, s)
}
