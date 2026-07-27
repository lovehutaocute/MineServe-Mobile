package com.mcserver.manager.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.launch

@Composable
fun BackupScreen(vm: McViewModel) {
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Backup & Restore", title = "备份与还原")

            McCard(title = "世界存档备份") {
                Text(
                    "本地路径：/home/server/world/",
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            vm.sendCommand("save-on")
                            scope.launch {
                                snackbarHostState.showSnackbar("已发送保存指令")
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("立即保存", color = Color.White) }

                    OutlinedButton(
                        onClick = {
                            vm.sendCommand("save-off")
                            scope.launch {
                                snackbarHostState.showSnackbar("已发送暂停保存指令")
                            }
                        },
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("暂停保存", color = Indigo) }
                }
            }

            McCard(title = "快照管理") {
                Text(
                    "将 world/ 目录打包为 zip 备份，便于回滚到任意时间点。",
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        vm.sendCommand("say backup starting...")
                        scope.launch {
                            snackbarHostState.showSnackbar("备份指令已发送，请查看日志")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("创建快照", color = Color.White, fontWeight = FontWeight.SemiBold) }
            }

            McCard(title = "历史快照") {
                Text(
                    "选择一个快照文件后，APP 将停止服务、覆盖 world/、再重启服务。",
                    color = Muted,
                    fontSize = 11.sp
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    "暂无历史快照",
                    color = Muted,
                    fontSize = 12.sp
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
