package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkScreen(vm: McViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val consoleLines by vm.consoleLines.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refreshLanIp() }
    // 收集操作结果和错误消息，通过 Snackbar 显示（修复"点击无反应"Bug的关键）
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
        // 返回栏（白底覆盖状态栏，配合全屏展示）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.s404))
            }
            Text(stringResource(R.string.s541), fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        }
        HeaderBlock(eyebrow = "Networking", title = stringResource(R.string.s585), statusBarPadding = false)

        // ── 服务器连接信息（局域网） ──────────────────────────
        McCard(title = stringResource(R.string.s586)) {
            // 运行状态指示灯
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (serverState.isRunning) Color(0xFF4CAF50) else Color(0xFFB0B7C3))
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (serverState.isRunning) stringResource(R.string.s587) else stringResource(R.string.s280),
                    fontSize = 12.sp,
                    color = if (serverState.isRunning) Color(0xFF2E7D32) else Muted,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))

            // 本地回环地址（本机直连，同设备测试用）
            Text(stringResource(R.string.s588), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF5F6FA))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    "127.0.0.1:${config.localPort}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MC Server", "127.0.0.1:${config.localPort}"))
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.s388), tint = Indigo)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.s589),
                color = Muted, fontSize = 10.sp
            )

            Spacer(Modifier.height(12.dp))

            // 局域网 IP（其他设备连接用）
            Text(stringResource(R.string.s590), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lanIp, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.refreshLanIp() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = stringResource(R.string.s591))
                }
            }
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.s592), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("${config.localPort}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Text(stringResource(R.string.s593), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF5F6FA))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    if (lanIp == "--") "--:${config.localPort}" else "$lanIp:${config.localPort}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.copyServerAddress(context) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.s388), tint = Indigo)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.s594),
                color = Muted, fontSize = 11.sp
            )
        }

        // ── 内网穿透状态卡片 ──────────────────────────────────
        TunnelStatusCard(
            tunnelState = tunnelState,
            onStart = { vm.startTunnel() },
            onStop = { vm.stopTunnel() },
            onCopyUrl = { vm.copyTunnelUrl(context) }
        )

        // ── 隧道日志预览 ──────────────────────────────────────
        TunnelLogPreview(consoleLines)

        // ── 本地端口配置 ──────────────────────────────────────
        McCard(title = stringResource(R.string.s595)) {
            Text(stringResource(R.string.s596), color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            DebouncedTextField(
                value = config.localPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.setLocalPort(it) } },
                sanitize = { it.filter(Char::isDigit) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── 内网穿透配置 ──────────────────────────────────────
        McCard(title = stringResource(R.string.s597)) {
            // 穿透类型选择器
            Text(stringResource(R.string.s598), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            TunnelType.values().forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (config.tunnelType == type) IndigoSoft else Color.Transparent
                        )
                        .clickable { vm.setTunnelType(type) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = config.tunnelType == type,
                        onClick = { vm.setTunnelType(type) },
                        colors = RadioButtonDefaults.colors(selectedColor = Indigo)
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(type.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(type.description, color = Muted, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 根据穿透类型显示不同配置
            AnimatedVisibility(
                visible = config.tunnelType == TunnelType.Frp,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Text(stringResource(R.string.s599), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    DebouncedTextField(
                        value = config.frpConfigText,
                        onValueChange = { v -> vm.updateConfig { it.copy(frpConfigText = v) } },
                        placeholder = { Text(stringResource(R.string.s600)) },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.s601),
                        color = Muted, fontSize = 10.sp
                    )
                }
            }

            AnimatedVisibility(
                visible = config.tunnelType == TunnelType.Bore,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Text(stringResource(R.string.s602), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    DebouncedTextField(
                        value = config.boreServerAddr,
                        onValueChange = { v -> vm.updateConfig { it.copy(boreServerAddr = v) } },
                        placeholder = { Text(stringResource(R.string.s603)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.s604),
                        color = Muted, fontSize = 10.sp
                    )
                }
            }
        }

        // ── 免费 FRP 平台 ──────────────────────────────────────
        FreeFrpPlatformsCard(context)

        // ── 操作指南（说明书） ──────────────────────────────────
        GuideSection(config.tunnelType)

        Spacer(Modifier.height(16.dp))
        }
    }
}

// ── 隧道状态卡片 ──────────────────────────────────────────────

@Composable
private fun TunnelStatusCard(
    tunnelState: TunnelState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyUrl: () -> Unit
) {
    McCard(title = stringResource(R.string.s605)) {
        // 状态指示灯 + 状态文本
        Row(verticalAlignment = Alignment.CenterVertically) {
            val unknownError = stringResource(R.string.s7)
            val (dotColor, statusText, statusColor) = when (tunnelState.status) {
                TunnelStatus.Running -> Triple(Color(0xFF4CAF50), stringResource(R.string.s606), Color(0xFF2E7D32))
                TunnelStatus.Starting -> Triple(Color(0xFFFFA726), stringResource(R.string.s379), Color(0xFFEF6C00))
                TunnelStatus.Failed -> Triple(Color(0xFFEF5350), stringResource(R.string.s607, tunnelState.errorMessage.ifBlank { unknownError }), Coral)
                TunnelStatus.Stopped -> Triple(Color(0xFFB0B7C3), stringResource(R.string.s608), Muted)
                TunnelStatus.Idle -> Triple(Color(0xFFB0B7C3), stringResource(R.string.s609), Muted)
            }
            if (tunnelState.status == TunnelStatus.Starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = statusColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(statusText, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            if (tunnelState.activeType != null) {
                Spacer(Modifier.size(6.dp))
                Text("·", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.size(6.dp))
                Text(tunnelState.activeType!!.displayName, color = Muted, fontSize = 11.sp)
            }
        }

        // 公网地址展示（始终显示，退出后也保留）
        if (tunnelState.publicUrl.isNotBlank()) {
            val isActive = tunnelState.status == TunnelStatus.Running
            Spacer(Modifier.height(12.dp))
            Text(
                if (isActive) stringResource(R.string.s610) else stringResource(R.string.s611),
                color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isActive) Color(0xFFE8F5E9) else Color(0xFFFFF3E0))
                    .border(1.dp, if (isActive) Color(0xFF4CAF50) else Color(0xFFFFA726), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    tunnelState.publicUrl,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCopyUrl) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.s612), tint = Indigo)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                if (isActive) stringResource(R.string.s613)
                else stringResource(R.string.s614),
                color = Muted, fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // 启停按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = tunnelState.status != TunnelStatus.Starting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo,
                    disabledContainerColor = Color(0xFFB0BEC5)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.s615), color = Color.White)
            }

            Button(
                onClick = onStop,
                enabled = tunnelState.status == TunnelStatus.Running || tunnelState.status == TunnelStatus.Starting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8890A0),
                    disabledContainerColor = Color(0xFFD0D4DA)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text(stringResource(R.string.s382), color = Color.White)
            }
        }
    }
}

// ── 隧道日志预览 ──────────────────────────────────────────────

@Composable
private fun TunnelLogPreview(consoleLines: List<String>) {
    // 过滤出隧道相关日志
    val tunnelLogs: List<String> = remember(consoleLines) {
        consoleLines.filter { it.contains("[tunnel]") }
    }

    if (tunnelLogs.isEmpty()) return

    var showFullLog by remember { mutableStateOf(false) }

    McCard(title = stringResource(R.string.s616)) {
        // 最近 8 条预览
        tunnelLogs.takeLast(8).forEach { line ->
            Text(
                line,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Muted,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }

        // 全屏按钮
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showFullLog = true },
            colors = ButtonDefaults.buttonColors(containerColor = IndigoSoft),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.s617, tunnelLogs.size), fontSize = 12.sp)
        }
    }

    // 全屏日志 Dialog
    if (showFullLog) {
        AlertDialog(
            onDismissRequest = { showFullLog = false },
            title = {
                Text(stringResource(R.string.s618, tunnelLogs.size), fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (tunnelLogs.isEmpty()) {
                        Text(stringResource(R.string.s619), color = Muted, fontSize = 13.sp)
                    } else {
                        tunnelLogs.forEach { line ->
                            Text(
                                line,
                                fontSize = 10.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Muted,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp)
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(onClick = { showFullLog = false }) {
                    Text(stringResource(R.string.s620))
                }
            }
        )
    }
}

// ── 操作指南 ──────────────────────────────────────────────────

@Composable
private fun GuideSection(tunnelType: TunnelType) {
    var expanded by remember { mutableStateOf(true) }

    McCard(title = stringResource(R.string.s621)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.s622, if (expanded) stringResource(R.string.s620) else stringResource(R.string.s623)),
                color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = Indigo
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                when (tunnelType) {
                    TunnelType.Frp -> FrpGuide()
                    TunnelType.Bore -> BoreGuide()
                }
            }
        }
    }
}

@Composable
private fun FrpGuide() {
    GuideBlock(title = stringResource(R.string.s624)) {
        Text(
            stringResource(R.string.s625),
            color = Coral, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
        GuideStep("1", stringResource(R.string.s626), stringResource(R.string.s627))
        GuideStep("2", stringResource(R.string.s628), stringResource(R.string.s629))
        GuideStep("3", stringResource(R.string.s630), stringResource(R.string.s631))
        GuideStep("4", stringResource(R.string.s633), stringResource(R.string.s634))
        GuideStep("5", stringResource(R.string.s635), stringResource(R.string.s636))
        GuideStep("6", stringResource(R.string.s637), stringResource(R.string.s638))
        GuideStep("7", stringResource(R.string.s639), stringResource(R.string.s640))
    }
}

@Composable
private fun BoreGuide() {
    GuideBlock(title = stringResource(R.string.s641)) {
        GuideStep("1", stringResource(R.string.s642), stringResource(R.string.s643))
        GuideStep("2", stringResource(R.string.s628), stringResource(R.string.s644))
        GuideStep("3", stringResource(R.string.s645), stringResource(R.string.s646))
        GuideStep("4", stringResource(R.string.s635), stringResource(R.string.s647))
        GuideStep("5", stringResource(R.string.s648), stringResource(R.string.s649))
        GuideStep("6", stringResource(R.string.s650), stringResource(R.string.s651))
    }
}

@Composable
private fun GuideBlock(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Indigo)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun GuideStep(step: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}

// ── 免费 FRP 平台 ──────────────────────────────────────────────

/** 免费 FRP 平台清单（官方网址，点击跳转浏览器） */
private data class FrpPlatform(val name: String, val url: String)

private val freeFrpPlatforms = listOf(
    FrpPlatform("OpenFrp", "https://www.openfrp.net/"),
    FrpPlatform("ChmlFrp", "https://www.chmlfrp.cn/"),
    FrpPlatform("StarryFrp（星空FRP）", "https://frp.starryfrp.com/"),
    FrpPlatform("SakuraFrp（樱花映射）", "https://www.natfrp.com/")
)

@Composable
private fun FreeFrpPlatformsCard(context: Context) {
    McCard(title = stringResource(R.string.s654)) {
        Text(
            stringResource(R.string.s655),
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(10.dp))
        freeFrpPlatforms.forEach { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IndigoSoft)
                    .clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                        } catch (e: Exception) {
                            // 设备上无可用浏览器时静默忽略
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.url, color = Indigo, fontSize = 10.sp)
                }
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = stringResource(R.string.s656),
                    tint = Indigo,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
