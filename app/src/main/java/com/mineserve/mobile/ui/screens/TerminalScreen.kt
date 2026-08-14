package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.TerminalLogTranslator
import com.mineserve.mobile.ui.TerminalSessionType

private data class QuickMcCommand(val label: String, val command: String)

private val quickMcCommands = listOf(
    QuickMcCommand("晴天", "weather clear"),
    QuickMcCommand("下雨", "weather rain"),
    QuickMcCommand("白天", "time set day"),
    QuickMcCommand("夜晚", "time set night"),
    QuickMcCommand("难度普通", "difficulty normal"),
    QuickMcCommand("创造模式", "gamemode creative @p"),
    QuickMcCommand("杀死实体", "kill @e[type=!player]"),
    QuickMcCommand("玩家列表", "list"),
    QuickMcCommand("保存世界", "save-all"),
    QuickMcCommand("停止服务端", "stop")
)

@Composable
fun TerminalScreen(vm: McViewModel) {
    val sessions by vm.terminalSessions.collectAsState()
    val activeId by vm.activeTerminalSessionId.collectAsState()
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.first()
    val mcLines by vm.consoleLines.collectAsState()
    val context = LocalContext.current
    var input by remember(activeId) { mutableStateOf("") }
    var translateLogs by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val rawLines = if (active.type == TerminalSessionType.Minecraft) mcLines else active.lines
    val visibleLines = remember(rawLines, translateLogs, active.type) {
        if (translateLogs && active.type == TerminalSessionType.Minecraft) rawLines.map(TerminalLogTranslator::translate) else rawLines
    }
    val isNearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 || (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 2
        }
    }
    LaunchedEffect(visibleLines.size) { if (visibleLines.isNotEmpty() && isNearBottom) listState.scrollToItem(visibleLines.lastIndex) }

    Column(Modifier.fillMaxSize().background(Color(0xFF121419)).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("终端", color = Color(0xFFE8E8E8), fontSize = 22.sp, fontFamily = FontFamily.Monospace)
            Row {
                IconButton(onClick = { showSettings = true }) { Icon(Icons.Outlined.Settings, "终端设置", tint = Color.White) }
                IconButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(active.name, rawLines.joinToString("\n")))
                    android.widget.Toast.makeText(context, "已复制当前日志", android.widget.Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Outlined.ContentCopy, "复制当前日志", tint = Color.White) }
                IconButton(onClick = vm::createTerminalSession) { Icon(Icons.Outlined.Add, "新建会话", tint = Color.White) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sessions.forEach { session ->
                Row(Modifier.background(if (session.id == activeId) Color(0xFF2B313C) else Color(0xFF20242C), RoundedCornerShape(8.dp)).padding(start = 12.dp), horizontalArrangement = Arrangement.Center) {
                    Text(session.name, color = Color(0xFFE8E8E8), fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp).clickable { vm.selectTerminalSession(session.id) })
                    if (session.type == TerminalSessionType.Termux) IconButton(onClick = { vm.closeTerminalSession(session.id) }) { Icon(Icons.Outlined.Close, "关闭", tint = Color(0xFF999999)) } else Spacer(Modifier.width(8.dp))
                }
            }
        }
        if (active.type == TerminalSessionType.Minecraft) {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickMcCommands.forEach { item -> AssistChip(onClick = { input = item.command }, label = { Text(item.label, fontSize = 11.sp) }) }
            }
        }
        SelectionContainer {
            LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(visibleLines) { line ->
                    val color = when {
                        line.startsWith("$ ") -> Color(0xFF57E357)
                        line.startsWith("错误：") || line.contains("[ERROR]", true) || line.contains("[FATAL]", true) -> Color(0xFFFF7B72)
                        line.startsWith("警告：") || line.contains("[WARN]", true) -> Color(0xFFE3B341)
                        else -> Color(0xFFE8E8E8)
                    }
                    Text(line, color = color, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp, 8.dp, 16.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (active.type == TerminalSessionType.Minecraft) ">" else "$", color = Color(0xFF57E357), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 14.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, modifier = Modifier.weight(1f), enabled = !active.busy)
            IconButton(onClick = {
                if (input.isNotBlank()) {
                    if (active.type == TerminalSessionType.Termux) vm.executeTerminalCommand(active.id, input) else vm.sendCommand(input)
                    input = ""
                }
            }, enabled = !active.busy) { Icon(Icons.Outlined.Send, "发送", tint = Color.White) }
        }
    }
    if (showSettings) AlertDialog(
        onDismissRequest = { showSettings = false },
        title = { Text("终端设置") },
        text = { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("关键日志汉化\n仅改变终端显示，保留原始日志") ; Switch(checked = translateLogs, onCheckedChange = { translateLogs = it }) } },
        confirmButton = { TextButton(onClick = { showSettings = false }) { Text("完成") } }
    )
}
