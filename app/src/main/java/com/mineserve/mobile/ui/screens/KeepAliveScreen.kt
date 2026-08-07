package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.service.McForegroundService
import com.mineserve.mobile.ui.BackBar
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.delay

/**
 * 后台保活设置页：开机自启 / 周期保活 / 崩溃自动重启 等开关，每项带功能说明。
 */
@Composable
fun KeepAliveScreen(vm: McViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    var bootAuto by remember { mutableStateOf(vm.isBootAutoStart()) }
    var keepAlive by remember { mutableStateOf(vm.isKeepAliveEnabled()) }
    var serviceRunning by remember { mutableStateOf(McForegroundService.isRunning) }

    // 轮询服务运行状态
    LaunchedEffect(Unit) {
        while (true) {
            serviceRunning = McForegroundService.isRunning
            delay(2000)
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // 统一返回栏
        BackBar(title = stringResource(R.string.s541), onBack = onBack)
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_keepalive), title = stringResource(R.string.s542), statusBarPadding = false)

            // 服务状态
            McCard(title = stringResource(R.string.s543)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(androidx.compose.foundation.shape.CircleShape)
                            .background(if (serviceRunning) Mint else Coral)
                    )
                    Spacer(Modifier.size(6.dp))
                    Text(
                        if (serviceRunning) stringResource(R.string.s544) else stringResource(R.string.s545),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (serviceRunning) Mint else Coral
                    )
                }
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.startKeepAliveService() },
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.s546), color = Color.White, fontSize = 12.sp) }
                    OutlinedButton(
                        onClick = { vm.stopKeepAliveService() },
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.s547), color = Coral, fontSize = 12.sp) }
                }
            }

            // 保活开关
            McCard(title = stringResource(R.string.s548)) {
                KeepAliveToggle(
                    title = stringResource(R.string.s549),
                    subtitle = stringResource(R.string.s550),
                    checked = bootAuto,
                    onChange = { vm.setBootAutoStart(it); bootAuto = it }
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.s551),
                    subtitle = stringResource(R.string.s552),
                    checked = keepAlive,
                    onChange = { vm.setKeepAliveEnabled(it); keepAlive = it }
                )
                Spacer(Modifier.height(8.dp))
                KeepAliveToggle(
                    title = stringResource(R.string.s396),
                    subtitle = stringResource(R.string.s553),
                    checked = config.autoRestartOnCrash,
                    onChange = { vm.setAutoRestart(it) }
                )
            }

            // 说明
            McCard(title = stringResource(R.string.s554)) {
                Text(
                    "Android 系统对后台进程有严格限制，保活能力受系统版本与厂商策略影响。\n" +
                        stringResource(R.string.s556),
                    color = Muted,
                    fontSize = 11.sp,
                    lineHeight = 17.sp
                )
            }
        }
    }
}

@Composable
private fun KeepAliveToggle(
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
            Text(subtitle, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
