package icu.yuqiuyijiaren.xiaobai.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.CropFree
import androidx.compose.material.icons.filled.FolderOpen
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import icu.yuqiuyijiaren.xiaobai.domain.AnalysisTier
import icu.yuqiuyijiaren.xiaobai.ui.theme.Amber
import icu.yuqiuyijiaren.xiaobai.ui.theme.Blue
import icu.yuqiuyijiaren.xiaobai.ui.theme.Green
import icu.yuqiuyijiaren.xiaobai.ui.theme.Ink
import icu.yuqiuyijiaren.xiaobai.ui.theme.Muted
import icu.yuqiuyijiaren.xiaobai.ui.theme.StudioElevated
import icu.yuqiuyijiaren.xiaobai.ui.theme.SurfaceGlass

@Composable
fun EditorTopBar(
    tier: AnalysisTier,
    canUndo: Boolean,
    roiActive: Boolean,
    roiEnabled: Boolean,
    onUndo: () -> Unit,
    onToggleRoi: () -> Unit,
    onPickFile: () -> Unit,
    onOpenTierSelector: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(SurfaceGlass)
            .windowInsetsPadding(WindowInsets.statusBars)
            .height(52.dp)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // Brand Left
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.clickable(onClick = onOpenTierSelector),
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Blue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Default.ContentCut,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
            Spacer(Modifier.width(8.dp))
            Column {
                Text(
                    text = "小白自动剪辑",
                    color = Ink,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Green),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "${tier.label}档",
                        color = Muted,
                        fontSize = 10.sp,
                    )
                }
            }
        }

        // Actions Right
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (canUndo) {
                IconButton(
                    onClick = onUndo,
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Default.Undo,
                        contentDescription = "撤销",
                        tint = Ink,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }

            if (roiEnabled) {
                Box(
                    modifier = Modifier
                        .height(30.dp)
                        .clip(RoundedCornerShape(15.dp))
                        .background(if (roiActive) Amber.copy(alpha = 0.25f) else StudioElevated)
                        .clickable(onClick = onToggleRoi)
                        .padding(horizontal = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = if (roiActive) "标定中" else "标定ROI",
                        color = if (roiActive) Amber else Ink,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Box(
                modifier = Modifier
                    .height(30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(Blue)
                    .clickable(onClick = onPickFile)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.FolderOpen,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "选片",
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}
