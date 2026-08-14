package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.mineserve.mobile.ui.McViewModel

@Composable
fun TextFileEditorScreen(vm: McViewModel, file: McViewModel.TextEditorFile, onBack: () -> Unit) {
    var text by remember(file.path, file.content) { mutableStateOf(file.content) }
    var confirmRunningSave by remember { mutableStateOf(false) }
    val state by vm.serverState.collectAsState()
    val context = LocalContext.current
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "返回") }
            Column(Modifier.weight(1f)) { Text(file.name); Text(file.path, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
            IconButton(onClick = { vm.openTextFile(java.io.File(file.path)) }) { Icon(Icons.Outlined.Refresh, "刷新") }
            IconButton(onClick = { (context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager).setPrimaryClip(android.content.ClipData.newPlainText(file.name, text)) }) { Icon(Icons.Outlined.ContentCopy, "复制") }
            IconButton(onClick = { if (state.isRunning) confirmRunningSave = true else vm.saveTextFile(file.path, text) }) { Icon(Icons.Outlined.Save, "保存") }
        }
        OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxSize().padding(12.dp), textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace), minLines = 20)
    }
    if (confirmRunningSave) AlertDialog(onDismissRequest = { confirmRunningSave = false }, title = { Text("服务端正在运行") }, text = { Text("保存配置或日志可能影响服务端运行。仍要保存吗？") }, confirmButton = { TextButton(onClick = { confirmRunningSave = false; vm.saveTextFile(file.path, text) }) { Text("保存") } }, dismissButton = { TextButton(onClick = { confirmRunningSave = false }) { Text("取消") } })
}
