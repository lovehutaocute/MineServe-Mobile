package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.R

import android.content.Intent
import android.net.Uri
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.DownloadMirror
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.QqGroupCard
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.DebouncedTextField
import com.mineserve.mobile.ui.SubPage
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Muted

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(vm: McViewModel, onNavigate: (SubPage) -> Unit = {}) {
    val config by vm.config.collectAsState()
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        HeaderBlock(eyebrow = "Settings", title = stringResource(R.string.s186))

        // JVM 内存上限
        McCard(title = stringResource(R.string.s1003)) {
            Text(
                stringResource(R.string.s1004),
                color = Muted,
                fontSize = 11.sp
            )
            Text(
                stringResource(R.string.s1005),
                color = Coral,
                fontSize = 10.sp
            )
            Spacer(Modifier.height(8.dp))
            DebouncedTextField(
                value = config.maxHeapMb.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.setMaxHeap(it) } },
                sanitize = { it.filter(Char::isDigit) },
                singleLine = true,
                label = { Text(stringResource(R.string.s1006)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // 下载源设置（Termux bootstrap rootfs 下载源）
        McCard(title = stringResource(R.string.s1007)) {
            Text(
                "Termux 运行环境下载源（约 50MB），影响初始化速度",
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.s1009), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        McCard(title = stringResource(R.string.s1010)) {
            Text(
                stringResource(R.string.s1011),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.s1012), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
        McCard(title = stringResource(R.string.s1013)) {
            SettingToggle(
                title = stringResource(R.string.s396),
                subtitle = stringResource(R.string.s397),
                checked = config.autoRestartOnCrash,
                onChange = { vm.setAutoRestart(it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingToggle(
                title = stringResource(R.string.s1014),
                subtitle = stringResource(R.string.s1015),
                checked = config.keepWifiLock,
                onChange = { vm.setKeepWifiLock(it) }
            )
            Spacer(Modifier.height(8.dp))
            SettingToggle(
                title = stringResource(R.string.s1016),
                subtitle = stringResource(R.string.s1017),
                checked = config.keepCpuWakelock,
                onChange = { vm.setKeepCpuWakelock(it) }
            )
        }

        // 后台保活（大尺寸独立入口）
        McCard(title = stringResource(R.string.s542)) {
            Text(
                stringResource(R.string.s1018),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = { onNavigate(SubPage.KeepAlive) },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
            ) {
                Text(stringResource(R.string.s1019), fontSize = 15.sp, fontWeight = FontWeight.Bold)
            }
        }

        // 快捷入口（子页面跳转）
        McCard(title = stringResource(R.string.s1020)) {
            SettingEntry(
                title = stringResource(R.string.s1021),
                subtitle = stringResource(R.string.s1022),
                onClick = { onNavigate(SubPage.DownloadHelp) }
            )
        }

        // 关于
        McCard(title = stringResource(R.string.s1023)) {
            SettingEntry(
                title = stringResource(R.string.update_current_version),
                subtitle = "v${BuildConfig.VERSION_NAME}",
                onClick = {} // 版本号行不可点击
            )
            Spacer(Modifier.height(4.dp))
            SettingEntry(
                title = stringResource(R.string.update_check),
                subtitle = stringResource(R.string.s1025),
                onClick = { vm.checkForUpdate(manual = true) }
            )
        }

        // 意见反馈
        McCard(title = stringResource(R.string.s1026)) {
            Text(
                stringResource(R.string.s1027),
                color = Muted,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    try {
                        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:167245484@qq.com"))
                        intent.putExtra(Intent.EXTRA_SUBJECT, "MineServeMobile 意见反馈")
                        context.startActivity(intent)
                    } catch (e: Exception) {
                        // 设备无邮件客户端时静默
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    stringResource(R.string.s1029),
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        // QQ 交流群入口
        QqGroupCard()
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
