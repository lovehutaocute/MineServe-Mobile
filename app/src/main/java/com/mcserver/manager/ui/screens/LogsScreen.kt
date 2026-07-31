package com.mcserver.manager.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Send
import androidx.compose.material3.TextButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.Ink
import com.mcserver.manager.ui.theme.Muted

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
    val context = LocalContext.current

    Column(modifier = Modifier.fillMaxSize()) {
        // 顶部带返回按钮的 Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(horizontal = 12.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "返回")
            }
            Column(modifier = Modifier.weight(1f)) {
                Text("LOG STREAM", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                Text("控制台日志", color = Ink, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
            // 一键复制日志按钮
            IconButton(onClick = {
                if (lines.isNotEmpty()) {
                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("MCServer Logs", lines.joinToString("\n")))
                    Toast.makeText(context, "已复制 ${lines.size} 行日志", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(context, "暂无日志可复制", Toast.LENGTH_SHORT).show()
                }
            }) {
                Icon(Icons.Outlined.ContentCopy, contentDescription = "复制日志", tint = Indigo)
            }
        }

        // 快捷指令按钮
        val quickCommands = listOf("/list", "/tps", "/say ", "/kick ", "/help")
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            quickCommands.forEach { cmd ->
                androidx.compose.material3.TextButton(
                    onClick = { input = cmd; vm.sendCommand(cmd); input = "" },
                    modifier = Modifier.defaultMinSize(minWidth = 1.dp, minHeight = 1.dp).height(28.dp),
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                    shape = RoundedCornerShape(4.dp),
                    colors = androidx.compose.material3.ButtonDefaults.textButtonColors(contentColor = Color(0xFF89B4FA))
                ) {
                    Text(cmd, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                }
            }
        }

        // 日志列表
        val listState = rememberLazyListState()
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
                    Text("暂无日志输出\n启动 MC 服务后将在此看到实时控制台流", color = Color(0xFF8888AA), fontSize = 12.sp)
                }
            } else {
                // 自动滚动到底部
                androidx.compose.runtime.LaunchedEffect(lines.size) {
                    if (lines.isNotEmpty()) listState.animateScrollToItem(lines.size - 1)
                }
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(8.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    items(lines) { line ->
                        val color = when {
                            line.contains("[ERROR]") || line.contains("ERROR") || line.contains("FATAL") -> Color(0xFFF38BA8)
                            line.contains("[WARN]") || line.contains("WARN") -> Color(0xFFF9E2AF)
                            line.contains("[tunnel]") -> Color(0xFF89B4FA)
                            line.contains("[crash]") -> Color(0xFFFAB387)
                            line.contains("[bootstrap]") -> Color(0xFFA6E3A1)
                            else -> Color(0xFFCDD6F4)
                        }
                        Text(line, color = color, fontSize = 10.sp, fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }

        // 控制台输入框（受控，非交互式 UI 入口）
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { input = it },
                placeholder = { Text("向控制台发送指令（如 /say hello）", fontSize = 12.sp) },
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
                Icon(Icons.Outlined.Send, contentDescription = "发送", tint = Indigo)
            }
        }
    }
}
