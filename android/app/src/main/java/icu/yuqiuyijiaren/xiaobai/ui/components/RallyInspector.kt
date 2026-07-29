package icu.yuqiuyijiaren.xiaobai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
 * Compact sticky trim bar under the video — designed for one-thumb review without scrolling.
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
            .padding(horizontal = 10.dp, vertical = 8.dp),
    ) {
        // Navigation + segment info + play
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = onPrev,
                enabled = index != null && index > 0,
                modifier = Modifier.width(36.dp),
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
                        fontSize = 14.sp,
                    )
                    Text(
                        "${formatClock(rally.startSec)}–${formatClock(rally.endSec)} · ${"%.1f".format(rally.durationSec)}s · 播头 ${formatClock(playheadSec)}",
                        color = Muted,
                        fontSize = 11.sp,
                    )
                } else {
                    Text("未选中回合", fontWeight = FontWeight.SemiBold, color = Ink, fontSize = 14.sp)
                    Text("播头 ${formatClock(playheadSec)} · 可打点新建", color = Muted, fontSize = 11.sp)
                }
            }
            IconButton(
                onClick = onNext,
                enabled = index != null && index < total - 1,
                modifier = Modifier.width(36.dp),
            ) {
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "下一段")
            }
        }

        Spacer(Modifier.height(4.dp))
        Button(
            onClick = onPlaySelected,
            enabled = rally != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(containerColor = Blue),
            shape = RoundedCornerShape(10.dp),
        ) {
            Icon(Icons.Default.PlayArrow, contentDescription = null)
            Spacer(Modifier.width(4.dp))
            Text("播放本段")
        }

        Spacer(Modifier.height(6.dp))
        // Single scrollable action row — primary trim tools
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionChip("删除", enabled = rally != null, danger = true, onClick = onDelete)
            ActionChip("起点=播头", enabled = rally != null, onClick = onSetStartAtPlayhead)
            ActionChip("终点=播头", enabled = rally != null, onClick = onSetEndAtPlayhead)
            ActionChip("入-0.2", enabled = rally != null) { onNudgeStart(-0.2) }
            ActionChip("入+0.2", enabled = rally != null) { onNudgeStart(0.2) }
            ActionChip("出-0.2", enabled = rally != null) { onNudgeEnd(-0.2) }
            ActionChip("出+0.2", enabled = rally != null) { onNudgeEnd(0.2) }
            ActionChip("←0.2", enabled = rally != null) { onNudgeBoth(-0.2) }
            ActionChip("0.2→", enabled = rally != null) { onNudgeBoth(0.2) }
            ActionChip("拆分", enabled = rally != null, onClick = onSplit)
            ActionChip("合并下段", enabled = canMergeNext, onClick = onMergeNext)
        }

        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ActionChip(
                label = if (rangeMarkInSec != null) "入 ${formatClock(rangeMarkInSec)}" else "打入点",
                onClick = onMarkIn,
            )
            ActionChip(
                label = if (rangeMarkOutSec != null) "出 ${formatClock(rangeMarkOutSec)}" else "打出点",
                onClick = onMarkOut,
            )
            if (rangeMarkInSec != null || rangeMarkOutSec != null) {
                TextButton(onClick = onClearMarks) {
                    Text("清除标记", color = Muted, fontSize = 12.sp)
                }
            }
        }

        if (expanded) {
            Spacer(Modifier.height(4.dp))
            Text("提示：时间轴拖左右柄可修剪，拖中间平移", color = Muted, fontSize = 10.sp)
        }
    }
}

@Composable
private fun ActionChip(
    label: String,
    enabled: Boolean = true,
    danger: Boolean = false,
    onClick: () -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(10.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 0.dp),
        modifier = Modifier.height(34.dp),
    ) {
        Text(
            label,
            color = when {
                !enabled -> Muted
                danger -> Color(0xFFFF3B30)
                else -> Blue
            },
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
