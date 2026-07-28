package com.mcserver.manager.data

import kotlinx.serialization.Serializable

/**
 * 服务端核心类型：Paper / Fabric / Forge / Vanilla
 */
@Serializable
enum class ServerCore(val displayName: String) {
    Paper("Paper"),
    Fabric("Fabric"),
    Forge("Forge"),
    Vanilla("Vanilla")
}

/**
 * 内网穿透方式
 * 注：ngrok 和 CF Tunnel 因未提供二进制安装，已移除，仅保留 frp
 */
@Serializable
enum class TunnelType(val displayName: String) {
    Frp("frp")
}

/**
 * 依赖安装步骤
 */
@Serializable
enum class InstallStep(val label: String) {
    Jdk("JDK 17 运行环境"),
    Tmux("Tmux 后台保活"),
    Wget("Wget 下载工具"),
    Frp("Frp 内网穿透")
}

@Serializable
enum class StepStatus { Done, Active, Wait }

@Serializable
data class StepState(val step: InstallStep, val status: StepStatus)

/**
 * 用户配置（持久化到 DataStore）
 */
@Serializable
data class McConfig(
    val selectedCore: ServerCore = ServerCore.Paper,
    val mcVersion: String = "1.20.4",
    val coreSubDescription: String = "性能优化版，兼容大部分插件",
    val localPort: Int = 25565,
    val customDomain: String = "myworld.mcserver.top",
    val tunnelType: TunnelType = TunnelType.Frp,
    val maxHeapMb: Int = 1024,            // -Xmx JVM 堆上限，按设备 RAM 给推荐值
    val autoRestartOnCrash: Boolean = false, // 默认关闭省电，避免误触发
    val keepWifiLock: Boolean = true,
    val keepCpuWakelock: Boolean = true,
    /** 已下载到本地的核心类型与版本，用于检测切换核心后需要重新下载 */
    val downloadedCore: ServerCore? = null,
    val downloadedVersion: String? = null
)

/**
 * 服务器实时状态（运行时内存态，由 Service 推送）
 */
data class ServerState(
    val isRunning: Boolean = false,
    val tps: Double = 0.0,
    val onlinePlayers: Int = 0,
    val maxPlayers: Int = 20,
    val usedMemoryMb: Long = 0L,
    val maxMemoryMb: Long = 0L,
    val healthPercent: Int = 0,            // 0-100，综合健康度
    val installSteps: List<StepState> = InstallStep.values().map {
        StepState(it, StepStatus.Wait)
    },
    val currentProgress: Int = 0           // 0-100 安装进度
) {
    val isInstallComplete: Boolean get() = installSteps.all { it.status == StepStatus.Done }
}

/**
 * 插件元信息
 */
@Serializable
data class PluginInfo(
    val id: String,
    val name: String,
    val description: String,
    val avatarText: String,            // 缩写占位
    val installed: Boolean = false
)
