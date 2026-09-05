package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.StepRow
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Tab 下标：崩溃页「前往安装」跳转到 Java 模块时使用 */
const val ENV_TAB_JAVA = 0
const val ENV_TAB_DEPS = 1
const val ENV_TAB_TERMUX = 2

/**
 * 依赖与环境管理：Java 版本 / 依赖 / Termux 环境三组模块，
 * 每组提供 卸载（直接删除文件）/ 重装 / 安装 三个操作，状态实时反馈。
 */
@Composable
fun EnvManagerScreen(vm: McViewModel, initialTab: Int, onBack: () -> Unit) {
    var tab by remember { mutableStateOf(initialTab.coerceIn(0, 2)) }
    var confirmText by remember { mutableStateOf<String?>(null) }
    var confirmAction by remember { mutableStateOf<(() -> Unit)?>(null) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    fun requestConfirm(text: String, action: () -> Unit) {
        confirmText = text
        confirmAction = action
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            BackBar(stringResource(R.string.env_title), onBack)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = tab == ENV_TAB_JAVA,
                    onClick = { tab = ENV_TAB_JAVA },
                    label = { Text(stringResource(R.string.env_tab_java)) }
                )
                FilterChip(
                    selected = tab == ENV_TAB_DEPS,
                    onClick = { tab = ENV_TAB_DEPS },
                    label = { Text(stringResource(R.string.env_tab_deps)) }
                )
                FilterChip(
                    selected = tab == ENV_TAB_TERMUX,
                    onClick = { tab = ENV_TAB_TERMUX },
                    label = { Text(stringResource(R.string.env_tab_termux)) }
                )
            }
            Column(
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                when (tab) {
                    ENV_TAB_JAVA -> JavaModule(vm, ::requestConfirm)
                    ENV_TAB_DEPS -> DependenciesModule(vm, ::requestConfirm)
                    else -> TermuxModule(vm, ::requestConfirm)
                }
            }
        }
    }

    confirmText?.let { text ->
        val action = confirmAction
        AlertDialog(
            onDismissRequest = { confirmText = null; confirmAction = null },
            title = { Text(stringResource(R.string.env_confirm_title), fontWeight = FontWeight.Bold) },
            text = { Text(text, color = Muted, fontSize = 12.sp) },
            confirmButton = {
                TextButton(onClick = {
                    confirmText = null
                    confirmAction?.invoke()
                    confirmAction = null
                }) { Text(stringResource(R.string.env_confirm_ok), color = Coral, fontWeight = FontWeight.SemiBold) }
            },
            dismissButton = {
                TextButton(onClick = { confirmText = null; confirmAction = null }) {
                    Text(stringResource(R.string.env_cancel), color = Muted)
                }
            }
        )
    }
}

/** 三键操作行：卸载 / 重装 / 安装，语义与配色固定，便于区分 */
@Composable
private fun ActionButtonsRow(
    uninstallEnabled: Boolean,
    reinstallEnabled: Boolean,
    installEnabled: Boolean,
    onUninstall: () -> Unit,
    onReinstall: () -> Unit,
    onInstall: () -> Unit
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onUninstall,
            enabled = uninstallEnabled,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.env_uninstall), color = Coral, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        OutlinedButton(
            onClick = onReinstall,
            enabled = reinstallEnabled,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.env_reinstall), color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
        Button(
            onClick = onInstall,
            enabled = installEnabled,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.weight(1f)
        ) { Text(stringResource(R.string.env_install), color = androidx.compose.ui.graphics.Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Composable
private fun OperationBadge(operation: String?) {
    if (operation == null) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
        Spacer(Modifier.width(8.dp))
        Text(operation, color = Indigo, fontSize = 12.sp)
    }
}

// ── Java 版本管理 ─────────────────────────────────────────────

@Composable
private fun JavaModule(vm: McViewModel, requestConfirm: (String, () -> Unit) -> Unit) {
    val installed by vm.installedJava.collectAsState()
    val operation by vm.javaOperation.collectAsState()
    val config by vm.config.collectAsState()
    val selected = config.selectedJavaVersion
    val busy = operation != null

    McCard(title = stringResource(R.string.env_tab_java)) {
        Text(stringResource(R.string.env_java_hint), color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        OperationBadge(operation)
        JavaVersion.values().forEach { version ->
            val isInstalled = version in installed
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { if (!busy) vm.setJavaVersion(version) }
                    .padding(vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        version.displayName + if (version == selected) "（${stringResource(R.string.env_java_selected)}）" else "",
                        fontSize = 13.sp,
                        fontWeight = if (version == selected) FontWeight.SemiBold else FontWeight.Normal,
                        color = if (version == selected) Indigo else androidx.compose.ui.graphics.Color.Unspecified
                    )
                }
                Text(
                    if (isInstalled) stringResource(R.string.dash_java_installed) else stringResource(R.string.dash_java_not_installed),
                    color = if (isInstalled) Mint else Muted,
                    fontSize = 11.sp
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        ActionButtonsRow(
            uninstallEnabled = !busy && selected in installed,
            reinstallEnabled = !busy && installed.isNotEmpty(),
            installEnabled = !busy && selected !in installed,
            onUninstall = {
                requestConfirm(
                    "将直接删除 ${selected.displayName} 的文件，不影响其他 Java 版本与存档。此操作不可撤销。"
                ) { vm.deleteJava(selected) }
            },
            onReinstall = { vm.clearAndReinstallJava() },
            onInstall = { vm.installJava(selected) }
        )
    }
}

// ── 依赖管理 ──────────────────────────────────────────────────

@Composable
private fun DependenciesModule(vm: McViewModel, requestConfirm: (String, () -> Unit) -> Unit) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val isInstalling by vm.isInstalling.collectAsState()
    val installSpeed by vm.installSpeed.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val depsUninstallConfirmText = stringResource(R.string.env_deps_uninstall_confirm)
    val steps = serverState.installSteps.filter { it.step != com.mineserve.mobile.data.InstallStep.Jdk }
    val anyInstalled = steps.any { it.status == StepStatus.Done }

    McCard(title = stringResource(R.string.env_tab_deps)) {
        Text(stringResource(R.string.env_deps_hint), color = Muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        if (isInstalling) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
                Spacer(Modifier.width(8.dp))
                Text(
                    "${stringResource(R.string.env_busy)} " +
                        if (installSpeed > 0) formatSpeed(installSpeed) else "",
                    color = Indigo, fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(8.dp))
        }
        if (!isBootstrapped) {
            Text(stringResource(R.string.env_deps_need_bootstrap), color = Coral, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        steps.forEachIndexed { idx, step ->
            val tag = when (step.status) {
                StepStatus.Done -> stringResource(R.string.s361)
                StepStatus.Active -> stringResource(R.string.s362)
                StepStatus.Wait -> stringResource(R.string.s363)
            }
            StepRow(name = "${idx + 1}. ${step.step.label}", status = step.status, tag = tag)
        }
        if (steps.isNotEmpty() && !anyInstalled) {
            Spacer(Modifier.height(4.dp))
            Text(stringResource(R.string.env_deps_none_installed), color = Muted, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        ActionButtonsRow(
            uninstallEnabled = !isInstalling && anyInstalled,
            reinstallEnabled = !isInstalling && isBootstrapped,
            installEnabled = !isInstalling && isBootstrapped,
            onUninstall = {
                requestConfirm(depsUninstallConfirmText) {
                    vm.deleteDependencies()
                }
            },
            onReinstall = { vm.reinstallDependencies() },
            onInstall = { vm.installDependencies() }
        )
    }
}

// ── Termux 环境管理 ───────────────────────────────────────────

@Composable
private fun TermuxModule(vm: McViewModel, requestConfirm: (String, () -> Unit) -> Unit) {
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val bootstrapError by vm.bootstrapError.collectAsState()
    val bootstrapSpeed by vm.bootstrapSpeed.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val termuxUninstallConfirmText = stringResource(R.string.env_termux_uninstall_confirm)
    val termuxReinstallConfirmText = stringResource(R.string.env_termux_reinstall_confirm)
    val progress = serverState.currentProgress
    val serverRunning = serverState.isRunning
    val busy = bootstrapError == null && !isBootstrapped && progress in 1..99

    McCard(title = stringResource(R.string.env_tab_termux)) {
        if (serverRunning) {
            Text(stringResource(R.string.env_running_block), color = Coral, fontSize = 11.sp)
            Spacer(Modifier.height(6.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (isBootstrapped) stringResource(R.string.env_termux_ready) else stringResource(R.string.env_termux_missing),
                color = if (isBootstrapped) Mint else Muted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            if (busy) {
                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Indigo)
            }
        }
        if (!isBootstrapped && progress in 1..99) {
            Spacer(Modifier.height(6.dp))
            Text(
                "$progress%" + if (bootstrapSpeed > 0) " · ${formatSpeed(bootstrapSpeed)}" else "",
                color = Indigo, fontSize = 11.sp
            )
        }
        bootstrapError?.let {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.env_termux_error, it), color = Coral, fontSize = 11.sp)
        }
        Spacer(Modifier.height(10.dp))
        ActionButtonsRow(
            uninstallEnabled = !serverRunning && !busy,
            reinstallEnabled = !serverRunning && !busy && isBootstrapped,
            installEnabled = !serverRunning && !busy && !isBootstrapped,
            onUninstall = {
                requestConfirm(termuxUninstallConfirmText) {
                    vm.forceDeleteBootstrap()
                }
            },
            onReinstall = {
                requestConfirm(termuxReinstallConfirmText) {
                    vm.deleteBootstrap()
                }
            },
            onInstall = { vm.retryBootstrap() }
        )
    }
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec >= 1024 * 1024 -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    bytesPerSec >= 1024 -> "%.0f KB/s".format(bytesPerSec / 1024.0)
    else -> "$bytesPerSec B/s"
}
