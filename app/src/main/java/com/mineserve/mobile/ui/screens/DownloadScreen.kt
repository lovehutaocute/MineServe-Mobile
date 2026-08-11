package com.mineserve.mobile.ui.screens

import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import com.mineserve.mobile.R

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.ui.HeaderBlock
import com.mineserve.mobile.ui.McCard
import com.mineserve.mobile.ui.McViewModel
import com.mineserve.mobile.ui.SegPill
import com.mineserve.mobile.ui.theme.Coral
import com.mineserve.mobile.ui.theme.Indigo
import com.mineserve.mobile.ui.theme.IndigoSoft
import com.mineserve.mobile.ui.theme.Mint
import com.mineserve.mobile.ui.theme.Muted
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * 下载页：选择服务端核心 + 选择游戏版本 + 下载服务端 JAR
 * 核心类型和版本均从官方 API 动态获取，确保下载源正确
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DownloadScreen(vm: McViewModel, onShowDownloadHelp: () -> Unit = {}) {
    val context = LocalContext.current
    val config by vm.config.collectAsState()
    val isBootstrapped by vm.isBootstrapped.collectAsState()
    val availableVersions by vm.availableVersions.collectAsState()
    val isLoadingVersions by vm.isLoadingVersions.collectAsState()
    val isDownloadingCore by vm.isDownloadingCore.collectAsState()
    val downloadProgress by vm.downloadProgress.collectAsState()
    val consoleLines by vm.consoleLines.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    // 自定义版本输入框
    var customVersion by remember { mutableStateOf("") }
    // 自定义核心名称
    var customCoreName by remember { mutableStateOf("") }

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
        snackbarHost = { SnackbarHost(snackbarHostState) },
        // 嵌套在外层 McApp Scaffold 内：insets 已由外层消费，这里不再重复应用，避免顶部空白/白色遮挡
        contentWindowInsets = WindowInsets(0.dp)
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
        ) {
            HeaderBlock(eyebrow = stringResource(R.string.eyebrow_download), title = stringResource(R.string.s450))

            // 当前下载状态
            McCard(title = stringResource(R.string.s451)) {
                val installed = config.installedCores
                if (installed.isEmpty()) {
                    Text(
                        stringResource(R.string.s452),
                        color = Muted,
                        fontSize = 13.sp
                    )
                } else {
                    installed.forEach { core ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        core.name,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    if (core.name == config.activeCoreName) {
                                        Spacer(Modifier.size(6.dp))
                                        Text(
                                            stringResource(R.string.s453),
                                            color = Mint,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Text(
                                    "${core.core.displayName} ${core.version}  ·  文件夹: ${core.dirName}",
                                    color = Muted,
                                    fontSize = 11.sp
                                )
                            }
                            OutlinedButton(
                                onClick = {
                                    vm.setActiveCore(core.name)
                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.s376, core.name)) }
                                },
                                enabled = core.name != config.activeCoreName,
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Indigo),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(stringResource(R.string.s455), color = Indigo, fontSize = 11.sp) }
                            Spacer(Modifier.size(6.dp))
                            OutlinedButton(
                                onClick = { vm.deleteCore(core.name) },
                                shape = RoundedCornerShape(12.dp),
                                border = BorderStroke(1.dp, Coral),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(stringResource(R.string.s339), color = Coral, fontSize = 11.sp) }
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.s456),
                    color = Muted,
                    fontSize = 11.sp
                )
            }

            // 选择核心类型
            McCard(title = stringResource(R.string.s457)) {
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
                        ServerCore.Purpur -> "Purpur：Paper 分支，额外提供更多原版特性开关，插件兼容"
                        ServerCore.Fabric -> "Fabric：轻量级模组加载器，支持最新版本快速更新"
                        ServerCore.Forge -> "Forge：老牌模组加载器，生态丰富，适合大型整合包"
                        ServerCore.NeoForge -> "NeoForge：Forge 继任者，模组生态活跃（下载后自动执行 installer）"
                        ServerCore.Quilt -> "Quilt：Fabric 分支模组加载器，注重社区驱动（下载后自动执行 installer）"
                        ServerCore.Vanilla -> "Vanilla：Minecraft 官方原版服务端"
                        ServerCore.Velocity -> "Velocity：高性能代理端，可连接多个后端服务器（不支持插件/模组）"
                        ServerCore.BungeeCord -> "BungeeCord：经典代理端，支持子服务器间切换（不支持插件/模组）"
                        ServerCore.PowerNukkitX -> "PowerNukkitX：基岩版 Bedrock 服务端，使用 ARM64 Java 运行；默认 UDP 端口 19132，需 Java 17 或 Java 25"
                        ServerCore.Unknown -> "未知核心：核心类型无法自动识别（通常来自还原备份），可在设置中修改"
                    },
                    color = Muted,
                    fontSize = 11.sp
                )
                // 当前核心插件/模组支持彩色提示
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(
                        if (config.selectedCore.supportsPlugins) "✓ 支持插件" else "✗ 不支持插件",
                        color = if (config.selectedCore.supportsPlugins) Mint else Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        if (config.selectedCore.supportsMods) "✓ 支持模组" else "✗ 不支持模组",
                        color = if (config.selectedCore.supportsMods) Mint else Coral,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // 选择游戏版本
            McCard(title = stringResource(R.string.s471)) {
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
                        Text(stringResource(R.string.s472), color = Muted, fontSize = 12.sp)
                    }
                } else if (availableVersions.isEmpty()) {
                    Text(stringResource(R.string.s473), color = Muted, fontSize = 12.sp)
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
                Text(stringResource(R.string.s474), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = customVersion,
                        onValueChange = { customVersion = it },
                        placeholder = { Text(stringResource(R.string.s475), fontSize = 12.sp) },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    Button(
                        onClick = {
                            if (customVersion.isNotBlank()) {
                                vm.setMcVersion(customVersion.trim())
                                scope.launch {
                                    snackbarHostState.showSnackbar(context.getString(R.string.s476, customVersion.trim()))
                                }
                            }
                        },
                        enabled = customVersion.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Indigo),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(stringResource(R.string.s477), color = Color.White, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(R.string.s478, config.mcVersion),
                    color = Indigo,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold
                )
                InstallerJavaNotice(
                    core = config.selectedCore,
                    minecraftVersion = config.mcVersion
                )
            }

            // 下载服务端
            McCard(title = stringResource(R.string.s479)) {
                // 下载提示行（卡片内顶部，含实时速度）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isDownloadingCore) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Indigo,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            "${downloadProgress.speedText} · ${downloadProgress.percent}%  ",
                            color = Indigo,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(14.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(Indigo.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("↓", color = Indigo, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.size(8.dp))
                        Text(
                            stringResource(R.string.s480),
                            color = Muted,
                            fontSize = 11.sp,
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        stringResource(R.string.s403),
                        color = Indigo,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.clickable { onShowDownloadHelp() }
                    )
                }
                Spacer(Modifier.height(8.dp))

                if (!isBootstrapped) {
                    Text(
                        "Termux 环境初始化中，请等待完成后再下载...",
                        color = Coral,
                        fontSize = 12.sp
                    )
                } else {
                    Text(
                        stringResource(R.string.s482, config.selectedCore.displayName, config.mcVersion),
                        color = Muted,
                        fontSize = 11.sp
                    )
                    Spacer(Modifier.height(10.dp))
                    // 自定义名称输入
                    Text(stringResource(R.string.s483), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    OutlinedTextField(
                        value = customCoreName,
                        onValueChange = { customCoreName = it },
                        placeholder = { Text(stringResource(R.string.s484), fontSize = 12.sp) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.downloadCore(customCoreName) },
                        enabled = !isDownloadingCore && customCoreName.isNotBlank(),
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
                            if (isDownloadingCore) stringResource(R.string.s485) else stringResource(R.string.s486),
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold
                        )
                    }

                    // 下载日志预览
                    if (isDownloadingCore || consoleLines.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(stringResource(R.string.s487), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
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

private data class InstallerJavaRequirement(
    val recommendedJava: JavaVersion?,
    val exactLegacyForgeRequirement: Boolean
)

private fun installerJavaRequirement(
    core: ServerCore,
    minecraftVersion: String
): InstallerJavaRequirement? {
    if (!core.needsInstaller) return null

    val versionParts = minecraftVersion
        .trim()
        .removePrefix("v")
        .split('.')
        .mapNotNull { it.toIntOrNull() }
    val major = versionParts.getOrNull(0)
    val minor = versionParts.getOrNull(1)
    val patch = versionParts.getOrNull(2)
    if (major != 1 || minor == null) return InstallerJavaRequirement(null, false)

    val java = when {
        minor <= 16 -> JavaVersion.Java8
        minor > 20 || (minor == 20 && (patch ?: 0) >= 5) -> JavaVersion.Java25
        else -> JavaVersion.Java17
    }
    return InstallerJavaRequirement(
        recommendedJava = java,
        exactLegacyForgeRequirement = core == ServerCore.Forge && minecraftVersion == "1.12.2"
    )
}

@Composable
private fun InstallerJavaNotice(core: ServerCore, minecraftVersion: String) {
    val requirement = installerJavaRequirement(core, minecraftVersion) ?: return
    Spacer(Modifier.height(10.dp))
    Text(
        if (requirement.exactLegacyForgeRequirement) {
            "Forge 1.12.2 建议使用 Java 8 安装和启动。"
        } else if (requirement.recommendedJava != null) {
            "${core.displayName} $minecraftVersion 建议准备 ${requirement.recommendedJava.displayName} 运行环境。"
        } else {
            "${core.displayName} 安装需要兼容的 Java 运行环境。"
        },
        color = Coral,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold
    )
    Spacer(Modifier.height(3.dp))
    Text(
        "首次安装会下载并配置依赖，耗时可能较长，请耐心等待。",
        color = Coral,
        fontSize = 11.sp
    )
    Spacer(Modifier.height(3.dp))
    Text(
        "注意：此处提示的 Java 版本有可能不对，请以服务器核心官方文档和安装器提示为准。",
        color = Coral,
        fontSize = 11.sp
    )
}


