package com.mcserver.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.data.ServerState
import com.mcserver.manager.data.StepStatus
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.CoralSoft
import com.mcserver.manager.ui.theme.FieldGray
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoDark
import com.mcserver.manager.ui.theme.IndigoRingBg
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Line
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.MintBright
import com.mcserver.manager.ui.theme.MintSoft
import com.mcserver.manager.ui.theme.Muted

/**
 * 顶部 Header：参考界面 eyebrow + h1
 */
@Composable
fun HeaderBlock(eyebrow: String, title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 20.dp, vertical = 22.dp)
    ) {
        Text(
            text = eyebrow,
            color = Muted,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.6.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = title,
            color = MaterialTheme.colorScheme.onBackground,
            fontSize = 21.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/**
 * Hero 卡片：参考界面 gradient indigo + 环形进度 + 3 项统计
 */
@Composable
fun HeroBlock(state: ServerState, coreLabel: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 16.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(colors = listOf(Indigo, IndigoDark))
            )
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // 环形进度：服务器未运行时显示 0%
            RingProgress(percent = if (state.isRunning) state.healthPercent else 0)
            Spacer(Modifier.size(18.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    if (state.isRunning) "服务器健康度 · $coreLabel" else "服务器未启动",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    HeroStat(
                        if (state.isRunning) String.format("%.1f", state.tps) else "--",
                        "TPS"
                    )
                    HeroStat(
                        if (state.isRunning) "${state.onlinePlayers}/${state.maxPlayers}" else "--/--",
                        "在线"
                    )
                    HeroStat(
                        if (state.isRunning) formatMemory(state.usedMemoryMb) else "--",
                        "内存"
                    )
                }
            }
        }
    }
}

@Composable
private fun HeroStat(value: String, label: String) {
    Column {
        Text(
            value,
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold
        )
        Text(
            label,
            color = Color.White.copy(alpha = 0.75f),
            fontSize = 10.sp
        )
    }
}

@Composable
private fun RingProgress(percent: Int) {
    Box(
        modifier = Modifier
            .size(74.dp)
            .clip(CircleShape)
            .background(
                Brush.sweepGradient(
                    colors = listOf(
                        MintBright,
                        MintBright,
                        Color.White.copy(alpha = 0.18f)
                    )
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(CircleShape)
                .background(IndigoRingBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "$percent%",
                color = Color.White,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 通用卡片容器：参考界面圆角 18 + 白底 + 1px line 边
 */
@Composable
fun McCard(
    title: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp),
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, Line)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
                trailing?.invoke()
            }
            Spacer(Modifier.height(12.dp))
            content()
        }
    }
}

/**
 * 步骤行（参考界面 step-row）
 */
@Composable
fun StepRow(
    name: String,
    status: StepStatus,
    tag: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (bg, fg, text) = when (status) {
            StepStatus.Done -> Triple(MintSoft, Mint, "✓")
            StepStatus.Active -> Triple(IndigoSoft, Indigo, "•")
            StepStatus.Wait -> Triple(FieldGray, Muted, "○")
        }
        Box(
            modifier = Modifier
                .size(22.dp)
                .clip(CircleShape)
                .background(bg),
            contentAlignment = Alignment.Center
        ) {
            Text(text, color = fg, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Text(
            name,
            color = if (status == StepStatus.Wait) Muted else MaterialTheme.colorScheme.onSurface,
            fontSize = 13.sp,
            modifier = Modifier.weight(1f)
        )
        Text(tag, color = Muted, fontSize = 10.sp)
    }
}

/**
 * 进度条（参考界面 progress-track）
 */
@Composable
fun ProgressTrack(percent: Int) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(8.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(FieldGray)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(percent / 100f)
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(
                    Brush.linearGradient(
                        colors = listOf(Indigo, Color(0xFF6E7FD6))
                    )
                )
        )
    }
}

/**
 * 圆角分段按钮：参考界面 .seg / .on
 */
@Composable
fun SegPill(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {}
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) Indigo else FieldGray)
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        Text(
            text,
            color = if (selected) Color.White else Muted,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

/**
 * Pill 按钮：参考界面 .pill-btn
 */
@Composable
fun PillButton(
    text: String,
    install: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    val (bg, fg) = if (install) Indigo to Color.White else CoralSoft to Coral
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(
            text,
            color = fg,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

private fun formatMemory(mb: Long): String =
    if (mb >= 1024) String.format("%.1fG", mb / 1024.0) else "${mb}M"
