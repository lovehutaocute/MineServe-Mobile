package com.mcserver.manager.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.data.AptMirror
import com.mcserver.manager.data.DownloadMirror
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.SegPill
import com.mcserver.manager.ui.DebouncedTextField
import com.mcserver.manager.ui.SubPage
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.Muted

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: McViewModel, onNavigate: (SubPage) -> Unit = {}) {
    val config by vm.config.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        HeaderBlock(eyebrow = "Settings", title = "设置")

        // JVM 内存上限
        McCard(title = "JVM 内存上限") {
            Text(
                "为 MC 进程分配 -Xmx，建议不超过设备可用 RAM 的 60%。",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            DebouncedTextField(
                value = config.maxHeapMb.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.setMaxHeap(it) } },
                sanitize = { it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text("最大堆 (MB)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 下载源设置（Termux bootstrap rootfs 下载源）
        McCard(title = "下载源设置") {
            Text(
                "Termux 运行环境下载源（约 50MB），影响初始化速度",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("Termux 下载源", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DownloadMirror.values().forEach { mirror ->
                    SegPill(
                        text = mirror.displayName,
                        selected = config.downloadMirror == mirror,
                        onClick = { vm.setDownloadMirror(mirror) }
                    )
                }
            }
        }

        // APT 镜像设置（JDK/wget/frp 等依赖包下载源）
        McCard(title = "APT 软件源") {
            Text(
                "影响 apt 安装依赖（JDK/wget/frp/proot）的下载速度",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Text("APT 镜像", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AptMirror.values().forEach { mirror ->
                    SegPill(
                        text = mirror.displayName,
                        selected = config.aptMirror == mirror,
                        onClick = { vm.setAptMirror(mirror) }
                    )
                }
            }
        }

        // 保活与恢复
        McCard(title = "保活与恢复") {
            SettingToggle(
                title = "崩溃自动重启",
                subtitle = "MC 进程异常退出时自动重启（默认关闭省电）",
                checked = config.autoRestartOnCrash,
                onChange = { vm.setAutoRestart(it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingToggle(
                title = "保持 Wi-Fi 连接",
                subtitle = "屏幕熄灭时防止 Wi-Fi 进入低功耗",
                checked = config.keepWifiLock,
                onChange = { vm.setKeepWifiLock(it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingToggle(
                title = "保持 CPU 唤醒",
                subtitle = "防止 CPU 休眠导致 TPS 掉电",
                checked = config.keepCpuWakelock,
                onChange = { vm.setKeepCpuWakelock(it) }
            )
        }

        // 快捷入口（子页面跳转）
        McCard(title = "快捷入口") {
            SettingEntry(
                title = "server.properties 编辑",
                subtitle = "修改服务器基础配置（难度/模式/人数等）",
                onClick = { onNavigate(SubPage.Properties) }
            )
            Spacer(Modifier.height(8.dp))
            SettingEntry(
                title = "下载帮助",
                subtitle = "下载慢？查看解决方案",
                onClick = { onNavigate(SubPage.DownloadHelp) }
            )
        }

        // 关于
        McCard(title = "关于") {
            Text(
                "MC 云控面板 · v1.0.0",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "基于 Termux 开源组件二次封装，proot-distro 无 root 运行。",
                color = Muted,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun SettingToggle(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun SettingEntry(
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Indigo)
            Text(subtitle, color = Muted, fontSize = 11.sp)
        }
        Text("→", color = Indigo, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}
