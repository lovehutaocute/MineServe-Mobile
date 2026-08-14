package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.TerminalSessionType
import com.mineserve.mobile.ui.theme.Card

@Composable
fun TerminalScreen(vm: McViewModel) {
    val sessions by vm.terminalSessions.collectAsState()
    val activeId by vm.activeTerminalSessionId.collectAsState()
    val active = sessions.firstOrNull { it.id == activeId } ?: sessions.first()
    val mcLines by vm.consoleLines.collectAsState()
    val context = LocalContext.current
    var input by remember { mutableStateOf("") }
    val listState = rememberLazyListState()
    val visibleLines = if (active.type == TerminalSessionType.Minecraft) mcLines else active.lines
    val isNearBottom by remember {
        derivedStateOf {
            val layout = listState.layoutInfo
            layout.totalItemsCount == 0 ||
                (layout.visibleItemsInfo.lastOrNull()?.index ?: -1) >= layout.totalItemsCount - 2
        }
    }
    LaunchedEffect(visibleLines.size) {
        if (visibleLines.isNotEmpty() && isNearBottom) listState.scrollToItem(visibleLines.lastIndex)
    }
    Column(Modifier.fillMaxSize().background(Color(0xFF121419)).imePadding()) {
        Row(Modifier.fillMaxWidth().padding(14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("终端", color = Color(0xFFE8E8E8), fontSize = 22.sp, fontFamily = FontFamily.Monospace)
            Row {
                IconButton(onClick = {
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(active.name, visibleLines.joinToString("\n")))
                    android.widget.Toast.makeText(context, "已复制当前日志", android.widget.Toast.LENGTH_SHORT).show()
                }) { Icon(Icons.Outlined.ContentCopy, "复制当前日志", tint = Color.White) }
                IconButton(onClick = vm::createTerminalSession) { Icon(Icons.Outlined.Add, "新建会话", tint = Color.White) }
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sessions.forEach { session ->
                Row(Modifier.background(if (session.id == activeId) Color(0xFF2B313C) else Color(0xFF20242C), RoundedCornerShape(8.dp)).padding(start = 12.dp), horizontalArrangement = Arrangement.Center) {
                    Text(session.name, color = Color(0xFFE8E8E8), fontSize = 12.sp, modifier = Modifier.padding(vertical = 10.dp).clickable { vm.selectTerminalSession(session.id) })
                    if (session.type == TerminalSessionType.Termux) IconButton(onClick = { vm.closeTerminalSession(session.id) }) { Icon(Icons.Outlined.Close, "关闭", tint = Color(0xFF999999)) }
                    else Spacer(Modifier.width(8.dp))
                }
            }
        }
        Column(Modifier.weight(1f)) {
            SelectionContainer {
                LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(visibleLines) { line -> Text(line, color = if (line.startsWith("$ ")) Color(0xFF57E357) else Color(0xFFE8E8E8), fontSize = 13.sp, fontFamily = FontFamily.Monospace) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(8.dp, 8.dp, 16.dp, 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("$", color = Color(0xFF57E357), fontFamily = FontFamily.Monospace, modifier = Modifier.padding(top = 14.dp))
            OutlinedTextField(value = input, onValueChange = { input = it }, singleLine = true, modifier = Modifier.weight(1f), enabled = !active.busy)
            IconButton(onClick = { if (input.isNotBlank()) { if (active.type == TerminalSessionType.Termux) vm.executeTerminalCommand(active.id, input) else vm.sendCommand(input); input = "" } }, enabled = !active.busy) { Icon(Icons.Outlined.Send, "发送", tint = Color.White) }
        }
    }
}
