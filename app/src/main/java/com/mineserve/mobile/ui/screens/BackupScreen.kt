package com.mineserve.mobile.ui.screens

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.res.stringResource
import com.mineserve.mobile.R

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.FileDownload
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.EmptyHint
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(vm: McViewModel, onBack: () -> Unit = {}) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val snapshots by vm.snapshots.collectAsState()
    var isSnapshotting by remember { mutableStateOf(false) }
    var showDeleteWorldConfirm by remember { mutableStateOf(false) }
    // 外部备份权限（每次进入页面检查）
    val hasStoragePerm = com.mineserve.mobile.server.ExternalBackupStore.hasPermission(context)

    LaunchedEffect(Unit) { vm.loadSnapshots() }
    // 收集错误/操作消息，修复"点击无响应"（此前未收集，还原/错误无任何反馈）
    LaunchedEffect(Unit) { vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) } }

    // 快照导出到本地（SAF 自定义路径）
    var exportTarget by remember { mutableStateOf<String?>(null) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri: Uri? ->
        uri?.let { target -> exportTarget?.let { vm.exportSnapshotToUri(it, target) } }
        exportTarget = null
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_backup), title = stringResource(R.string.s324))

            // 外部备份权限引导（未授权时显示）
            if (!hasStoragePerm) {
                McCard(title = stringResource(R.string.backup_ext_perm_title)) {
                    Text(stringResource(R.string.backup_ext_perm_content), color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = {
                            try {
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    android.net.Uri.parse("package:" + context.packageName)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            } catch (e: Exception) {
                                // 部分 ROM 无此 Activity，回退到应用详情设置
                                val intent = android.content.Intent(
                                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    android.net.Uri.parse("package:" + context.packageName)
                                ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                context.startActivity(intent)
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text(stringResource(R.string.backup_ext_perm_action), color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // 快照操作
            McCard(title = stringResource(R.string.s325)) {
                Text(stringResource(R.string.s326), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.sendCommand("save-all")
                            scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s327)) }
                        },
                        enabled = isBootstrapped,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.s328), color = Color.White, fontSize = 12.sp) }
                    Button(
                        onClick = {
                            isSnapshotting = true
                            scope.launch {
                                val path = vm.createSnapshot()
                                isSnapshotting = false
                                val msg = if (path != null) context.getString(R.string.s329, path) else context.getString(R.string.s330)
                                snackbarHostState.showSnackbar(msg)
                                vm.loadSnapshots()
                            }
                        },
                        enabled = isBootstrapped && !isSnapshotting,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)
                    ) {
                        if (isSnapshotting) CircularProgressIndicator(Modifier.size(16.dp), Color.White, strokeWidth = 2.dp)
                        else Text(stringResource(R.string.s331), color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // 外部备份（保存到 /storage/emulated/0/世界与服务器的备份/，卸载保留）
            McCard(title = stringResource(R.string.backup_ext_title)) {
                Text(stringResource(R.string.backup_ext_hint), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { vm.backupWorldToExternal() },
                        enabled = isBootstrapped && hasStoragePerm,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.backup_ext_world), color = Color.White, fontSize = 12.sp) }
                    Button(
                        onClick = { vm.backupServerToExternal() },
                        enabled = isBootstrapped && hasStoragePerm,
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)
                    ) { Text(stringResource(R.string.backup_ext_server), color = Color.White, fontSize = 12.sp) }
                }
            }

            // 世界管理（删除世界文件夹）
            McCard(title = stringResource(R.string.ui_world_delete)) {                Text(stringResource(R.string.ui_world_delete_hint), color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = { showDeleteWorldConfirm = true },
                    enabled = isBootstrapped,
                    colors = ButtonDefaults.buttonColors(containerColor = Coral),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(stringResource(R.string.ui_world_delete), color = Color.White, fontSize = 12.sp)
                }
            }

            // 备份列表
            McCard(
                title = stringResource(R.string.s332, snapshots.size),
                trailing = {
                    IconButton(onClick = { vm.loadSnapshots() }) {
                        Icon(Icons.Outlined.Refresh, stringResource(R.string.s333), tint = Indigo, modifier = Modifier.size(18.dp))
                    }
                }
            ) {
                if (snapshots.isEmpty()) {
                    EmptyHint(icon = Icons.Outlined.Archive, text = stringResource(R.string.s334))
                } else {
                    snapshots.forEachIndexed { i, snap ->
                        if (i > 0) Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snap.name, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text("${snap.sizeText} · ${snap.createdText}", color = Muted, fontSize = 10.sp)
                            }
                            IconButton(
                                onClick = {
                                    exportTarget = snap.name
                                    exportLauncher.launch(snap.name)
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Outlined.FileDownload, stringResource(R.string.s335), tint = Indigo, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    vm.restoreSnapshot(snap.name)
                                    snackbarHostState.showSnackbar(context.getString(R.string.s336))
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Restore, stringResource(R.string.s337), tint = Indigo, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    vm.deleteSnapshot(snap.name)
                                    vm.loadSnapshots()
                                    snackbarHostState.showSnackbar(context.getString(R.string.s338, snap.name))
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Delete, stringResource(R.string.s339), tint = Coral, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }

    // 删除世界文件夹二次确认
    if (showDeleteWorldConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteWorldConfirm = false },
            title = { Text(stringResource(R.string.ui_world_delete), fontSize = 15.sp, fontWeight = FontWeight.Bold) },
            text = { Text(stringResource(R.string.ui_world_delete_hint), fontSize = 13.sp) },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteWorldConfirm = false
                    vm.deleteWorldDirs()
                }) { Text(stringResource(R.string.s339), color = Coral) }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteWorldConfirm = false }) {
                    Text(stringResource(R.string.s620))
                }
            }
        )
    }
}
