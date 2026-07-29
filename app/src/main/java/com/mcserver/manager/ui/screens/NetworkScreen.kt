package com.mcserver.manager.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.server.TunnelManager.TunnelStatus
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.Muted

@Composable
fun NetworkScreen(vm: McViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val consoleLines by vm.consoleLines.collectAsState()
    val context = LocalContext.current

    LaunchedEffect(Unit) { vm.refreshLanIp() }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        // 返回栏
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text("返回设置", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        }
        HeaderBlock(eyebrow = "Networking", title = "端口与内网穿透")

        // ── 服务器连接信息（局域网） ──────────────────────────
        McCard(title = "局域网连接地址") {
            // 运行状态指示灯
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(if (serverState.isRunning) Color(0xFF4CAF50) else Color(0xFFB0B7C3))
                )
                Spacer(Modifier.size(8.dp))
                Text(
                    if (serverState.isRunning) "服务器运行中" else "服务器未运行",
                    fontSize = 12.sp,
                    color = if (serverState.isRunning) Color(0xFF2E7D32) else Muted,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Spacer(Modifier.height(12.dp))

            Text("局域网 IP", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(lanIp, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.refreshLanIp() }) {
                    Icon(Icons.Outlined.Refresh, contentDescription = "刷新 IP")
                }
            }
            Spacer(Modifier.height(12.dp))

            Text("端口", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text("${config.localPort}", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            Text("连接地址", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFF5F6FA))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    if (lanIp == "--") "--:${config.localPort}" else "$lanIp:${config.localPort}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f)
                )
                IconButton(onClick = { vm.copyServerAddress(context) }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", tint = Indigo)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "在 Minecraft Java 版「多人游戏」→「直接连接」中粘贴即可加入（仅同局域网）",
                color = Muted, fontSize = 11.sp
            )
        }

        // ── 内网穿透状态卡片 ──────────────────────────────────
        TunnelStatusCard(
            tunnelState = tunnelState,
            onStart = { vm.startTunnel() },
            onStop = { vm.stopTunnel() },
            onCopyUrl = { vm.copyTunnelUrl(context) }
        )

        // ── 本地端口配置 ──────────────────────────────────────
        McCard(title = "本地端口") {
            Text("Minecraft 服务器监听端口，默认 25565", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = config.localPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.setLocalPort(it) } },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth()
            )
        }

        // ── 内网穿透配置 ──────────────────────────────────────
        McCard(title = "内网穿透配置") {
            // 穿透类型选择器
            Text("穿透类型", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(8.dp))

            TunnelType.values().forEach { type ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(
                            if (config.tunnelType == type) IndigoSoft else Color.Transparent
                        )
                        .clickable { vm.setTunnelType(type) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = config.tunnelType == type,
                        onClick = { vm.setTunnelType(type) },
                        colors = RadioButtonDefaults.colors(selectedColor = Indigo)
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(type.displayName, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(type.description, color = Muted, fontSize = 11.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // 根据穿透类型显示不同配置
            AnimatedVisibility(
                visible = config.tunnelType == TunnelType.Frp,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Text("frp 服务端地址", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = config.tunnelServerAddr,
                        onValueChange = { v -> vm.updateConfig { it.copy(tunnelServerAddr = v) } },
                        placeholder = { Text("例如: your-vps.com 或 1.2.3.4") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    Text("frp 服务端端口", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = config.tunnelServerPort.toString(),
                        onValueChange = { v ->
                            v.toIntOrNull()?.let { port -> vm.updateConfig { it.copy(tunnelServerPort = port) } }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))

                    Text("认证 Token（可选，与服务端一致）", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = config.tunnelToken,
                        onValueChange = { v -> vm.updateConfig { it.copy(tunnelToken = v) } },
                        placeholder = { Text("frp 服务端的 token") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            AnimatedVisibility(
                visible = config.tunnelType == TunnelType.Cloudflared,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Quick Tunnel（零配置）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text("无需域名，自动获得 *.trycloudflare.com 公网地址", color = Muted, fontSize = 11.sp)
                        }
                        Switch(
                            checked = config.cloudflareQuickTunnel,
                            onCheckedChange = { v -> vm.updateConfig { it.copy(cloudflareQuickTunnel = v) } }
                        )
                    }

                    if (!config.cloudflareQuickTunnel) {
                        Spacer(Modifier.height(8.dp))
                        Text("Cloudflare 域名", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = config.cloudflareDomain,
                            onValueChange = { v -> vm.updateConfig { it.copy(cloudflareDomain = v) } },
                            placeholder = { Text("例如: mc.yourdomain.com") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Named Tunnel 模式需从 PC 端执行 cloudflared tunnel login 并将凭证 JSON 放到 home/tunnel/mc-tunnel.json",
                            color = Coral, fontSize = 10.sp
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = config.tunnelType == TunnelType.Ngrok,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Text("ngrok Authtoken", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = config.ngrokAuthtoken,
                        onValueChange = { v -> vm.updateConfig { it.copy(ngrokAuthtoken = v) } },
                        placeholder = { Text("从 ngrok.com Dashboard 获取") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "免费层限制: 1GB/月流量, 随机 TCP 地址, 3 个并发端点",
                        color = Coral, fontSize = 10.sp
                    )
                }
            }
        }

        // ── 隧道日志预览 ──────────────────────────────────────
        TunnelLogPreview(consoleLines)

        // ── 操作指南（说明书） ──────────────────────────────────
        GuideSection(config.tunnelType)

        Spacer(Modifier.height(16.dp))
    }
}

// ── 隧道状态卡片 ──────────────────────────────────────────────

@Composable
private fun TunnelStatusCard(
    tunnelState: com.mcserver.manager.server.TunnelManager.TunnelState,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onCopyUrl: () -> Unit
) {
    McCard(title = "内网穿透状态") {
        // 状态指示灯 + 状态文本
        Row(verticalAlignment = Alignment.CenterVertically) {
            val (dotColor, statusText, statusColor) = when (tunnelState.status) {
                TunnelStatus.Running -> Triple(Color(0xFF4CAF50), "运行中", Color(0xFF2E7D32))
                TunnelStatus.Starting -> Triple(Color(0xFFFFA726), "启动中...", Color(0xFFEF6C00))
                TunnelStatus.Failed -> Triple(Color(0xFFEF5350), "已停止（${tunnelState.errorMessage.ifBlank { "未知错误" }}）", Coral)
                TunnelStatus.Stopped -> Triple(Color(0xFFB0B7C3), "已停止", Muted)
                TunnelStatus.Idle -> Triple(Color(0xFFB0B7C3), "未启动", Muted)
            }
            if (tunnelState.status == TunnelStatus.Starting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(14.dp),
                    strokeWidth = 2.dp,
                    color = statusColor
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(dotColor)
                )
            }
            Spacer(Modifier.size(8.dp))
            Text(statusText, fontSize = 13.sp, color = statusColor, fontWeight = FontWeight.SemiBold)
            if (tunnelState.activeType != null) {
                Spacer(Modifier.size(6.dp))
                Text("·", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.size(6.dp))
                Text(tunnelState.activeType!!.displayName, color = Muted, fontSize = 11.sp)
            }
        }

        // 公网地址展示（仅 cloudflared/ngrok 有）
        if (tunnelState.publicUrl.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            Text("公网连接地址", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFFE8F5E9))
                    .border(1.dp, Color(0xFF4CAF50), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp)
            ) {
                Text(
                    tunnelState.publicUrl,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onCopyUrl) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制公网地址", tint = Indigo)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "将此地址分享给玩家，在 Minecraft「直接连接」中粘贴即可",
                color = Muted, fontSize = 11.sp
            )
        }

        Spacer(Modifier.height(12.dp))

        // 启停按钮
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = onStart,
                enabled = tunnelState.status != TunnelStatus.Starting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Indigo,
                    disabledContainerColor = Color(0xFFB0BEC5)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("启动穿透", color = Color.White)
            }

            Button(
                onClick = onStop,
                enabled = tunnelState.status == TunnelStatus.Running || tunnelState.status == TunnelStatus.Starting,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF8890A0),
                    disabledContainerColor = Color(0xFFD0D4DA)
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Outlined.Stop, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("停止", color = Color.White)
            }
        }
    }
}

// ── 隧道日志预览 ──────────────────────────────────────────────

@Composable
private fun TunnelLogPreview(consoleLines: List<String>) {
    // 过滤出隧道相关日志，最多显示 8 条最新的
    val tunnelLogs: List<String> = remember(consoleLines) {
        consoleLines.filter { it.contains("[tunnel]") }.takeLast(8)
    }

    if (tunnelLogs.isEmpty()) return

    McCard(title = "隧道日志") {
        tunnelLogs.forEach { line ->
            Text(
                line,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Muted,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }
    }
}

// ── 操作指南 ──────────────────────────────────────────────────

@Composable
private fun GuideSection(tunnelType: TunnelType) {
    var expanded by remember { mutableStateOf(true) }

    McCard(title = "操作指南") {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                "点击${if (expanded) "收起" else "展开"}详细配置指引",
                color = Indigo, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Icon(
                if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore,
                contentDescription = null,
                tint = Indigo
            )
        }

        AnimatedVisibility(visible = expanded) {
            Column {
                when (tunnelType) {
                    TunnelType.Frp -> FrpGuide()
                    TunnelType.Cloudflared -> CloudflaredGuide()
                    TunnelType.Ngrok -> NgrokGuide()
                }
            }
        }
    }
}

@Composable
private fun FrpGuide() {
    GuideBlock(title = "frp 内网穿透配置指引") {
        GuideStep("1", "准备公网服务器", "需要一台有公网 IP 的 VPS（推荐 Oracle Cloud ARM 免费实例）。在 VPS 上下载并部署 frps（frp 服务端）。")
        GuideStep("2", "配置 frps.toml（服务端）", "在 VPS 上创建 frps.toml：\nbindPort = 7000\nauth.method = \"token\"\nauth.token = \"你的密码\"")
        GuideStep("3", "启动 frps", "在 VPS 上执行: ./frps -c frps.toml")
        GuideStep("4", "填写本页配置", "将 VPS 的公网 IP 或域名填入「frp 服务端地址」，端口默认 7000，token 与服务端一致。")
        GuideStep("5", "启动穿透", "点击上方「启动穿透」按钮，frpc 将连接到你的 frps 服务器。首次使用会通过 apt 自动安装 frp。")
        GuideStep("6", "玩家连接", "玩家使用 VPS 的公网 IP:25565 在 Minecraft 中直接连接即可。")
        GuideStep("7", "安全建议", "在 VPS 防火墙开放 7000 和 25565 端口。建议启用 token 认证防止未授权连接。")
    }
}

@Composable
private fun CloudflaredGuide() {
    GuideBlock(title = "Cloudflare Tunnel 配置指引") {
        GuideStep("1", "Quick Tunnel（推荐新手）", "打开 Quick Tunnel 开关，点击「启动穿透」。\ncloudflared 会自动分配一个 *.trycloudflare.com 的随机公网地址。\n首次使用会自动从 GitHub 下载 cloudflared 二进制。")
        GuideStep("2", "获取公网地址", "启动后状态卡片会自动显示分配的公网地址，点击复制按钮即可分享给玩家。\n注意：Quick Tunnel 地址每次重启都会变化。")
        GuideStep("3", "Named Tunnel（固定域名）", "关闭 Quick Tunnel 开关，填写你的 Cloudflare 域名。\n前提：域名 DNS 已托管在 Cloudflare（免费）。")
        GuideStep("4", "准备凭证文件", "Named Tunnel 需在 PC 端执行:\ncloudflared tunnel login\ncloudflared tunnel create mc-tunnel\n然后将生成的 mc-tunnel.json 传到手机 home/tunnel/ 目录。")
        GuideStep("5", "配置 DNS 路由", "在 PC 端执行:\ncloudflared tunnel route dns mc-tunnel mc.yourdomain.com\n这会自动在 Cloudflare DNS 添加 CNAME 记录。")
        GuideStep("6", "启动穿透", "点击「启动穿透」按钮，cloudflared 将通过 Cloudflare 边缘网络转发流量。\n玩家使用域名连接，享受 Cloudflare 的 CDN 加速和 DDoS 防护。")
        GuideStep("7", "注意事项", "Quick Tunnel 限制 200 并发请求，不支持 SSE。\n生产环境建议使用 Named Tunnel。Android 无法直接登录 Cloudflare，凭证文件需从 PC 端导入。")
    }
}

@Composable
private fun NgrokGuide() {
    GuideBlock(title = "ngrok 配置指引") {
        GuideStep("1", "注册账号", "访问 ngrok.com 注册免费账号。免费层限制：1GB/月流量、随机 TCP 地址、3 个并发端点。")
        GuideStep("2", "获取 Authtoken", "登录后在 Dashboard 页面复制你的 Authtoken，粘贴到上方输入框。")
        GuideStep("3", "启动穿透", "点击「启动穿透」按钮。首次使用会自动从 equinox.io 下载 ngrok 二进制。\nngrok 会分配一个随机 TCP 地址（如 0.tcp.ngrok.io:12345）。")
        GuideStep("4", "获取公网地址", "启动后状态卡片会自动显示分配的公网地址，点击复制按钮即可分享给玩家。")
        GuideStep("5", "玩家连接", "玩家使用分配的地址:端口在 Minecraft 中直接连接。")
        GuideStep("6", "注意事项", "免费层地址每次重启都会变化，且流量有限。\n长期使用建议升级到付费版或使用 frp/Cloudflare Tunnel。")
    }
}

@Composable
private fun GuideBlock(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Indigo)
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun GuideStep(step: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(step, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(description, color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
        }
    }
}
