package com.mcserver.manager.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mcserver.manager.ui.screens.BackupScreen
import com.mcserver.manager.ui.screens.DashboardScreen
import com.mcserver.manager.ui.screens.DownloadHelpScreen
import com.mcserver.manager.ui.screens.DownloadScreen
import com.mcserver.manager.ui.screens.FileManagerScreen
import com.mcserver.manager.ui.screens.MtGuideScreen
import com.mcserver.manager.ui.screens.LogsScreen
import com.mcserver.manager.ui.screens.NetworkScreen
import com.mcserver.manager.ui.screens.PlayersScreen
import com.mcserver.manager.ui.screens.PluginsScreen
import com.mcserver.manager.ui.screens.PropertiesScreen
import com.mcserver.manager.ui.screens.SettingsScreen
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Muted

/** 子页面类型（从设置页进入的二级页面） */
enum class SubPage { Properties, Network, Backup, DownloadHelp, MtGuide }

/**
 * 应用根布局：底部 6 Tab；概览页可跳转日志页；设置页可跳转子页面
 */
@Composable
fun McApp() {
    val vm: McViewModel = viewModel(factory = McViewModel.Factory)
    var tab by remember { mutableStateOf(McTab.Dashboard) }
    var showLogs by remember { mutableStateOf(false) }
    var subPage by remember { mutableStateOf<SubPage?>(null) }

    // 系统返回键：逐级返回上一页面，禁止直接退出应用。
    // 优先级：日志页 → 子页面 → 其他 Tab → 概览（Dashboard）；已在概览时消费返回键不退出。
    BackHandler {
        when {
            showLogs -> showLogs = false
            subPage != null -> subPage = null
            tab != McTab.Dashboard -> {
                tab = McTab.Dashboard
                showLogs = false
                subPage = null
            }
        }
    }

    Scaffold(
        // 全屏展示：内容延伸到状态栏，顶部状态栏区域由各页面首个组件（HeaderBlock/返回栏）
        // 以白色背景覆盖，不再显示系统状态栏灰色条
        contentWindowInsets = WindowInsets(0.dp),
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                McTab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t && subPage == null,
                        onClick = { tab = t; showLogs = false; subPage = null },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, fontSize = 10.sp) },
                        colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Indigo,
                            selectedTextColor = Indigo,
                            indicatorColor = IndigoSoft,
                            unselectedIconColor = Muted,
                            unselectedTextColor = Muted
                        )
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .padding(padding)
        ) {
            when {
                showLogs -> LogsScreen(vm = vm, onBack = { showLogs = false })
                subPage != null -> {
                    when (subPage) {
                        SubPage.Properties -> PropertiesScreen(vm = vm, onBack = { subPage = null })
                        SubPage.Network -> NetworkScreen(vm = vm, onBack = { subPage = null })
                        SubPage.Backup -> BackupScreen(vm = vm, onBack = { subPage = null })
                        SubPage.DownloadHelp -> DownloadHelpScreen(vm = vm, onBack = { subPage = null })
                        SubPage.MtGuide -> MtGuideScreen(onBack = { subPage = null })
                        null -> {}
                    }
                }
                else -> when (tab) {
                    McTab.Dashboard -> DashboardScreen(
                        vm = vm,
                        onShowLogs = { showLogs = true },
                        onShowDownloadHelp = { subPage = SubPage.DownloadHelp }
                    )
                    McTab.Download -> DownloadScreen(
                        vm = vm,
                        onShowDownloadHelp = { subPage = SubPage.DownloadHelp }
                    )
                    McTab.Players -> PlayersScreen(vm = vm)
                    McTab.Plugins -> PluginsScreen(vm = vm)
                    McTab.Files -> FileManagerScreen(vm = vm, onOpenMtGuide = { subPage = SubPage.MtGuide })
                    McTab.Network -> NetworkScreen(vm = vm, onBack = {})
                    McTab.Backup -> BackupScreen(vm = vm, onBack = {})
                    McTab.Settings -> SettingsScreen(
                        vm = vm,
                        onNavigate = { subPage = it }
                    )
                }
            }
        }
    }
}
