package com.mcserver.manager.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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
import com.mcserver.manager.ui.screens.LogsScreen
import com.mcserver.manager.ui.screens.NetworkScreen
import com.mcserver.manager.ui.screens.PluginsScreen
import com.mcserver.manager.ui.screens.SettingsScreen
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Muted

/**
 * 应用根布局：底部 5 Tab；概览页可跳转日志页（"查看日志"链接）
 */
@Composable
fun McApp() {
    val vm: McViewModel = viewModel(factory = McViewModel.Factory)
    var tab by remember { mutableStateOf(McTab.Dashboard) }
    var showLogs by remember { mutableStateOf(false) }

    Scaffold(
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                McTab.values().forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t; showLogs = false },
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
            if (showLogs) {
                LogsScreen(vm = vm, onBack = { showLogs = false })
            } else {
                when (tab) {
                    McTab.Dashboard -> DashboardScreen(vm = vm, onShowLogs = { showLogs = true })
                    McTab.Plugins -> PluginsScreen(vm = vm)
                    McTab.Network -> NetworkScreen(vm = vm)
                    McTab.Backup -> BackupScreen(vm = vm)
                    McTab.Settings -> SettingsScreen(vm = vm)
                }
            }
        }
    }
}
