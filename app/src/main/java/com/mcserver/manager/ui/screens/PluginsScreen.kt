package com.mcserver.manager.ui.screens

import androidx.compose.ui.res.stringResource
import com.mcserver.manager.R

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Update
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.data.ServerCore
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

private enum class PluginTab(val label: String) { Installed("已安装"), Upload("本地上传") }

/** 资源类型：插件 / 模组（按核心兼容性屏蔽） */
private enum class ResourceType(val label: String) { Plugin("插件"), Mod("模组") }

/** 插件资源站点（点击跳转官网） */
private data class PluginSite(val name: String, val desc: String, val url: String)

private val pluginSites = listOf(
    PluginSite(
        "SpigotMC Resources",
        "老牌大型插件资源平台，绝大多数 Bukkit/Spigot 插件首发站点，含免费与付费资源（全英文、国内访问较慢）",
        "https://www.spigotmc.org/resources/"
    ),
    PluginSite(
        "Hangar（Paper 官方）",
        "Paper 官方搭建平台，面向 Paper / Velocity / Waterfall，界面简洁、筛选完善",
        "https://hangar.papermc.io/"
    ),
    PluginSite(
        "Modrinth",
        "现代化开源资源平台，收录插件/模组/数据包，下载快，可按游戏版本与加载器筛选",
        "https://modrinth.com/plugins"
    ),
    PluginSite(
        "BuiltByBit（原 MC-Market）",
        "商业付费插件聚集地，也提供免费资源与成套服务端配置方案",
        "https://builtbybit.com"
    ),
    PluginSite(
        "CurseForge Bukkit 分区",
        "老牌资源站点，插件总数略少于 SpigotMC Resources",
        "https://www.curseforge.com/minecraft/bukkit-plugins"
    )
)
private enum class InstalledFilter(val label: String) { All("全部"), Enabled("启用"), Disabled("禁用"), Local("本地") }

/**
 * 插件管理页（重构完善版）
 *
 * 完善功能：
 *  - P0-1 plugin.yml 元信息解析（真实名称/版本/作者/依赖）
 *  - P0-2 GitHub API 更新检测（5 分钟缓存）
 *  - P0-3 插件详情对话框（完整元信息）
 *  - P1-4 搜索 + 筛选标签
 *  - P1-5 自定义 URL 下载
 *  - P1-6 删除时询问清理数据目录
 */
@Composable
fun PluginsScreen(vm: McViewModel) {
    val config by vm.config.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val installedPlugins by vm.installedPlugins.collectAsState()
    val mods by vm.mods.collectAsState()
    val modrinthResults by vm.modrinthResults.collectAsState()
    val modrinthLoaders by vm.modrinthLoaders.collectAsState()
    val downloadProgress by vm.pluginDownloadProgress.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(PluginTab.Installed) }
    var pendingDelete by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }
    var pendingModDelete by remember { mutableStateOf<PluginManager.ModEntry?>(null) }
    var detailPlugin by remember { mutableStateOf<PluginManager.InstalledPlugin?>(null) }

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
    val coreType = activeCore?.core ?: config.selectedCore
    // 按核心兼容性计算可用资源类型（不支持的分类自动屏蔽）
    val availableTypes = buildList {
        if (coreType.supportsPlugins) add(ResourceType.Plugin)
        if (coreType.supportsMods) add(ResourceType.Mod)
    }
    var resourceType by remember { mutableStateOf(ResourceType.Plugin) }
    // 核心切换时校正资源类型（默认取第一个可用类型）
    LaunchedEffect(coreType) {
        resourceType = availableTypes.firstOrNull() ?: ResourceType.Plugin
    }
    // 进入模组分类时刷新模组列表 + 加载 Modrinth 加载器列表
    LaunchedEffect(isBootstrapped, config.activeCoreName, resourceType) {
        if (isBootstrapped && config.activeCoreName != null && resourceType == ResourceType.Mod) {
            vm.refreshMods()
            vm.loadModrinthLoaders()
        }
    }
    val pluginsPath = vm.currentPluginsPath()
    val isServerRunning = serverState.isRunning

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Plugin Manager", title = stringResource(R.string.s750))

            // ── 当前核心状态卡片 ──
            McCard(
                title = stringResource(R.string.s751),
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
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(
                            if (coreType.supportsPlugins) "✓ 支持插件" else "✗ 不支持插件",
                            color = if (coreType.supportsPlugins) Mint else Coral,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            if (coreType.supportsMods) "✓ 支持模组" else "✗ 不支持模组",
                            color = if (coreType.supportsMods) Mint else Coral,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            // ── 资源类型切换（按核心兼容性屏蔽不支持的分类） ──
            if (availableTypes.isEmpty()) {
                Text(
                    "当前核心（${coreType.displayName}）不支持插件与模组",
                    color = Coral,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
                )
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(IndigoSoft)
                        .padding(4.dp)
                ) {
                    availableTypes.forEach { t ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (resourceType == t) Indigo else Color.Transparent)
                                .clickable { resourceType = t }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                t.label,
                                color = if (resourceType == t) Color.White else Muted,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }

            // ── 标签切换栏（仅插件分类显示） ──
            if (resourceType == ResourceType.Plugin) {
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
            }

            // ── 内容区（插件 / 模组按资源类型分流） ──
            if (resourceType == ResourceType.Plugin) {
            when (activeTab) {
                PluginTab.Installed -> InstalledTab(
                    installed = installedPlugins,
                    activeCoreExists = activeCore != null,
                    onToggle = { vm.togglePluginEnabled(it.fileName) },
                    onDelete = { pendingDelete = it },
                    onShowDetail = { detailPlugin = it }
                )

                PluginTab.Upload -> UploadTab(
                    activeCoreExists = activeCore != null,
                    customUrlProgress = vm.customUrlDownloadProgress(),
                    onPickFile = { uri -> vm.installPluginFromUri(uri) },
                    onInstallFromUrl = { url, name -> vm.installPluginFromUrl(url, name) },
                    onGotoInstalled = { activeTab = PluginTab.Installed }
                )
            }
            } else {
                ModsTab(
                    mods = mods,
                    curatedMods = vm.curatedMods,
                    activeCoreExists = activeCore != null,
                    coreType = coreType,
                    modrinthResults = modrinthResults,
                    modrinthLoaders = modrinthLoaders,
                    onToggle = { vm.toggleModEnabled(it) },
                    onDelete = { pendingModDelete = it },
                    onUpload = { uri -> vm.installModFromUri(uri) },
                    onInstallCurated = { vm.installCuratedMod(it) },
                    onSearchModrinth = { query, loaders, sort -> vm.searchModrinthMods(query, loaders, sort) },
                    onInstallModrinth = { vm.installModrinthMod(it) }
                )
            }

            // ── 底部热重载 ──
            McCard(title = stringResource(R.string.s759)) {
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

            // 底部配置提示
            Text(
                "插件相关配置请前往对应配置文件夹内修改配置文件。",
                color = Muted,
                fontSize = 10.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )

            // ── 插件资源站点 ──
            Spacer(Modifier.height(10.dp))
            McCard(title = stringResource(R.string.s765)) {
                Text(
                    "优质插件/模组下载平台，点击跳转官网",
                    color = Muted,
                    fontSize = 10.sp
                )
                Spacer(Modifier.height(8.dp))
                val context = LocalContext.current
                pluginSites.forEach { site ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                try {
                                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.url)))
                                } catch (e: Exception) { /* 无浏览器时忽略 */ }
                            }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(site.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Text(
                                site.desc,
                                color = Muted,
                                fontSize = 10.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Outlined.OpenInNew,
                            contentDescription = "打开",
                            tint = Indigo,
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 删除确认对话框（含数据目录清理选项）
    pendingDelete?.let { plugin ->
        DeletePluginDialog(
            plugin = plugin,
            onConfirm = { alsoRemoveData ->
                vm.deletePlugin(plugin.fileName, alsoRemoveData)
                pendingDelete = null
            },
            onDismiss = { pendingDelete = null }
        )
    }

    // 模组删除确认对话框
    pendingModDelete?.let { mod ->
        AlertDialog(
            onDismissRequest = { pendingModDelete = null },
            title = { Text(stringResource(R.string.s768), fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "即将删除模组：\n${mod.baseName}\n\n此操作不可撤销。",
                    color = Muted,
                    fontSize = 12.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.deleteMod(mod.fileName)
                    pendingModDelete = null
                }) {
                    Text(stringResource(R.string.s339), color = Coral, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingModDelete = null }) {
                    Text(stringResource(R.string.s402), color = Muted)
                }
            }
        )
    }

    // 插件详情对话框
    detailPlugin?.let { plugin ->
        PluginDetailDialog(
            plugin = plugin,
            onDismiss = { detailPlugin = null }
        )
    }
}

// ── 已安装标签 ──────────────────────────────────────────────────────

@Composable
private fun InstalledTab(
    installed: List<PluginManager.InstalledPlugin>,
    activeCoreExists: Boolean,
    onToggle: (PluginManager.InstalledPlugin) -> Unit,
    onDelete: (PluginManager.InstalledPlugin) -> Unit,
    onShowDetail: (PluginManager.InstalledPlugin) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var activeFilter by remember { mutableStateOf(InstalledFilter.All) }

    McCard(title = stringResource(R.string.s390)) {
        if (!activeCoreExists) {
            EmptyHint("请先在「概览」页选择服务端核心")
            return@McCard
        }
        if (installed.isEmpty()) {
            EmptyHint("当前核心尚未安装任何插件，可前往「精选推荐」或「本地上传」安装")
            return@McCard
        }

        // 搜索框
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text(stringResource(R.string.s772), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        Spacer(Modifier.height(8.dp))

        // 筛选标签
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            InstalledFilter.values().forEach { filter ->
                val count = when (filter) {
                    InstalledFilter.All -> installed.size
                    InstalledFilter.Enabled -> installed.count { it.isEnabled }
                    InstalledFilter.Disabled -> installed.count { !it.isEnabled }
                    InstalledFilter.Local -> installed.count { it.sourceTag == "本地" }
                }
                val label = "$filter $count"
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(if (activeFilter == filter) Indigo else IndigoSoft)
                        .clickable { activeFilter = filter }
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        label,
                        color = if (activeFilter == filter) Color.White else Muted,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        Spacer(Modifier.height(10.dp))

        // 过滤 + 搜索
        val filtered = installed.filter { p ->
            (activeFilter == InstalledFilter.All ||
                (activeFilter == InstalledFilter.Enabled && p.isEnabled) ||
                (activeFilter == InstalledFilter.Disabled && !p.isEnabled) ||
                (activeFilter == InstalledFilter.Local && p.sourceTag == "本地")) &&
                (searchQuery.isBlank() || p.baseName.contains(searchQuery, ignoreCase = true) ||
                    (p.meta?.name?.contains(searchQuery, ignoreCase = true) == true))
        }

        if (filtered.isEmpty()) {
            EmptyHint("没有符合筛选条件的插件")
            return@McCard
        }

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            filtered.forEach { p ->
                InstalledPluginRow(
                    plugin = p,
                    onToggle = { onToggle(p) },
                    onDelete = { onDelete(p) },
                    onShowDetail = { onShowDetail(p) }
                )
            }
        }
    }
}

@Composable
private fun InstalledPluginRow(
    plugin: PluginManager.InstalledPlugin,
    onToggle: () -> Unit,
    onDelete: () -> Unit,
    onShowDetail: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(if (plugin.isEnabled) IndigoSoft else CoralSoft.copy(alpha = 0.4f))
            .clickable { onShowDetail() }
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    plugin.meta?.name ?: plugin.baseName,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (plugin.isEnabled) MaterialTheme.colorScheme.onSurface else Muted
                )
                if (plugin.meta?.version != null) {
                    Spacer(Modifier.size(4.dp))
                    Text(
                        "v${plugin.meta.version}",
                        color = Muted,
                        fontSize = 9.sp
                    )
                }
            }
            val metaLine = buildString {
                append(plugin.sizeText)
                append("  ·  ")
                append(plugin.lastModifiedText)
                append("  ·  ")
                append(plugin.sourceTag)
                if (!plugin.isEnabled) append("  ·  已禁用")
            }
            Text(
                metaLine,
                color = Muted,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        // 详情按钮
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color.Transparent)
                .clickable { onShowDetail() },
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Outlined.Info, contentDescription = "详情", tint = Indigo, modifier = Modifier.size(14.dp))
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
    curatedUpdates: Map<String, PluginManager.CuratedUpdateInfo>,
    isCheckingUpdates: Boolean,
    coreType: ServerCore,
    isCuratedInstalled: (PluginManager.CuratedPlugin) -> Boolean,
    onInstall: (PluginManager.CuratedPlugin) -> Unit,
    onCheckUpdates: () -> Unit,
    onForceRecheck: () -> Unit
) {
    McCard(
        title = stringResource(R.string.s775),
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isCheckingUpdates) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        color = Indigo,
                        strokeWidth = 1.5.dp
                    )
                    Spacer(Modifier.size(4.dp))
                }
                Text(
                    if (isCheckingUpdates) "检测中..." else "检查更新",
                    color = Indigo,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.clickable(enabled = !isCheckingUpdates) {
                        onCheckUpdates()
                    }
                )
            }
        }
    ) {
        Text(
            "内置 8 款常用插件，自动从 GitHub Releases 跟随最新版本下载",
            color = Muted,
            fontSize = 10.sp
        )
        // 当前核心兼容性彩色提示（醒目展示支持/不支持内容）
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (coreType.supportsPlugins) "✓ 本核心支持插件" else "✗ 本核心不支持插件",
                color = if (coreType.supportsPlugins) Mint else Coral,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                if (coreType.supportsMods) "✓ 本核心支持模组" else "✗ 本核心不支持模组",
                color = if (coreType.supportsMods) Mint else Coral,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        }
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            curatedList.forEach { curated ->
                CuratedPluginRow(
                    curated = curated,
                    isInstalled = isCuratedInstalled(curated),
                    progress = downloadProgress[curated.id],
                    updateInfo = curatedUpdates[curated.id],
                    onInstall = { onInstall(curated) }
                )
            }
        }

        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = onForceRecheck,
            enabled = !isCheckingUpdates,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Refresh, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.s783), color = Indigo, fontSize = 11.sp)
        }
    }

    // ── 插件资源站点 ──
    val context = LocalContext.current
    McCard(title = stringResource(R.string.s765)) {
        Text(
            "优质插件/模组下载平台，点击跳转官网",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(8.dp))
        pluginSites.forEach { site ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site.url)))
                        } catch (e: Exception) { /* 无浏览器时忽略 */ }
                    }
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(site.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(
                        site.desc,
                        color = Muted,
                        fontSize = 10.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "打开",
                    tint = Indigo,
                    modifier = Modifier.size(14.dp)
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
    updateInfo: PluginManager.CuratedUpdateInfo?,
    onInstall: () -> Unit
) {
    val hasUpdate = updateInfo?.hasUpdate == true
    val context = LocalContext.current

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
                    .background(Brush.linearGradient(listOf(Indigo, Mint))),
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
                        BadgeChip(text = "已安装", color = Mint, bg = MintSoft)
                    }
                    if (hasUpdate) {
                        Spacer(Modifier.size(4.dp))
                        BadgeChip(text = "有更新", color = Coral, bg = CoralSoft)
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
                // 版本信息
                if (updateInfo != null) {
                    Spacer(Modifier.height(2.dp))
                    Row(
                        modifier = Modifier
                            .clickable {
                                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(updateInfo.latestReleaseUrl))
                                context.startActivity(intent)
                            },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Outlined.Update, contentDescription = null, tint = Indigo, modifier = Modifier.size(10.dp))
                        Spacer(Modifier.size(3.dp))
                        Text(
                            buildString {
                                append("最新: ")
                                append(updateInfo.latestVersion)
                                if (updateInfo.installedVersion != null) {
                                    append("  ·  当前: ")
                                    append(updateInfo.installedVersion)
                                }
                            },
                            color = if (hasUpdate) Coral else Muted,
                            fontSize = 9.sp,
                            fontWeight = if (hasUpdate) FontWeight.SemiBold else FontWeight.Normal
                        )
                    }
                }
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
                enabled = !isInstalled || hasUpdate,
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isInstalled && !hasUpdate) MintSoft else Indigo,
                    disabledContainerColor = MintSoft
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                when {
                    isInstalled && hasUpdate -> {
                        Icon(Icons.Outlined.Update, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s789), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    isInstalled -> {
                        Text(stringResource(R.string.s790), color = Mint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                    else -> {
                        Icon(Icons.Outlined.Extension, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.size(4.dp))
                        Text(stringResource(R.string.s791), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}

// ── 本地上传标签 ────────────────────────────────────────────────────

@Composable
private fun UploadTab(
    activeCoreExists: Boolean,
    customUrlProgress: McViewModel.PluginDownloadProgress?,
    onPickFile: (Uri) -> Unit,
    onInstallFromUrl: (String, String) -> Unit,
    onGotoInstalled: () -> Unit
) {
    // SAF 文件选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) onPickFile(uri)
    }

    var customUrl by remember { mutableStateOf("") }
    var customName by remember { mutableStateOf("") }

    McCard(title = stringResource(R.string.s792)) {
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
                .border(width = 1.dp, color = Indigo, shape = RoundedCornerShape(12.dp))
                .clickable {
                    filePickerLauncher.launch(arrayOf("application/java-archive", "application/octet-stream", "*/*"))
                },
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.CloudUpload, contentDescription = "上传", tint = Indigo, modifier = Modifier.size(36.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.s795), color = Indigo, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.size(2.dp))
                Text(stringResource(R.string.s796), color = Muted, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(20.dp))

        // 分隔线
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = Modifier.weight(1f).height(1.dp).background(IndigoSoft))
            Text(stringResource(R.string.s797), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(horizontal = 8.dp))
            Box(modifier = Modifier.weight(1f).height(1.dp).background(IndigoSoft))
        }

        Spacer(Modifier.height(16.dp))

        // 自定义 URL 下载
        Text(stringResource(R.string.s798), color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(4.dp))
        Text(
            "粘贴 .jar 文件的直链下载 URL（如 SpigotMC、Modrinth 手动复制的直链）",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(8.dp))

        OutlinedTextField(
            value = customUrl,
            onValueChange = { customUrl = it },
            label = { Text(stringResource(R.string.s800), fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = customName,
            onValueChange = { customName = it },
            label = { Text(stringResource(R.string.s801), fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )

        // 自定义 URL 下载进度
        if (customUrlProgress != null) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = customUrlProgress.percent / 100f,
                modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                color = Mint,
                trackColor = IndigoSoft
            )
            Spacer(Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("${customUrlProgress.percent}%", color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                Text(customUrlProgress.speedText, color = Muted, fontSize = 10.sp)
            }
        }

        Spacer(Modifier.height(10.dp))
        Button(
            onClick = {
                onInstallFromUrl(customUrl.trim(), customName.trim())
            },
            enabled = customUrl.isNotBlank() && customName.isNotBlank() && customUrlProgress == null,
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Link, contentDescription = null, modifier = Modifier.size(14.dp))
            Spacer(Modifier.size(4.dp))
            Text(stringResource(R.string.s486), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(
            onClick = onGotoInstalled,
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.s802), color = Indigo, fontSize = 11.sp)
        }
    }
}

// ── 对话框组件 ──────────────────────────────────────────────────────

@Composable
private fun DeletePluginDialog(
    plugin: PluginManager.InstalledPlugin,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var alsoRemoveData by remember { mutableStateOf(true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.s803), fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text(stringResource(R.string.s804), fontSize = 13.sp)
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
                Spacer(Modifier.height(12.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { alsoRemoveData = !alsoRemoveData }
                ) {
                    Checkbox(
                        checked = alsoRemoveData,
                        onCheckedChange = { alsoRemoveData = it }
                    )
                    Spacer(Modifier.size(4.dp))
                    Column {
                        Text(stringResource(R.string.s807), fontSize = 12.sp)
                        Text(
                            if (plugin.meta?.name != null)
                                "将删除 plugins/${plugin.meta.name}/ 下的配置和数据"
                            else
                                "将删除插件同名目录下的配置和数据",
                            color = Muted,
                            fontSize = 10.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(alsoRemoveData) },
                colors = ButtonDefaults.buttonColors(containerColor = Coral)
            ) { Text(stringResource(R.string.s339), color = Color.White) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s402)) }
        }
    )
}

@Composable
private fun PluginDetailDialog(
    plugin: PluginManager.InstalledPlugin,
    onDismiss: () -> Unit
) {
    val meta = plugin.meta
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Extension, contentDescription = null, tint = Indigo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(stringResource(R.string.s810), fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                DetailRow("文件名", plugin.fileName)
                DetailRow("显示名", plugin.baseName)
                if (meta != null) {
                    DetailRow("插件名", meta.name)
                    DetailRow("版本", meta.version)
                    DetailRow("主类", meta.mainClass.ifEmpty { "—" })
                    DetailRow("作者", meta.author)
                    DetailRow("API 版本", meta.apiVersion.ifEmpty { "—" })
                    if (meta.description.isNotBlank()) {
                        DetailRow("描述", meta.description)
                    }
                    if (meta.depends.isNotEmpty()) {
                        DetailRow("依赖", meta.depends.joinToString(", "))
                    }
                    if (meta.softDepends.isNotEmpty()) {
                        DetailRow("软依赖", meta.softDepends.joinToString(", "))
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "未能解析 plugin.yml 元信息（可能非标准插件或格式不兼容）",
                        color = Muted,
                        fontSize = 10.sp
                    )
                }
                DetailRow("文件大小", plugin.sizeText)
                DetailRow("修改时间", plugin.lastModifiedText)
                DetailRow("来源", plugin.sourceTag)
                DetailRow("状态", if (plugin.isEnabled) "启用" else "已禁用")
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.s620)) }
        }
    )
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

// ── 通用组件 ──

@Composable
private fun BadgeChip(text: String, color: Color, bg: Color) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .background(bg)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Text(text, color = color, fontSize = 9.sp, fontWeight = FontWeight.Bold)
    }
}

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

// ── 模组分类 ──────────────────────────────────────────────────────

@Composable
private fun ModsTab(
    mods: List<PluginManager.ModEntry>,
    curatedMods: List<PluginManager.CuratedMod>,
    activeCoreExists: Boolean,
    coreType: ServerCore,
    modrinthResults: List<PluginManager.ModrinthHit>,
    modrinthLoaders: List<String>,
    onToggle: (String) -> Unit,
    onDelete: (PluginManager.ModEntry) -> Unit,
    onUpload: (Uri) -> Unit,
    onInstallCurated: (PluginManager.CuratedMod) -> Unit,
    onSearchModrinth: (String, List<String>, String) -> Unit,
    onInstallModrinth: (PluginManager.ModrinthHit) -> Unit
) {
    var modrinthQuery by remember { mutableStateOf("") }
    var selectedLoaders by remember { mutableStateOf(setOf<String>()) }
    var sortIndex by remember { mutableStateOf(0) }
    val sortOptions = listOf("downloads" to "按下载量", "relevance" to "按相关性", "newest" to "按最新")
    // 本地上传模组选择器
    val filePickerLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(onUpload) }

    // 已安装模组
    McCard(title = stringResource(R.string.s828)) {
        if (mods.isEmpty()) {
            Text(
                "暂无已安装模组",
                color = Muted,
                fontSize = 12.sp,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                mods.forEach { mod ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(IndigoSoft)
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Indigo),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(mod.baseName.take(2).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(mod.baseName, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                "${mod.sizeText} · ${if (mod.isEnabled) "已启用" else "已禁用"}",
                                color = Muted,
                                fontSize = 10.sp
                            )
                        }
                        TextButton(onClick = { onToggle(mod.fileName) }) {
                            Text(
                                if (mod.isEnabled) "禁用" else "启用",
                                color = if (mod.isEnabled) Coral else Mint,
                                fontSize = 11.sp
                            )
                        }
                        IconButton(onClick = { onDelete(mod) }) {
                            Icon(Icons.Outlined.Delete, contentDescription = "删除", tint = Coral, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(
            onClick = { filePickerLauncher.launch(arrayOf("application/java-archive", "*/*")) },
            enabled = activeCoreExists,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.Upload, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(stringResource(R.string.s830), color = Indigo, fontSize = 12.sp)
        }
    }


    // ── Modrinth 模组获取 ──
    var sortMenuOpen by remember { mutableStateOf(false) }
    @OptIn(ExperimentalLayoutApi::class)
    McCard(title = stringResource(R.string.s831)) {
        Text(
            "从 Modrinth 开放平台搜索并一键安装模组",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(6.dp))
        // 搜索框 + 排序 + 搜索按钮
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = modrinthQuery,
                onValueChange = { modrinthQuery = it },
                placeholder = { Text(stringResource(R.string.s833), fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.size(6.dp))
            Box {
                OutlinedButton(
                    onClick = { sortMenuOpen = true },
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(sortOptions[sortIndex].second, fontSize = 11.sp, color = Indigo)
                }
                DropdownMenu(expanded = sortMenuOpen, onDismissRequest = { sortMenuOpen = false }) {
                    sortOptions.forEachIndexed { i, (_, label) ->
                        DropdownMenuItem(
                            text = { Text(label, fontSize = 12.sp) },
                            onClick = { sortIndex = i; sortMenuOpen = false }
                        )
                    }
                }
            }
            Spacer(Modifier.size(6.dp))
            Button(
                onClick = { onSearchModrinth(modrinthQuery.trim(), selectedLoaders.toList(), sortOptions[sortIndex].first) },
                enabled = modrinthQuery.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text(stringResource(R.string.s834), color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }
        }
        // 加载器多选筛选
        if (modrinthLoaders.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.s835), color = Muted, fontSize = 10.sp)
            Spacer(Modifier.height(4.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                modrinthLoaders.take(12).forEach { loader ->
                    val selected = loader in selectedLoaders
                    FilterChip(
                        selected = selected,
                        onClick = {
                            selectedLoaders = if (selected) selectedLoaders - loader else selectedLoaders + loader
                        },
                        label = { Text(loader, fontSize = 10.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = IndigoSoft,
                            selectedLabelColor = Indigo
                        )
                    )
                }
            }
        }
        // 结果列表
        if (modrinthResults.isNotEmpty()) {
            Spacer(Modifier.height(8.dp))
            val coreLoader = if (coreType == ServerCore.Fabric || coreType == ServerCore.Quilt) "fabric" else "forge"
            modrinthResults.forEach { hit ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModrinthIcon(hit.icon_url)
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(hit.title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "${hit.author} · ${formatModrinthDownloads(hit.downloads)}",
                            color = Muted,
                            fontSize = 10.sp
                        )
                        // 当前核心不匹配红字提醒
                        if (hit.categories.isNotEmpty() && coreLoader !in hit.categories) {
                            Text(
                                "⚠ 该模组不匹配当前核心（${coreType.displayName}）",
                                color = Coral,
                                fontSize = 9.sp
                            )
                        } else if (hit.description.isNotBlank()) {
                            Text(hit.description, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    Button(
                        onClick = { onInstallModrinth(hit) },
                        colors = ButtonDefaults.buttonColors(containerColor = Mint),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.s837), color = Color.White, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

/** Modrinth 下载量格式化 */
private fun formatModrinthDownloads(d: Long): String = when {
    d >= 1_000_000 -> String.format("%.1fM 下载", d / 1_000_000.0)
    d >= 1_000 -> String.format("%.1fK 下载", d / 1_000.0)
    else -> "$d 下载"
}

/** Modrinth 模组图标（网络加载，失败显示占位） */
@Composable
private fun ModrinthIcon(url: String, size: Dp = 32.dp) {
    var bitmap by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(url) {
        if (url.isNotBlank()) {
            bitmap = withContext(Dispatchers.IO) {
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.setRequestProperty("User-Agent", "McServerManager/1.0 (mcserver-manager)")
                    val bytes = conn.inputStream.use { it.readBytes() }
                    conn.disconnect()
                    android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.asImageBitmap()
                } catch (e: Exception) {
                    null
                }
            }
        }
    }
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(IndigoSoft),
        contentAlignment = Alignment.Center
    ) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp,
                contentDescription = null,
                modifier = Modifier.size(size),
                contentScale = ContentScale.Crop
            )
        } else {
            Text("🟦", fontSize = 14.sp)
        }
    }
}
