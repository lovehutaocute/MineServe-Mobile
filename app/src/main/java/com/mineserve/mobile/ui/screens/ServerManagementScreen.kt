package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Muted

@Composable
fun ServerManagementScreen(
    vm: McViewModel,
    onPlugins: () -> Unit,
    onProperties: () -> Unit,
    onIcon: () -> Unit
) {
    val config by vm.config.collectAsState()
    val state by vm.serverState.collectAsState()
    val active = config.installedCores.firstOrNull { it.name == config.activeCoreName }
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("服务器管理", fontSize = 22.sp, fontWeight = FontWeight.Bold)
        Text(
            active?.let { "${it.name} · ${it.core.displayName} ${it.version}" } ?: "请先选择服务器核心",
            color = if (active == null) Color(0xFFD94B4B) else Muted,
            fontSize = 12.sp
        )
        Text(if (state.isRunning) "服务器运行中" else "服务器未运行", color = if (state.isRunning) Color(0xFF2E9B62) else Muted, fontSize = 12.sp)
        ManagementEntry("模组与插件", "安装、更新和管理服务器插件或模组", Icons.Outlined.Extension, onPlugins)
        ManagementEntry("服务器配置", "编辑当前核心对应的配置文件", Icons.Outlined.Tune, onProperties)
        ManagementEntry("服务器图标", "更换 Java Edition 的 server-icon.png", Icons.Outlined.Image, onIcon)
        if (active?.core?.isBedrock == true) {
            Text("PowerNukkitX 暂不支持 Java Edition 的服务器图标。", color = Color(0xFFD94B4B), fontSize = 11.sp)
        }
    }
}

@Composable
private fun ManagementEntry(title: String, description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        androidx.compose.foundation.layout.Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = Indigo)
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold)
                Text(description, color = Muted, fontSize = 12.sp)
            }
        }
    }
}
