package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.McViewModel.UpdateUiState
import com.mineserve.mobile.ui.theme.Indigo

@Composable
fun UpdateDialog(vm: McViewModel) {
    val visible by vm.updateDialogVisible.collectAsState()
    val state by vm.updateState.collectAsState()
    if (!visible) return
    when (val current = state) {
        UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text("正在检查更新") },
            text = { CircularProgressIndicator() },
            confirmButton = {},
            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text("取消") } }
        )
        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = vm::skipCurrentUpdate,
            title = { Text("发现新版本 ${current.release.tag}") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text("本次更新内容", fontSize = 13.sp)
                    Text(
                        current.release.notes.ifBlank { "暂无更新说明" },
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp).heightIn(max = 180.dp).verticalScroll(rememberScrollState())
                    )
                    Text(
                        "重要提醒：更新应用可能导致服务器核心和世界数据丢失，请先自行备份后再更新。",
                        color = Color(0xFFD32F2F),
                        fontSize = 12.sp,
                        modifier = Modifier.padding(top = 12.dp)
                    )
                }
            },
            confirmButton = { TextButton(onClick = vm::downloadUpdate) { Text("下载更新", color = Indigo) } },
            dismissButton = { TextButton(onClick = vm::skipCurrentUpdate) { Text("本次不更新") } }
        )
        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("正在下载更新") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator({ current.progress.coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                    Text("${(current.progress.coerceIn(0f, 1f) * 100).toInt()}%", modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = {}
        )
        is UpdateUiState.Downloaded -> AlertDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text("下载完成") },
            text = { Text("安装前请确认已备份服务器核心和世界数据。") },
            confirmButton = { TextButton(onClick = { vm.installDownloadedUpdate(current.apkPath) }) { Text("安装更新", color = Indigo) } },
            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text("稍后安装") } }
        )
        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = vm::dismissUpdateDialog,
            title = { Text("更新失败") },
            text = { Text(current.message, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    if (current.release == null) vm.checkForUpdate(manual = true) else vm.downloadUpdate()
                }) { Text(if (current.release == null) "重新检查" else "重新下载", color = Indigo) }
            },
            dismissButton = { TextButton(onClick = vm::dismissUpdateDialog) { Text("关闭") } }
        )
        UpdateUiState.Idle -> Unit
    }
}
