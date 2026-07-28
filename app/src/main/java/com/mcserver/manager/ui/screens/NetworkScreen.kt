package com.mcserver.manager.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.Muted

@Composable
fun NetworkScreen(vm: McViewModel) {
    val config by vm.config.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val isRunning by vm.serverState.collectAsState()
    val context = LocalContext.current

    // 进入页面时自动刷新一次局域网 IP
    LaunchedEffect(Unit) { vm.refreshLanIp() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        HeaderBlock(eyebrow = "Networking", title = "端口与域名")

        // 服务器连接信息卡片
        McCard(title = "服务器连接地址") {
            // 运行状态指示灯
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (isRunning.isRunning) Color(0xFF4CAF50) else Color(0xFFB0B7C3))
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (isRunning.isRunning) "服务器运行中" else "服务器未运行",
                    fontSize = 12.sp,
                    color = if (isRunning.isRunning) Color(0xFF2E7D32) else Muted,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))

            // 局域网 IP
            Text("局域网 IP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = lanIp,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.refreshLanIp() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新 IP")
                }
            }
            Spacer(Modifier.height(12.dp))

            // 端口
            Text("端口", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                "${config.localPort}",
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(12.dp))

            // 完整连接地址
            Text("连接地址", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF5F6FA))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    text = if (lanIp == "--") "--:$config.localPort" else "$lanIp:${config.localPort}",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.copyServerAddress(context) }) {
                    Icon(
                        Icons.Outlined.ContentCopy,
                        contentDescription = "复制",
                        tint = Indigo
                    )
                }
            }
            Spacer(Modifier.height(12.dp))

            // 一键复制按钮
            Button(
                onClick = { vm.copyServerAddress(context) },
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = null, tint = Color.White)
                Spacer(Modifier.size(8.dp))
                Text("一键复制服务器链接", color = Color.White)
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "在 Minecraft Java 版「多人游戏」→「直接连接」中粘贴即可加入",
                color = Muted,
                fontSize = 11.sp
            )
        }

        McCard(title = "本地端口与域名") {
            // 本地端口
            Text("本地端口", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = config.localPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.setLocalPort(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))

            // 自定义域名
            Text("自定义域名", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            OutlinedTextField(
                value = config.customDomain,
                onValueChange = { vm.setDomain(it) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(12.dp))
        }

        McCard(title = "内网穿透控制（frp）") {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { vm.startTunnel() },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("启动穿透", color = Color.White) }

                Button(
                    onClick = { vm.stopTunnel() },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF8890A0)),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.weight(1f)
                ) { Text("停止", color = Color.White) }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "穿透方式：frp（仅支持），端口 ${config.localPort} → ${config.customDomain}",
                color = Muted,
                fontSize = 11.sp
            )
        }
        Spacer(Modifier.height(16.dp))
    }
}
