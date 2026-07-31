package com.mcserver.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(vm: McViewModel, onBack: () -> Unit = {}) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val snapshots by vm.snapshots.collectAsState()
    var isSnapshotting by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.loadSnapshots() }

    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier.fillMaxSize().padding(paddingValues).verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Backup & Restore", title = "备份与还原")

            // 快照操作
            McCard(title = "创建备份") {
                Text("将 world 目录打包为 zip，保存到 home/snapshots/", color = Muted, fontSize = 11.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.sendCommand("save-all")
                            scope.launch { snackbarHostState.showSnackbar("已发送 save-all") }
                        },
                        enabled = isBootstrapped,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)
                    ) { Text("保存", color = Color.White, fontSize = 12.sp) }
                    Button(
                        onClick = {
                            isSnapshotting = true
                            scope.launch {
                                val path = vm.createSnapshot()
                                isSnapshotting = false
                                val msg = if (path != null) "已创建: $path" else "创建失败"
                                snackbarHostState.showSnackbar(msg)
                                vm.loadSnapshots()
                            }
                        },
                        enabled = isBootstrapped && !isSnapshotting,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(8.dp), modifier = Modifier.weight(1f)
                    ) {
                        if (isSnapshotting) CircularProgressIndicator(Modifier.size(16.dp), Color.White, strokeWidth = 2.dp)
                        else Text("新建备份", color = Color.White, fontSize = 12.sp)
                    }
                }
            }

            // 备份列表
            McCard(title = "备份列表 (${snapshots.size})") {
                if (snapshots.isEmpty()) {
                    Text("暂无备份", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(vertical = 8.dp))
                } else {
                    snapshots.forEachIndexed { i, snap ->
                        if (i > 0) Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(snap.fileName, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                                Text("${snap.sizeText} · ${snap.date}", color = Muted, fontSize = 10.sp)
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    val ok = vm.restoreSnapshot(snap.fileName)
                                    snackbarHostState.showSnackbar(if (ok) "还原成功" else "还原失败")
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Restore, "还原", tint = Indigo, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = {
                                scope.launch {
                                    vm.deleteSnapshot(snap.fileName)
                                    vm.loadSnapshots()
                                    snackbarHostState.showSnackbar("已删除 ${snap.fileName}")
                                }
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Outlined.Delete, "删除", tint = Coral, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
