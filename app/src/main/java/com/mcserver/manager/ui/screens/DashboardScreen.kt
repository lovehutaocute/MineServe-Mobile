package com.mcserver.manager.ui.screens

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.data.InstalledCore
import com.mcserver.manager.data.ServerCore
import com.mcserver.manager.data.ServerState
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.HeroBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.PillButton
import com.mcserver.manager.ui.ProgressTrack
import com.mcserver.manager.ui.SegPill
import com.mcserver.manager.ui.StepRow
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 概览页：完全对齐参考界面 hero + 安装步骤 + 核心选择 + 启停按钮
 * 插件与端口字段拆到对应 Tab，但概览页提供入口按钮（参考界面把插件/端口也放在首页）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: McViewModel, onShowLogs: () -> Unit, onShowDownloadHelp: () -> Unit) {
    val config by vm.config.collectAsState()
    val state by vm.serverState.collectAsState()
    val deviceStats by vm.deviceStats.collectAsState()
    val installedPlugins by vm.installedPlugins.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val bootstrapError by vm.bootstrapError.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val consoleLines by vm.consoleLines.map { it.takeLast(5) }.collectAsState(initial = emptyList())
    val isInstalling by vm.isInstalling.collectAsState()
    // 依赖是否已全部装齐（installSteps 全部 Done）
    val depsInstalled = state.installSteps.isNotEmpty() &&
        state.installSteps.all { it.status == com.mcserver.manager.data.StepStatus.Done }
    val downloadProgress by vm.downloadProgress.collectAsState()
    val bootstrapSpeed by vm.bootstrapSpeed.collectAsState()
    val installSpeed by vm.installSpeed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showCoreDropdown by remember { mutableStateOf(false) }

    // 收集 errorFlow 和 messageFlow，显示 Snackbar
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { msg ->
            snackbarHostState.showSnackbar(msg)
        }
    }
    // 当核心或环境状态变化时，刷新真实插件列表
    LaunchedEffect(isBootstrapped, config.activeCoreName) {
        if (isBootstrapped && config.activeCoreName != null) {
            vm.refreshInstalledPlugins()
        }
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
            HeaderBlock(eyebrow = "Local Server", title = "云控面板")
            val activeCore = config.installedCores.find { it.name == config.activeCoreName }
            val coreLabel = activeCore?.let { "${it.name} (${it.core.displayName} ${it.version})" }
                ?: "${config.selectedCore.displayName} ${config.mcVersion}"
            HeroBlock(state = state, coreLabel = coreLabel)

            // ── 设备状态卡片（常规权限可采集） ──
            McCard(title = "设备状态") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DeviceStatCell(
                        label = "设备内存",
                        value = if (deviceStats.totalMemoryMb > 0)
                            "${formatDeviceMb(deviceStats.availMemoryMb)} / ${formatDeviceMb(deviceStats.totalMemoryMb)}"
                        else "--",
                        modifier = Modifier.weight(1f)
                    )
                    DeviceStatCell(
                        label = "存储",
                        value = if (deviceStats.totalStorageMb > 0)
                            "${formatDeviceMb(deviceStats.availStorageMb)} / ${formatDeviceMb(deviceStats.totalStorageMb)}"
                        else "--",
                        modifier = Modifier.weight(1f)
                    )
                    DeviceStatCell(
                        label = "电池",
                        value = when {
                            deviceStats.batteryPercent < 0 -> "--"
                            deviceStats.isCharging -> "${deviceStats.batteryPercent}% 充电中"
                            else -> "${deviceStats.batteryPercent}%"
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }

            // ── MC 终端入口（设备状态卡片下方） ──
            Button(
                onClick = { vm.launchMcConsole(); onShowLogs() },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp)
            ) {
                Text("▶ 打开 MC 终端", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            // bootstrap 初始化进度（未完成时显示）
            if (!isBootstrapped) {
                McCard(title = "初始化运行环境") {
                    // 下载提示行（卡片内顶部）
                    DownloadHintHeader(
                        isBusy = true,
                        busyText = "正在下载并解压 Termux 环境（${state.currentProgress}%）",
                        idleText = "下载 Termux 运行环境（约 50MB）",
                        speedBps = bootstrapSpeed,
                        onShowHelp = onShowDownloadHelp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (bootstrapError != null) {
                        // 失败时显示错误和重试按钮
                        Text(
                            "初始化失败",
                            color = Coral,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            bootstrapError!!,
                            color = Muted,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        // 显示最近的 bootstrap 日志
                        consoleLines.takeLast(3).forEach { line ->
                            Text(
                                line,
                                color = Muted.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.retryBootstrap() },
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重试", color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        // 初始化中
                        Text(
                            "正在下载并解压 Termux 运行环境，请耐心等待...",
                            color = Muted,
                            fontSize = 12.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(
                            progress = state.currentProgress / 100f,
                            modifier = Modifier.fillMaxWidth(),
                            color = Indigo,
                            trackColor = IndigoSoft
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "${state.currentProgress}%",
                            color = Muted,
                            fontSize = 10.sp
                        )
                        // 显示实时日志
                        Spacer(Modifier.height(8.dp))
                        consoleLines.takeLast(5).forEach { line ->
                            Text(
                                line,
                                color = Muted.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }

            // 一键安装依赖（依赖未装齐时在页面显眼位置展示；装齐后移到底部）
            if (!depsInstalled) {
            McCard(
                title = "一键安装依赖",
                trailing = {
                    Text(
                        "查看日志",
                        color = Indigo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onShowLogs() }
                    )
                }
            ) {
                // 下载提示行（卡片内顶部）
                DownloadHintHeader(
                    isBusy = isInstalling,
                    busyText = "正在通过 apt 下载 JDK / wget / frp",
                    idleText = "通过 apt 安装 openjdk / wget / frp 等依赖",
                    speedBps = installSpeed,
                    onShowHelp = onShowDownloadHelp
                )
                Spacer(Modifier.height(8.dp))
                state.installSteps.forEachIndexed { idx, step ->
                    val tag = when (step.status) {
                        com.mcserver.manager.data.StepStatus.Done -> "已完成"
                        com.mcserver.manager.data.StepStatus.Active -> "进行中"
                        com.mcserver.manager.data.StepStatus.Wait -> "待安装"
                    }
                    StepRow(name = "${idx + 1}. ${step.step.label}", status = step.status, tag = tag)
                }
                Spacer(Modifier.height(10.dp))
                ProgressTrack(percent = state.currentProgress)

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        vm.installDependencies()
                    },
                    enabled = !isInstalling && isBootstrapped,
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        when {
                            !isBootstrapped -> "环境初始化中..."
                            isInstalling -> "安装中..."
                            else -> "开始安装"
                        },
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                // 删除运行环境按钮（仅在环境就绪且非安装中时显示）
                if (isBootstrapped && !isInstalling) {
                    Spacer(Modifier.height(8.dp))
                    TextButton(
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            "删除 Termux 运行环境",
                            color = Coral,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            }

            // 启动哪个服务端（显示已安装的核心列表，支持下拉选择）
            McCard(title = "启动服务端") {
                val installed = config.installedCores
                val activeCore = installed.find { it.name == config.activeCoreName }
                if (installed.isEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(IndigoSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↓", color = Indigo, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "尚未下载服务端核心",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                "请切换到「下载」Tab 下载服务端核心",
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    // 核心选择（强化视觉展示，避免用户忽略该选项）
                    Text("选择服务器核心", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (activeCore != null)
                            "当前核心：${activeCore.name}（${activeCore.core.displayName} ${activeCore.version}）"
                        else "尚未选择核心！请点击下方按钮选择后再启动",
                        color = if (activeCore != null) Mint else Coral,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth()) {
                        Button(
                            onClick = { showCoreDropdown = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (activeCore != null) IndigoSoft else Indigo,
                                contentColor = if (activeCore != null) Indigo else Color.White
                            ),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                if (activeCore != null) "切换服务器核心：${activeCore.name}" else "▼ 选择服务器核心",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                        DropdownMenu(
                            expanded = showCoreDropdown,
                            onDismissRequest = { showCoreDropdown = false }
                        ) {
                            installed.forEach { core ->
                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text(core.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                            Text(
                                                "${core.core.displayName} ${core.version}",
                                                color = Muted,
                                                fontSize = 11.sp
                                            )
                                        }
                                    },
                                    onClick = {
                                        vm.setActiveCore(core.name)
                                        showCoreDropdown = false
                                        scope.launch { snackbarHostState.showSnackbar("已选用「${core.name}」") }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 启停控制
            McCard(title = "服务控制") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            isStarting = true
                            vm.startServer()
                            scope.launch { isStarting = false }
                        },
                        enabled = !isStarting && !state.isRunning && isBootstrapped,
                        colors = ButtonDefaults.buttonColors(containerColor = Mint),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            if (isStarting) "启动中..." else "启动",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            isStopping = true
                            vm.stopServer()
                            scope.launch { isStopping = false }
                        },
                        enabled = !isStopping && state.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isStopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            if (isStopping) "停止中..." else "停止",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.isRunning) "状态：运行中" else "状态：已停止",
                    color = if (state.isRunning) Mint else Muted,
                    fontSize = 11.sp
                )
            }

            // ── 服务器地址 ──
            McCard(title = "服务器地址") {
                // 局域网
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text("局域网", color = Muted, fontSize = 10.sp)
                        androidx.compose.material3.Text("${lanIp}:${config.localPort}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    androidx.compose.material3.IconButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("", "${lanIp}:${config.localPort}"))
                    }) {
                        androidx.compose.material3.Icon(Icons.Outlined.ContentCopy, "复制", tint = Indigo, modifier = Modifier.size(16.dp))
                    }
                }
                // 公网穿透
                if (tunnelState.publicUrl.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text("公网穿透", color = Muted, fontSize = 10.sp)
                            androidx.compose.material3.Text(tunnelState.publicUrl, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Indigo, maxLines = 1)
                        }
                        androidx.compose.material3.IconButton(onClick = { vm.copyTunnelUrl(context) }) {
                            androidx.compose.material3.Icon(Icons.Outlined.ContentCopy, "复制", tint = Indigo, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // 插件入口（精简）
            McCard(title = "已安装插件") {
                if (installedPlugins.isEmpty()) {
                    Text("暂无已安装插件", color = Muted, fontSize = 11.sp)
                } else {
                    Text("${installedPlugins.size} 个插件已安装", fontSize = 11.sp)
                }
            }

            // 依赖已装齐：页面底部放「重新安装依赖」小入口
            if (depsInstalled) {
                OutlinedButton(
                    onClick = { vm.installDependencies() },
                    enabled = !isInstalling,
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                ) {
                    Text(
                        if (isInstalling) "重新安装中..." else "重新安装依赖",
                        color = Indigo,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        // 删除运行环境确认对话框
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text("删除运行环境", fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        "将删除 Termux 运行环境（包括所有已安装的依赖包和缓存），删除后会自动重新下载和初始化。此操作不可撤销。",
                        color = Muted,
                        fontSize = 12.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteConfirm = false
                            vm.deleteBootstrap()
                        }
                    ) {
                        Text("确认删除", color = Coral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirm = false }
                    ) {
                        Text("取消", color = Muted)
                    }
                }
            )
        }
    }
}

/**
 * 下载提示行（嵌入卡片内顶部使用）
 *
 * 简洁的一行布局：左侧状态文字+速度（含下载中旋转图标），右侧"下载慢?查看解决方式"按钮。
 * - isBusy=true 时显示 busyText + 速度 + 旋转图标
 * - isBusy=false 时显示 idleText + 下载图标
 */
@Composable
private fun DownloadHintHeader(
    isBusy: Boolean,
    busyText: String,
    idleText: String,
    speedBps: Long = 0L,
    onShowHelp: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 左侧：状态图标 + 文字 + 速度
        if (isBusy) {
            CircularProgressIndicator(
                modifier = Modifier.size(14.dp),
                color = Indigo,
                strokeWidth = 2.dp
            )
        } else {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Indigo.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Text("↓", color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
        Spacer(Modifier.size(8.dp))
        Text(
            if (isBusy) busyText else idleText,
            color = if (isBusy) Indigo else Muted,
            fontSize = 11.sp,
            fontWeight = if (isBusy) FontWeight.SemiBold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        // 速度显示（仅忙碌且有速度时显示）
        if (isBusy && speedBps > 0) {
            Text(
                formatSpeedShort(speedBps),
                color = Indigo,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.size(8.dp))
        }
        // 右侧：下载慢?查看解决方式
        Text(
            "下载慢?查看解决方式 →",
            color = Indigo,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onShowHelp() }
        )
    }
}

/** 格式化速度为简短字符串（如 2.3 MB/s, 456 KB/s） */
private fun formatSpeedShort(bytesPerSec: Long): String {
    return when {
        bytesPerSec >= 1_048_576 -> String.format("%.1f MB/s", bytesPerSec / 1_048_576.0)
        bytesPerSec >= 1024 -> String.format("%.0f KB/s", bytesPerSec / 1024.0)
        else -> "$bytesPerSec B/s"
    }
}

// ── 设备状态卡片辅助组件 ────────────────────────────────────────────

@Composable
private fun DeviceStatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(2.dp))
        Text(
            value,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** MB 数值格式化：超过 1GB 显示 G */
private fun formatDeviceMb(mb: Long): String =
    if (mb >= 1024) String.format("%.1fG", mb / 1024.0) else "${mb}M"
