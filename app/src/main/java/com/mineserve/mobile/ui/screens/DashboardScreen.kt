package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.produceState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.HeroBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.PillButton
import com.mineserve.mobile.ui.ProgressTrack
import com.mineserve.mobile.ui.QqGroupCard
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.StepRow
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 概览页：完全对齐参考界面 hero + 安装步骤 + 核心选择 + 启停按钮
 * 插件与端口字段拆到对应 Tab，但概览页提供入口按钮（参考界面把插件/端口也放在首页）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(
    vm: McViewModel,
    onShowLogs: () -> Unit,
    onShowDownloadHelp: () -> Unit,
    onShowDiagnostics: () -> Unit
) {
    val config by vm.config.collectAsState()
    val state by vm.serverState.collectAsState()
    val resources by vm.serverResources.collectAsState()
    val diagnosticReport by vm.diagnosticReport.collectAsState()
    val isDiagnosing by vm.isDiagnosing.collectAsState()
    val isRepairingRuntime by vm.isRepairingRuntime.collectAsState()
    val installedPlugins by vm.installedPlugins.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val bootstrapError by vm.bootstrapError.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    val consoleLines by vm.consoleLines.map { it.takeLast(5) }.collectAsState(initial = emptyList())
    val isInstalling by vm.isInstalling.collectAsState()
    val installedJava by vm.installedJava.collectAsState()
    val javaOperation by vm.javaOperation.collectAsState()
    // 依赖是否已全部装齐（installSteps 全部 Done）
    val dependencySteps = state.installSteps.filter { it.step != com.mineserve.mobile.data.InstallStep.Jdk }
    val depsInstalled = dependencySteps.isNotEmpty() && dependencySteps.all {
        it.status == com.mineserve.mobile.data.StepStatus.Done
    }
    val javaInstalled = JavaVersion.values().filter { it != JavaVersion.Java21 }.all { it in installedJava }
    val javaCardAtBottom = config.javaCardAtBottom || javaInstalled
    val downloadProgress by vm.downloadProgress.collectAsState()
    val bootstrapSpeed by vm.bootstrapSpeed.collectAsState()
    val currentMirrorIndex by vm.currentMirrorIndex.collectAsState()
    val installSpeed by vm.installSpeed.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var isStopping by remember { mutableStateOf(false) }
    var showStartSettings by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDeleteDepsConfirm by remember { mutableStateOf(false) }
    var showCoreDropdown by remember { mutableStateOf(false) }
    var showJavaDropdown by remember { mutableStateOf(false) }
    var switchInProgress by remember { mutableStateOf(false) }
    // 服务器图标选择器
    val iconPickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) vm.setServerIcon(uri)
    }
    // Snackbar 文案需在 Composable 上下文取值（onClick 内不可调用 @Composable）
    val mirrorSwitchMsg = stringResource(R.string.mirror_switch_requested)

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
    LaunchedEffect(isBootstrapped) {
        vm.refreshJava()
        vm.refreshDependencies()
        vm.runDiagnostics()
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
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_dashboard), title = stringResource(R.string.s340))
            val activeCore = config.installedCores.find { it.name == config.activeCoreName }
            val coreLabel = activeCore?.let { "${it.name} (${it.core.displayName} ${it.version})" }
                ?: "${config.selectedCore.displayName} ${config.mcVersion}"
            HeroBlock(state = state, coreLabel = coreLabel)
            if (!javaCardAtBottom) {
                JavaManagementCard(
                    vm = vm,
                    installed = installedJava,
                    busy = isInstalling,
                    operation = javaOperation,
                    atBottom = false,
                    allInstalled = false
                )
            }

            McCard(title = "运行诊断", compact = true) {
                val issues = diagnosticReport.issueCount
                Text(
                    when {
                        isRepairingRuntime -> "正在执行安全修复，完成后会自动复检"
                        isDiagnosing -> "正在检查当前服务端和运行环境"
                        diagnosticReport.generatedAtMs == 0L -> "尚未执行诊断"
                        issues == 0 -> "当前运行环境检查通过"
                        else -> "发现 $issues 项需要注意的问题"
                    },
                    color = when { isRepairingRuntime || isDiagnosing -> Indigo; issues == 0 && diagnosticReport.generatedAtMs > 0 -> Mint; else -> Coral },
                    fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = onShowDiagnostics, modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp)) {
                        Text("查看详情", color = Indigo, fontSize = 12.sp)
                    }
                    Button(
                        onClick = { vm.safeRepairRuntime() },
                        enabled = !isDiagnosing && !isRepairingRuntime && isBootstrapped,
                        modifier = Modifier.weight(1f), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo)
                    ) { Text(if (isRepairingRuntime) "修复中" else "一键安全修复", color = Color.White, fontSize = 12.sp) }
                }
            }

            McCard(title = "服务端资源", compact = true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    DeviceStatCell(
                        label = "进程内存",
                        value = resources.processMemoryMb?.let { "${it} MB / ${config.maxHeapMb} MB" } ?: "未运行",
                        modifier = Modifier.weight(1f)
                    )
                    DeviceStatCell(
                        label = "目录可用空间",
                        value = resources.availableBytes?.let(::formatServerBytes) ?: "暂不可用",
                        modifier = Modifier.weight(1f)
                    )
                    DeviceStatCell(
                        label = "Java",
                        value = if (resources.javaAvailable) "${config.selectedJavaVersion.displayName} 已就绪" else "${config.selectedJavaVersion.displayName} 不可用",
                        modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text("服务端目录占用：${resources.directoryBytes?.let(::formatServerBytes) ?: "暂不可用"}", color = Muted, fontSize = 11.sp)
            }

            // ── MC 终端入口（设备状态卡片下方） ──
            OutlinedButton(
                onClick = { vm.launchMcConsole(); onShowLogs() },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
            ) {
                Text(stringResource(R.string.s350), color = Indigo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }

            // bootstrap 初始化进度（未完成时显示）
            if (!isBootstrapped) {
                McCard(title = stringResource(R.string.s351), compact = true) {
                    // 下载提示行（卡片内顶部）
                    DownloadHintHeader(
                        isBusy = true,
                        busyText = stringResource(R.string.s352, state.currentProgress),
                        idleText = stringResource(R.string.s353),
                        speedBps = bootstrapSpeed,
                        onShowHelp = onShowDownloadHelp
                    )
                    Spacer(Modifier.height(8.dp))
                    if (bootstrapError != null) {
                        // 失败时显示错误和重试按钮
                        Text(
                            stringResource(R.string.s354),
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
                            Text(stringResource(R.string.s355), color = Color.White, fontWeight = FontWeight.SemiBold)
                        }
                    } else {
                        // 初始化中
                        Text(
                            stringResource(R.string.s356),
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
                        // 当前镜像源 + 切换按钮（仅下载阶段显示）
                        if (currentMirrorIndex >= 0) {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.s1057, vm.mirrorSources.getOrElse(currentMirrorIndex) { "" }),
                                    color = Muted,
                                    fontSize = 10.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                OutlinedButton(
                                    onClick = {
                                        if (switchInProgress) {
                                            android.util.Log.w("DashboardScreen", "[切换] 按钮点击但 switchInProgress=true, 忽略")
                                            return@OutlinedButton
                                        }
                                        android.util.Log.i("DashboardScreen", "[切换] 按钮点击: currentMirrorIndex=$currentMirrorIndex, 即将调用 vm.switchBootstrapMirror()")
                                        switchInProgress = true
                                        vm.switchBootstrapMirror()
                                        scope.launch {
                                            snackbarHostState.showSnackbar(mirrorSwitchMsg)
                                            delay(1500)
                                            switchInProgress = false
                                            android.util.Log.i("DashboardScreen", "[切换] switchInProgress 已重置为 false")
                                        }
                                    },
                                    enabled = !switchInProgress,
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        stringResource(if (switchInProgress) R.string.mirror_switching else R.string.s1058),
                                        fontSize = 10.sp, color = Indigo
                                    )
                                }
                            }
                        }
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
                title = stringResource(R.string.s357),
                compact = true,
                trailing = {
                    Text(
                        stringResource(R.string.s358),
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
                    busyText = stringResource(R.string.s359),
                    idleText = stringResource(R.string.s360),
                    speedBps = installSpeed,
                    onShowHelp = onShowDownloadHelp
                )
                Spacer(Modifier.height(8.dp))
                dependencySteps.forEachIndexed { idx, step ->
                    val tag = when (step.status) {
                        com.mineserve.mobile.data.StepStatus.Done -> stringResource(R.string.s361)
                        com.mineserve.mobile.data.StepStatus.Active -> stringResource(R.string.s362)
                        com.mineserve.mobile.data.StepStatus.Wait -> stringResource(R.string.s363)
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
                            !isBootstrapped -> stringResource(R.string.s364)
                            isInstalling -> stringResource(R.string.s365)
                            else -> stringResource(R.string.s366)
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
                            stringResource(R.string.s367),
                            color = Coral,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
            }

            // 启动哪个服务端（显示已安装的核心列表，支持下拉选择）
            McCard(title = stringResource(R.string.s368), compact = true) {
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
                                stringResource(R.string.s369),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Text(
                                stringResource(R.string.s370),
                                color = Muted,
                                fontSize = 11.sp
                            )
                        }
                    }
                } else {
                    // 核心选择（强化视觉展示，避免用户忽略该选项）
                    Text(stringResource(R.string.s371), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        if (activeCore != null)
                            stringResource(R.string.s372, activeCore.name, activeCore.core.displayName, activeCore.version)
                        else stringResource(R.string.s373),
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
                                if (activeCore != null) stringResource(R.string.s374, activeCore.name) else stringResource(R.string.s375),
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
                                            if (core.core.isBedrock) {
                                                Text("基岩版 · UDP", color = Coral, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                            }
                                        }
                                    },
                                    onClick = {
                                        vm.setActiveCore(core.name)
                                        showCoreDropdown = false
                                        scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s376, core.name)) }
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // 启停控制
            McCard(
                title = stringResource(R.string.s377),
                compact = true,
                trailing = {
                    IconButton(onClick = { showStartSettings = true }) {
                        Icon(Icons.Outlined.Settings, stringResource(R.string.s378), tint = Indigo, modifier = Modifier.size(18.dp))
                    }
                }
            ) {
                val activeServerCore = config.installedCores.find { it.name == config.activeCoreName }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.weight(0.9f)) {
                        OutlinedButton(
                            onClick = { showJavaDropdown = true },
                            enabled = !state.isRunning,
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) { Text(config.selectedJavaVersion.displayName, color = Indigo, fontSize = 12.sp) }
                        DropdownMenu(
                            expanded = showJavaDropdown,
                            onDismissRequest = { showJavaDropdown = false }
                        ) {
                            JavaVersion.values().forEach { version ->
                                DropdownMenuItem(
                                    text = { Text(version.displayName) },
                                    onClick = { vm.setJavaVersion(version); showJavaDropdown = false }
                                )
                            }
                        }
                    }
                    Button(
                        onClick = {
                            if (state.isRunning) {
                                isStopping = true
                                vm.stopServer()
                                scope.launch { isStopping = false }
                            } else {
                                vm.startServer()
                            }
                        },
                        enabled = if (state.isRunning) !isStopping else isBootstrapped,
                        colors = ButtonDefaults.buttonColors(containerColor = if (state.isRunning) Coral else Mint),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.weight(1.1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                    ) {
                        if (state.isRunning && isStopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            when {
                                state.isRunning && isStopping -> stringResource(R.string.s381)
                                state.isRunning -> stringResource(R.string.s382)
                                else -> stringResource(R.string.s380)
                            },
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 12.sp
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.isRunning) stringResource(R.string.s383) else stringResource(R.string.s384),
                    color = if (state.isRunning) Mint else Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(R.string.s385),
                    color = Muted,
                    fontSize = 10.sp
                )
                if (activeServerCore?.core == ServerCore.PowerNukkitX &&
                    (config.selectedJavaVersion == JavaVersion.Java8 || config.selectedJavaVersion == JavaVersion.Java17)) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "注意：PowerNukkitX 更推荐使用 Java 25；当前 Java 版本可能不兼容，实际要求以核心文档和启动日志为准。",
                        color = Coral,
                        fontSize = 11.sp
                    )
                }
            }

            // ── 服务器图标（server-icon.png，玩家在多人游戏列表看到的图标） ──
            McCard(title = stringResource(R.string.ui_server_icon), compact = true) {
                val activeCoreForIcon = config.installedCores.find { it.name == config.activeCoreName }
                val iconUnsupported = activeCoreForIcon?.core == ServerCore.PowerNukkitX
                val iconVersion by vm.serverIconVersion.collectAsState()
                val iconBmp by produceState<Bitmap?>(initialValue = null, iconVersion) {
                    value = withContext(Dispatchers.IO) {
                        vm.serverIconFile()?.let { BitmapFactory.decodeFile(it.absolutePath) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(IndigoSoft),
                        contentAlignment = Alignment.Center
                    ) {
                        val previewBmp = iconBmp
                        if (previewBmp != null) {
                            Image(
                                bitmap = previewBmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Text("🖼️", fontSize = 22.sp)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (iconUnsupported) "PowerNukkitX 暂不支持 Java Edition 的 server-icon.png。" else stringResource(R.string.ui_server_icon_hint),
                            color = Muted,
                            fontSize = 10.sp,
                            lineHeight = 14.sp
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { iconPickerLauncher.launch("image/*") },
                        enabled = !iconUnsupported,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ui_server_icon_change), color = Color.White, fontSize = 12.sp)
                    }
                    OutlinedButton(
                        onClick = { vm.removeServerIcon() },
                        enabled = !iconUnsupported,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(stringResource(R.string.ui_server_icon_reset), color = Coral, fontSize = 12.sp)
                    }
                }
                // 服务器运行中：更换/删除图标不会立即生效，醒目提示需重启
                if (state.isRunning) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.ui_server_icon_running_warn),
                        color = Coral,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // ── 服务器地址 ──
            McCard(title = stringResource(R.string.s386), compact = true) {
                // 局域网
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        androidx.compose.material3.Text(stringResource(R.string.s387), color = Muted, fontSize = 10.sp)
                        androidx.compose.material3.Text("${lanIp}:${config.localPort}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
                    }
                    // 刷新局域网 IP
                    androidx.compose.material3.IconButton(onClick = { vm.refreshLanIp() }) {
                        androidx.compose.material3.Icon(Icons.Outlined.Refresh, stringResource(R.string.s333), tint = Indigo, modifier = Modifier.size(16.dp))
                    }
                    androidx.compose.material3.IconButton(onClick = {
                        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                        cm.setPrimaryClip(android.content.ClipData.newPlainText("", "${lanIp}:${config.localPort}"))
                    }) {
                        androidx.compose.material3.Icon(Icons.Outlined.ContentCopy, stringResource(R.string.s388), tint = Indigo, modifier = Modifier.size(16.dp))
                    }
                }
                // 公网穿透
                if (tunnelState.publicUrl.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            androidx.compose.material3.Text(stringResource(R.string.s389), color = Muted, fontSize = 10.sp)
                            androidx.compose.material3.Text(tunnelState.publicUrl, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Indigo, maxLines = 1)
                        }
                        androidx.compose.material3.IconButton(onClick = { vm.copyTunnelUrl(context) }) {
                            androidx.compose.material3.Icon(Icons.Outlined.ContentCopy, stringResource(R.string.s388), tint = Indigo, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            // 插件入口（精简）
            McCard(title = stringResource(R.string.s390), compact = true) {
                if (installedPlugins.isEmpty()) {
                    Text(stringResource(R.string.s391), color = Muted, fontSize = 11.sp)
                } else {
                    Text(stringResource(R.string.s392, installedPlugins.size), fontSize = 11.sp)
                }
            }

            // 依赖已装齐：页面底部放「重新安装依赖」小入口 + 「删除依赖」（直接删文件）
            if (depsInstalled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    OutlinedButton(
                        onClick = { vm.installDependencies() },
                        enabled = !isInstalling,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            if (isInstalling) stringResource(R.string.s393) else stringResource(R.string.s394),
                            color = Indigo,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedButton(
                        onClick = { showDeleteDepsConfirm = true },
                        enabled = !isInstalling,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            stringResource(R.string.delete_deps),
                            color = Coral,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }
            // QQ 交流群入口
            if (javaCardAtBottom) {
                JavaManagementCard(
                    vm = vm,
                    installed = installedJava,
                    busy = isInstalling,
                    operation = javaOperation,
                    atBottom = true,
                    allInstalled = javaInstalled
                )
            }
            QqGroupCard()
            Spacer(Modifier.height(16.dp))
        }

        // 服务器启动设置弹窗
        if (showStartSettings) {
            AlertDialog(
                onDismissRequest = { showStartSettings = false },
                title = { Text(stringResource(R.string.s395), fontWeight = FontWeight.Bold) },
                text = {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(stringResource(R.string.s396), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                Text(
                                    stringResource(R.string.s397),
                                    color = Muted,
                                    fontSize = 10.sp
                                )
                            }
                            Switch(
                                checked = config.autoRestartOnCrash,
                                onCheckedChange = { vm.setAutoRestart(it) }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.s398, config.maxHeapMb),
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { showStartSettings = false }) {
                        Text(stringResource(R.string.s73), color = Indigo, fontWeight = FontWeight.SemiBold)
                    }
                }
            )
        }

        // 删除运行环境确认对话框
        if (showDeleteConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteConfirm = false },
                title = { Text(stringResource(R.string.s399), fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        stringResource(R.string.s400),
                        color = Muted,
                        fontSize = 12.sp
                    )
                },
                confirmButton = {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                vm.deleteBootstrap()
                            }
                        ) {
                            Text(stringResource(R.string.s401), color = Coral, fontWeight = FontWeight.SemiBold)
                        }
                        TextButton(
                            onClick = {
                                showDeleteConfirm = false
                                vm.forceDeleteBootstrap()
                            }
                        ) {
                            Text(stringResource(R.string.force_delete), color = Coral, fontWeight = FontWeight.SemiBold)
                        }
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteConfirm = false }
                    ) {
                        Text(stringResource(R.string.s402), color = Muted)
                    }
                }
            )
        }

        // 删除依赖确认框（直接删除运行环境与依赖文件，不自动重装）
        if (showDeleteDepsConfirm) {
            AlertDialog(
                onDismissRequest = { showDeleteDepsConfirm = false },
                title = { Text(stringResource(R.string.delete_deps_title), fontWeight = FontWeight.Bold) },
                text = {
                    Text(
                        stringResource(R.string.delete_deps_content),
                        color = Muted,
                        fontSize = 12.sp
                    )
                },
                confirmButton = {
                    TextButton(
                        onClick = {
                            showDeleteDepsConfirm = false
                            vm.forceDeleteBootstrap()
                        }
                    ) {
                        Text(stringResource(R.string.delete_deps), color = Coral, fontWeight = FontWeight.SemiBold)
                    }
                },
                dismissButton = {
                    TextButton(
                        onClick = { showDeleteDepsConfirm = false }
                    ) {
                        Text(stringResource(R.string.s402), color = Muted)
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
private fun JavaManagementCard(
    vm: McViewModel,
    installed: Set<JavaVersion>,
    busy: Boolean,
    operation: String?,
    atBottom: Boolean,
    allInstalled: Boolean
) {
    McCard(title = "Java 运行环境", compact = true) {
        Text("仅提供 Java 8、Java 17、Java 25", color = Muted, fontSize = 11.sp)
        Text(
            "注意：Java 8 使用 Ubuntu ARM64 glibc 原生运行库，非 Termux 官方源；需要 Ubuntu 容器。",
            color = Coral,
            fontSize = 11.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        if (operation != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                Spacer(Modifier.width(8.dp))
                Text(operation, color = Indigo, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        JavaVersion.values().forEach { version ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(version.displayName, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Text(
                    if (version in installed) "已安装" else "未安装",
                    color = if (version in installed) Mint else Muted,
                    fontSize = 11.sp
                )
                if (version !in installed) {
                    TextButton(onClick = { vm.installJava(version) }, enabled = !busy) {
                        Text("安装", color = Indigo)
                    }
                }
            }
        }
        if (JavaVersion.values().filter { it != JavaVersion.Java21 }.all { it in installed }) {
            OutlinedButton(
                onClick = { vm.clearAndReinstallJava() },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) { Text("清除并重装 Java", color = Coral, fontSize = 12.sp) }
        }
        Spacer(Modifier.height(6.dp))
        if (allInstalled) {
            Text("三个 Java 版本均已安装，卡片已自动置于页面底部", color = Muted, fontSize = 11.sp)
        } else {
            OutlinedButton(
                onClick = { vm.setJavaCardAtBottom(!atBottom) },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(
                    if (atBottom) "恢复默认位置" else "将卡片移至页面底部",
                    color = Indigo,
                    fontSize = 12.sp
                )
            }
        }
    }
}

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
            stringResource(R.string.s403),
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

private fun formatServerBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
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

/** 字节数格式化（网络流量）：KB/MB/GB */
private fun formatNetBytes(bytes: Long): String = when {
    bytes >= 1024 * 1024 * 1024 -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
    bytes >= 1024 * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
    bytes >= 1024 -> String.format("%.1fKB", bytes / 1024.0)
    else -> "${bytes}B"
}
