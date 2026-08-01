package com.mcserver.manager.ui.screens

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.mcserver.manager.data.TunnelStatus
import com.mcserver.manager.data.TunnelState
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.SegPill
import com.mcserver.manager.ui.DebouncedTextField
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkScreen(vm: McViewModel, onBack: () -> Unit) {
    val config by vm.config.collectAsState()
    val lanIp by vm.lanIp.collectAsState()
    val serverState by vm.serverState.collectAsState()
    val tunnelState by vm.tunnelState.collectAsState()
    val consoleLines by vm.consoleLines.collectAsState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.refreshLanIp() }
    // 收集操作结果和错误消息，通过 Snackbar 显示（修复"点击无反应"Bug的关键）
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
        // 返回栏（白底覆盖状态栏，配合全屏展示）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Text("返回设置", fontSize = 14.sp, fontWeight = FontWeight.Medium, modifier = Modifier.padding(start = 4.dp))
        }
        HeaderBlock(eyebrow = "Networking", title = "端口与内网穿透", statusBarPadding = false)

        // ── 服务器连接信息（局域网） ──────────────────────────
        McCard(title = "本地连接地址") {
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

            // 本地回环地址（本机直连，同设备测试用）
            Text("本机地址", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                    "127.0.0.1:${config.localPort}",
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MC Server", "127.0.0.1:${config.localPort}"))
                }) {
                    Icon(Icons.Outlined.ContentCopy, contentDescription = "复制", tint = Indigo)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "同一台手机上用 MC 客户端测试时使用此地址",
                color = Muted, fontSize = 10.sp
            )

            Spacer(Modifier.height(12.dp))

            // 局域网 IP（其他设备连接用）
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

            Text("局域网连接地址", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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
                    fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f)
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

        // ── 隧道日志预览 ──────────────────────────────────────
        TunnelLogPreview(consoleLines)

        // ── 本地端口配置 ──────────────────────────────────────
        McCard(title = "本地端口") {
            Text("Minecraft 服务器监听端口，默认 25565", color = Muted, fontSize = 11.sp)
            Spacer(Modifier.height(8.dp))
            DebouncedTextField(
                value = config.localPort.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { vm.setLocalPort(it) } },
                sanitize = { it.filter(Char::isDigit) },
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
                    Text("frpc.toml 配置", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    DebouncedTextField(
                        value = config.frpConfigText,
                        onValueChange = { v -> vm.updateConfig { it.copy(frpConfigText = v) } },
                        placeholder = { Text("粘贴完整 frpc.toml 内容...") },
                        minLines = 6,
                        maxLines = 12,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "粘贴完整的 frpc.toml 配置文本，或从文件管理器导入",
                        color = Muted, fontSize = 10.sp
                    )
                }
            }

            AnimatedVisibility(
                visible = config.tunnelType == TunnelType.Bore,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    Text("bore 服务端地址", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    DebouncedTextField(
                        value = config.boreServerAddr,
                        onValueChange = { v -> vm.updateConfig { it.copy(boreServerAddr = v) } },
                        placeholder = { Text("例如: your-vps.com:7835") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "bore 是纯手机端运行的轻量隧道，无需下载额外程序。在 VPS 上运行 bore server 即可。",
                        color = Muted, fontSize = 10.sp
                    )
                }
            }
        }

        // ── 免费 FRP 平台 ──────────────────────────────────────
        FreeFrpPlatformsCard(context)

        // ── 操作指南（说明书） ──────────────────────────────────
        GuideSection(config.tunnelType)

        Spacer(Modifier.height(16.dp))
        }
    }
}

// ── 隧道状态卡片 ──────────────────────────────────────────────

@Composable
private fun TunnelStatusCard(
    tunnelState: TunnelState,
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

        // 公网地址展示（始终显示，退出后也保留）
        if (tunnelState.publicUrl.isNotBlank()) {
            val isActive = tunnelState.status == TunnelStatus.Running
            Spacer(Modifier.height(12.dp))
            Text(
                if (isActive) "公网连接地址" else "隧道已断开，上次地址",
                color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.height(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (isActive) Color(0xFFE8F5E9) else Color(0xFFFFF3E0))
                    .border(1.dp, if (isActive) Color(0xFF4CAF50) else Color(0xFFFFA726), RoundedCornerShape(12.dp))
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
                if (isActive) "将此地址分享给玩家，在 Minecraft「直接连接」中粘贴即可"
                else "重新启动隧道获取新地址",
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
    // 过滤出隧道相关日志
    val tunnelLogs: List<String> = remember(consoleLines) {
        consoleLines.filter { it.contains("[tunnel]") }
    }

    if (tunnelLogs.isEmpty()) return

    var showFullLog by remember { mutableStateOf(false) }

    McCard(title = "隧道日志") {
        // 最近 8 条预览
        tunnelLogs.takeLast(8).forEach { line ->
            Text(
                line,
                fontSize = 10.sp,
                fontFamily = FontFamily.Monospace,
                color = Muted,
                maxLines = 1,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
            )
        }

        // 全屏按钮
        Spacer(Modifier.height(8.dp))
        Button(
            onClick = { showFullLog = true },
            colors = ButtonDefaults.buttonColors(containerColor = IndigoSoft),
            shape = RoundedCornerShape(8.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Outlined.OpenInFull, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text("查看全部日志 (${tunnelLogs.size} 条)", fontSize = 12.sp)
        }
    }

    // 全屏日志 Dialog
    if (showFullLog) {
        AlertDialog(
            onDismissRequest = { showFullLog = false },
            title = {
                Text("隧道日志 (${tunnelLogs.size} 条)", fontWeight = FontWeight.Bold)
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    if (tunnelLogs.isEmpty()) {
                        Text("暂无隧道日志", color = Muted, fontSize = 13.sp)
                    } else {
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
            },
            confirmButton = {
                Button(onClick = { showFullLog = false }) {
                    Text("关闭")
                }
            }
        )
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
                    TunnelType.Bore -> BoreGuide()
                }
            }
        }
    }
}

@Composable
private fun FrpGuide() {
    GuideBlock(title = "frp 使用教程（自建服务器）") {
        Text(
            "💡 不想自建服务器？页面下方「免费 FRP 平台」卡片提供 OpenFrp / ChmlFrp / StarryFrp / SakuraFrp 等免费 frp 服务：注册账号后，把平台分配的节点地址、端口与 Token 填入上方 frpc.toml 配置，点「启动穿透」即可。",
            color = Coral, fontSize = 11.sp, lineHeight = 15.sp
        )
        Spacer(Modifier.height(10.dp))
        GuideStep("1", "啥是 frp？", "简单说就是打个「隧道」：你的手机通过 frp 连到一台有公网 IP 的服务器，别人访问那台服务器就能连到你的手机。就像在墙上凿了个洞，外面的人通过洞就能看到里面的东西。")
        GuideStep("2", "你需要一台云服务器", "去阿里云、腾讯云或 Oracle Cloud（有免费的 ARM 实例）租一台云服务器，最便宜的就够用。关键是要有「公网 IP」。")
        GuideStep("3", "在服务器上装 frp 服务端", "登录服务器后，去 GitHub 搜 fatedier/frp，下载对应版本，解压后编辑 frps.toml 文件：\nbindPort = 7000\nauth.method = \"token\"\nauth.token = \"随便设个密码\"\n然后运行 ./frps -c frps.toml 启动服务端。")
        GuideStep("4", "在手机上填配置", "把服务器的公网 IP 填到上面「frp 服务端地址」，端口填 7000，Token 填你刚才设的密码（要跟服务端一模一样）。")
        GuideStep("5", "点启动就行", "点上面的「启动穿透」按钮，程序会自动帮你装好 frp 客户端并连接服务器。首次会通过 apt 自动安装，稍等一会儿。")
        GuideStep("6", "告诉朋友怎么连", "启动成功后，把服务器公网 IP 和端口（默认 25565）告诉朋友，在 Minecraft「多人游戏」→「直接连接」里输入 IP:25565 就能进来了。")
        GuideStep("7", "别忘了开端口", "服务器的防火墙要放行 7000（frp 通信用）和 25565（MC 用）两个端口，不然连不上。建议设 Token 密码防别人乱连。")
    }
}

@Composable
private fun BoreGuide() {
    GuideBlock(title = "bore 使用教程（纯手机端运行）") {
        GuideStep("1", "bore 是啥？", "bore 是一个极简的 TCP 隧道工具，协议超级简单只有 3 种消息。手机端直接用纯 Kotlin 实现，无需下载任何程序，秒启动。")
        GuideStep("2", "你需要一台云服务器", "跟 frp 一样，需要一台有公网 IP 的 VPS。去 GitHub 搜 ekzhang/bore，下载 bore 二进制，在服务器上运行：./bore server")
        GuideStep("3", "填服务端地址", "把服务器的 IP 和端口（默认 7835）填到上面的输入框，格式如 your-vps.com:7835。")
        GuideStep("4", "点启动就行", "点「启动穿透」按钮，程序直接用手机网络连接 bore 服务端。不需要 Termux，不需要下载，启动只需几秒。")
        GuideStep("5", "把地址给朋友", "启动后状态卡片显示公网地址（serverIP:端口），复制发给朋友在 MC 直接连接粘贴即可。")
        GuideStep("6", "优点和限制", "优点：极轻量（~300 行代码），秒启动。限制：仅 TCP，需自建服务器。适合追求低延迟和轻量体验的用户。")
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

// ── 免费 FRP 平台 ──────────────────────────────────────────────

/** 免费 FRP 平台清单（官方网址，点击跳转浏览器） */
private data class FrpPlatform(val name: String, val url: String)

private val freeFrpPlatforms = listOf(
    FrpPlatform("OpenFrp", "https://www.openfrp.net/"),
    FrpPlatform("ChmlFrp", "https://www.chmlfrp.cn/"),
    FrpPlatform("StarryFrp（星空FRP）", "https://frp.starryfrp.com/"),
    FrpPlatform("SakuraFrp（樱花映射）", "https://www.natfrp.com/")
)

@Composable
private fun FreeFrpPlatformsCard(context: Context) {
    McCard(title = "免费 FRP 平台") {
        Text(
            "不想自建服务器？以下免费 frp 平台注册后即可使用：把平台提供的节点地址、端口与 Token 填入上方 frpc.toml 配置即可启动。",
            color = Muted, fontSize = 10.sp, lineHeight = 14.sp
        )
        Spacer(Modifier.height(10.dp))
        freeFrpPlatforms.forEach { p ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(IndigoSoft)
                    .clickable {
                        try {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(p.url)))
                        } catch (e: Exception) {
                            // 设备上无可用浏览器时静默忽略
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(p.name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(p.url, color = Indigo, fontSize = 10.sp)
                }
                Icon(
                    Icons.AutoMirrored.Outlined.OpenInNew,
                    contentDescription = "打开官网",
                    tint = Indigo,
                    modifier = Modifier.size(14.dp)
                )
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}
