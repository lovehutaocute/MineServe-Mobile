package com.mcserver.manager.ui.screens

import androidx.compose.ui.platform.LocalContext

import androidx.compose.ui.res.stringResource
import com.mcserver.manager.R

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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.Muted
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
            HeaderBlock(eyebrow = "Backup & Restore", title = stringResource(R.string.s324))

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
                    Text(stringResource(R.string.s334), color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
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
}
