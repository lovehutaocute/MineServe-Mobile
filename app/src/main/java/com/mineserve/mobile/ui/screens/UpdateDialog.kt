package com.mineserve.mobile.ui.screens

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.R
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.McViewModel.UpdateUiState
import com.mineserve.mobile.ui.theme.Indigo

/** 软件更新对话框：检查中 / 有新版（含更新说明与下载）/ 下载进度 / 失败提示 */
@Composable
fun UpdateDialog(vm: McViewModel) {
    val visible by vm.updateDialogVisible.collectAsState()
    val state by vm.updateState.collectAsState()
    if (!visible) return

    when (val s = state) {
        is UpdateUiState.Checking -> AlertDialog(
            onDismissRequest = { vm.dismissUpdateDialog() },
            title = { Text(stringResource(R.string.update_checking)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    CircularProgressIndicator()
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { vm.dismissUpdateDialog() }) {
                    Text(stringResource(R.string.s620))
                }
            }
        )

        is UpdateUiState.Available -> AlertDialog(
            onDismissRequest = { vm.dismissUpdateDialog() },
            title = { Text(stringResource(R.string.update_title, s.info.versionName)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.update_notes),
                        fontSize = 13.sp
                    )
                    Text(
                        text = s.info.notes.ifBlank { "-" },
                        fontSize = 13.sp,
                        modifier = Modifier
                            .padding(top = 6.dp)
                            .heightIn(max = 220.dp)
                            .verticalScroll(rememberScrollState())
                    )
                    // 更新前备份存档（防更新后世界/核心丢失）
                    TextButton(
                        onClick = { vm.backupBeforeUpdate() },
                        modifier = Modifier.padding(top = 4.dp)
                    ) {
                        Text(stringResource(R.string.update_backup), color = Indigo, fontSize = 12.sp)
                    }
                }
            },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { vm.openGithubUpdate() }) {
                        Text(stringResource(R.string.update_github))
                    }
                    TextButton(onClick = { vm.downloadUpdate() }) {
                        Text(stringResource(R.string.update_inapp))
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = { vm.dismissUpdateDialog() }) {
                    Text(stringResource(R.string.update_later))
                }
            }
        )

        is UpdateUiState.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text(stringResource(R.string.update_download)) },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { s.progress.coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${(s.progress.coerceIn(0f, 1f) * 100).toInt()}%",
                        fontSize = 13.sp,
                        modifier = Modifier.padding(top = 6.dp)
                    )
                }
            },
            confirmButton = {}
        )

        is UpdateUiState.Downloaded -> {
            // 已触发系统安装器，安装流程交给系统
            vm.dismissUpdateDialog()
        }

        is UpdateUiState.Failed -> AlertDialog(
            onDismissRequest = { vm.dismissUpdateDialog() },
            title = { Text(stringResource(R.string.update_download_failed)) },
            text = { Text(s.message, fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = { vm.dismissUpdateDialog() }) {
                    Text(stringResource(R.string.s620))
                }
            }
        )

        UpdateUiState.Idle -> Unit
    }
}
