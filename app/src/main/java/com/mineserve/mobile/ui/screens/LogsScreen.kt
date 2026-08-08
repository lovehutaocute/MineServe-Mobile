package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.TextButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.Icon
import androidx.compose.runtime.derivedStateOf
import androidx.compose.material3.IconButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Card
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Ink
import com.mineserve.mobile.ui.theme.Muted

/**
 * 日志页：只读可视化反馈（不是交互入口）。
 * - 顶部"返回"按钮回到概览
 * - 实时滚动展示 consoleFlow 输出
 * - 底部提供一条 send 框（受控、给高级用户用），不影响"全程不接触命令行"定位
 */
@Composable
fun LogsScreen(vm: McViewModel, onBack: () -> Unit) {
    val lines by vm.consoleLines.collectAsState()
    var input by remember { mutableStateOf("") }
    // 会话面板状态
    val serverState by vm.serverState.collectAsState()
    val termuxLines by vm.termuxLines.collectAsState()
    val termuxBusy by vm.termuxBusy.collectAsState()
    var termuxInput by remember { mutableStateOf("") }
    var mcExpanded by remember { mutableStateOf(true) }
    var termuxExpanded by remember { mutableStateOf(true) }
    val context = LocalContext.current
    // 日志汉化开关（prefs，默认开启）；仅中文系统语言下生效
    val logPrefs = remember { context.getSharedPreferences("log_prefs", Context.MODE_PRIVATE) }
    var logLocalized by remember { mutableStateOf(logPrefs.getBoolean("localize", true)) }
    var showLogSettings by remember { mutableStateOf(false) }
    val isChineseLocale = java.util.Locale.getDefault().language == "zh"

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部带返回按钮的 Header（白底覆盖状态栏，配合全屏展示）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card)
                .statusBarsPadding()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = stringResource(R.string.s404))
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("LOG STREAM", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text(stringResource(R.string.s557), color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            // 一键复制日志按钮
            IconButton(onClick = {
                if (lines.isNotEmpty()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MCServer Logs", lines.joinToString("\n")))
                    Toast.makeText(context, context.getString(R.string.s558, lines.size), Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, context.getString(R.string.s559), Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = stringResource(R.string.s560), tint = Indigo)
            }
            // 日志设置齿轮按钮（汉化开关）
            IconButton(onClick = { showLogSettings = true }) {
                Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.ui_log_localize), tint = Indigo)
            }
        }

        // 快捷指令栏：原版 MC 常用指令，横向滑动查看全部
        val quickCommands = listOf(
            Triple("🕐", R.string.cmd_time_day, "/time set day"),
            Triple("🌙", R.string.cmd_time_night, "/time set night"),
            Triple("☀️", R.string.cmd_weather_clear, "/weather clear"),
            Triple("🌧️", R.string.cmd_weather_rain, "/weather rain"),
            Triple("⛈️", R.string.cmd_weather_thunder, "/weather thunder"),
            Triple("🎮", R.string.cmd_gamemode_survival, "/gamemode survival"),
            Triple("🧱", R.string.cmd_gamemode_creative, "/gamemode creative"),
            Triple("👥", R.string.cmd_list, "/list"),
            Triple("🐢", R.string.cmd_tps, "/tps"),
            Triple("💾", R.string.cmd_save_all, "/save-all"),
            Triple("📣", R.string.cmd_say, "/say "),
            Triple("👟", R.string.cmd_kick, "/kick ")
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickCommands.forEach { (emoji, labelRes, cmd) ->
                androidx.compose.material3.TextButton(
                    onClick = { vm.sendCommand(cmd) },
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).height(28.dp),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color(0xFF89B4FA))
                ) {
                    Text(
                        "$emoji ${stringResource(labelRes)}",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // ── 会话面板 1：MC 终端（仅服务器启动后显示） ──
        if (serverState.isRunning) {
            TerminalPanel(
                title = stringResource(R.string.mc_terminal),
                statusColor = Color(0xFFA6E3A1),
                expanded = mcExpanded,
                onToggle = { mcExpanded = !mcExpanded },
                modifier = if (mcExpanded) Modifier.weight(1f) else Modifier
            ) {
        // 日志列表
        val listState = rememberLazyListState()
        // 进入页面时定位到最新日志（底部），而非顶部
        LaunchedEffect(Unit) {
            if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1)
        }
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1E1E2E))
        ) {
            if (lines.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.s561), color = Color(0xFF8888AA), fontSize = 12.sp)
                }
            } else {
                // 自动滚动：仅当用户已位于底部附近时才跟随（瞬时，不打扰上翻）
                val atBottom by remember {
                    derivedStateOf {
                        val info = listState.layoutInfo
                        val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                        lastVisible >= info.totalItemsCount - 3
                    }
                }
                androidx.compose.runtime.LaunchedEffect(lines.size, atBottom) {
                    if (lines.isNotEmpty() && atBottom) listState.scrollToItem(lines.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    itemsIndexed(lines, key = { index, _ -> index }) { _, line ->
                        val color = remember(line) {
                            when {
                                line.contains("[ERROR]") || line.contains("ERROR") || line.contains("FATAL") -> Color(0xFFF38BA8)
                                line.contains("[WARN]") || line.contains("WARN") -> Color(0xFFF9E2AF)
                                line.contains("[tunnel]") -> Color(0xFF89B4FA)
                                line.contains("[crash]") -> Color(0xFFFAB387)
                                line.contains("[bootstrap]") -> Color(0xFFA6E3A1)
                                else -> Color(0xFFCDD6F4)
                            }
                        }
                        Text(
                            if (logLocalized && isChineseLocale) localizeLogLine(line) else line,
                            color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace
                        )
                    }
                }
            }
        }

        // 控制台输入框（受控，非交互式 UI 入口）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Card)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text(stringResource(R.string.s562), fontSize = 12.sp) },
                singleLine = true,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.height(0.dp))
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    vm.sendCommand(input)
                    input = ""
                }
            }) {
                Icon(Icons.Outlined.Send, contentDescription = stringResource(R.string.s563), tint = Indigo)
            }
        }
            }  // MC 终端面板结束

            // ── 会话面板 2：Termux 终端（始终显示） ──
            TerminalPanel(
                title = stringResource(R.string.termux_terminal),
                statusColor = Color(0xFF89B4FA),
                expanded = termuxExpanded,
                onToggle = { termuxExpanded = !termuxExpanded },
                modifier = if (termuxExpanded) Modifier.weight(1f) else Modifier
            ) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF1E1E2E))
                ) {
                    if (termuxLines.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(stringResource(R.string.termux_placeholder), color = Color(0xFF8888AA), fontSize = 12.sp)
                        }
                    } else {
                        val tState = rememberLazyListState()
                        LaunchedEffect(termuxLines.size) {
                            if (termuxLines.isNotEmpty()) tState.scrollToItem(termuxLines.size - 1)
                        }
                        LazyColumn(
                            state = tState,
                            modifier = Modifier.fillMaxSize().padding(6.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            itemsIndexed(termuxLines) { _, line ->
                                Text(
                                    line,
                                    color = if (line.startsWith("$ ")) Color(0xFFA6E3A1) else Color(0xFFCDD6F4),
                                    fontSize = 10.sp,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Card)
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = termuxInput,
                        onValueChange = { termuxInput = it },
                        placeholder = { Text(stringResource(R.string.termux_hint), fontSize = 12.sp) },
                        singleLine = true,
                        enabled = !termuxBusy,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(Modifier.height(0.dp))
                    IconButton(
                        onClick = {
                            if (termuxInput.isNotBlank() && !termuxBusy) {
                                vm.execTermuxCommand(termuxInput)
                                termuxInput = ""
                            }
                        },
                        enabled = !termuxBusy
                    ) {
                        if (termuxBusy) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = Indigo)
                        } else {
                            Icon(Icons.Outlined.Send, contentDescription = stringResource(R.string.s563), tint = Indigo)
                        }
                    }
                }
            }
        }
    }

    // 日志设置弹窗（汉化开关）
    if (showLogSettings) {
        AlertDialog(
            onDismissRequest = { showLogSettings = false },
            title = { Text(stringResource(R.string.ui_log_localize), fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.ui_log_localize), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text(stringResource(R.string.ui_log_localize_hint), color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                    }
                    Switch(
                        checked = logLocalized,
                        onCheckedChange = {
                            logLocalized = it
                            logPrefs.edit().putBoolean("localize", it).apply()
                        }
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showLogSettings = false }) {
                    Text(stringResource(R.string.s620))
                }
            }
        )
    }
}

/** 日志行部分汉化：进服/离服/在线人数/世界保存/启动完成（仅显示层，不改原始数据） */
private fun localizeLogLine(line: String): String {
    return line
        .replace(Regex("(\\w+) joined the game"), "玩家 $1 加入了游戏")
        .replace(Regex("(\\w+) left the game"), "玩家 $1 离开了游戏")
        .replace(Regex("There are (\\d+) of a max of (\\d+) players online"), "当前在线 $1/$2 人")
        .replace("Saving worlds", "正在保存世界")
        .replace("Saved the game", "世界已保存")
        .replace("Saved the world", "世界已保存")
        .replace(Regex("Done \\(([\\d.]+)s\\)!.*"), "服务器启动完成（$1s）")
}

/** 可折叠会话面板（标题栏：状态点 + 名称 + 展开/收起箭头） */
@Composable
private fun TerminalPanel(
    title: String,
    statusColor: Color,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Card)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(statusColor))
            Spacer(Modifier.width(6.dp))
            Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.weight(1f))
            Icon(
                if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
                contentDescription = null,
                tint = Muted
            )
        }
        if (expanded) {
            // 占满父面板剩余高度，保证内部 content 的 weight(1f) 生效（否则日志区高度为 0 → 空白）
            Column(modifier = Modifier.fillMaxWidth().weight(1f)) { content() }
        }
    }
}
