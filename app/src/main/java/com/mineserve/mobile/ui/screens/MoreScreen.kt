package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.HealthAndSafety
import androidx.compose.material.icons.outlined.HelpOutline
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Muted

private data class MoreEntry(val title: String, val description: String, val icon: ImageVector, val onClick: () -> Unit)

@Composable
fun MoreScreen(onNetwork: () -> Unit, onBackup: () -> Unit, onDiagnostics: () -> Unit, onKeepAlive: () -> Unit, onHelp: () -> Unit, onCrashReports: () -> Unit) {
    val entries = listOf(
        MoreEntry("网络", "地址、隧道与端口", Icons.Outlined.Cloud, onNetwork),
        MoreEntry("世界与备份", "备份、恢复与导入", Icons.Outlined.Backup, onBackup),
        MoreEntry("运行诊断", "检查并安全修复环境", Icons.Outlined.HealthAndSafety, onDiagnostics),
        MoreEntry("崩溃报告", "分析异常与导出日志", Icons.Outlined.Warning, onCrashReports),
        MoreEntry("保活", "后台运行与启动设置", Icons.Outlined.FavoriteBorder, onKeepAlive),
        MoreEntry("下载帮助", "镜像和下载问题说明", Icons.Outlined.HelpOutline, onHelp)
    )
    Column(Modifier.fillMaxSize()) {
        HeaderBlock("MORE", "更多功能")
        LazyVerticalGrid(columns = GridCells.Fixed(2), modifier = Modifier.fillMaxSize().padding(14.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(entries) { entry ->
                Card(onClick = entry.onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(entry.icon, null, tint = Indigo, modifier = Modifier.size(26.dp))
                        Text(entry.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                        Text(entry.description, fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
    }
}
