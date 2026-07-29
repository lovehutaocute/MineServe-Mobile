package com.mcserver.manager.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.server.PluginManager
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.CoralSoft
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.MintSoft
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class PluginTab(val label: String) { Installed("已安装"), Curated("精选推荐"), Upload("本地上传") }

/**
 * 插件管理页（重构版）
 *
 * 交互逻辑：
 *  - 顶部显示当前核心信息与 plugins 路径，支持一键刷新
 *  - 三标签切换：已安装 / 精选推荐 / 本地上传
 *  - 已安装列表支持启用/禁用切换（Bukkit `-` 前缀标准）与删除
 *  - 精选推荐从内置 GitHub Releases latest 重定向源下载，带实时进度
 *  - 本地上传通过 SAF 选择 .jar 文件，复制到 plugins/
 *  - 底部热重载按钮仅在服务器运行时可点击
 */
@Composable
fun PluginsScreen(vm: McViewModel) {
    val config by vm.config.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val installedPlugins by vm.installedPlugins.collectAsState()
    val downloadProgress by vm.pluginDownloadProgress.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(PluginTab.Installed) }
    var pendingDelete by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }

    // 进入页面或核心切换时自动刷新
    LaunchedEffect(isBootstrapped, config.activeCoreName) {
        if (isBootstrapped && config.activeCoreName != null) {
            vm.refreshInstalledPlugins()
        }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    val activeCore = config.installedCores.find { it.name == config.activeCoreName }
    val pluginsPath = vm.currentPluginsPath()
    val isServerRunning = serverState.isRunning

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Plugin Manager", title = "插件管理")

            // ── 当前核心状态卡片 ──
            McCard(
                title = "当前服务端核心",
                trailing = {
                    Text(
                        "刷新",
                        color = Indigo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable {
                            if (activeCore != null) {
                                vm.refreshInstalledPlugins()
                                scope.launch { snackbarHostState.showSnackbar("已刷新插件列表") }
                            } else {
                                scope.launch { snackbarHostState.showSnackbar("请先选择服务端核心") }
                            }
                        }
                    )
                }
            ) {
                if (activeCore == null) {
                    Text(
                        "尚未选择服务端核心，请在「概览」页选用",
                        color = Coral,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(vertical = 6.dp)
                    )
                } else {
                    Text(
                        "${activeCore.name}  ·  ${activeCore.core.displayName} ${activeCore.version}",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "插件目录：$pluginsPath",
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (installedPlugins.isEmpty()) Muted else Mint)
                        )
                        Spacer(Modifier.size(6.dp))
                        Text(
                            "已安装 ${installedPlugins.size} 个插件" +
                                if (installedPlugins.count { !it.isEnabled } > 0)
                                    "（${installedPlugins.count { !it.isEnabled }} 个已禁用）"
                                else "",
                            color = Muted,
                            fontSize = 11.sp
                        )
                    }
                }
            }

            // ── 标签切换栏 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IndigoSoft)
                    .padding(4.dp)
            ) {
                PluginTab.values().forEach { tab ->
                    val count = when (tab) {
                        PluginTab.Installed -> installedPlugins.size
                        else -> 0
                    }
                    val label = if (count > 0) "${tab.label} $count" else tab.label
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (activeTab == tab) Indigo else Color.Transparent)
                            .clickable { activeTab = tab }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (activeTab == tab) Color.White else Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ── 内容区（按标签切换） ──
            when (activeTab) {
                PluginTab.Installed -> InstalledTab(
                    installed = installedPlugins,
                    activeCoreExists = activeCore != null,
                    onToggle = { vm.togglePluginEnabled(it.fileName) },
                    onDelete = { pendingDelete = it }
                )

                PluginTab.Curated -> CuratedTab(
                    curatedList = vm.curatedPlugins,
                    downloadProgress = downloadProgress,
                    isCuratedInstalled = { vm.isCuratedPluginInstalled(it) },
                    onInstall = { vm.installCuratedPlugin(it) }
                )

                PluginTab.Upload -> UploadTab(
                    activeCoreExists = activeCore != null,
                    onPickFile = { uri -> vm.installPluginFromUri(uri) },
                    onGotoInstalled = { activeTab = PluginTab.Installed }
                )
            }

            // ── 底部热重载 ──
            McCard(title = "插件热重载") {
                Text(
                    if (isServerRunning)
                        "服务器运行中，可发送 reload 指令重新加载所有插件"
                    else
                        "服务器未运行，热重载按钮不可用。请先在「概览」页启动服务端",
                    color = if (isServerRunning) Muted else Coral,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = {
                        vm.sendCommand("reload")
                        scope.launch { snackbarHostState.showSnackbar("已发送 reload 指令") }
                    },
                    enabled = isServerRunning,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Indigo,
                        disabledContainerColor = IndigoSoft
                    ),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (isServerRunning) "发送 reload 指令" else "服务器未运行",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 删除确认对话框
    pendingDelete?.let { plugin ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("删除插件", fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("确认删除以下插件？", fontSize = 13.sp)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        plugin.fileName,
                        color = Indigo,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "大小：${plugin.sizeText}  ·  修改：${plugin.lastModifiedText}",
                        color = Muted,
                        fontSize = 10.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "删除后无法恢复，如需保留可改为禁用。",
                        color = Coral,
                        fontSize = 10.sp
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        vm.deletePlugin(plugin.fileName)
                        pendingDelete = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Coral)
                ) { Text("删除", color = Color.White) }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("取消") }
            }
        )
    }
}

// ── 已安装标签 ──────────────────────────────────────────────────────

@Composable
private fun InstalledTab(
    installed: List<PluginManager.InstalledPlugin>,
    activeCoreExists: Boolean,
    onToggle: (PluginManager.InstalledPlugin) -> Unit,
    onDelete: (PluginManager.InstalledPlugin) -> Unit
) {
    McCard(title = "已安装插件") {
        if (!activeCoreExists) {
            EmptyHint("请先在「概览」页选择服务端核心")
            return@McCard
        }
        if (installed.isEmpty()) {
            EmptyHint("当前核心尚未安装任何插件，可前往「精选推荐」或「本地上传」安装")
            return@McCard
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            installed.forEach { p ->
                InstalledPluginRow(
                    plugin = p,
                    onToggle = { onToggle(p) },
                    onDelete = { onDelete(p) }
                )
            }
        }
    }
}

@Composable
private fun InstalledPluginRow(
    plugin: PluginManager.InstalledPlugin,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (plugin.isEnabled) IndigoSoft else CoralSoft.copy(alpha = 0.4f))
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (plugin.isEnabled) Indigo else Muted),
            contentAlignment = Alignment.Center
        ) {
            Text(
                plugin.baseName.take(2).uppercase(),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                plugin.baseName,
                fontSize = 12.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (plugin.isEnabled) MaterialTheme.colorScheme.onSurface else Muted
            )
            Text(
                "${plugin.sizeText}  ·  ${plugin.lastModifiedText}  ·  ${plugin.sourceTag}",
                color = Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 启用/禁用开关
        Switch(
            checked = plugin.isEnabled,
            onCheckedChange = { onToggle() },
            modifier = Modifier.size(width = 36.dp, height = 20.dp)
        )
        Spacer(Modifier.size(6.dp))
        // 删除按钮
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(CoralSoft)
                .clickable { onDelete() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = Coral, modifier = Modifier.size(14.dp))
        }
    }
}

// ── 精选推荐标签 ────────────────────────────────────────────────────

@Composable
private fun CuratedTab(
    curatedList: List<PluginManager.CuratedPlugin>,
    downloadProgress: Map<String, McViewModel.PluginDownloadProgress>,
    isCuratedInstalled: (PluginManager.CuratedPlugin) -> Boolean,
    onInstall: (PluginManager.CuratedPlugin) -> Unit
) {
    McCard(title = "精选插件推荐") {
        Text(
            "内置 6 款常用插件，自动从 GitHub Releases 跟随最新版本下载",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            curatedList.forEach { curated ->
                CuratedPluginRow(
                    curated = curated,
                    isInstalled = isCuratedInstalled(curated),
                    progress = downloadProgress[curated.id],
                    onInstall = { onInstall(curated) }
                )
            }
        }
    }
}

@Composable
private fun CuratedPluginRow(
    curated: PluginManager.CuratedPlugin,
    isInstalled: Boolean,
    progress: McViewModel.PluginDownloadProgress?,
    onInstall: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(IndigoSoft)
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(
                        Brush.linearGradient(listOf(Indigo, Mint))
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    curated.avatarText,
                    color = Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.size(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        curated.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    if (isInstalled) {
                        Spacer(Modifier.size(6.dp))
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(MintSoft)
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text("已安装", color = Mint, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
                Text(
                    "作者：${curated.author}",
                    color = Muted,
                    fontSize = 10.sp
                )
                Text(
                    curated.description,
                    color = Muted,
                    fontSize = 10.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        // 下载进度条
        if (progress != null) {
            LinearProgressIndicator(
                progress = progress.percent / 100f,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp)),
                color = Mint,
                trackColor = Color.White
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    "${progress.percent}%",
                    color = Indigo,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    progress.speedText,
                    color = Muted,
                    fontSize = 10.sp
                )
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "下载中，请耐心等待...",
                color = Muted,
                fontSize = 10.sp
            )
        } else {
            Button(
                onClick = onInstall,
                enabled = !isInstalled,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isInstalled) MintSoft else Indigo,
                    disabledContainerColor = MintSoft
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                if (isInstalled) {
                    Text("已安装", color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                } else {
                    Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.size(4.dp))
                    Text("下载安装", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ── 本地上传标签 ────────────────────────────────────────────────────

@Composable
private fun UploadTab(
    activeCoreExists: Boolean,
    onPickFile: (Uri) -> Unit,
    onGotoInstalled: () -> Unit
) {
    // SAF 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onPickFile(uri)
    }

    McCard(title = "本地上传插件") {
        if (!activeCoreExists) {
            EmptyHint("请先在「概览」页选择服务端核心")
            return@McCard
        }
        Text(
            "支持上传 Bukkit / Spigot / Paper 服务端插件（.jar 格式）",
            color = Muted,
            fontSize = 11.sp
        )
        Spacer(Modifier.height(4.dp))
        Text(
            "上传后会自动复制到当前核心的 plugins/ 目录，需要 reload 或重启服务器后生效",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(16.dp))

        // 大按钮区
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(IndigoSoft)
                .border(
                    width = 1.dp,
                    color = Indigo,
                    shape = RoundedCornerShape(12.dp)
                )
                .clickable {
                    filePickerLauncher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(
                    Icons.Outlined.CloudUpload,
                    contentDescription = "上传",
                    tint = Indigo,
                    modifier = Modifier.size(36.dp)
                )
                Spacer(Modifier.size(8.dp))
                Text("点击选择 .jar 文件", color = Indigo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(2.dp))
                Text("支持任意来源的 .jar 文件", color = Muted, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onGotoInstalled,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("查看已安装列表", color = Indigo, fontSize = 11.sp)
        }
    }
}

// ── 通用空状态提示 ──

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Outlined.Extension,
            contentDescription = null,
            tint = Muted.copy(alpha = 0.4f),
            modifier = Modifier.size(32.dp)
        )
        Spacer(Modifier.size(8.dp))
        Text(text, color = Muted, fontSize = 11.sp)
    }
}
