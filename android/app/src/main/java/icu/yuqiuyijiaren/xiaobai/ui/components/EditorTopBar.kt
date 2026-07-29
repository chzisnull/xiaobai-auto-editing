package icu.yuqiuyijiaren.xiaobai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.Undo
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier
import icu.yuqiuyijiaren.xiaobai.ui.theme.Amber
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Ink
import icu.yuqiuyijiaren.xiaobai.ui.theme.Muted

/**
 * Compact frosted-style header: device capability + recognition intensity chips.
 * No large product title (Stitch "Precision Motion" editor bar).
 */
@Composable
fun EditorTopBar(
    deviceSummary: String,
    capabilityLine: String,
    selectedTier: AnalysisTier,
    recommendedTier: AnalysisTier,
    configSummary: String,
    canUndo: Boolean,
    roiActive: Boolean,
    roiEnabled: Boolean,
    analyzing: Boolean,
    onSelectTier: (AnalysisTier) -> Unit,
    onUndo: () -> Unit,
    onToggleRoi: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xCCF2F2F7))
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = deviceSummary.ifBlank { "设备探测中…" },
                    color = Ink,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = capabilityLine.ifBlank { configSummary },
                    color = Muted,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onUndo, enabled = canUndo, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.Undo,
                    contentDescription = "撤销",
                    tint = if (canUndo) Blue else Muted,
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onToggleRoi, enabled = roiEnabled, modifier = Modifier.size(36.dp)) {
                Icon(
                    Icons.Default.CropFree,
                    contentDescription = "球场标定",
                    tint = when {
                        !roiEnabled -> Muted
                        roiActive -> Amber
                        else -> Blue
                    },
                    modifier = Modifier.size(20.dp),
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = "识别强度",
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x14767680))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            AnalysisTier.entries.forEach { tier ->
                val selected = tier == selectedTier
                val isRecommended = tier == recommendedTier
                val label = buildString {
                    append(tier.label)
                    if (isRecommended) append("·荐")
                }
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Blue else Color.Transparent)
                        .clickable(enabled = !analyzing) { onSelectTier(tier) }
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = label,
                        color = if (selected) Color.White else Ink,
                        fontSize = 13.sp,
                        fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
                    )
                }
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = if (analyzing) {
                "分析中不可切换强度"
            } else {
                "${selectedTier.hint} · $configSummary"
            },
            color = Muted,
            fontSize = 10.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
