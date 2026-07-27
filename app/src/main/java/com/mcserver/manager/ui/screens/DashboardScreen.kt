package com.mcserver.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.data.ServerCore
import com.mcserver.manager.data.ServerState
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.HeroBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.PillButton
import com.mcserver.manager.ui.ProgressTrack
import com.mcserver.manager.ui.SegPill
import com.mcserver.manager.ui.StepRow
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.launch

/**
 * 概览页：完全对齐参考界面 hero + 安装步骤 + 核心选择 + 启停按钮
 * 插件与端口字段拆到对应 Tab，但概览页提供入口按钮（参考界面把插件/端口也放在首页）
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DashboardScreen(vm: McViewModel, onShowLogs: () -> Unit) {
    val config by vm.config.collectAsState()
    val state by vm.serverState.collectAsState()
    val plugins by vm.plugins.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var isInstalling by remember { mutableStateOf(false) }
    var isStarting by remember { mutableStateOf(false) }
    var isStopping by remember { mutableStateOf(false) }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Local Server", title = "云控面板")
            HeroBlock(state = state, coreLabel = "${config.selectedCore.displayName} ${config.mcVersion}")

            // 一键安装依赖
            McCard(
                title = "一键安装依赖",
                trailing = {
                    Text(
                        "查看日志",
                        color = Indigo,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onShowLogs() }
                    )
                }
            ) {
                state.installSteps.forEachIndexed { idx, step ->
                    val tag = when (step.status) {
                        com.mcserver.manager.data.StepStatus.Done -> "已完成"
                        com.mcserver.manager.data.StepStatus.Active -> "进行中"
                        com.mcserver.manager.data.StepStatus.Wait -> "待安装"
                    }
                    StepRow(name = "${idx + 1}. ${step.step.label}", status = step.status, tag = tag)
                }
                Spacer(Modifier.height(10.dp))
                ProgressTrack(percent = state.currentProgress)

                Spacer(Modifier.height(12.dp))
                Button(
                    onClick = {
                        isInstalling = true
                        scope.launch {
                            vm.installDependencies()
                            snackbarHostState.showSnackbar("依赖安装已开始，请查看日志")
                            isInstalling = false
                        }
                    },
                    enabled = !isInstalling,
                    colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isInstalling) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        if (isInstalling) "安装中..." else "开始安装",
                        color = Color.White,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // 选择服务端核心
            McCard(title = "选择服务端核心") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerCore.values().forEach { core ->
                        SegPill(
                            text = core.displayName,
                            selected = config.selectedCore == core,
                            onClick = {
                                vm.selectCore(core)
                                scope.launch {
                                    snackbarHostState.showSnackbar("已选择 ${core.displayName} 核心")
                                }
                            }
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    "当前选择：${config.selectedCore.displayName} · ${config.mcVersion} · ${config.coreSubDescription}",
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            // 启停控制
            McCard(title = "服务控制") {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            isStarting = true
                            scope.launch {
                                vm.startServer()
                                snackbarHostState.showSnackbar("服务器启动指令已发送")
                                isStarting = false
                            }
                        },
                        enabled = !isStarting && !state.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Mint),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isStarting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            if (isStarting) "启动中..." else "启动",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    Button(
                        onClick = {
                            isStopping = true
                            scope.launch {
                                vm.stopServer()
                                snackbarHostState.showSnackbar("服务器停止指令已发送")
                                isStopping = false
                            }
                        },
                        enabled = !isStopping && state.isRunning,
                        colors = ButtonDefaults.buttonColors(containerColor = Coral),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        if (isStopping) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(
                            if (isStopping) "停止中..." else "停止",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    if (state.isRunning) "状态：运行中" else "状态：已停止",
                    color = if (state.isRunning) Mint else Muted,
                    fontSize = 11.sp
                )
            }

            // 插件预览（前 3 个）
            McCard(title = "插件预览") {
                plugins.take(3).forEach { p ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(IndigoSoft),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                p.avatarText,
                                color = Indigo,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(Modifier.size(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(p.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                            Text(p.description, color = Muted, fontSize = 11.sp)
                        }
                        PillButton(
                            text = if (p.installed) "卸载" else "安装",
                            install = !p.installed,
                            onClick = {
                                if (p.installed) {
                                    vm.uninstallPlugin(p)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("${p.name} 卸载指令已发送")
                                    }
                                } else {
                                    vm.installPlugin(p)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("${p.name} 安装指令已发送")
                                    }
                                }
                            }
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
