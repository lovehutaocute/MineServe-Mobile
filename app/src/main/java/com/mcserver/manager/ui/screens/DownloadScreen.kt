package com.mcserver.manager.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
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
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mcserver.manager.data.ServerCore
import com.mcserver.manager.ui.HeaderBlock
import com.mcserver.manager.ui.McCard
import com.mcserver.manager.ui.McViewModel
import com.mcserver.manager.ui.SegPill
import com.mcserver.manager.ui.theme.Coral
import com.mcserver.manager.ui.theme.Indigo
import com.mcserver.manager.ui.theme.IndigoSoft
import com.mcserver.manager.ui.theme.Mint
import com.mcserver.manager.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载页：选择服务端核心 + 选择游戏版本 + 下载服务端 JAR
 * 核心类型和版本均从官方 API 动态获取，确保下载源正确
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(vm: McViewModel) {
    val config by vm.config.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val availableVersions by vm.availableVersions.collectAsState()
    val isLoadingVersions by vm.isLoadingVersions.collectAsState()
    val isDownloadingCore by vm.isDownloadingCore.collectAsState()
    val consoleLines by vm.consoleLines.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 自定义版本输入框
    var customVersion by remember { mutableStateOf("") }

    // 切换核心时自动加载版本列表
    LaunchedEffect(config.selectedCore) {
        vm.loadVersions(config.selectedCore)
    }

    // 收集消息
    LaunchedEffect(Unit) {
        vm.messageFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }
    LaunchedEffect(Unit) {
        vm.errorFlow.collectLatest { snackbarHostState.showSnackbar(it) }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = "Core Download", title = "服务端核心下载")

            // 当前下载状态
            McCard(title = "当前核心状态") {
                val downloaded = config.downloadedCore != null
                if (downloaded) {
                    Text(
                        "已下载：${config.downloadedCore?.displayName} ${config.downloadedVersion}",
                        color = Mint,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                } else {
                    Text(
                        "尚未下载任何服务端核心",
                        color = Muted,
                        fontSize = 13.sp
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    "下载路径：/home/server/server.jar",
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            // 选择核心类型
            McCard(title = "1. 选择核心类型") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ServerCore.values().forEach { core ->
                        SegPill(
                            text = core.displayName,
                            selected = config.selectedCore == core,
                            onClick = { vm.selectCore(core) }
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    when (config.selectedCore) {
                        ServerCore.Paper -> "PaperMC：高性能优化核心，兼容大部分插件，推荐用于生产环境"
                        ServerCore.Fabric -> "Fabric：轻量级模组加载器，支持最新版本快速更新"
                        ServerCore.Forge -> "Forge：老牌模组加载器，生态丰富，适合大型整合包"
                        ServerCore.Vanilla -> "Vanilla：Minecraft 官方原版服务端"
                    },
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            // 选择游戏版本
            McCard(title = "2. 选择游戏版本") {
                if (isLoadingVersions) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            color = Indigo,
                            strokeWidth = 2.dp
                        )
                        Text("正在从官方 API 获取版本列表...", color = Muted, fontSize = 12.sp)
                    }
                } else if (availableVersions.isEmpty()) {
                    Text("暂无可用版本，请使用下方自定义版本输入", color = Muted, fontSize = 12.sp)
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        availableVersions.take(20).forEach { ver ->
                            SegPill(
                                text = ver,
                                selected = config.mcVersion == ver,
                                onClick = { vm.setMcVersion(ver) }
                            )
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))
                Text("自定义版本", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customVersion,
                        onValueChange = { customVersion = it },
                        placeholder = { Text("如 1.20.4", fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (customVersion.isNotBlank()) {
                                vm.setMcVersion(customVersion.trim())
                                scope.launch {
                                    snackbarHostState.showSnackbar("已设置版本为 ${customVersion.trim()}")
                                }
                            }
                        },
                        enabled = customVersion.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text("确定", color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    "当前选择：${config.mcVersion}",
                    color = Indigo,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }

            // 下载服务端
            McCard(title = "3. 下载服务端核心") {
                if (!isBootstrapped) {
                    Text(
                        "Termux 环境初始化中，请等待完成后再下载...",
                        color = Coral,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        "将下载 ${config.selectedCore.displayName} ${config.mcVersion} 到 /home/server/server.jar",
                        color = Muted,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.downloadCore("/home/server/server.jar") },
                        enabled = !isDownloadingCore,
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isDownloadingCore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(Modifier.size(8.dp))
                        }
                        Text(
                            if (isDownloadingCore) "下载中..." else "开始下载",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 下载日志预览
                    if (isDownloadingCore || consoleLines.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text("下载日志：", color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        consoleLines.takeLast(8).forEach { line ->
                            Text(
                                line,
                                color = Muted.copy(alpha = 0.7f),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
