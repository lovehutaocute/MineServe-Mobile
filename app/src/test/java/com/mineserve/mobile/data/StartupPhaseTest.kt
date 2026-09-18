package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StartupPhaseTest {
    @Test
    fun recognizesCommonCoreStartupMessages() {
        assertEquals(StartupPhase.LoadingCore, startupPhaseForLog("[main/INFO]: Loading Minecraft 1.21.1"))
        assertEquals(StartupPhase.StartingNetwork, startupPhaseForLog("[main/INFO]: Listening on /0.0.0.0:25565"))
        assertEquals(StartupPhase.Ready, startupPhaseForLog("[Server thread/INFO]: Done (2.1s)! For help, type \"help\""))
        assertEquals(StartupPhase.Ready, startupPhaseForLog("[Server thread/INFO]: Done (2.1s)!"))
        assertEquals(StartupPhase.Ready, startupPhaseForLog("[main/INFO]: Velocity has started"))
    }

    @Test
    fun recognizesMojangDownloadMessages() {
        // Paper 中文日志：正在加载 mojang_1.20.6.jar
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在加载 mojang_1.20.6.jar"))
        // Paper 英文日志：Downloading mojang_26.2.jar
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("Downloading mojang_26.2.jar"))
        // PowerNukkitX 缓存下载
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在加载 mojang_1.21.0.jar"))
        // 通用下载日志不被误判为 LoadingCore
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("[main/INFO]: 正在加载依赖库 libraries..."))
    }

    @Test
    fun loadingCoreStillTakesPriorityOverDownloading() {
        // "loading minecraft" 应匹配 LoadingCore（在 DownloadingDependencies 之前检测）
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[main/INFO]: Loading Minecraft 1.20.6"))
        // "loading nukkit" 应匹配 LoadingCore
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[main/INFO]: Loading Nukkit 1.0"))
    }

    /**
     * 引导阶段的日志必须能推动进度条。
     *
     * 回归背景：`PreparingEnvironment` **曾经没有任何关键词覆盖**，
     * 于是启动头几秒进度条停在初始值不动（"启动卡住"）。
     * 这些行是 App 自己输出的引导日志。
     */
    @Test
    fun bootstrapLogsAdvanceToPreparingEnvironment() {
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[bootstrap] 修复 6 个脚本路径"))
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[bootstrap] Java 25 wrapper 脚本已创建完成: 6 个命令已就绪"))
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[repair] 自动修复完成，共处理 6 项"))
    }

    /**
     * 中文日志的下载阶段。
     *
     * 回归背景（本文件最重要的一组用例）：服务端在中文环境下输出
     * `正在加载 library xxx`，而代码里只写了英文复数 `libraries`，
     * 于是**整段下载日志一行都识别不出来**，进度条长期停在旧档位，
     * 然后突然跳到很后面的阶段（用户反馈"阶段被跳过" + "整体都不对"）。
     *
     * 教训：中文关键词必须和英文成对维护。
     */
    @Test
    fun recognizesChineseDownloadLogs() {
        // "library" 是单数 —— 以前的 "libraries" 匹配不到
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("Down正在加载 library org.ow2.asm:asm:9.10.1"))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在加载 library net.fabricmc:fabric-loader:0.19.5"))
        // 展开 jar 阶段
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("Unpacking org/slf4j/slf4j-api/2.0.17/slf4j-api-2.0.17.jar ..."))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("Generating server launch JAR"))
        // 装加载器
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("Installing Fabric Loader 0.19.5(26.3) on the server"))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在加载 required files"))
    }

    /**
     * "正在加载 Minecraft server" 必须判为**下载**，不能判成加载核心。
     *
     * 这行同时含 "minecraft" 与 "server"（都是核心分支的关键词），
     * 但它出现在下载阶段（引导器在拉服务端 jar）。若核心分支在前，
     * 这行会判成 62%，整段下载（后面还有十几行）就全被跳过了。
     *
     * 这是"下载分支必须排在核心分支之前"这一顺序约束的**直接证据**。
     */
    @Test
    fun bootstrapDownloadLineWinsOverCoreKeywords() {
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在加载 Minecraft server"))
    }

    /**
     * 中文核心加载日志。
     *
     * 回归背景：这些行以前一行都识别不出来（中文 + 没有对应关键词），
     * 导致"加载核心"这一档几乎从不显示。
     */
    @Test
    fun recognizesChineseCoreLoadingLogs() {
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[main/INFO]: 正在加载 Minecraft 26.3 with Fabric Loader 0.19.5"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[main/INFO]: 正在加载 4 mods:"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[Worker-Main-5/INFO]: No existing world data, creating new world"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[Server thread/INFO]: Starting minecraft server version 26.3"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[Server thread/INFO]: Generating keypair"))
    }

    @Test
    fun recognizesChineseWorldAndNetworkLogs() {
        assertEquals(StartupPhase.StartingNetwork,
            startupPhaseForLog("[Server thread/INFO]: Starting Minecraft server on *:25565"))
        assertEquals(StartupPhase.CreatingWorld,
            startupPhaseForLog("[Server thread/INFO]: Preparing level \"world\""))
        assertEquals(StartupPhase.CreatingWorld,
            startupPhaseForLog("[Server thread/INFO]: Preparing spawn area: 100%"))
        assertEquals(StartupPhase.Ready,
            startupPhaseForLog("[Server thread/INFO]: Done (2.988s)! For help, type \"help\""))
    }

    /**
     * 真实日志顺序：**先创建世界，再绑定端口**。
     *
     * ```
     * [06:47:00] Loading properties                        ← 加载核心
     * [06:47:01] Starting Minecraft server on *:25565      ← 绑定端口（启动网络）
     * [06:47:01] Preparing level "world"                   ← 创建世界
     * ```
     *
     * 注意 `Starting Minecraft server on *:25565` 这一行有两个可能的归属：
     *   - 它是"开始监听端口"，属于 `StartingNetwork`
     *   - 它也可能被误读成"准备启动服务端"，即 `LoadingCore`
     *
     * 这里**必须判为 `StartingNetwork`**（72%），否则整段核心加载
     * （它排在 `Loading properties` 之后、`Preparing level` 之前，
     * 还有十几行）会被这行抢走，进度条提前跳到核心档。
     *
     * 由此推出顺序约束：`StartingNetwork` 出现时刻**早于**
     * `CreatingWorld`，所以它的 progress 必须**低于** `CreatingWorld`，
     * 否则进度条会先冲到网络档再跌回世界档，出现可见的回退跳动。
     */
    @Test
    fun networkBindPrecedesWorldCreationInProgressOrder() {
        assertTrue(
            "StartingNetwork(${StartupPhase.StartingNetwork.progress}) " +
                "应低于 CreatingWorld(${StartupPhase.CreatingWorld.progress})",
            StartupPhase.StartingNetwork.progress < StartupPhase.CreatingWorld.progress
        )
        // 行为上也必须是网络，不能落到 LoadingCore
        assertEquals(StartupPhase.StartingNetwork,
            startupPhaseForLog("[06:47:01] [Server thread/INFO]: Starting Minecraft server on *:25565"))
    }

    /**
     * 中文启动 Java 日志。
     *
     * ⚠️ 修正记录：这条用例原先断言
     * `正在启动 net.fabricmc.loader…BundlerClassPathCapture` → `StartingJava`，
     * **这是错的**。它是 `Starting net.fabricmc.loader…BundlerClassPathCapture`
     * 的译文，而那句在**加载核心**段（类加载器已接手，Java 进程早起来了），
     * 用户在真实日志里也正是把它标在「加载核心」下。
     * 判成 StartingJava(20%) 会让进度条从 62% 掉回 20%，
     * 被 `localizedStartupSequenceAdvancesMonotonically` 抓了出来。
     *
     * 真正标识"启动 Java"的是 `[startMc] java 路径` 与 JVM 版本声明。
     */
    @Test
    fun recognizesChineseJavaStartup() {
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("正在启动 net.fabricmc.loader.impl.game.minecraft.BundlerClassPathCapture"))
        // 这一档真正的中文证据
        assertEquals(StartupPhase.StartingJava,
            startupPhaseForLog("[startMc] java 路径: /data/user/0/com.mineserve.mobile/files/home/lib/jvm/java-25-openjdk/bin/java"))
        assertEquals(StartupPhase.StartingJava,
            startupPhaseForLog("openjdk 版本 \"25.0.3\" 2026-04-15"))
    }

    /**
     * 过宽的词**不许**回到判定里 —— 这是最容易反复踩的坑。
     *
     * 历史两次翻车：
     *   - `contains("jvm")`：把 JVM 参数回显判成启动 Java
     *   - `contains("mixin")` / `contains("remapping")`：这两个词在资源重载、
     *     类编译、报错、插件配置路径里都会出现，误伤面极大
     *     （实测 `正在编辑 remapping.yml`、`Mixin apply failed` 都被误判）
     *
     * 这类行宁可"不判定"（保持上一档），也不要判错。
     */
    @Test
    fun overBroadKeywordsMustNotReturnPhase() {
        assertNull(startupPhaseForLog("[main/INFO]: SpongePowered MIXIN Subsystem Version=0.8.7"))
        assertNull(startupPhaseForLog("[main/WARN]: Mixin apply failed"))
        assertNull(startupPhaseForLog("[Server thread/INFO]: 正在编辑 remapping.yml"))
    }

    /**
     * 终端日志汉化插件开启后，**全部译文都必须能判定**。
     *
     * 回归背景：开启汉化后进度条"卡在 7% 的启动 Java 不动"。
     * 原因是译文里的关键词一个都没收录：
     *   - `正在下载依赖库 …`       中文是"下载…库"，而代码认的是 `library`(英文)
     *   - `正在服务器上安装…`       对应英文 `Installing …`
     *   - `完成（0.924秒）！`       对应英文 `Done (0.924s)!`
     *
     * 这一组用例的字符串**直接抄自用户的真实日志**，不许改成"看起来
     * 应该是这样"的版本 —— 上两次翻车都是因为照着猜的文案改关键词。
     */
    @Test
    fun recognizesChineseLocalizedLogs() {
        // ── 下载依赖 ────────────────────────────────
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在下载 Minecraft server"))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在服务器上安装 Fabric 加载器 0.19.5(26.3)"))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在下载所需的文件"))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在下载依赖库 org.ow2.asm:asm:9.10.1"))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("正在下载依赖库 net.fabricmc:fabric-loader:0.19.5"))
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("生成服务器启动 jar"))

        // ── 加载核心 ────────────────────────────────
        // 这两句是核心加载的锚点，**不下沉**到下载档：
        // `缺少映射` 两侧都是"正在加载 Minecraft"/"正在加载 N 个模组"，
        // 下沉会造成 62% → 40% → 62% 的一次抖动（用户反馈的"左右跳"）。
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:46:57] [main/INFO]: 正在加载 Minecraft 26.3，使用 Fabric 加载器 0.19.5"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:46:57] [main/INFO]: 缺少映射！"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:46:57] [main/INFO]: 正在加载 4 个模组："))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:46:57] [main/INFO]: 正在加载库 net.fabricmc:sponge-mixin:0.17.4"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:46:57] [main/INFO]: 正在加载 jna 原生库"))
        // `正在启动 net.fabricmc.loader…BundlerClassPathCapture` == 英文
        // `Starting net.fabricmc.loader…BundlerClassPathCapture`，**属于加载核心**，
        // 不是"启动 Java"（旧用例把它期望成 LoadingCore 是对的，
        // 分类器此前误判为 StartingJava，已修正）。
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("正在启动 net.fabricmc.loader.impl.game.minecraft.BundlerClassPathCapture"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:47:00] [Worker-Main-4/INFO]: 没有现有的世界数据，正在创建新世界"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:47:00] [Server thread/INFO]: 正在启动 Minecraft 服务端版本 26.3"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:47:00] [Server thread/INFO]: 正在加载配置"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:47:00] [Server thread/INFO]: 正在生成密钥对"))

        // ── 启动网络 ────────────────────────────────
        // 注意译文是"世界"不是"服务端"，两种都要认
        assertEquals(StartupPhase.StartingNetwork,
            startupPhaseForLog("[06:47:01] [Server thread/INFO]: 正在启动 Minecraft 世界，端口 *:25565"))

        // ── 创建世界 ────────────────────────────────
        assertEquals(StartupPhase.CreatingWorld,
            startupPhaseForLog("[06:47:01] [Server thread/INFO]: 正在准备世界 \"world\""))
        assertEquals(StartupPhase.CreatingWorld,
            startupPhaseForLog("[06:47:01] [Server thread/INFO]: 正在选择全局世界出生点..."))
        assertEquals(StartupPhase.CreatingWorld,
            startupPhaseForLog("[06:47:03] [Server thread/INFO]: 正在加载 0 个持久化区块..."))
        assertEquals(StartupPhase.CreatingWorld,
            startupPhaseForLog("[06:47:03] [Server thread/INFO]: 正在准备出生点区域：100%"))

        // ── 已完成 ──────────────────────────────────
        assertEquals(StartupPhase.Ready,
            startupPhaseForLog("[06:47:03] [Server thread/INFO]: 完成（2.483秒）！如需帮助，请输入 \"help\""))
    }

    /**
     * 汉化后的日志**整体跑一遍**，进度必须单调前进、不得回退。
     *
     * 这是"进度条跳过阶段"问题的最直接回归用例：
     * 断言的是**序列**而不是单行，所以只要有一行判错导致回退就会失败。
     */
    @Test
    fun localizedStartupSequenceAdvancesMonotonically() {
        val log = listOf(
            // 准备环境（App 自身引导日志，不受汉化影响）
            "[bootstrap] 修复 6 个脚本路径",
            "[bootstrap] Java 25 wrapper 脚本已创建完成: 6 个命令已就绪",
            "[repair] 自动修复完成，共处理 6 项",
            // 启动 Java
            "[startMc] java 路径: /data/user/0/com.mineserve.mobile/files/home/lib/jvm/java-25-openjdk/bin/java",
            // 下载依赖
            "正在下载 Minecraft server",
            "正在服务器上安装 Fabric 加载器 0.19.5(26.3)",
            "正在下载所需的文件",
            "正在下载依赖库 org.ow2.asm:asm:9.10.1",
            "正在下载依赖库 net.fabricmc:fabric-loader:0.19.5",
            "生成服务器启动 jar",
            // 加载核心
            "正在加载 Minecraft 26.3，使用 Fabric 加载器 0.19.5",
            "缺少映射！",
            "正在加载 4 个模组：",
            "正在加载库 net.fabricmc:sponge-mixin:0.17.4",
            "正在启动 net.fabricmc.loader.impl.game.minecraft.BundlerClassPathCapture",
            "[Worker-Main-4/INFO]: 没有现有的世界数据，正在创建新世界",
            "正在启动 Minecraft 服务端版本 26.3",
            "正在加载配置",
            "正在生成密钥对",
            // 启动网络（先绑定端口）
            "正在启动 Minecraft 世界，端口 *:25565",
            // 创建世界
            "正在准备世界 \"world\"",
            "正在选择全局世界出生点...",
            "正在加载 0 个持久化区块...",
            "正在准备出生点区域：100%",
            // 已完成
            "完成（2.483秒）！如需帮助，请输入 \"help\""
        )

        var last = 0f
        var recognized = 0
        log.forEach { line ->
            val phase = startupPhaseForLog(line) ?: return@forEach
            recognized++
            assertTrue(
                "阶段回退了：'$line' 判为 ${phase.label}(${phase.progress})，" +
                    "但上一档已经是 $last",
                phase.progress >= last
            )
            last = phase.progress
        }
        // 汉化后的这 25 行必须**一行不漏**地全部识别出来
        assertEquals("汉化日志识别率应为 100%", log.size, recognized)
    }

    /**
     * **异常堆栈行不得推动进度条**。
     *
     * 回归背景（本组是"进度条跳到更早阶段"的真正原因）：
     * 堆栈里的类名/包名会**回显**先前阶段的关键字，而且在很久之后才回显。
     * 用户真实日志里最脏的几条：
     * ```
     * at net.fabricmc.installer.ServerLauncher.main(ServerLauncher.java:69)   → 含 "server"
     * at java.base/jdk.internal.loader.NativeLibraries$…open(…)               → 含 "libraries"
     * at knot//net.minecraft.SystemReport.putHardware(SystemReport.java:105)  → 含 "server"
     * ```
     * 实测第一条会被判成"下载依赖"(40%)，而它出现在"加载核心"之后，
     * 进度条于是从 62% 掉回 40%。同类还有几十行，`when` 的短路求值
     * 救不了"整段堆栈都命中同一个宽松关键词"。
     */
    @Test
    fun stackTraceFramesNeverAdvancePhase() {
        assertTrue(isStackFrameLine("at net.fabricmc.installer.ServerLauncher.main(ServerLauncher.java:69)"))
        assertNull(startupPhaseForLog("at net.fabricmc.installer.ServerLauncher.main(ServerLauncher.java:69)"))
        // 注意 `$` 必须转义：Kotlin 会把它当字符串模板，`${'$'}NativeLibraryImpl` 才能编译
        assertNull(startupPhaseForLog("at java.base/jdk.internal.loader.NativeLibraries${'$'}NativeLibraryImpl.open(NativeLibraries.java:321)"))
        assertNull(startupPhaseForLog("at knot//net.minecraft.SystemReport.putHardware(SystemReport.java:105)"))
        assertNull(startupPhaseForLog("  at knot//oshi.util.Memoizer${'$'}1.get(Memoizer.java:65)"))
        assertNull(startupPhaseForLog("at net.fabricmc.loader.impl.game.minecraft.MinecraftGameProvider.launch(MinecraftGameProvider.java:517)"))
    }

    /**
     * 但**正常日志行**不能被堆栈过滤误伤 —— 过滤必须保守。
     */
    @Test
    fun normalLinesAreNotMistakenForStackFrames() {
        // 正常的状态播报行不以 "at " 开头
        assertTrue(!isStackFrameLine("[06:47:01] [Server thread/INFO]: Starting Minecraft server on *:25565"))
        assertTrue(!isStackFrameLine("Downloading library org.ow2.asm:asm:9.10.1"))
        // 这些行照旧要能判定
        assertEquals(StartupPhase.StartingNetwork,
            startupPhaseForLog("[06:47:01] [Server thread/INFO]: Starting Minecraft server on *:25565"))
        assertEquals(StartupPhase.LoadingCore,
            startupPhaseForLog("[06:47:00] [Server thread/INFO]: Loading properties"))
        // App 自身以 "at"/"look at" 之类词开头的行不受影响
        assertTrue(!isStackFrameLine("Looking at JVM args"))
    }

    /**
     * 汉化后的 `[bootstrap] … 已创建完成` **不能**被判成"已完成"。
     *
     * 回归背景：`Ready` 分支含 `完成(`（对应英文 `Done (`），
     * 而中文"已创建完成: 6 个命令已就绪"里正好含 `完成:`——
     * 如果关键词写成 `完成`（不带前缀）就会误命中，进度条会在
     * **启动第 2 秒直接跳到 100%**，后面全部阶段都被吞掉。
     * 而 `已就绪` 这个词在这行里也存在（"6 个命令已就绪"）。
     *
     * 所以这两个词必须限定上下文，只认句子级写法。
     */
    @Test
    fun localizedBootstrapCompletionDoesNotJumpToReady() {
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[bootstrap] Java 25 wrapper 脚本已创建完成: 6 个命令已就绪"))
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[bootstrap] 修复 6 个脚本路径"))
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[repair] 自动修复完成，共处理 6 项"))
        // 但真正的就绪行仍要判 Ready
        assertEquals(StartupPhase.Ready,
            startupPhaseForLog("[06:47:03] [Server thread/INFO]: 完成（2.483秒）！如需帮助，请输入 \"help\""))
        assertEquals(StartupPhase.Ready,
            startupPhaseForLog("服务端已启动完成"))
    }

    /**
     * 汉化译文的关键词不能过宽 —— 特别要保证 App 自身的引导日志
     * 仍归"准备环境"(7%)，不要被核心分支的 `正在加载 ` 抢走。
     */
    @Test
    fun localizedCoreWildcardDoesNotStealBootstrapLogs() {
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[bootstrap] Java 25 wrapper 脚本已创建完成: 6 个命令已就绪"))
        assertEquals(StartupPhase.PreparingEnvironment,
            startupPhaseForLog("[bootstrap] 修复 6 个脚本路径"))
        // "[startMc] java 路径" 含中文"路径"但不含"正在加载 "，仍归启动 Java
        assertEquals(StartupPhase.StartingJava,
            startupPhaseForLog("[startMc] java 路径: /data/.../bin/java"))
    }

    /**
     * 阶段进度必须严格递增，保证进度条不会出现"跳档"或"回退"的观感。
     */
    @Test
    fun phaseProgressIsStrictlyIncreasing() {
        val ordered = listOf(
            StartupPhase.Idle,
            StartupPhase.PreparingEnvironment,
            StartupPhase.StartingJava,
            StartupPhase.DownloadingDependencies,
            StartupPhase.LoadingCore,
            StartupPhase.StartingNetwork,
            StartupPhase.CreatingWorld,
            StartupPhase.Ready
        )
        ordered.zipWithNext { a, b ->
            assertTrue("$a(${a.progress}) 应低于 $b(${b.progress})", a.progress < b.progress)
        }
    }

    @Test
    fun bareJvmMentionsAreNotTreatedAsJavaLaunch() {
        // 回归：过宽的 `contains("jvm")` 曾把任意含 JVM 字样的日志判成 StartingJava(20%)，
        // 导致进度条被钉在低值不动（"启动进度在瞎说"）。
        // 这类只是回显/环境信息的行**不应该**产生阶段判定。
        assertNull(startupPhaseForLog("JVM args: -Xmx2048m -Djava.awt.headless=true"))
        assertNull(startupPhaseForLog("[INFO] Looking up JVM version"))
        assertNull(startupPhaseForLog("Using JVM: /usr/lib/jvm/java-25-openjdk"))
    }

    @Test
    fun explicitJavaStartupStillDetected() {
        // 但明确的 Java 启动证据仍要能识别出来
        assertEquals(StartupPhase.StartingJava,
            startupPhaseForLog("[startMc] java 路径: /data/.../bin/java"))
        assertEquals(StartupPhase.StartingJava,
            startupPhaseForLog("openjdk version \"25.0.3\" 2026-04-15"))
        assertEquals(StartupPhase.StartingJava, startupPhaseForLog("正在启动 Java 服务端"))
    }

    @Test
    fun downloadPhaseWinsOverJavaMentions() {
        // 下载依赖阶段常伴随 JVM/进程信息，必须优先判为下载，否则进度会退回 20%
        assertEquals(StartupPhase.DownloadingDependencies,
            startupPhaseForLog("Downloading libraries for Paper 1.21 (jvm: 25)"))
    }

    // ────────────────────────────────────────────────────────────────
    // 用户真实日志的"只增不减"回归
    //
    // 症状（用户原话）：
    //   ① "下载核心与加载核心，然后它们两个左右跳"
    //   ② "然后直接进度条完成"
    //
    // 原因：
    //   ① 核心探针行夹在下载段中间，随后下载又继续 → 40%↔62% 横跳
    //   ② 启动网络/创建世界各只出现 1~2 行，被"连续 3 行才采纳"的防抖吞掉
    //      → 62% 直接蹦到 100%
    //
    // 修法：推进规则改为**只增不减**（低/平级忽略，高值立即采纳），
    //       噪声改由 isStackFrameLine 在判定源头拦掉。
    // ────────────────────────────────────────────────────────────────

    /**
     * 模拟 McViewModel.updateStartupPhaseFromLog 的推进规则（无状态、纯函数版）。
     */
    private fun foldPhases(lines: List<String>, initial: StartupPhase): List<StartupPhase> {
        var cur = initial
        val trace = mutableListOf(cur)
        for (line in lines) {
            val phase = startupPhaseForLog(line) ?: continue
            if (phase.progress <= cur.progress) continue   // 只增不减
            cur = phase
            trace.add(cur)
        }
        return trace
    }

    @Test
    fun localizedStartupSequenceNeverGoesBackwards() {
        // 症状①的回归：用用户真实日志顺序喂进去，进度必须**单调不减**。
        val realLog = listOf(
            // 准备环境
            "[bootstrap] 正在准备运行环境",
            "[bootstrap] 正在检查 Java 运行时",
            // 下载依赖（注意核心探针夹在中间）
            "正在下载依赖库",
            "正在服务器上安装 Fabric Loader",
            "正在加载 Minecraft 26.3 及依赖",        // ← 核心探针，夹在下载中间
            "缺少映射！正在重新映射",
            "正在加载 4 个模组:",
            "正在下载所需文件",
            "正在下载 library: net.fabricmc:xxx",
            "正在生成服务端启动 JAR",
            // 启动 Java（全程只 1 行）
            "[startMc] java 路径: /data/.../bin/java",
            "[bootstrap] 自动修复完成，共处理 6 项",   // ← App 引导日志，会拖回 7%
            // 启动网络（全程只 1 行）
            "正在启动 Minecraft 世界",               // 启动网络档
            // 创建世界
            "正在加载世界 \"world\"",
            "正在准备出生点区域: 100%",
            // 完成
            "完成（2.483 秒）！"
        )

        val trace = foldPhases(realLog, StartupPhase.PreparingEnvironment)

        // 单调不减
        trace.zipWithNext { a, b ->
            assertTrue("阶段回退：$a(${a.progress}) → $b(${b.progress})", a.progress <= b.progress)
        }
        // 关键：不能被夹在下载中间的核心探针带得 40%↔62% 来回跳
        val oscillation = trace.windowed(2).count { (a, b) -> b.progress < a.progress }
        assertEquals("进度条出现了 $oscillation 次回退（左右跳）", 0, oscillation)
        // 关键：最终必须能走到 Ready，且中途经过两个高阶段
        assertEquals(StartupPhase.Ready, trace.last())
        assertTrue("必须经过启动网络档", trace.contains(StartupPhase.StartingNetwork))
        assertTrue("必须经过创建世界档", trace.contains(StartupPhase.CreatingWorld))
    }

    @Test
    fun singleLinePhasesAreStillAdopted() {
        // 症状②的回归：只出现 1 行的阶段必须能被采纳。
        // 旧实现用"连续 3 行确认"，会把只出现 1~2 行的阶段整档吞掉，
        // 进度条直接从 62% 蹦到 100%（用户："然后直接进度条完成"）。
        //
        // ⚠️ 这里**不**包含 StartingJava 档，因为它的 progress(0.20) 低于
        // DownloadingDependencies(0.40)。本次启动既然真的要下载依赖，
        // "下载 40%" 就会先命中，20% 那一档在"只增不减"下必然被跳过。
        // 这不是缺陷：用户看到的仍是"下载→加载核心→启动网络→创建世界→完成"
        // 这条不会倒退的路径。StartingJava 只在**不需要下载**的快速启动里出现
        // （见 javaStartupOnWarmCacheIsAdopted）。
        val trace = foldPhases(
            listOf(
                "正在下载依赖库",
                "正在加载 4 个模组:",                        // 只 1 行
                "正在启动 Minecraft 世界",                   // 只 1 行
                "正在加载世界 \"world\"",                    // 只 1 行
                "完成（2.483 秒）！"
            ),
            StartupPhase.PreparingEnvironment
        )
        // 每一档都命中，一个都不能漏
        assertEquals(
            listOf(
                StartupPhase.PreparingEnvironment,
                StartupPhase.DownloadingDependencies,
                StartupPhase.LoadingCore,
                StartupPhase.StartingNetwork,
                StartupPhase.CreatingWorld,
                StartupPhase.Ready
            ),
            trace
        )
        // 高阶段之间必须是相邻单档跳（没有"跳过"）。
        // 首跳 PreparingEnvironment(7%) → DownloadingDependencies(40%) 例外：
        // 中间那档 StartingJava(20%) 见上方注释，本次启动必然被跳过。
        val order = StartupPhase.entries.sortedBy { it.progress }
        trace.drop(1).zipWithNext { a, b ->
            assertEquals("$a → $b 不是相邻单档跳，进度条会显得在跳段",
                1, order.indexOf(b) - order.indexOf(a))
        }
        assertTrue("末段必须完整走到 Ready", trace.last() == StartupPhase.Ready)
    }

    @Test
    fun javaStartupOnWarmCacheIsAdopted() {
        // 缓存已就绪（不下载依赖）时，启动 Java 档必须正常显示。
        // 这也是"启动 Java 只 1 行"的场景，旧防抖会把它吞掉。
        val trace = foldPhases(
            listOf(
                "[startMc] java 路径: /data/.../bin/java",
                "正在加载 4 个模组:",
                "正在启动 Minecraft 世界",
                "正在加载世界 \"world\"",
                "完成（2.483 秒）！"
            ),
            StartupPhase.PreparingEnvironment
        )
        assertEquals(
            listOf(
                StartupPhase.PreparingEnvironment,
                StartupPhase.StartingJava,
                StartupPhase.LoadingCore,
                StartupPhase.StartingNetwork,
                StartupPhase.CreatingWorld,
                StartupPhase.Ready
            ),
            trace
        )
    }

    @Test
    fun bootstrapLogsAfterJavaStartupDoNotDragProgressBack() {
        // 症状②的另一半：App 引导日志（准备环境 7%）出现在启动 Java 之后，
        // 会算成 7%，但在"只增不减"下必须被忽略。
        //
        // 注意 trace 末值取决于日志里出现过什么：这里先有"下载依赖库"(40%)，
        // 后面 [startMc] java(20%) 与 [bootstrap](7%) 都低于它 → 一律忽略，
        // 所以末值停在 40%，关键断言是**绝不回退**。
        val trace = foldPhases(
            listOf(
                "正在下载依赖库",
                "[startMc] java 路径: /data/.../bin/java",
                "[bootstrap] 正在准备运行环境",   // 会算成 7%，但必须被忽略
                "[bootstrap] 自动修复完成，共处理 6 项"
            ),
            StartupPhase.PreparingEnvironment
        )
        trace.zipWithNext { a, b ->
            assertTrue("引导日志把进度拽回去了：$a(${a.progress}) → $b(${b.progress})",
                b.progress >= a.progress)
        }
        assertTrue("下载档之后不应回落到准备环境", trace.last().progress >= 0.40f)
    }
}
