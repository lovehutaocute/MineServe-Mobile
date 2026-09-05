package com.mineserve.mobile.ui.screens

// 性能修改理由：资源订阅保持局部化，输入框使用本地编辑状态，并让概览内容避让输入法。
import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Construction
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
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
import com.mineserve.mobile.ui.DebouncedTextField
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    onShowDownloadHelp: () -> Unit,
    onShowDiagnostics: () -> Unit,
    onEnvManager: (Int) -> Unit
) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showStartSettings by remember { mutableStateOf(false) }
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
    // 首屏优先完成布局；各功能卡片自行订阅并刷新局部状态。

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .imePadding()
        ) {
            item {
                HeaderBlock(
                    eyebrow = stringResource(R.string.eyebrow_dashboard),
                    title = stringResource(R.string.s340),
                    trailing = {
                        // 视觉突出的「依赖与环境管理」入口（靛蓝填充 + 图标，高对比）
                        Button(
                            onClick = { onEnvManager(0) },
                            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                            shape = RoundedCornerShape(14.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Icon(
                                Icons.Outlined.Construction,
                                contentDescription = stringResource(R.string.env_entry_desc),
                                tint = androidx.compose.ui.graphics.Color.White,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text(
                                stringResource(R.string.env_title),
                                color = androidx.compose.ui.graphics.Color.White,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1
                            )
                        }
                    }
                )
            }

            item {
                DashboardHeroBlock(
                    vm = vm,
                    onRefresh = { vm.refreshServerStatus() }
                )
            }

            item {
                // Java 运行环境未安装时的初始入口（安装成功后自动隐藏）
                DashboardJavaInstallCard(vm = vm)
            }

            item {
                DashboardDiagnosticsCard(
                    vm = vm,
                    isBootstrapped = isBootstrapped,
                    onShowDiagnostics = onShowDiagnostics
                )
            }

            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    DashboardResourceCard(vm = vm)

                    // ── MC 终端入口（设备状态卡片下方） ──
                    DashboardBootstrapCard(
                        vm = vm,
                        onShowHelp = onShowDownloadHelp,
                        onShowMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                    )

                    // 一键安装依赖（依赖未装齐时在页面显眼位置展示；装齐后移到底部）
                    DashboardDependenciesCard(vm = vm, onShowHelp = onShowDownloadHelp)

                    DashboardCoreSelectionCard(
                        vm = vm,
                        onShowMessage = { message -> scope.launch { snackbarHostState.showSnackbar(message) } }
                    )

                    DashboardServerControlCard(
                        vm = vm,
                        isBootstrapped = isBootstrapped,
                        onShowSettings = { showStartSettings = true }
                    )

                    AdvancedStartupCard(vm = vm)
                    DashboardAddressCard(vm = vm)
                    DashboardPluginsCard(vm = vm)
                    QqGroupCard()
                }
            }
        }

        // 服务器启动设置弹窗
        if (showStartSettings) {
            val autoRestartOnCrash by vm.config.map { it.autoRestartOnCrash }.distinctUntilChanged().collectAsState(initial = false)
            val configuredMaxHeapMb by vm.config.map { it.maxHeapMb }.distinctUntilChanged().collectAsState(initial = 1024)
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
                                checked = autoRestartOnCrash,
                                onCheckedChange = { vm.setAutoRestart(it) }
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        Text(
                            stringResource(R.string.s398, configuredMaxHeapMb),
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
    }
}

/**
 * Java 运行环境初始入口卡片：仅在所选 Java 版本缺失时显示，安装成功后自动隐藏。
 * 完整的 Java 版本管理（卸载/重装/多版本）在「依赖与环境管理」页。
 */
@Composable
private fun DashboardJavaInstallCard(vm: McViewModel) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val installed by vm.installedJava.collectAsState()
    val config by vm.config.collectAsState()
    val isInstalling by vm.isInstalling.collectAsState()
    val operation by vm.javaOperation.collectAsState()
    val selected = config.selectedJavaVersion

    // 进入页面与环境就绪时刷新已装 Java 列表，保证卡片随安装结果实时隐藏
    LaunchedEffect(isBootstrapped) { if (isBootstrapped) vm.refreshJava() }

    if (!isBootstrapped || selected in installed) return
    val op = operation

    McCard(title = stringResource(R.string.dash_java_title), compact = true) {
        Text(
            stringResource(R.string.env_java_need, selected.displayName),
            color = Coral,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        if (op != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                Spacer(Modifier.width(8.dp))
                Text(op, color = Indigo, fontSize = 12.sp)
            }
            Spacer(Modifier.height(8.dp))
        }
        Button(
            onClick = { vm.installJava(selected) },
            enabled = !isInstalling,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.env_java_install_btn), color = Color.White, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun DashboardCoreSelectionCard(
    vm: McViewModel,
    onShowMessage: (String) -> Unit
) {
    val installedCores by vm.config
        .map { it.installedCores }
        .distinctUntilChanged()
        .collectAsState(initial = emptyList())
    val activeCoreName by vm.config
        .map { it.activeCoreName }
        .distinctUntilChanged()
        .collectAsState(initial = null)
    var showCoreDropdown by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val activeCore = installedCores.find { it.name == activeCoreName }

    McCard(title = stringResource(R.string.s368), compact = true) {
        if (installedCores.isEmpty()) {
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
                    Text(stringResource(R.string.s369), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(stringResource(R.string.s370), color = Muted, fontSize = 11.sp)
                }
            }
        } else {
            Text(stringResource(R.string.s371), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(4.dp))
            Text(
                if (activeCore != null) {
                    stringResource(R.string.s372, activeCore.name, activeCore.core.displayName, activeCore.version)
                } else {
                    stringResource(R.string.s373)
                },
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
                        if (activeCore != null) stringResource(R.string.s374, activeCore.name)
                        else stringResource(R.string.s375),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
                DropdownMenu(
                    expanded = showCoreDropdown,
                    onDismissRequest = { showCoreDropdown = false }
                ) {
                    installedCores.forEach { core ->
                        DropdownMenuItem(
                            text = {
                                Column {
                                    Text(core.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("${core.core.displayName} ${core.version}", color = Muted, fontSize = 11.sp)
                                    if (core.core.isBedrock) {
                                        Text(
                                            stringResource(R.string.dash_bedrock_udp),
                                            color = Coral,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            },
                            onClick = {
                                vm.setActiveCore(core.name)
                                showCoreDropdown = false
                                onShowMessage(context.getString(R.string.s376, core.name))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardServerControlCard(
    vm: McViewModel,
    isBootstrapped: Boolean,
    onShowSettings: () -> Unit
) {
    val installedCores by vm.config
        .map { it.installedCores }
        .distinctUntilChanged()
        .collectAsState(initial = emptyList())
    val activeCoreName by vm.config
        .map { it.activeCoreName }
        .distinctUntilChanged()
        .collectAsState(initial = null)
    val selectedJavaVersion by vm.config
        .map { it.selectedJavaVersion }
        .distinctUntilChanged()
        .collectAsState(initial = JavaVersion.Java17)
    val isRunning by vm.serverState
        .map { it.isRunning }
        .distinctUntilChanged()
        .collectAsState(initial = false)
    var isStopping by remember { mutableStateOf(false) }
    var showJavaDropdown by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val activeServerCore = installedCores.find { it.name == activeCoreName }

    McCard(
        title = stringResource(R.string.s377),
        compact = true,
        trailing = {
            IconButton(onClick = onShowSettings) {
                Icon(Icons.Outlined.Settings, stringResource(R.string.s378), tint = Indigo, modifier = Modifier.size(18.dp))
            }
        }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(modifier = Modifier.weight(0.9f)) {
                OutlinedButton(
                    onClick = { showJavaDropdown = true },
                    enabled = !isRunning,
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                ) { Text(selectedJavaVersion.displayName, color = Indigo, fontSize = 12.sp) }
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
                    if (isRunning) {
                        isStopping = true
                        vm.stopServer()
                        scope.launch { isStopping = false }
                    } else {
                        vm.startServer()
                    }
                },
                enabled = if (isRunning) !isStopping else isBootstrapped,
                colors = ButtonDefaults.buttonColors(containerColor = if (isRunning) Coral else Mint),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1.1f),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                if (isRunning && isStopping) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), color = Color.White, strokeWidth = 2.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(
                    when {
                        isRunning && isStopping -> stringResource(R.string.s381)
                        isRunning -> stringResource(R.string.s382)
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
            if (isRunning) stringResource(R.string.s383) else stringResource(R.string.s384),
            color = if (isRunning) Mint else Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.s385), color = Muted, fontSize = 10.sp)
        if (activeServerCore?.core == ServerCore.PowerNukkitX &&
            (selectedJavaVersion == JavaVersion.Java8 || selectedJavaVersion == JavaVersion.Java17)) {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.dash_pnx_java_note), color = Coral, fontSize = 11.sp)
        }
    }
}

@Composable
private fun AdvancedStartupCard(vm: McViewModel) {
    val customCommandEnabled by vm.config.map { it.advancedCustomCommandEnabled }.distinctUntilChanged().collectAsState(initial = false)
    val customCommand by vm.config.map { it.advancedCustomCommand }.distinctUntilChanged().collectAsState(initial = "")
    var showAdvanced by remember { mutableStateOf(false) }
    McCard(
        title = "高级启动选项",
        compact = true,
        trailing = {
            Icon(
                if (showAdvanced) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = if (showAdvanced) "收起" else "展开",
                tint = Indigo,
                modifier = Modifier
                    .size(20.dp)
                    .clickable { showAdvanced = !showAdvanced }
            )
        }
    ) {
        AnimatedVisibility(
            visible = showAdvanced,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            Column {
                Text("开启后使用手动输入的完整命令；关闭后由应用自动生成启动命令", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "完全自定义启动命令",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text("关闭时使用应用默认启动命令", color = Muted, fontSize = 10.sp)
                    }
                    Switch(
                        checked = customCommandEnabled,
                        onCheckedChange = { enabled ->
                            vm.updateConfig { it.copy(advancedCustomCommandEnabled = enabled) }
                        }
                    )
                }
                Spacer(Modifier.height(10.dp))
                if (customCommandEnabled) {
                    DebouncedTextField(
                        value = customCommand,
                        onValueChange = { value ->
                            vm.updateConfig { it.copy(advancedCustomCommand = value) }
                        },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("完整启动命令", fontSize = 11.sp) },
                        placeholder = {
                            Text("java -Xmx1024M -jar server.jar nogui", fontSize = 11.sp, color = Muted)
                        },
                        minLines = 3,
                        maxLines = 6,
                        textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardDependenciesCard(
    vm: McViewModel,
    onShowHelp: () -> Unit
) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val isInstalling by vm.isInstalling.collectAsState()
    val installSpeed by vm.installSpeed.collectAsState()
    val dependencySteps by vm.serverState
        .map { state -> state.installSteps.filter { it.step != com.mineserve.mobile.data.InstallStep.Jdk } }
        .distinctUntilChanged()
        .collectAsState(initial = emptyList())
    val progress by vm.serverState
        .map { it.currentProgress }
        .distinctUntilChanged()
        .collectAsState(initial = 0)
    val depsInstalled = dependencySteps.isNotEmpty() && dependencySteps.all {
        it.status == com.mineserve.mobile.data.StepStatus.Done
    }
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
                )
            }
        ) {
            DownloadHintHeader(
                isBusy = isInstalling,
                busyText = stringResource(R.string.s359),
                idleText = stringResource(R.string.s360),
                speedBps = installSpeed,
                onShowHelp = onShowHelp
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
            ProgressTrack(percent = progress)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.installDependencies() },
                enabled = !isInstalling && isBootstrapped,
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isInstalling) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp), color = Color.White, strokeWidth = 2.dp)
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
        }
    }
}

@Composable
private fun DashboardBootstrapCard(
    vm: McViewModel,
    onShowHelp: () -> Unit,
    onShowMessage: (String) -> Unit
) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    if (isBootstrapped) return

    val bootstrapError by vm.bootstrapError.collectAsState()
    val bootstrapSpeed by vm.bootstrapSpeed.collectAsState()
    val currentMirrorIndex by vm.currentMirrorIndex.collectAsState()
    val progress by vm.serverState
        .map { it.currentProgress }
        .distinctUntilChanged()
        .collectAsState(initial = 0)
    var switchInProgress by remember { mutableStateOf(false) }
    var switchingFromMirror by remember { mutableStateOf(-1) }
    LaunchedEffect(currentMirrorIndex) {
        if (switchInProgress && (currentMirrorIndex == -1 || currentMirrorIndex != switchingFromMirror)) {
            switchInProgress = false
        }
    }
    val mirrorSwitchMsg = stringResource(R.string.mirror_switch_requested)
    McCard(title = stringResource(R.string.s351), compact = true) {
        DownloadHintHeader(
            isBusy = true,
            busyText = stringResource(R.string.s352, progress),
            idleText = stringResource(R.string.s353),
            speedBps = bootstrapSpeed,
            onShowHelp = onShowHelp
        )
        Spacer(Modifier.height(8.dp))
        if (bootstrapError != null) {
            Text(stringResource(R.string.s354), color = Coral, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(4.dp))
            Text(bootstrapError!!, color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            DashboardConsolePreview(vm = vm, maxLines = 3)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { vm.retryBootstrap() },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.s355), color = Color.White, fontWeight = FontWeight.SemiBold) }
        } else {
            Text(stringResource(R.string.s356), color = Muted, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth(),
                color = Indigo,
                trackColor = IndigoSoft
            )
            Spacer(Modifier.height(4.dp))
            Text("${progress}%", color = Muted, fontSize = 10.sp)
            if (currentMirrorIndex >= 0) {
                Spacer(Modifier.height(8.dp))
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.s1057, vm.mirrorSources.getOrElse(currentMirrorIndex) { "" }),
                        color = Muted,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedButton(
                        onClick = {
                            if (switchInProgress) return@OutlinedButton
                            switchingFromMirror = currentMirrorIndex
                            switchInProgress = true
                            vm.switchBootstrapMirror()
                            onShowMessage(mirrorSwitchMsg)
                        },
                        enabled = !switchInProgress,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.height(48.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                    ) {
                        Text(stringResource(if (switchInProgress) R.string.mirror_switching else R.string.s1058), fontSize = 10.sp, color = Indigo)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            DashboardConsolePreview(vm = vm, maxLines = 5)
        }
    }
}

@Composable
private fun DashboardHeroBlock(
    vm: McViewModel,
    onRefresh: () -> Unit
) {
    val installedCores by vm.config.map { it.installedCores }.distinctUntilChanged().collectAsState(initial = emptyList())
    val activeCoreName by vm.config.map { it.activeCoreName }.distinctUntilChanged().collectAsState(initial = null)
    val selectedCore by vm.config.map { it.selectedCore }.distinctUntilChanged().collectAsState(initial = ServerCore.Paper)
    val mcVersion by vm.config.map { it.mcVersion }.distinctUntilChanged().collectAsState(initial = "")
    val activeCore = installedCores.find { it.name == activeCoreName }
    val coreLabel = activeCore?.let { "${it.name} (${it.core.displayName} ${it.version})" }
        ?: "${selectedCore.displayName} ${mcVersion}"
    val heroState by vm.serverState
        .map { state ->
            ServerState(
                isRunning = state.isRunning,
                tps = state.tps,
                onlinePlayers = state.onlinePlayers,
                maxPlayers = state.maxPlayers,
                usedMemoryMb = state.usedMemoryMb,
                runningSinceMs = state.runningSinceMs,
                startupPhase = state.startupPhase
            )
        }
        .distinctUntilChanged()
        .collectAsState(initial = ServerState())
    val resources by vm.serverResources.collectAsState()
    val serverProperties by vm.serverProperties.collectAsState()
    val onlineModeEnabled = serverProperties["online-mode"]
        ?.trim()
        ?.equals("true", ignoreCase = true) == true
    HeroBlock(
        state = heroState,
        coreLabel = coreLabel,
        cpuPercent = resources.cpuPercent,
        onlineModeEnabled = onlineModeEnabled,
        onRefresh = onRefresh
    )
}

@Composable
private fun DashboardDiagnosticsCard(
    vm: McViewModel,
    isBootstrapped: Boolean,
    onShowDiagnostics: () -> Unit
) {
    val diagnosticReport by vm.diagnosticReport.collectAsState()
    val isDiagnosing by vm.isDiagnosing.collectAsState()
    val isRepairingRuntime by vm.isRepairingRuntime.collectAsState()
    McCard(title = stringResource(R.string.dash_diag_title), compact = true) {
        val issues = diagnosticReport.issueCount
        Text(
            when {
                isRepairingRuntime -> stringResource(R.string.dash_diag_repairing)
                isDiagnosing -> stringResource(R.string.dash_diag_running)
                diagnosticReport.generatedAtMs == 0L -> stringResource(R.string.dash_diag_not_run)
                issues == 0 -> stringResource(R.string.dash_diag_pass)
                else -> stringResource(R.string.dash_diag_issues, issues)
            },
            color = when {
                isRepairingRuntime || isDiagnosing -> Indigo
                issues == 0 && diagnosticReport.generatedAtMs > 0 -> Mint
                else -> Coral
            },
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                onClick = onShowDiagnostics,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text(stringResource(R.string.dash_diag_detail), color = Indigo, fontSize = 12.sp)
            }
            Button(
                onClick = { vm.safeRepairRuntime() },
                enabled = !isDiagnosing && !isRepairingRuntime && isBootstrapped,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(8.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo)
            ) {
                Text(
                    if (isRepairingRuntime) stringResource(R.string.dash_diag_repairing_btn)
                    else stringResource(R.string.dash_diag_repair_btn),
                    color = Color.White,
                    fontSize = 12.sp
                )
            }
        }
    }
}

@Composable
private fun DashboardAddressCard(vm: McViewModel) {
    val localPort by vm.config.map { it.localPort }.distinctUntilChanged().collectAsState(initial = 25565)
    val lanIp by vm.lanIp.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { vm.refreshLanIp() }
    McCard(title = stringResource(R.string.s386), compact = true) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.s387), color = Muted, fontSize = 10.sp)
                Text("${lanIp}:${localPort}", fontSize = 12.sp, fontWeight = FontWeight.Medium)
            }
            IconButton(onClick = { vm.refreshLanIp() }) {
                Icon(Icons.Outlined.Refresh, stringResource(R.string.s333), tint = Indigo, modifier = Modifier.size(16.dp))
            }
            IconButton(onClick = {
                val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                cm.setPrimaryClip(android.content.ClipData.newPlainText("", "${lanIp}:${localPort}"))
            }) {
                Icon(Icons.Outlined.ContentCopy, stringResource(R.string.s388), tint = Indigo, modifier = Modifier.size(16.dp))
            }
        }
        if (tunnelState.publicUrl.isNotBlank()) {
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.s389), color = Muted, fontSize = 10.sp)
                    Text(tunnelState.publicUrl, fontSize = 12.sp, fontWeight = FontWeight.Medium, color = Indigo, maxLines = 1)
                }
                IconButton(onClick = { vm.copyTunnelUrl(context) }) {
                    Icon(Icons.Outlined.ContentCopy, stringResource(R.string.s388), tint = Indigo, modifier = Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun DashboardPluginsCard(vm: McViewModel) {
    val installedPlugins by vm.installedPlugins.collectAsState()
    val pluginsDescription = if (installedPlugins.isEmpty()) {
        stringResource(R.string.s391)
    } else {
        stringResource(R.string.s392, installedPlugins.size)
    }
    McCard(title = stringResource(R.string.s390), compact = true) {
        Column(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = pluginsDescription
            }
        ) {
            if (installedPlugins.isEmpty()) {
                Text(stringResource(R.string.s391), color = Muted, fontSize = 11.sp)
            } else {
                Text(stringResource(R.string.s392, installedPlugins.size), fontSize = 11.sp)
            }
        }
    }
}

@Composable
private fun DashboardResourceCard(vm: McViewModel) {
    val maxHeapMb by vm.config.map { it.maxHeapMb }.distinctUntilChanged().collectAsState(initial = 1024)
    val javaVersionName by vm.config.map { it.selectedJavaVersion.displayName }.distinctUntilChanged().collectAsState(initial = JavaVersion.Java17.displayName)
    val resources by vm.serverResources.collectAsState()
    val memText = resources.processMemoryMb?.let { "${it} MB / $maxHeapMb MB" } ?: stringResource(R.string.dash_res_not_running)
    val spaceText = resources.availableBytes?.let(::formatServerBytes) ?: stringResource(R.string.dash_res_na)
    val javaText = if (resources.javaAvailable) {
        stringResource(R.string.dash_res_java_ready, javaVersionName)
    } else {
        stringResource(R.string.dash_res_java_unavail, javaVersionName)
    }
    val dirText = resources.directoryBytes?.let(::formatServerBytes) ?: stringResource(R.string.dash_res_na)
    McCard(title = stringResource(R.string.dash_res_title), compact = true) {
        Column(
            modifier = Modifier.clearAndSetSemantics {
                contentDescription = listOf(memText, spaceText, javaText, dirText).joinToString(", ")
            }
        ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            DeviceStatCell(
                label = stringResource(R.string.dash_res_mem),
                value = resources.processMemoryMb?.let { "${it} MB / $maxHeapMb MB" }
                    ?: stringResource(R.string.dash_res_not_running),
                modifier = Modifier.weight(1f)
            )
            DeviceStatCell(
                label = stringResource(R.string.dash_res_space),
                value = resources.availableBytes?.let(::formatServerBytes)
                    ?: stringResource(R.string.dash_res_na),
                modifier = Modifier.weight(1f)
            )
            DeviceStatCell(
                label = "Java",
                value = if (resources.javaAvailable) {
                    stringResource(R.string.dash_res_java_ready, javaVersionName)
                } else {
                    stringResource(R.string.dash_res_java_unavail, javaVersionName)
                },
                modifier = Modifier.weight(1f)
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(
                R.string.dash_res_dir_usage,
                resources.directoryBytes?.let(::formatServerBytes) ?: stringResource(R.string.dash_res_na)
            ),
            color = Muted,
            fontSize = 11.sp
        )
        }
    }
}

@Composable
private fun DashboardConsolePreview(vm: McViewModel, maxLines: Int) {
    val consoleLines by vm.consolePreviewLines.collectAsState()
    val visibleLines = consoleLines.takeLast(maxLines)
    Column(
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = visibleLines.joinToString(", ")
        }
    ) {
        visibleLines.forEach { line ->
            Text(
                line,
                color = Muted.copy(alpha = 0.7f),
                fontSize = 10.sp
            )
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
