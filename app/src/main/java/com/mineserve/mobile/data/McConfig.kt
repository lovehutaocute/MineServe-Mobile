package com.mineserve.mobile.data

import kotlinx.serialization.Serializable

/**
 * 服务端核心类型：Paper / Purpur / Leaves / Leaf / Spigot / CraftBukkit / Fabric / Forge / NeoForge / Quilt / Vanilla / Velocity / BungeeCord
 */
enum class PropertiesMode { JavaProperties, PowerNukkitXYaml, Unsupported }

@Serializable
enum class ServerCore(val displayName: String) {
    Paper("Paper"),
    Purpur("Purpur"),
    Leaves("Leaves"),
    Leaf("Leaf"),
    Spigot("Spigot"),
    CraftBukkit("CraftBukkit"),
    Fabric("Fabric"),
    Forge("Forge"),
    NeoForge("NeoForge"),
    Quilt("Quilt"),
    Vanilla("Vanilla"),
    Velocity("Velocity"),
    BungeeCord("BungeeCord"),
    PowerNukkitX("PowerNukkitX"),
    PocketMine("PocketMine-MP"),
    Allay("Allay"),
    Unknown("未知");

    /** 是否为基岩版（UDP 网络、Bedrock 客户端连接） */
    val isBedrock: Boolean get() = this == PowerNukkitX || this == PocketMine || this == Allay

    /** 控制台保存世界的命令：基岩版（PowerNukkitX）不支持 save-all，用 save hold */
    val consoleSaveCommand: String get() = if (isBedrock) "save hold" else "save-all"

    val supportsPlugins: Boolean get() = this == Paper || this == Purpur ||
        this == Leaves || this == Leaf || this == Spigot || this == CraftBukkit || this == PowerNukkitX

    /** 是否支持 Fabric/Forge 模组体系 */
    val supportsMods: Boolean get() = this == Fabric || this == Forge || this == NeoForge || this == Quilt

    /** 是否支持桥接兼容层（插件↔模组互转，如 CardBoard 等，需用户自行安装） */
    val supportsBridge: Boolean get() = this == Fabric || this == Forge || this == NeoForge || this == Quilt

    val propertiesMode: PropertiesMode
        get() = when (this) {
            PowerNukkitX -> PropertiesMode.PowerNukkitXYaml
            Allay -> PropertiesMode.Unsupported
            Unknown -> PropertiesMode.Unsupported
            else -> PropertiesMode.JavaProperties
        }

    /** 是否需 installer 流程（下载 installer.jar 后需执行安装命令生成启动环境） */
    val needsInstaller: Boolean get() = this == Forge || this == NeoForge || this == Quilt
}

/**
 * 内网穿透方式
 * - Frp: 自建 frp 服务器，最灵活，功能最全
 * - Bore: 自建 bore 服务器，协议极简，纯 Kotlin 实现无需 Termux
 * - SakuraFrp: SakuraFrp 平台，填 Token 自动拉取隧道配置，无需自建服务器
 */
@Serializable
enum class TunnelType(val displayName: String, val description: String) {
    Frp("frp", "自建服务器，功能最全，支持自定义端口"),
    Bore("bore", "自建服务器，协议最简，纯手机端运行无需下载二进制"),
    SakuraFrp("SakuraFrp", "填入 Token 管理隧道，官方节点，无需自建服务器")
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
    val activeType: TunnelType? = null,
    val activeConnections: Int = 0,
    val totalUploadBytes: Long = 0,
    val totalDownloadBytes: Long = 0,
    val uploadBytesPerSecond: Long = 0,
    val downloadBytesPerSecond: Long = 0
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
    // Kept only for old DataStore values; new installs never select an overseas APT source.
    Official("清华镜像 (旧配置)", "https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main")
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
    /** TUR 社区仓库提供的 Termux 原生 openjdk-11（Forge 1.13–1.16 的推荐运行时）。 */
    Java11("Java 11", "openjdk-11", "java-11-openjdk"),
    Java17("Java 17", "openjdk-17", "java-17-openjdk"),
    Java21("Java 21", "openjdk-21", "java-21-openjdk"),
    Java25("Java 25", "openjdk-25", "java-25-openjdk")
}

/**
 * 运行环境大类：Java 系核心跑 JVM，PocketMine-MP 跑 PHP。
 *
 * 启动控制卡片的模式下拉、运行环境列表、依赖管理页的 PHP 版本选择共用这一个枚举，
 * 避免各处各写一套判断。
 */
enum class RuntimeKind { Java, Php }

/**
 * PocketMine-MP 使用的 PHP 运行时。
 *
 * 说明：目前上游（ItzxDwi/AndroidPHP）只发布了 **一个** aarch64 预编译包，
 * PHP 版本固定为 8.2 —— 这同时是 PocketMine-MP 5 的硬性要求
 * （需要 chunkutils2 / encoding / leveldb / pmmpthread 等扩展，官方不发布
 * Linux ARM64 的 PHP 构建）。因此这里只提供真实可安装的版本，不虚构选项；
 * 将来上游若发布多版本，只需在枚举里追加一项并在 [PhpVersion.runtimeDirName]
 * 里给出对应目录即可，UI 与状态逻辑无需改动。
 */
@Serializable
enum class PhpVersion(
    val displayName: String,
    /** 上游发布 tag，用于拼接下载地址 */
    val releaseTag: String,
    /** 运行时安装目录名（相对 home/） */
    val runtimeDirName: String,
    /** 说明文案资源 id，null 表示不展示 */
    val noteRes: Int? = null
) {
    /**
     * PocketMine-MP 5 官方要求：PHP 8.2 + 内置 pmmp/ext-encoding 0.4.x。
     * 对应可兼容的最高 PocketMine 版本为 5.33.1（见 McServerController）。
     */
    Php82("PHP 8.2", "pm5-latest", "php-pmmp", com.mineserve.mobile.R.string.php_note_pmmp_required);

    /** 下载地址文件名（上游资产命名固定） */
    val tarballAsset: String get() = "php-android-$releaseTag.tar.gz"

    companion object {
        /** 默认（也是当前唯一）版本：PocketMine-MP 强制使用 */
        val Default: PhpVersion = Php82

        fun fromName(name: String?): PhpVersion? =
            entries.firstOrNull { it.name == name }
    }
}

@Serializable
enum class AutoBackupType(val displayName: String) {
    World("世界备份"),
    Server("完整服务器")
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
    val dirName: String,
    /** 实际启动入口文件名；旧配置缺省为 server.jar，导入的完整目录可暂未指定。 */
    val serverFile: String? = "server.jar"
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
    /** SakuraFrp: 访问令牌（api.natfrp.com） */
    val sakuraToken: String = "",
    /** SakuraFrp: 选中的隧道 ID（启动时据此拉取官方配置） */
    val sakuraTunnelId: String = "",
    val maxHeapMb: Int = 1024,            // -Xmx JVM 堆上限，按设备 RAM 给推荐值
    val autoRestartOnCrash: Boolean = false, // 默认关闭省电，避免误触发
    val selectedJavaVersion: JavaVersion = JavaVersion.Java17,
    /**
     * 启动控制卡片的运行模式（Java / PHP）。
     *
     * 仅作为"用户想看哪一类运行环境"的界面状态；真正决定用哪个运行时的是
     * 当前核心类型 —— PocketMine-MP 一律走 PHP，其他核心一律走 JVM。
     * 切换核心时会自动纠正该字段，避免出现 Java 模式配 PHP 核心的非法组合。
     */
    val runtimeKind: RuntimeKind = RuntimeKind.Java,
    /** 所选 PHP 运行时版本（PocketMine-MP 使用） */
    val selectedPhpVersion: PhpVersion = PhpVersion.Default,
    /** 用户手动将 Java 管理卡片固定到概览页底部。 */
    val javaCardAtBottom: Boolean = false,
    val keepWifiLock: Boolean = true,
    val keepCpuWakelock: Boolean = true,
    /** 服务端运行且主界面可见时保持屏幕常亮。 */
    val keepScreenOnWhileRunning: Boolean = false,
    /** 服务端运行时显示可拖动的状态悬浮窗。 */
    val keepStatusOverlay: Boolean = false,
    /** 自动备份间隔（分钟），0 表示关闭 */
    val autoBackupIntervalMin: Int = 0,
    /** 自动备份内容，默认仅备份世界以避免无意生成大型 ZIP。 */
    val autoBackupType: AutoBackupType = AutoBackupType.World,
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
    val downloadedVersion: String? = null,
    // ── 高级启动选项 ──────────────────────────────────────────
    /** 完全自定义启动命令开关：开启后直接执行整条命令，忽略自动拼接 */
    val advancedCustomCommandEnabled: Boolean = false,
    /** 完全自定义启动命令（整条 Java 启动指令） */
    val advancedCustomCommand: String = "",
    // 注：原「模组兼容模式」开关（moduleCompatMode）已删除。那组缓解 OSHI/JNA 崩服的
    // JVM 属性改为无条件注入（见 TermuxRuntime.baseJvmProperties），用户无需再判断
    // 何时开启。旧配置文件里的残留键由 Json(ignoreUnknownKeys = true) 自动忽略。
    // ── 定时任务与停止备份 ────────────────────────────────────
    /** 每日定时开服开关 */
    val dailyStartEnabled: Boolean = false,
    /** 每日定时开服时间（小时，0-23） */
    val dailyStartHour: Int = 8,
    /** 每日定时开服时间（分钟，0-59） */
    val dailyStartMinute: Int = 0,
    /** 每日定时关服开关 */
    val dailyStopEnabled: Boolean = false,
    /** 每日定时关服时间（小时，0-23） */
    val dailyStopHour: Int = 23,
    /** 每日定时关服时间（分钟，0-59） */
    val dailyStopMinute: Int = 0,
    /** 服务器停止后自动备份一份世界快照（服从 maxSnapshots 保留策略） */
    val stopAutoBackup: Boolean = false,
    // ── MCP（Model Context Protocol）内嵌服务器 ──────────────
    /** 启用内嵌 MCP 服务器，局域网内 AI 助手可通过 HTTP 管理 MC 服务器 */
    val mcpEnabled: Boolean = false,
    /** MCP HTTP 监听端口 */
    val mcpPort: Int = 8931,
    /** MCP 访问令牌（Bearer 鉴权），首次启用时自动生成 */
    val mcpToken: String = ""
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
    /** 服务端进程 CPU 占用率（%），按可用核心数归一化到 0-100；未运行时为 null。 */
    val cpuPercent: Int? = null,
    val maxMemoryMb: Long = 0L,
    val healthPercent: Int = 0,            // 0-100，综合健康度
    /** 服务器本次启动完成的时刻（SystemClock.elapsedRealtime 基准），0 表示未启动 */
    val runningSinceMs: Long = 0L,
    /** 启动过程阶段，仅保存在运行时状态，不写入配置。 */
    val startupPhase: StartupPhase = StartupPhase.Idle,
    val installSteps: List<StepState> = InstallStep.values().map {
        StepState(it, StepStatus.Wait)
    },
    val currentProgress: Int = 0           // 0-100 安装进度
) {
    val isInstallComplete: Boolean get() = installSteps.filter { it.step != InstallStep.Jdk }.isNotEmpty() &&
        installSteps.filter { it.step != InstallStep.Jdk }.all { it.status == StepStatus.Done }
}

enum class StartupPhase(val label: String, val progress: Float) {
    Idle("未启动", 0f),
    PreparingEnvironment("准备环境", 0.07f),
    StartingJava("启动 Java", 0.20f),
    DownloadingDependencies("下载依赖", 0.40f),
    LoadingCore("加载核心", 0.62f),
    /**
     * 网络监听。
     *
     * 进度**低于**"创建世界"是刻意的：真实日志顺序是
     * `Starting Minecraft server on *:25565`（绑定端口）
     * **先于** `Preparing level "world"`（创建世界）。
     * 若这里给更高的值，进度条会先冲到 92% 再跌回 80%，
     * 出现可见的回退跳动。
     */
    StartingNetwork("启动网络", 0.72f),
    CreatingWorld("创建世界", 0.82f),
    Ready("已完成", 1f),
    Failed("启动失败", 0f)
}

/**
 * 从一行服务端日志推断当前启动阶段。
 *
 * ## 判定顺序 = 优先级（严格按 progress 从高到低）
 * `when` 是短路求值，所以**先匹配到的赢**。分支顺序必须与
 * [StartupPhase.progress] 的降序一致，否则一条同时含多个关键词的行
 * 会被误判到更早的阶段，进度条就会"卡住不动"。
 *
 * ## 关键词必须覆盖服务端的**真实输出**
 * 这是本函数最容易出错的地方。有两类中文日志都要认：
 *
 * ### 1. 服务端自己的中文输出
 * MineServe 打包的服务端（Fabric/Forge 等）在中文环境下会输出：
 * ```
 * 正在加载 Minecraft server
 * 正在加载 required files
 * 正在加载 library org.ow2.asm:asm:9.10.1
 * 正在启动 net.fabricmc.loader.impl.game.minecraft.BundlerClassPathCapture
 * ```
 * 而早期关键词几乎全是英文（`libraries`、`loading minecraft`），
 * 结果是**大段日志一行都识别不出来**：
 *   - `library` 是**单数**，而代码里只写了 `libraries`
 *   - `正在加载 Minecraft` 是中文，`loading minecraft` 匹配不到
 *
 * ### 2. 终端日志汉化插件的译文
 * 用户开启"终端内日志汉化"后，**服务端与用户的日志全部变成中文**：
 * ```
 * 正在下载依赖库 …                        （原 Downloading library …）
 * 正在正在服务器上安装 Fabric 加载器 …      （原 Installing Fabric Loader …）
 * 完成（0.924秒）！如需帮助，请输入 "help"   （原 Done (0.924s)! …）
 * ```
 * 这类译文的关键词**不能凭空猜**，必须按用户实际贴出的日志原文补
 * （曾两次因为猜测而翻车）。补词时优先用 `正在加载 ` 这类
 * **精确到含空格**的短语做兜底，而不是 `加载`、`mixin` 这类过宽的词。
 *
 * 所以中英文关键词必须**成对维护**：每加一个英文词，就要确认它的
 * 中文对应写法（服务端原生 + 汉化译文两种）是否也存在。
 *
 * 表现为进度条长期停在某一档不动，然后又突然跳到很后面的阶段
 * （"阶段被跳过" + "整体都不对"）。
 *
 * ## 关键词要够窄，否则会误伤
 * 曾经有过 `text.contains("jvm")`，`jvm` 出现在太多无关行里
 * （JVM 参数回显、`[startmc]` 环境信息、mod 的 JVM 检测输出……），
 * 结果进度被反复钉死在 `StartingJava`(20%)。
 * 也曾有过 `mixin` / `remapping`，这两个词在资源重载、类编译、报错、
 * 插件配置路径里都会出现，误伤面极大。
 * **过宽的词一律不要**，只认明确表达该动作的短语。
 */
fun startupPhaseForLog(line: String): StartupPhase? {
    val text = line.lowercase()
    // 异常堆栈行不参与阶段判定，见 [isStackFrameLine]。
    if (isStackFrameLine(line)) return null
    return when {
        // ── 就绪（最高优先级：出现即完成） ────────────────
        //
        // ⚠️ 这一档的每个词都必须**限定上下文**，否则会把前面阶段的日志
        // 误判成"已完成"，进度条直接跳 100%，后面所有阶段全被吞掉。
        // 踩过的两个真实坑（都由单元测试挡住）：
        //   - `已就绪`：`[bootstrap] Java 25 wrapper 脚本已创建完成: 6 个命令已就绪`
        //     含"已就绪"，会在**启动第 2 秒**就判成就绪。
        //   - `完成(` ：同一行的"已创建完成:"后面正好跟冒号，被 `完成` 前缀匹配。
        // 所以只认能明确表达"服务端启动完毕"的整句写法。
        text.contains("启动完成") || text.contains("服务端已启动完成") ||
            text.contains("启动完毕") || text.contains("服务端已就绪") ||
            text.contains("服务端就绪") ||
            text.contains("server started") ||
            text.contains("done (") || text.contains("done!") ||
            // 汉化插件会把 "Done (2.483s)!" 翻成 "完成（2.483秒）！"
            // 注意必须是**全角左括号**紧跟"完成"，不能只写"完成"
            text.contains("完成（") ||
            text.contains("enabled bungeecord") || text.contains("velocity has started") ||
            // Allay：网络接口启动完成即为就绪（空闲期不再输出日志）
            text.contains("network interface started at") ||
            text.contains("网络接口已启动于") -> StartupPhase.Ready

        // ── 创建世界 ──────────────────────────────────
        // 排在网络之前：进度 0.82 > 0.72，`when` 短路要求高进度在前。
        text.contains("preparing level") || text.contains("preparing start region") ||
            text.contains("preparing spawn") || text.contains("preparing world") ||
            text.contains("preparing spawn area") || text.contains("加载世界") ||
            text.contains("正在加载世界") || text.contains("正在准备出生点区域") ||
            text.contains("创建世界") ||
            // 汉化插件对应的中文写法
            text.contains("选择全局世界出生点") ||
            text.contains("正在准备世界") ||
            // "Loading 0 persistent chunks..." 的译文。
            // 注意必须写成"正在加载 %d 个持久化区块"，不能只写"持久化区块"：
            // 后者会被上面核心档的 `正在加载 ` 抢走
            text.contains("个持久化区块") -> StartupPhase.CreatingWorld

        // ── 网络监听 ──────────────────────────────────
        // 排在创建世界**之后**：两者进度分别是 0.72 / 0.82，
        // `when` 短路要求高进度在前，否则顺序与 progress 不一致。
        text.contains("listening on") || text.contains("starting minecraft server on") ||
            // 中文日志："正在启动 Minecraft 服务端" 紧随 "Starting Minecraft server on" 出现。
            //
            // ⚠️ 但 "Starting minecraft server **version** 26.3" 的译文中也含
            // "正在启动 minecraft 服务端"（即 "正在启动 Minecraft 服务端版本 26.3"），
            // 而那句属于**加载核心**（原始英文含 "version"，靠核心档的
            // `starting minecraft server version` 命中）。这里显式排除掉，
            // 否则核心档那句会被网络档抢先，进度条提前跳到 72%。
            text.contains("正在启动 minecraft 服务端") && !text.contains("版本") ||
            // 汉化插件译文（注意是"世界"不是"服务端"）
            text.contains("正在启动 minecraft 世界") ||
            text.contains("启动 gs4") || text.contains("query 运行") ||
            text.contains("server bound") || text.contains("监听") -> StartupPhase.StartingNetwork

        // ── 下载依赖 ──────────────────────────────────
        // 必须排在 LoadingCore 之前：下载阶段的日志会**夹带**核心加载的字样。
        // 例如 "正在加载 Minecraft server" 同时含 "server" 与 "minecraft"，
        // 若核心分支在前，这行会被判成"加载核心"，整段下载都被跳过。
        // "Installing Fabric Loader …" 同理（含 "fabric loader"）。
        text.contains("[download]") || text.contains("下载依赖") ||
            text.contains("安装依赖") || text.contains("依赖包") ||
            text.contains("downloading") || text.contains("downloaded") ||
            // library 与 libraries 都要认：服务端实际输出的是**单数** library
            text.contains("library") || text.contains("libraries") ||
            text.contains("mojang_") ||
            // 中文下载日志
            text.contains("正在下载") || text.contains("下载 ") ||
            text.contains("安装 ") ||
            text.contains("所需文件") ||
            text.contains("正在解包") || text.contains("解压 ") ||
            text.contains("生成服务端启动") || text.contains("服务器启动 jar") ||
            text.contains("required files") || text.contains("unpacking") ||
            text.contains("generating server launch jar") ||
            text.contains("server launch jar") ||
            // 引导阶段：装核心 / 装加载器 / 展开服务端 jar
            text.contains("正在加载 minecraft server") ||
            text.contains("installing ") || text.contains("installing fabric") ||
            text.contains("正在服务器上安装") ||
            // ⚠️ 启动器的"环境探测"行：下载阶段会先打
            //   "Loading Minecraft 26.3 with Fabric Loader…"（译文
            //   "正在加载 Minecraft 26.3 及依赖"），探完**才继续下载**。
            // 若把它算成"加载核心"(62%)，在"只增不减"下后面真正的下载行
            // (40%) 会被整段忽略，进度条卡在 62% 不动 —— 用户看到的正是
            // "下载和加载核心两个左右跳"。
            // 判定依据：这句带"及依赖 / with … loader"这类**环境描述**，
            // 而真正的核心加载是"正在加载 Minecraft 服务端 / mods / 配置"。
            (text.contains("正在加载 minecraft") && text.contains("依赖")) ||
            (text.contains("loading minecraft") && text.contains("with")) ||
            // ── 模糊行下沉（见"加载核心"档顶部的说明）──────────────
            // 只下沉**确实可能出现在下载阶段**的行。
            // ⚠️ 下面这些**不下沉**，保留在核心档，因为它们都是核心加载的锚点：
            //     - `个模组`：模组列表只在核心真正加载时打印
            //     - `缺少映射 / mappings not present`：这是 Fabric 核心加载器
            //       启动时打印的，两侧都是"正在加载 Minecraft"/"正在加载 N 个模组"。
            //       实测把它下沉会造成 62% → 40% → 62% 的一次抖动，
            //       用户看到的就是"两个左右跳"。
            text.contains("正在替换旧版本") || text.contains("正在修复") -> StartupPhase.DownloadingDependencies

        // ── 加载核心 ──────────────────────────────────
        //
        // ⚠️⚠️ 模糊行一律下沉到"下载依赖"档 ⚠️⚠️
        //
        // `Mappings not present!` / `Loading 4 mods:` 这类行在 Fabric 启动器里
        // **既可能出现在下载阶段（环境探测），也可能出现在真正的核心加载阶段**，
        // 单看这一行无法区分。
        //
        // 遇到这种模糊时**必须选低档（下载 40%）**，不能选高档（核心 62%）：
        //   - 选低档：只可能让进度条多停在 40% 一会儿 → 用户能接受
        //   - 选高档：后面真正的下载行（也是 40%）会被"只增不减"全部忽略，
        //             进度条**彻底卡死**在 62%，直到"启动网络"才动 → 就是
        //             用户反馈的"两个左右跳 / 卡着不动"
        //
        // 所以下面这些行统一由前面的"下载依赖"分支接走（见那里的"模糊行下沉"段），
        // 这里只保留**无歧义**的核心加载证据。
        text.contains("loading server") || text.contains("loading properties") ||
            text.contains("loading nukkit") || text.contains("loading plugins") ||
            text.contains("loading minecraft") || text.contains("mod loading") ||
            text.contains("modlauncher") || text.contains("quilt loader") ||
            text.contains("fabric loader") || text.contains("booting up velocity") ||
            text.contains("starting bungeecord") || text.contains("正在启动 minecraft") ||
            // "Starting net.fabricmc.loader…BundlerClassPathCapture" 的译文。
            // 这句话里没有 "minecraft"，所以必须单独列出（见"启动 Java"档的注释）
            text.contains("正在启动 net.fabricmc") ||
            text.contains("starting org.bukkit") ||
            // 中文核心加载日志（关键补充：这些行以前一行都识别不出来）
            text.contains("正在加载 minecraft") || text.contains("正在加载服务端配置") ||
            text.contains("正在加载 mods") || text.contains("mods:") ||
            // "Loading libraries..." 的译文是"正在加载库 …" → "库"
            // 注意不能用裸"库"（太宽），用"正在加载库 "带空格限定
            text.contains("正在加载库 ") ||
            // "正在加载配置" == "Loading properties" 的译文
            text.contains("正在加载配置") ||
            // 汉化插件译文：其余每条都以"正在加载 "开头（注意含空格）。
            // 上面那些特定写法先被命中，所以这条通配不会误伤；
            // 而"正在加载 library …"（带引号的下载行）在更前面被下载分支接走。
            // 这**不会**影响 "[bootstrap] … 已创建完成"：
            // 那里是"已创建"，不含"正在加载 "，故仍归准备环境。
            text.contains("正在加载 ") ||
            text.contains("starting minecraft server version") ||
            text.contains("generating keypair") || text.contains("no existing world data") ||
            text.contains("mappings not present") ||
            // 汉化译文
            text.contains("没有现有的世界数据") || text.contains("缺少映射") ||
            text.contains("正在生成密钥对") -> StartupPhase.LoadingCore

        // ── 启动 Java（关键词收窄，不再匹配裸 "jvm"） ──────
        text.contains("[startmc] java 路径") || text.contains("正在启动 java") ||
            text.contains("starting java") ||
            // ⚠️ 不要把 "正在启动 net.fabricmc.loader…" 放这里。
            // 它是 "Starting net.fabricmc.loader…BundlerClassPathCapture" 的译文，
            // 而那句属于**加载核心**（类加载器已接手，Java 进程早起来了）。
            // 用户真实日志里它正是「加载核心」段的第一行；若归到这一档(20%)，
            // 进度条会从 62% 掉回 20%。核心档的 `正在启动 minecraft` 已覆盖它。
            // 只认带上下文的版本声明，避免命中任意含 "jvm" 的日志。
            // 汉化插件会把 "version" 翻成"版本"，所以 `openjdk 版本` 也要认。
            text.contains("openjdk version") || text.contains("openjdk 版本") ||
            text.contains("java version") || text.contains("java 版本") ||
            text.contains("java hotspot") -> StartupPhase.StartingJava

        // ── 准备环境（最早期，进度最低，放最后） ──────────
        // App 自身的引导日志走这里。以前**没有任何关键词覆盖这一段**，
        // 于是启动的头几秒进度条停在初始值不动（表现为"启动卡住"）。
        //
        // 位置说明：`when` 是短路求值，**前面的分支优先**。这一档 progress 最低
        // （0.07），所以必须放在所有其他阶段之后，否则会抢走更靠后阶段的日志。
        // 例如 "[startMc] java 路径: ..." 含 "[startmc]" 但不含 [bootstrap]，
        // 两档不冲突；但任何同时命中的行都会先被判为 Java 启动 —— 这是对的，
        // 因为出现 java 路径就说明环境准备已经过了。
        text.contains("[bootstrap]") || text.contains("[repair]") ||
            text.contains("[jvm]") || text.contains("[proot]") ||
            text.contains("修复") && text.contains("脚本路径") ||
            text.contains("wrapper 脚本") || text.contains("脚本已创建") ||
            text.contains("正在检查环境") || text.contains("准备环境") -> StartupPhase.PreparingEnvironment

        else -> null
    }
}

/**
 * 判断一行是不是 **Java 异常堆栈帧**（属于堆栈输出，而非服务端的状态播报）。
 *
 * ## 为什么必须把堆栈排除掉
 * 堆栈里的类名/包名会**回显**先前阶段的关键字，而且是在很久之后才回显。
 * 实测最典型的一条（来自用户的真实日志）：
 * ```
 * at net.fabricmc.installer.ServerLauncher.main(ServerLauncher.java:69)
 * ```
 * 含 `server`，被判成"下载依赖"(40%)；而它出现在**加载核心阶段之后**，
 * 于是进度条从 62% 掉回 40%。同类还有
 * ```
 * at java.base/jdk.internal.loader.NativeLibraries$NativeLibraryImpl.open(...)   → 含 "libraries"
 * at knot//net.minecraft.SystemReport.putHardware(SystemReport.java:105)         → 含 "server"
 * ```
 * 这些行**一个都不该**推动进度。
 *
 * ## 如何识别
 * 只在行首附近出现 `at `（含 `at java.base/`、`at knot//` 这类 JDK 与
 * 加载器前缀）才判为堆栈帧。刻意做得保守：
 *   - 只认行首的 `at`，不认正文里出现的 "at"，否则会误伤正常日志
 *   - 不把 `Caused by:` / `... 12 more` 也一起拦掉太激进，它们本来就不含
 *     阶段关键词，靠关键词表即可自然过滤
 *
 * 这样 App 自己输出的 `[startMc] … at …` 之类正常行不受影响。
 */
internal fun isStackFrameLine(line: String): Boolean {
    val t = line.trimStart()
    if (!t.startsWith("at ")) return false
    // 排除误伤：真正的堆栈帧至少形如 "at xxx.yyy(" 或 "at xxx.yyy.zzz("
    return t.contains('(') || t.contains('.')
}
