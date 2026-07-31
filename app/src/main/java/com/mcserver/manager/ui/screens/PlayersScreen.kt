package com.mcserver.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Person
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.server.PlayerManager
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.CoralSoft
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.MintSoft
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

private enum class PlayerListTab(val label: String) { Ops("OP 列表"), Whitelist("白名单"), Banned("封禁列表") }

private enum class BanDuration(val label: String, val value: String) {
    FOREVER("永久", ""),
    MIN30("30 分钟", "30m"),
    HOUR1("1 小时", "1h"),
    HOURS6("6 小时", "6h"),
    DAY1("1 天", "1d"),
    DAYS7("7 天", "7d"),
    DAYS30("30 天", "30d")
}

private enum class OpLevel(val label: String, val value: Int) {
    L1("1 - 最低（仅基础命令）", 1),
    L2("2 - 中等（含封禁/踢出）", 2),
    L3("3 - 高（含停服/重载）", 3),
    L4("4 - 最高（完整权限）", 4)
}

/**
 * 玩家管理页（重构版）
 *
 * 优化点：
 *  - 命令发送前校验服务器运行状态，未运行给明确错误反馈
 *  - 命令后延迟 600ms 再刷新列表，避免读到旧 JSON
 *  - 新增限时封禁（tempban）、白名单开关、OP 等级选择
 *  - 每个列表加搜索框
 *  - 点击列表项弹出玩家详情对话框
 *  - 移除冗余的快捷操作卡片（操作整合到列表行）
 *  - 服务器未运行时显示醒目状态横幅
 */
@Composable
fun PlayersScreen(vm: McViewModel) {
    val serverState by vm.serverState.collectAsState()
    val ops by vm.ops.collectAsState()
    val whitelist by vm.whitelist.collectAsState()
    val banned by vm.bannedPlayers.collectAsState()
    val whitelistEnabled by vm.whitelistEnabled.collectAsState()
    val config by vm.config.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(PlayerListTab.Ops) }
    var detailPlayer by remember { mutableStateOf<DetailInfo?>(null) }

    LaunchedEffect(isBootstrapped, config.activeCoreName) {
        if (isBootstrapped && config.activeCoreName != null) {
            vm.refreshPlayers()
        }
    }
    LaunchedEffect(Unit) { vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) } }

    val isRunning = serverState.isRunning
    val activeCore = config.installedCores.find { it.name == config.activeCoreName }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Player Management", title = "玩家管理")

            // ── 服务器状态横幅 ──
            ServerStatusBanner(
                isRunning = isRunning,
                isBootstrapped = isBootstrapped,
                hasActiveCore = activeCore != null,
                onlineCount = serverState.onlinePlayers,
                maxPlayers = serverState.maxPlayers,
                onRefresh = { vm.refreshPlayers() }
            )

            // ── 标签切换 ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(IndigoSoft)
                    .padding(4.dp)
            ) {
                PlayerListTab.values().forEach { tab ->
                    val count = when (tab) {
                        PlayerListTab.Ops -> ops.size
                        PlayerListTab.Whitelist -> whitelist.size
                        PlayerListTab.Banned -> banned.size
                    }
                    val label = if (count > 0) "${tab.label} $count" else tab.label
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (activeTab == tab) Indigo else Color.Transparent)
                            .clickable { activeTab = tab }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            label,
                            color = if (activeTab == tab) Color.White else Muted,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            when (activeTab) {
                PlayerListTab.Ops -> OpsTab(
                    ops = ops,
                    isRunning = isRunning,
                    onOpAdd = { name, level -> vm.opPlayerWithLevel(name, level) },
                    onShowDetail = { detailPlayer = DetailInfo.Op(it) }
                )
                PlayerListTab.Whitelist -> WhitelistTab(
                    whitelist = whitelist,
                    isRunning = isRunning,
                    whitelistEnabled = whitelistEnabled,
                    onToggle = { vm.toggleWhitelist(it) },
                    onWhitelistAdd = { vm.whitelistAdd(it) },
                    onShowDetail = { detailPlayer = DetailInfo.Whitelist(it) }
                )
                PlayerListTab.Banned -> BannedTab(
                    banned = banned,
                    isRunning = isRunning,
                    onBan = { name, duration, reason ->
                        if (duration.isBlank()) vm.banPlayer(name, reason)
                        else vm.tempBanPlayer(name, duration, reason)
                    },
                    onShowDetail = { detailPlayer = DetailInfo.Banned(it) }
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 详情对话框
    detailPlayer?.let { info ->
        PlayerDetailDialog(
            info = info,
            isRunning = isRunning,
            onDismiss = { detailPlayer = null },
            onDeop = { vm.deopPlayer(it); detailPlayer = null },
            onWhitelistRemove = { vm.whitelistRemove(it); detailPlayer = null },
            onPardon = { vm.pardonPlayer(it); detailPlayer = null },
            onKick = { vm.kickPlayer(it, "管理员踢出"); detailPlayer = null },
            onBan = { vm.banPlayer(it, "管理员封禁"); detailPlayer = null },
            onSetGameMode = { name, mode -> vm.setGameMode(name, mode) },
            onGiveXp = { name, amount -> vm.giveXp(name, amount) }
        )
    }
}

// ── 服务器状态横幅 ──────────────────────────────────────────────────

@Composable
private fun ServerStatusBanner(
    isRunning: Boolean,
    isBootstrapped: Boolean,
    hasActiveCore: Boolean,
    onlineCount: Int,
    maxPlayers: Int,
    onRefresh: () -> Unit
) {
    McCard(
        title = "服务器状态",
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Outlined.Refresh,
                    contentDescription = "刷新",
                    tint = Indigo,
                    modifier = Modifier.size(14.dp).clickable { onRefresh() }
                )
            }
        }
    ) {
        if (!isBootstrapped) {
            StatusRow(color = Coral, text = "Termux 环境未就绪，请先初始化")
        } else if (!hasActiveCore) {
            StatusRow(color = Coral, text = "未选择服务端核心，请在「概览」页选用")
        } else if (!isRunning) {
            StatusRow(color = Coral, text = "服务器未运行 · 所有命令操作不可用，请先启动服务端")
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Mint)
                )
                Spacer(Modifier.size(6.dp))
                Text(
                    "服务器运行中 · 在线 $onlineCount / $maxPlayers",
                    color = Mint,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun StatusRow(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(8.dp).clip(RoundedCornerShape(4.dp)).background(color))
        Spacer(Modifier.size(6.dp))
        Text(text, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

// ── OP 列表标签 ──────────────────────────────────────────────────────

@Composable
private fun OpsTab(
    ops: List<PlayerManager.OpEntry>,
    isRunning: Boolean,
    onOpAdd: (String, Int) -> Unit,
    onShowDetail: (PlayerManager.OpEntry) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var opName by remember { mutableStateOf("") }
    var selectedLevel by remember { mutableStateOf(OpLevel.L4) }
    var levelMenuOpen by remember { mutableStateOf(false) }

    McCard(title = "OP 管理") {
        Text(
            "OP 拥有管理员权限，等级越高可用命令越多。建议仅给信任的玩家。",
            color = Muted,
            fontSize = 10.sp
        )
        Spacer(Modifier.height(10.dp))

        // 搜索框
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("搜索 OP 玩家名...", fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(10.dp))

        if (ops.isEmpty()) {
            EmptyHint("暂无 OP 玩家")
        } else {
            val filtered = ops.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
            if (filtered.isEmpty()) {
                EmptyHint("没有匹配的 OP 玩家")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { op ->
                        PlayerListRow(
                            name = op.name,
                            subtitle = "等级 ${op.level} · ${op.uuid.take(8)}...",
                            badgeText = "Lv.${op.level}",
                            badgeColor = Indigo,
                            onClick = { onShowDetail(op) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("添加 OP", color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = opName,
            onValueChange = { opName = it },
            label = { Text("玩家名", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(6.dp))
        // 等级选择
        Box {
            OutlinedButton(
                onClick = { levelMenuOpen = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedLevel.label, color = Indigo, fontSize = 11.sp)
            }
            DropdownMenu(expanded = levelMenuOpen, onDismissRequest = { levelMenuOpen = false }) {
                OpLevel.values().forEach { lvl ->
                    DropdownMenuItem(
                        text = { Text(lvl.label, fontSize = 11.sp) },
                        onClick = { selectedLevel = lvl; levelMenuOpen = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!isRunning) {
            Text("服务器未运行，无法添加 OP", color = Coral, fontSize = 10.sp)
        }
        Button(
            onClick = {
                if (opName.isNotBlank()) {
                    onOpAdd(opName.trim(), selectedLevel.value)
                    opName = ""
                }
            },
            enabled = isRunning && opName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Indigo),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("添加 OP（等级 ${selectedLevel.value}）", color = Color.White, fontSize = 11.sp)
        }
    }
}

// ── 白名单标签 ──────────────────────────────────────────────────────

@Composable
private fun WhitelistTab(
    whitelist: List<PlayerManager.WhitelistEntry>,
    isRunning: Boolean,
    whitelistEnabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onWhitelistAdd: (String) -> Unit,
    onShowDetail: (PlayerManager.WhitelistEntry) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }

    McCard(title = "白名单") {
        // 白名单开关
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "白名单 ${if (whitelistEnabled) "已开启" else "已关闭"}",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (whitelistEnabled) Mint else Muted
                )
                Text(
                    if (whitelistEnabled) "仅白名单内玩家可加入"
                    else "所有玩家均可加入（开启后可限制）",
                    color = Muted,
                    fontSize = 10.sp
                )
            }
            Switch(
                checked = whitelistEnabled,
                onCheckedChange = onToggle,
                enabled = isRunning
            )
        }
        if (!isRunning) {
            Spacer(Modifier.height(4.dp))
            Text("服务器未运行，无法切换白名单", color = Coral, fontSize = 10.sp)
        }

        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = search,
            onValueChange = { search = it },
            placeholder = { Text("搜索白名单玩家...", fontSize = 11.sp) },
            leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(10.dp))

        if (whitelist.isEmpty()) {
            EmptyHint("白名单为空")
        } else {
            val filtered = whitelist.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
            if (filtered.isEmpty()) {
                EmptyHint("没有匹配的白名单玩家")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { w ->
                        PlayerListRow(
                            name = w.name,
                            subtitle = w.uuid,
                            badgeText = null,
                            badgeColor = Color.Transparent,
                            onClick = { onShowDetail(w) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("添加白名单", color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = newName,
                onValueChange = { newName = it },
                label = { Text("玩家名", fontSize = 11.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(10.dp)
            )
            Button(
                onClick = {
                    if (newName.isNotBlank()) {
                        onWhitelistAdd(newName.trim())
                        newName = ""
                    }
                },
                enabled = isRunning && newName.isNotBlank(),
                colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                shape = RoundedCornerShape(10.dp)
            ) { Text("添加", color = Color.White, fontSize = 11.sp) }
        }
    }
}

// ── 封禁列表标签 ────────────────────────────────────────────────────

@Composable
private fun BannedTab(
    banned: List<PlayerManager.BannedEntry>,
    isRunning: Boolean,
    onBan: (String, String, String) -> Unit,
    onShowDetail: (PlayerManager.BannedEntry) -> Unit
) {
    var search by remember { mutableStateOf("") }
    var banName by remember { mutableStateOf("") }
    var banReason by remember { mutableStateOf("") }
    var selectedDuration by remember { mutableStateOf(BanDuration.FOREVER) }
    var durationMenuOpen by remember { mutableStateOf(false) }

    McCard(title = "封禁列表") {
        if (banned.isEmpty()) {
            EmptyHint("暂无封禁玩家")
        } else {
            OutlinedTextField(
                value = search,
                onValueChange = { search = it },
                placeholder = { Text("搜索封禁玩家...", fontSize = 11.sp) },
                leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null, modifier = Modifier.size(14.dp)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp)
            )
            Spacer(Modifier.height(10.dp))
            val filtered = banned.filter { search.isBlank() || it.name.contains(search, ignoreCase = true) }
            if (filtered.isEmpty()) {
                EmptyHint("没有匹配的封禁玩家")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    filtered.forEach { b ->
                        val isTemp = b.expires != "forever" && b.expires.isNotBlank()
                        PlayerListRow(
                            name = b.name,
                            subtitle = if (isTemp) "限时至 ${b.expires}" else "永久封禁",
                            badgeText = if (isTemp) "限时" else "永久",
                            badgeColor = if (isTemp) Coral else CoralSoft,
                            onClick = { onShowDetail(b) }
                        )
                    }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        Text("封禁玩家", color = Coral, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = banName,
            onValueChange = { banName = it },
            label = { Text("玩家名", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = banReason,
            onValueChange = { banReason = it },
            label = { Text("封禁原因（可选）", fontSize = 11.sp) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(10.dp)
        )
        Spacer(Modifier.height(6.dp))
        // 封禁时长选择
        Box {
            OutlinedButton(
                onClick = { durationMenuOpen = true },
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(selectedDuration.label, color = Indigo, fontSize = 11.sp)
            }
            DropdownMenu(expanded = durationMenuOpen, onDismissRequest = { durationMenuOpen = false }) {
                BanDuration.values().forEach { d ->
                    DropdownMenuItem(
                        text = { Text(d.label, fontSize = 11.sp) },
                        onClick = { selectedDuration = d; durationMenuOpen = false }
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        if (!isRunning) {
            Text("服务器未运行，无法执行封禁", color = Coral, fontSize = 10.sp)
        }
        Button(
            onClick = {
                if (banName.isNotBlank()) {
                    onBan(banName.trim(), selectedDuration.value, banReason.trim())
                    banName = ""
                    banReason = ""
                }
            },
            enabled = isRunning && banName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = Coral),
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (selectedDuration == BanDuration.FOREVER) "永久封禁"
                else "限时封禁（${selectedDuration.label}）",
                color = Color.White,
                fontSize = 11.sp
            )
        }
    }
}

// ── 详情对话框 ──────────────────────────────────────────────────────

private sealed class DetailInfo {
    data class Op(val entry: PlayerManager.OpEntry) : DetailInfo()
    data class Whitelist(val entry: PlayerManager.WhitelistEntry) : DetailInfo()
    data class Banned(val entry: PlayerManager.BannedEntry) : DetailInfo()
}

@Composable
private fun PlayerDetailDialog(
    info: DetailInfo,
    isRunning: Boolean,
    onDismiss: () -> Unit,
    onDeop: (String) -> Unit,
    onWhitelistRemove: (String) -> Unit,
    onPardon: (String) -> Unit,
    onKick: (String) -> Unit,
    onBan: (String) -> Unit,
    onSetGameMode: (String, Int) -> Unit,
    onGiveXp: (String, Int) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Person, contentDescription = null, tint = Indigo, modifier = Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("玩家详情", fontWeight = FontWeight.Bold)
            }
        },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                when (info) {
                    is DetailInfo.Op -> {
                        val op = info.entry
                        DetailRow("玩家名", op.name)
                        DetailRow("UUID", op.uuid)
                        DetailRow("OP 等级", "${op.level} / 4")
                    }
                    is DetailInfo.Whitelist -> {
                        val w = info.entry
                        DetailRow("玩家名", w.name)
                        DetailRow("UUID", w.uuid)
                    }
                    is DetailInfo.Banned -> {
                        val b = info.entry
                        DetailRow("玩家名", b.name)
                        DetailRow("UUID", b.uuid)
                        DetailRow("封禁原因", b.reason.ifBlank { "未填写" })
                        DetailRow("过期时间", b.expires.ifBlank { "永久" })
                        if (b.source.isNotBlank()) DetailRow("来源", b.source)
                    }
                }

                Spacer(Modifier.height(12.dp))
                if (!isRunning) {
                    Text("服务器未运行，命令操作不可用", color = Coral, fontSize = 10.sp)
                } else {
                    Text("快捷操作", color = Indigo, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(8.dp))

                    val playerName = when (info) {
                        is DetailInfo.Op -> info.entry.name
                        is DetailInfo.Whitelist -> info.entry.name
                        is DetailInfo.Banned -> info.entry.name
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = { onKick(playerName) }, modifier = Modifier.weight(1f)) {
                            Text("踢出", color = Coral, fontSize = 11.sp)
                        }
                        TextButton(onClick = { onBan(playerName) }, modifier = Modifier.weight(1f)) {
                            Text("封禁", color = Coral, fontSize = 11.sp)
                        }
                    }

                    // 根据类型显示对应操作
                    when (info) {
                        is DetailInfo.Op -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onDeop(playerName) }, modifier = Modifier.weight(1f)) {
                                    Text("取消 OP", color = Coral, fontSize = 11.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                            Text("游戏模式", color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf("生存" to 0, "创造" to 1, "冒险" to 2, "旁观" to 3).forEach { (name, mode) ->
                                    TextButton(onClick = { onSetGameMode(playerName, mode) }) {
                                        Text(name, fontSize = 10.sp, color = Indigo)
                                    }
                                }
                            }
                        }
                        is DetailInfo.Whitelist -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onWhitelistRemove(playerName) }, modifier = Modifier.weight(1f)) {
                                    Text("移出白名单", color = Coral, fontSize = 11.sp)
                                }
                            }
                        }
                        is DetailInfo.Banned -> {
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                                TextButton(onClick = { onPardon(playerName) }, modifier = Modifier.weight(1f)) {
                                    Text("解除封禁", color = Mint, fontSize = 11.sp)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭") }
        }
    )
}

// ── 通用组件 ──────────────────────────────────────────────────────

@Composable
private fun PlayerListRow(
    name: String,
    subtitle: String,
    badgeText: String?,
    badgeColor: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(IndigoSoft)
            .clickable { onClick() }
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Indigo),
            contentAlignment = Alignment.Center
        ) {
            Text(name.take(2).uppercase(), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(Modifier.size(10.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(name, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (badgeText != null) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(badgeColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(badgeText, color = badgeColor, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.size(6.dp))
        }
        Icon(Icons.Outlined.Info, contentDescription = "详情", tint = Muted, modifier = Modifier.size(14.dp))
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Muted, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        Text(value, fontSize = 11.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun EmptyHint(text: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(Icons.Outlined.Person, contentDescription = null, tint = Muted.copy(alpha = 0.4f), modifier = Modifier.size(32.dp))
        Spacer(Modifier.size(8.dp))
        Text(text, color = Muted, fontSize = 11.sp)
    }
}
