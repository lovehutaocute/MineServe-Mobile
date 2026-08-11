package com.mineserve.mobile.data

import kotlinx.serialization.Serializable

/**
 * 服务端核心类型：Paper / Purpur / Fabric / Forge / NeoForge / Quilt / Vanilla / Velocity / BungeeCord
 */
@Serializable
enum class ServerCore(val displayName: String) {
    Paper("Paper"),
    Purpur("Purpur"),
    Fabric("Fabric"),
    Forge("Forge"),
    NeoForge("NeoForge"),
    Quilt("Quilt"),
    Vanilla("Vanilla"),
    Velocity("Velocity"),
    BungeeCord("BungeeCord"),
    Unknown("未知");

    /** 是否支持 Bukkit/Spigot/Paper 插件体系 */
    val supportsPlugins: Boolean get() = this == Paper || this == Purpur

    /** 是否支持 Fabric/Forge 模组体系 */
    val supportsMods: Boolean get() = this == Fabric || this == Forge || this == NeoForge || this == Quilt

    /** 是否支持桥接兼容层（插件↔模组互转，如 CardBoard 等，需用户自行安装） */
    val supportsBridge: Boolean get() = this == Fabric || this == Forge || this == NeoForge || this == Quilt

    /** 是否需 installer 流程（下载 installer.jar 后需执行安装命令生成启动环境） */
    val needsInstaller: Boolean get() = this == Forge || this == NeoForge || this == Quilt
}

/**
 * 内网穿透方式
 * - Frp: 自建 frp 服务器，最灵活，功能最全
 * - Bore: 自建 bore 服务器，协议极简，纯 Kotlin 实现无需 Termux
 */
@Serializable
enum class TunnelType(val displayName: String, val description: String) {
    Frp("frp", "自建服务器，功能最全，支持自定义端口"),
    Bore("bore", "自建服务器，协议最简，纯手机端运行无需下载二进制")
}

/**
 * 隧道运行状态枚举（与 TunnelManager 解耦，可直接用于 UI）
 */
enum class TunnelStatus {
    Idle,       // 未启动
    Starting,   // 正在启动
    Running,    // 运行中
    Failed,     // 失败
    Stopped     // 已手动停止
}

/**
 * 隧道运行时状态快照（由 TunnelManager 推送至 StateFlow）
 */
data class TunnelState(
    val isRunning: Boolean = false,
    val publicUrl: String = "",
    val status: TunnelStatus = TunnelStatus.Idle,
    val errorMessage: String = "",
    val activeType: TunnelType? = null
)

/**
 * Termux bootstrap rootfs 下载源
 * - Auto: 按顺序尝试所有镜像（镜像优先）
 * - 其余: 指定镜像优先，其余回退
 */
@Serializable
enum class DownloadMirror(val displayName: String, val baseUrl: String) {
    Auto("自动（镜像优先）", ""),
    GitHub("GitHub 直连", "https://github.com/termux/termux-packages/releases/download"),
    GhProxy("gh-proxy.com", "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download"),
    MirrorGhproxy("mirror.ghproxy.com", "https://mirror.ghproxy.com/https://github.com/termux/termux-packages/releases/download"),
    GhproxyNet("ghproxy.net", "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download"),
    Moeyy("github.moeyy.xyz", "https://github.moeyy.xyz/https://github.com/termux/termux-packages/releases/download"),
    Api99988866("gh.api.99988866.xyz", "https://gh.api.99988866.xyz/https://github.com/termux/termux-packages/releases/download"),
    Ghfast("ghfast.top", "https://ghfast.top/https://github.com/termux/termux-packages/releases/download")
}

/**
 * Termux apt 软件源（JDK/wget/frp 等依赖包下载）
 * 默认使用清华镜像，切换后下次初始化时生效。
 */
@Serializable
enum class AptMirror(val displayName: String, val url: String) {
    Tuna("清华镜像 (TUNA)", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main"),
    Aliyun("阿里云镜像", "https://mirrors.aliyun.com/termux/apt/termux-main"),
    Ustc("中科大镜像 (USTC)", "https://mirrors.ustc.edu.cn/termux/apt/termux-main"),
    Nju("南京大学镜像", "https://mirror.nju.edu.cn/termux/apt/termux-main"),
    Official("Termux 官方", "https://packages.termux.dev/apt/termux-main")
}

/**
 * 依赖安装步骤
 * 注：已去掉 Tmux 步骤（改用 Android 原生 ProcessBuilder 管理进程）
 */
@Serializable
enum class InstallStep(val label: String) {
    Jdk("JDK 17 运行环境"),
    Wget("Wget 下载工具"),
    Frp("Frp 内网穿透"),
    Rclone("Rclone 远程挂载"),
    Proot("PRoot 兼容层")
}

@Serializable
enum class JavaVersion(val displayName: String, val packageName: String, val directoryName: String) {
    Java8("Java 8", "ubuntu-java8", "java-8-ubuntu"),
    Java17("Java 17", "openjdk-17", "java-17-openjdk"),
    Java25("Java 25", "openjdk-25", "java-25-openjdk")
}

@Serializable
enum class StepStatus { Done, Active, Wait }

@Serializable
data class StepState(val step: InstallStep, val status: StepStatus)

/**
 * 一个已安装到本地的服务端核心实例。
 * 每个核心有独立的文件夹（home/servers/{dirName}/），互不干扰。
 */
@Serializable
data class InstalledCore(
    /** 用户自定义名称（显示用），例如 "生存服-1.20.4" */
    val name: String,
    /** 核心类型 */
    val core: ServerCore,
    /** MC 版本 */
    val version: String,
    /** 文件夹名（从 name 自动生成，sanitized），例如 "sheng-cun-fu-1-20-4" */
    val dirName: String
)

/**
 * 用户配置（持久化到 DataStore）
 */
@Serializable
data class McConfig(
    /** 下载页当前选择的核心类型（临时 UI 状态，不持久化） */
    val selectedCore: ServerCore = ServerCore.Paper,
    /** 下载页当前选择的 MC 版本（临时 UI 状态） */
    val mcVersion: String = "1.20.4",
    val coreSubDescription: String = "性能优化版，兼容大部分插件",
    val localPort: Int = 25565,
    val customDomain: String = "myworld.mcserver.top",
    val tunnelType: TunnelType = TunnelType.Frp,
    /** frp: 完整 frpc.toml 配置文本（粘贴或文件导入） */
    val frpConfigText: String = "",
    /** bore: 服务端地址 (serverAddr:port) */
    val boreServerAddr: String = "",
    val maxHeapMb: Int = 1024,            // -Xmx JVM 堆上限，按设备 RAM 给推荐值
    val autoRestartOnCrash: Boolean = false, // 默认关闭省电，避免误触发
    val selectedJavaVersion: JavaVersion = JavaVersion.Java17,
    /** 用户手动将 Java 管理卡片固定到概览页底部。 */
    val javaCardAtBottom: Boolean = false,
    val keepWifiLock: Boolean = true,
    val keepCpuWakelock: Boolean = true,
    /** 自动备份间隔（分钟），0 表示关闭 */
    val autoBackupIntervalMin: Int = 0,
    /** 保留的最大快照数量，超过则自动删除最旧的 */
    val maxSnapshots: Int = 10,
    /** Termux 环境/依赖下载源，默认镜像优先 */
    val downloadMirror: DownloadMirror = DownloadMirror.Auto,
    /** Termux apt 软件源（JDK/wget/frp 依赖包），默认清华镜像 */
    val aptMirror: AptMirror = AptMirror.Tuna,
    /** 已安装到本地的服务端核心列表（多核心支持） */
    val installedCores: List<InstalledCore> = emptyList(),
    /** 当前选用启动的核心名称（对应 InstalledCore.name），null 表示未选择 */
    val activeCoreName: String? = null,
    /** @deprecated 旧版兼容字段，由 installedCores 替代 */
    val downloadedCore: ServerCore? = null,
    /** @deprecated 旧版兼容字段 */
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
    /** 服务器本次启动完成的时刻（SystemClock.elapsedRealtime 基准），0 表示未启动 */
    val runningSinceMs: Long = 0L,
    val installSteps: List<StepState> = InstallStep.values().map {
        StepState(it, StepStatus.Wait)
    },
    val currentProgress: Int = 0           // 0-100 安装进度
) {
    val isInstallComplete: Boolean get() = installSteps.filter { it.step != InstallStep.Jdk }.isNotEmpty() &&
        installSteps.filter { it.step != InstallStep.Jdk }.all { it.status == StepStatus.Done }
}
