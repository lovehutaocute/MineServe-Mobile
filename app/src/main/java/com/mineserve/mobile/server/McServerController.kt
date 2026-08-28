package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.R
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.MultiThreadDownloader
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.MinecraftVersionNormalizer
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.jar.JarFile
import java.util.Locale

internal fun selectNeoForgeVersion(minecraftVersion: String, versions: List<String>): String? {
    val prefix = minecraftVersion.trim().removePrefix("1.") + "."
    return versions.asSequence()
        .filter { it.startsWith(prefix) }
        .maxWithOrNull(Comparator { left, right ->
            val leftParts = Regex("\\d+").findAll(left).map { it.value.toInt() }.toList()
            val rightParts = Regex("\\d+").findAll(right).map { it.value.toInt() }.toList()
            leftParts.zip(rightParts).firstOrNull { it.first != it.second }
                ?.let { (a, b) -> a.compareTo(b) }
                ?: leftParts.size.compareTo(rightParts.size).takeIf { it != 0 }
                ?: left.compareTo(right)
        })
}

/** Resolve the actual PNX entry point instead of assuming every release is legacy Nukkit. */
internal fun powerNukkitXMainClass(jarFile: File): String? = runCatching {
    JarFile(jarFile).use { jar ->
        listOf(
            "org/powernukkitx/Server.class" to "org.powernukkitx.Server",
            "org/powernukkitx/JarStart.class" to "org.powernukkitx.JarStart",
            "cn/nukkit/Nukkit.class" to "cn.nukkit.Nukkit",
            "cn/nukkit/JarStart.class" to "cn.nukkit.JarStart"
        ).firstOrNull { (entry, _) -> jar.getEntry(entry) != null }?.second
    }
}.getOrNull()

/** Uses imported Bukkit dependencies when the core JAR is not self-contained. */
internal fun bukkitLaunchArguments(serverDir: File, coreJar: File): String? {
    fun hasClass(jar: File, entry: String): Boolean = runCatching {
        JarFile(jar).use { it.getEntry(entry) != null }
    }.getOrDefault(false)

    if (hasClass(coreJar, "joptsimple/OptionException.class")) return null

    val libraries = listOf("libraries", "lib", "libs")
        .map { File(serverDir, it) }
        .filter(File::isDirectory)
        .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension.equals("jar", true) }.toList() }
        .distinct()
    if (libraries.none { hasClass(it, "joptsimple/OptionException.class") }) {
        throw RuntimeException("导入的 ${coreJar.name} 缺少 jopt-simple 依赖；请导入包含 libraries 或 lib 目录的完整服务端文件夹")
    }
    val mainClass = runCatching {
        JarFile(coreJar).use { it.manifest?.mainAttributes?.getValue("Main-Class") }
    }.getOrNull()?.takeIf { it.startsWith("org.bukkit.craftbukkit.") }
        ?: "org.bukkit.craftbukkit.Main".takeIf { hasClass(coreJar, "org/bukkit/craftbukkit/Main.class") }
        ?: throw RuntimeException("导入的 ${coreJar.name} 没有可识别的 CraftBukkit 启动入口")
    fun quote(value: String) = "'${value.replace("'", "'\\''")}'"
    return "-cp ${listOf(coreJar).plus(libraries).joinToString(":") { quote(it.absolutePath) }} $mainClass"
}

/**
 * MC 服务控制器（生产化）：
 *  - 一键安装依赖（JDK/tmux/wget 等）
 *  - 4 种服务端核心下载（Paper/Fabric/Forge/Vanilla），动态解析最新版本
 *  - 启停控制
 */
class McServerController(
    private val termux: TermuxRuntime,
    private val repo: ServerRepository
) {
    data class StartupFailure(val code: Int, val reportPath: String?, val detail: String)
    private val _startupFailures = MutableSharedFlow<StartupFailure>(extraBufferCapacity = 8)
    val startupFailures = _startupFailures.asSharedFlow()

    private val powerNukkitXRepos = listOf(
        "PowerNukkitX/PowerNukkitX",
        "PowerNukkitX/PowerNukkitX-Legacy"
    )

    @Volatile
    private var powerNukkitXVersionCache: Map<String, CoreVersionOption> = emptyMap()

    data class CoreVersionOption(
        val version: String,
        val supportedGameVersion: String? = null,
        val sourceRepository: String? = null,
        val releaseTag: String? = null,
        val assetUrl: String? = null,
        val assetDigest: String? = null,
        val publishedAt: String = ""
    )

    private suspend fun runInstallerWithRetry(
        jarPath: String,
        serverDir: File,
        label: String,
        tempDir: File,
        javaVersion: JavaVersion
    ): Int {
        var lastCode = 1
        repeat(3) { attempt ->
            // Installer runs before start(), so repair the selected JDK wrapper here as well.
            if (javaVersion != JavaVersion.Java8) termux.autoRepairRuntime(javaVersion, needsFonts = false)
            lastCode = if (javaVersion == JavaVersion.Java8) {
                termux.runJava8Installer(jarPath, serverDir)
            } else {
                termux.execOnce(
                    "java", "-Djava.io.tmpdir=${tempDir.absolutePath}",
                    "-jar", jarPath, "--installServer", serverDir.absolutePath
                )
            }
            if (lastCode == 0) return 0
            if (attempt < 2) {
                termux.emitLog("[install] $label 安装失败，${attempt + 2}/3 次重试前等待网络恢复...")
                delay((attempt + 1) * 2000L)
            }
        }
        return lastCode
    }

    private val json = Json { ignoreUnknownKeys = true }

    /** 崩溃报告管理器：MC 进程异常退出时捕获日志生成报告 */
    private val crashReportManager = CrashReportManager(termux)

    @Volatile
    private var isInstalling = false

    /** 崩溃自动重启的最大重试次数，防止无限循环 */
    private val maxRestartAttempts = 3
    @Volatile
    private var restartAttempts = 0
    @Volatile
    private var startupDeadlineMs = 0L
    @Volatile
    private var processStartedAtMs = 0L
    @Volatile
    private var lastStartupFailure: StartupFailure? = null
    @Volatile
    private var lastStartupFailureAtMs = 0L

    /** 最近一次非零退出的失败信息：App 重启/ViewModel 重建后仍可补弹，maxAgeMs 内有效。 */
    fun recentStartupFailure(maxAgeMs: Long = 5 * 60_000L): StartupFailure? {
        val failure = lastStartupFailure ?: return null
        return if (System.currentTimeMillis() - lastStartupFailureAtMs <= maxAgeMs) failure else null
    }

    /**
     * 一键安装依赖
     * @param onSpeed apt 下载速度回调（bytes/s）
     */
    suspend fun installDependencies(onSpeed: ((Long) -> Unit)? = null) = withContext(Dispatchers.IO) {
        // 防止并发调用（ViewModel 和 start() 可能同时调用）
        if (isInstalling) return@withContext false
        isInstalling = true
        // 监控 apt 缓存目录大小变化，计算下载速度
        val aptCacheDir = File(termux.installer.rootDir, "var/cache/apt/archives")
        var monitoring = true
        val monitorJob = launch {
            var lastSize = aptCacheDir.getSizeBytes()
            var lastTime = System.currentTimeMillis()
            while (monitoring) {
                delay(500)
                val nowSize = aptCacheDir.getSizeBytes()
                val now = System.currentTimeMillis()
                val elapsedSec = (now - lastTime) / 1000.0
                val speed = if (elapsedSec > 0) ((nowSize - lastSize) / elapsedSec).toLong() else 0L
                onSpeed?.invoke(speed.coerceAtLeast(0))
                lastSize = nowSize
                lastTime = now
            }
        }
        try {
            // 先确保 Termux 环境已初始化
            if (!termux.isReady()) {
                throw RuntimeException("Termux 环境未初始化，请等待初始化完成")
            }
            termux.fixRootfsPermissions()
            if (!termux.prepareAptPackages("wget", "fontconfig", "ttf-dejavu")) {
                return@withContext false
            }
            val steps = InstallStep.values().filter { it != InstallStep.Jdk }
            steps.forEachIndexed { idx, step ->
                repo.markStep(step, StepStatus.Active, idx * (100 / steps.size))
                val code = when (step) {
                    InstallStep.Jdk -> 0
                    InstallStep.Wget -> termux.execOnce(
                        "apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y",
                        "wget", "fontconfig", "ttf-dejavu"
                    )
                    InstallStep.Frp -> termux.execOnce("/system/bin/sh", "-c",
                        "which frpc >/dev/null 2>&1 || (apt-cache show frp >/dev/null 2>&1 && apt-get -o DPkg::Lock::Timeout=60 install --allow-unauthenticated -y frp)")
                    InstallStep.Rclone -> if (termux.prepareAptPackages("rclone")) termux.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "rclone") else 1
                    InstallStep.Proot -> if (termux.prepareAptPackages("proot")) termux.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "proot") else 1
                }
                if (code == 0) {
                    termux.repairInstalledCommands()
                    if (step == InstallStep.Wget) {
                        termux.repairFontRuntime()
                    }
                }
                if (code == 0 && termux.isDependencyInstalled(step)) {
                    repo.markStep(step, StepStatus.Done, (idx + 1) * (100 / steps.size))
                } else {
                    if (code == 0) termux.emitLog("[install] ${step.label} verification failed; the dependency card will remain visible")
                    repo.markStep(step, StepStatus.Wait, idx * (100 / steps.size))
                    return@withContext false
                }
            }
            true
        } finally {
            monitoring = false
            monitorJob.cancel()
            onSpeed?.invoke(0L)
            isInstalling = false
        }
    }

    /** 递归计算目录总大小（bytes） */
    private fun File.getSizeBytes(): Long {
        if (!exists()) return 0L
        if (isFile) return length()
        var total = 0L
        listFiles()?.forEach { total += it.getSizeBytes() }
        return total
    }

    /**
     * 下载服务端核心到指定自定义名称的独立目录。
     * 下载到 home/servers/{dirName}/server.jar，使用 Java HTTP 直下载，不依赖 wget。
     * 下载成功后自动将核心信息添加到 config.installedCores 并设为 activeCore。
     *
     * @param customName 用户自定义名称（显示用），如 "生存服-1.20.4"
     * @return 生成的 dirName（文件夹名）
     */
    suspend fun downloadCore(config: McConfig, customName: String) = withContext(Dispatchers.IO) {
        downloadCore(config, customName) { _, _, _ -> }
    }

    /**
     * 下载服务端核心（带进度回调版本）。
     * @param onProgress 回调参数：(已下载字节, 总字节, 速度 bytes/s)，总字节为 -1 表示未知
     */
    suspend fun downloadCore(config: McConfig, customName: String, onProgress: (Long, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val normalizedConfig = config.copy(
            mcVersion = MinecraftVersionNormalizer.forCore(config.selectedCore, config.mcVersion)
        )
        if (normalizedConfig.mcVersion != config.mcVersion) {
            termux.emitLog("[version] Minecraft target normalized from ${config.mcVersion} to ${normalizedConfig.mcVersion}")
        }
        if (normalizedConfig.selectedCore.needsInstaller && !termux.isJavaInstalled(normalizedConfig.selectedJavaVersion)) {
            throw RuntimeException("${normalizedConfig.selectedJavaVersion.displayName} is not installed. Install and verify it before running ${normalizedConfig.selectedCore.displayName} installer.")
        }
        val dirName = sanitizeDirName(customName)
        val jarPath = termux.serverJarFileFor(dirName).absolutePath
        downloadCoreTo(jarPath, normalizedConfig, dirName, onProgress)
        // 在新核心目录下创建 eula.txt 和 plugins/ 目录
        val serverDir = termux.serverDirFor(dirName)
        if (normalizedConfig.selectedCore == ServerCore.PowerNukkitX) {
            val properties = File(serverDir, "server.properties")
            val current = if (properties.exists()) properties.readText() else ""
            properties.writeText(updatePowerNukkitXProperties(current, normalizedConfig.localPort))
            PowerNukkitXConfigManager(termux).updatePort(dirName, normalizedConfig.localPort)
        }
        // NeoForge/Quilt：下载的是 installer.jar，执行安装命令生成启动环境（首次需下载依赖）
        if (normalizedConfig.selectedCore.needsInstaller) {
            termux.emitLog("[install] 正在执行 ${config.selectedCore.displayName} installer，首次安装需下载依赖，请耐心等待...")
            val installerTempDir = File(termux.installer.rootDir, "tmp").apply { mkdirs() }
            when (normalizedConfig.selectedCore) {
                ServerCore.Forge -> {
                    val code = runInstallerWithRetry(
                        jarPath, serverDir, normalizedConfig.selectedCore.displayName, installerTempDir,
                        normalizedConfig.selectedJavaVersion
                    )
                    if (code != 0) throw RuntimeException("Forge installer 执行失败 (exit=$code)")
                }
                ServerCore.NeoForge -> {
                    val code = runInstallerWithRetry(
                        jarPath, serverDir, normalizedConfig.selectedCore.displayName, installerTempDir,
                        normalizedConfig.selectedJavaVersion
                    )
                    if (code != 0) throw RuntimeException("NeoForge installer 执行失败 (exit=$code)")
                }
                ServerCore.Quilt -> {
                    val code = if (normalizedConfig.selectedJavaVersion == JavaVersion.Java8) {
                        val guestJar = "/srv/mineserve/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
                        termux.runJava8Command(
                            serverDir,
                            "export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-arm64; cd /srv/mineserve && " +
                                "exec /usr/bin/java -Djava.io.tmpdir=/tmp -jar '$guestJar' " +
                                "install server '${normalizedConfig.mcVersion}' --install-dir=/srv/mineserve --download-server"
                        )
                    } else {
                        termux.execOnce(
                            "java", "-jar", jarPath, "install", "server", normalizedConfig.mcVersion,
                            "--install-dir=${serverDir.absolutePath}", "--download-server"
                        )
                    }
                    if (code != 0) throw RuntimeException("Quilt installer 执行失败 (exit=$code)")
                }
                else -> {}
            }
            requireInstallerLaunchArtifacts(normalizedConfig.selectedCore, serverDir)
            termux.emitLog("[install] ${normalizedConfig.selectedCore.displayName} 安装完成")
        }
        val eula = File(serverDir, "eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
        File(serverDir, "plugins").mkdirs()
        // 添加到已安装列表
        val newCore = InstalledCore(
            name = customName,
            core = normalizedConfig.selectedCore,
            version = normalizedConfig.mcVersion,
            dirName = dirName
        )
        val updated = config.installedCores.filter { it.dirName != dirName } + newCore
        repo.saveConfig(config.copy(
            installedCores = updated,
            activeCoreName = customName
        ))
        dirName
    }

    /**
     * 动态解析下载 URL（在 APP 层用 Java HTTP + JSON 解析）。
     * 修复点：
     *  - Paper: 调用 PaperMC v2 API 获取最新 SUCCESS build 号
     *  - Vanilla: 调用 Mojang version_manifest_v2.json → 版本元数据 JSON → server.jar URL
     *  - Fabric: 调用 Fabric meta API 获取最新 loader/installer 版本
     *  - Forge: 调用 Forge maven-metadata.xml 获取推荐版本
     */
    private fun resolveDownloadUrl(core: ServerCore, version: String): String {
        return when (core) {
            ServerCore.Paper -> resolvePaperUrl(version)
            ServerCore.Purpur -> resolvePurpurUrl(version)
            ServerCore.Leaves -> resolveLeavesUrl(version)
            ServerCore.Leaf -> resolveLeafUrl(version)
            ServerCore.Spigot -> "https://cdn.getbukkit.org/spigot/spigot-$version.jar"
            ServerCore.CraftBukkit -> "https://cdn.getbukkit.org/craftbukkit/craftbukkit-$version.jar"
            ServerCore.Fabric -> resolveFabricUrl(version)
            ServerCore.Forge -> resolveForgeUrl(version)
            ServerCore.NeoForge -> resolveNeoForgeUrl(version)
            ServerCore.Quilt -> resolveQuiltUrl(version)
            ServerCore.Vanilla -> resolveVanillaUrl(version)
            ServerCore.Velocity -> resolveVelocityUrl(version)
            ServerCore.BungeeCord -> resolveBungeeUrl()
            ServerCore.PowerNukkitX -> resolvePowerNukkitXUrl(version)
            ServerCore.Unknown -> throw IllegalArgumentException("未知核心类型，无法解析下载地址")
        }
    }

    private fun resolveDownloadUrls(core: ServerCore, version: String): List<String> {
        val official = resolveDownloadUrl(core, version)
        val mirrors = when (core) {
            ServerCore.Vanilla -> listOf("https://bmclapi2.bangbang93.com/version/$version/server")
            ServerCore.Forge -> {
                val coordinate = official.substringAfter("/forge/").substringBefore("/")
                val forgeVersion = coordinate.removePrefix("$version-")
                listOf("https://bmclapi2.bangbang93.com/forge/download?mcversion=$version&version=$forgeVersion&category=installer&format=jar")
            }
            ServerCore.Fabric -> listOf(official.replace("https://meta.fabricmc.net/", "https://bmclapi2.bangbang93.com/fabric-meta/"))
            else -> emptyList()
        }
        return if (core == ServerCore.PowerNukkitX) {
            if (official.contains("maven.org") || official.contains("maven.aliyun.com") || official.contains("huaweicloud.com")) {
                listOf(
                    official,
                    official.replace("https://repo1.maven.org/maven2", "https://maven.aliyun.com/repository/central"),
                    official.replace("https://repo1.maven.org/maven2", "https://repo.huaweicloud.com/repository/maven")
                ).distinct()
            } else {
                listOf(
                    "https://ghfast.top/$official",
                    "https://gh-proxy.com/$official",
                    "https://mirror.ghproxy.com/$official",
                    "https://ghproxy.net/$official",
                    "https://github.moeyy.xyz/$official",
                    official
                ).distinct()
            }
        } else (mirrors + official).distinct()
    }

    // ── PaperMC v3 API（fill.papermc.io）：动态获取最新 STABLE build 号 ───

    private fun resolvePaperUrl(version: String): String {
        // v3 API：返回 builds 数组，每个 build 有 channel（STABLE/ALPHA/BETA/RECOMMENDED）和 downloads.server:default.url
        val buildsUrl = "https://fill.papermc.io/v3/projects/paper/versions/$version/builds"
        val builds = fetchJsonElement(buildsUrl).jsonArray
        if (builds.isEmpty()) {
            throw RuntimeException("PaperMC v3: no builds for version $version")
        }

        // 优先级：STABLE > RECOMMENDED > BETA > ALPHA
        // 在同一 channel 中选 id 最大的（最新）
        val channelPriority = mapOf("STABLE" to 4, "RECOMMENDED" to 3, "BETA" to 2, "ALPHA" to 1)
        var bestEntry: JsonObject? = null
        var bestPriority = 0
        var bestId = -1

        for (entry in builds) {
            val obj = entry.jsonObject
            val channel = obj["channel"]?.jsonPrimitive?.content ?: continue
            val id = obj["id"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val priority = channelPriority[channel] ?: 0
            if (priority > bestPriority || (priority == bestPriority && id > bestId)) {
                // 确保有 server:default 下载
                val hasServer = obj["downloads"]?.jsonObject
                    ?.get("server:default")?.jsonObject != null
                if (hasServer) {
                    bestPriority = priority
                    bestId = id
                    bestEntry = obj
                }
            }
        }

        if (bestEntry == null) {
            // 兜底：取数组第一个
            bestEntry = builds.first().jsonObject
        }

        val downloadUrl = bestEntry!!["downloads"]?.jsonObject
            ?.get("server:default")?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: throw RuntimeException("PaperMC v3: no server download URL for version $version")

        val buildId = bestEntry["id"]?.jsonPrimitive?.content ?: "?"
        val channel = bestEntry["channel"]?.jsonPrimitive?.content ?: "?"
        Log.i(TAG, "PaperMC v3: selected build $buildId (channel=$channel) for $version")
        return downloadUrl
    }

    // ── Mojang Vanilla：version_manifest_v2.json → 版本 JSON → server.jar URL ─

    private fun resolveVanillaUrl(version: String): String {
        // 1. 获取版本清单
        val manifest = fetchJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val versions = manifest["versions"]?.jsonArray
            ?: throw RuntimeException("Mojang: no versions in manifest")

        // 2. 找到目标版本
        var versionMetaUrl: String? = null
        for (v in versions) {
            val obj = v.jsonObject
            if (obj["id"]?.jsonPrimitive?.content == version) {
                versionMetaUrl = obj["url"]?.jsonPrimitive?.content
                break
            }
        }
        if (versionMetaUrl == null) {
            throw RuntimeException("Mojang: version $version not found in manifest")
        }

        // 3. 获取版本元数据，提取 server.jar 下载 URL
        val versionMeta = fetchJson(versionMetaUrl)
        val serverUrl = versionMeta["downloads"]?.jsonObject
            ?.get("server")?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: throw RuntimeException("Mojang: no server.jar URL for version $version")
        return serverUrl
    }

    // ── Fabric：动态获取最新 loader + installer 版本 ────────────────

    private fun resolveFabricUrl(version: String): String {
        // Fabric loader API 返回 JSON 数组，需用 fetchJsonElement
        val loaderResp = fetchJsonElement("https://meta.fabricmc.net/v2/versions/loader")
        val loaders = loaderResp.jsonArray
        val loaderVer = loaders.firstOrNull()
            ?.jsonObject?.get("version")?.jsonPrimitive?.content
            ?: "0.16.5"

        // 获取最新 installer 版本
        val installerResp = fetchJsonElement("https://meta.fabricmc.net/v2/versions/installer")
        val installers = installerResp.jsonArray
        val installerVer = installers.firstOrNull()
            ?.jsonObject?.get("version")?.jsonPrimitive?.content
            ?: "0.11.2"

        return "https://meta.fabricmc.net/v2/versions/loader/$version/$loaderVer/$installerVer/server/jar"
    }

    // ── Forge：从 maven-metadata.xml 获取推荐版本号 ──────────────────

    private fun resolveForgeUrl(version: String): String {
        // Forge 没有标准 JSON API，用 maven-metadata.xml 解析
        // 简化：使用 Forge 官方 promotions API 获取推荐版本
        val promotionsUrl = "https://files.minecraftforge.net/net/minecraftforge/forge/promotions_slim.json"
        val resp = fetchJson(promotionsUrl)
        val promos = resp["promos"]?.jsonObject
            ?: throw RuntimeException("Forge: no promotions found")

        // 查找 "{version}-recommended" 或 "{version}-latest"
        val recommendedKey = "$version-recommended"
        val latestKey = "$version-latest"
        val forgeVer = promos[recommendedKey]?.jsonPrimitive?.content
            ?: promos[latestKey]?.jsonPrimitive?.content
            ?: throw RuntimeException("Forge: no build for MC $version")

        return "https://maven.minecraftforge.net/net/minecraftforge/forge/$version-$forgeVer/forge-$version-$forgeVer-installer.jar"
    }

    // ── Purpur：官方 API 直链（latest build 302 重定向到实际 jar） ──

    private fun resolvePurpurUrl(version: String): String {
        return "https://api.purpurmc.org/v2/purpur/$version/latest/download"
    }

    private fun resolveLeavesUrl(version: String): String {
        val build = fetchJson("https://api.leavesmc.org/v2/projects/leaves/versions/$version/builds/latest")
        val buildNumber = build["build"]?.jsonPrimitive?.content
            ?: throw RuntimeException("Leaves: no build for $version")
        val name = build["downloads"]?.jsonObject?.get("application")?.jsonObject
            ?.get("name")?.jsonPrimitive?.content
            ?: throw RuntimeException("Leaves: no application download for $version")
        return "https://api.leavesmc.org/v2/projects/leaves/versions/$version/builds/$buildNumber/downloads/$name"
    }

    private fun resolveLeafUrl(version: String): String {
        val versions = fetchJson("https://api.leafmc.one/v2/projects/leaf/versions/$version")
        val build = versions["builds"]?.jsonArray
            ?.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
            ?.maxOrNull()
            ?: throw RuntimeException("Leaf: no build for $version")
        val buildJson = fetchJson("https://api.leafmc.one/v2/projects/leaf/versions/$version/builds/$build")
        val name = buildJson["downloads"]?.jsonObject?.get("primary")?.jsonObject
            ?.get("name")?.jsonPrimitive?.content
            ?: throw RuntimeException("Leaf: no primary download for $version")
        return "https://api.leafmc.one/v2/projects/leaf/versions/$version/builds/$build/downloads/$name"
    }

    // ── Velocity：PaperMC v3 API（取最新 build 的 application 下载） ──

    private fun resolveVelocityUrl(version: String): String {
        val buildsUrl = "https://fill.papermc.io/v3/projects/velocity/versions/$version/builds"
        val builds = fetchJsonElement(buildsUrl).jsonObject["builds"]?.jsonArray
            ?: throw RuntimeException("Velocity: no builds for version $version")
        val best = builds.lastOrNull()?.jsonObject
            ?: throw RuntimeException("Velocity: no build for version $version")
        return best["downloads"]?.jsonObject
            ?.get("application")?.jsonObject
            ?.get("url")?.jsonPrimitive?.content
            ?: throw RuntimeException("Velocity: no application download for version $version")
    }

    // ── BungeeCord：md-5 Jenkins 直链（无版本概念，取最新构建） ──

    private fun resolveBungeeUrl(): String {
        return "https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/artifact/bootstrap/target/BungeeCord.jar"
    }

    private fun resolvePowerNukkitXUrl(version: String): String {
        powerNukkitXVersionCache[version.trim().removePrefix("v").removePrefix("V").lowercase(Locale.ROOT)]
            ?.assetUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { return it }
        val normalized = version.trim().removePrefix("v").removePrefix("V")
        if (normalized.contains("-r") || normalized.endsWith("-PNX", ignoreCase = true)) {
            return "https://repo1.maven.org/maven2/cn/powernukkitx/powernukkitx/$normalized/powernukkitx-$normalized-shaded.jar"
        }
        val release = findPowerNukkitXRelease(version)
        return powerNukkitXAsset(release)
            ?.get("browser_download_url")?.jsonPrimitive?.content
            ?: throw RuntimeException("PowerNukkitX $version: release has no powernukkitx.jar asset")
    }

    // ── NeoForge：maven-metadata 按所选 MC 版本匹配 NeoForge 版本 installer ──

    private fun resolveNeoForgeUrl(version: String): String {
        val xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        val versions = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        val ver = selectNeoForgeVersion(version, versions)
            ?: Regex("<release>([^<]+)</release>").find(xml)?.groupValues?.get(1)
            ?: throw RuntimeException("NeoForge: no version for MC $version")
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
    }

    private fun updatePowerNukkitXProperties(current: String, port: Int): String {
        fun setProperty(text: String, key: String, value: String): String {
            val line = "$key=$value"
            val pattern = Regex("(?m)^${Regex.escape(key)}=.*$")
            return if (pattern.containsMatchIn(text)) pattern.replace(text, line)
            else "$text${if (text.isNotEmpty() && !text.endsWith("\n")) "\n" else ""}$line\n"
        }
        return setProperty(setProperty(current, "server-port", port.toString()), "allow-shaded", "true")
    }

    // ── Quilt：meta API 取 installer 版本（quilt-installer 独立版本号，与 loader 不同） ──

    private fun resolveQuiltUrl(version: String): String {
        val resp = fetchJson("https://meta.quiltmc.org/v3/versions/installer")
        val installerVer = resp.jsonArray.firstOrNull()?.jsonObject
            ?.get("version")?.jsonPrimitive?.content
            ?: throw RuntimeException("Quilt: no installer version")
        return "https://maven.quiltmc.org/repository/release/org/quiltmc/quilt-installer/$installerVer/quilt-installer-$installerVer.jar"
    }

    /** 读取原始文本（用于 XML 等非 JSON 接口） */
    private fun fetchText(urlStr: String): String {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "MineServeMobile/1.0 (Android)")
            val code = conn.responseCode
            if (code !in 200..299) throw RuntimeException("HTTP $code")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    // ── JSON HTTP 工具 ──────────────────────────────────────────────

    private fun fetchJson(urlStr: String): JsonObject = fetchJsonElement(urlStr).jsonObject

    /**
     * 获取指定核心的可用版本列表（供 DownloadScreen 选择）。
     * Paper: 从 PaperMC API 获取该核心支持的版本
     * Vanilla: 从 Mojang manifest 获取所有版本
     * Fabric: 返回主流预设版本（Fabric loader 不依赖具体 MC 版本列表 API）
     * Forge: 从 Forge maven-metadata.xml 获取
     */
    suspend fun fetchVersions(core: ServerCore): List<String> = withContext(Dispatchers.IO) {
        fetchVersionOptions(core).map { it.version }
    }

    suspend fun fetchVersionOptions(core: ServerCore): List<CoreVersionOption> = withContext(Dispatchers.IO) {
        when (core) {
            ServerCore.Paper -> fetchPaperVersions().map(::CoreVersionOption)
            ServerCore.Purpur -> fetchPurpurVersions().map(::CoreVersionOption)
            ServerCore.Leaves -> fetchLeavesVersions().map(::CoreVersionOption)
            ServerCore.Leaf -> fetchLeafVersions().map(::CoreVersionOption)
            ServerCore.Spigot, ServerCore.CraftBukkit -> DEFAULT_MC_VERSIONS.map(::CoreVersionOption)
            ServerCore.Vanilla -> fetchVanillaVersions().map(::CoreVersionOption)
            ServerCore.Fabric -> fetchFabricVersions().map(::CoreVersionOption)
            ServerCore.Forge -> fetchForgeVersions().map(::CoreVersionOption)
            ServerCore.NeoForge -> fetchNeoForgeVersions().map(::CoreVersionOption)
            ServerCore.Quilt -> fetchQuiltVersions().map(::CoreVersionOption)
            ServerCore.Velocity -> fetchVelocityVersions().map(::CoreVersionOption)
            ServerCore.BungeeCord -> fetchBungeeVersions().map(::CoreVersionOption)
            ServerCore.PowerNukkitX -> fetchPowerNukkitXVersionOptions()
            ServerCore.Unknown -> emptyList()
        }
    }

    private fun fetchPurpurVersions(): List<String> {
        // Purpur API：{versions: [...]}
        val resp = fetchJson("https://api.purpurmc.org/v2/purpur")
        return resp["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { !it.contains("rc") && !it.contains("pre") }
            ?.sortedDescending() ?: DEFAULT_MC_VERSIONS
    }

    private fun fetchLeavesVersions(): List<String> = fetchProjectVersions("https://api.leavesmc.org/v2/projects/leaves")

    private fun fetchLeafVersions(): List<String> = fetchProjectVersions("https://api.leafmc.one/v2/projects/leaf")

    private fun fetchProjectVersions(url: String): List<String> = runCatching {
        fetchJson(url)["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.filter { it.isNotBlank() }
            ?.sortedDescending()
            ?.take(30)
            ?.takeIf { it.isNotEmpty() }
    }.getOrNull() ?: DEFAULT_MC_VERSIONS

    private fun fetchVelocityVersions(): List<String> {
        // PaperMC v3：velocity versions 数组
        val resp = fetchJson("https://fill.papermc.io/v3/projects/velocity")
        return resp["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.sortedDescending() ?: DEFAULT_MC_VERSIONS
    }

    private fun fetchBungeeVersions(): List<String> = listOf("latest")

    private fun fetchPowerNukkitXVersionOptions(): List<CoreVersionOption> {
        val releaseObjects = powerNukkitXRepos.flatMap { repoName ->
            runCatching {
                fetchGithubJsonElement("https://api.github.com/repos/$repoName/releases?per_page=100")
                    .jsonArray.map { it.jsonObject }
            }.getOrElse { e ->
                termux.emitLog("[download] PowerNukkitX 版本源失败: $repoName (${e.message ?: "unknown"})")
                emptyList()
            }
        }
        val releaseOptions = releaseObjects
            .filter {
                !(it["draft"]?.jsonPrimitive?.boolean ?: false) &&
                    !(it["prerelease"]?.jsonPrimitive?.boolean ?: false)
            }
            .mapNotNull { release ->
                val rawTag = release["tag_name"]?.jsonPrimitive?.content?.trim() ?: return@mapNotNull null
                val tag = rawTag.removePrefix("v").removePrefix("V").trim()
                val assets = release["assets"]?.jsonArray.orEmpty().map { it.jsonObject }
                val asset = assets.firstOrNull { it["name"]?.jsonPrimitive?.content.equals("powernukkitx.jar", true) }
                    ?: assets.firstOrNull { it["name"]?.jsonPrimitive?.content?.endsWith(".jar", true) == true }
                val body = release["body"]?.jsonPrimitive?.content.orEmpty()
                val name = release["name"]?.jsonPrimitive?.content.orEmpty()
                val assetNames = assets.mapNotNull { it["name"]?.jsonPrimitive?.content }
                CoreVersionOption(
                    tag,
                    parsePowerNukkitXGameVersion("$name\n$body", assetNames),
                    sourceRepository = release["html_url"]?.jsonPrimitive?.content
                        ?.removePrefix("https://github.com/")?.substringBefore("/releases"),
                    releaseTag = rawTag,
                    assetUrl = asset?.get("browser_download_url")?.jsonPrimitive?.content,
                    assetDigest = asset?.get("digest")?.jsonPrimitive?.content?.removePrefix("sha256:"),
                    publishedAt = release["published_at"]?.jsonPrimitive?.content.orEmpty()
                )
            }
        // PowerNukkitX-Legacy has no GitHub Releases; its historical cores are published to Maven Central.
        val legacyMavenOptions = fetchPowerNukkitXLegacyMavenOptions()
        val options = (releaseOptions + legacyMavenOptions)
            .distinctBy { it.version.lowercase(Locale.ROOT) }
            .sortedWith(compareByDescending<CoreVersionOption> { it.publishedAt }.thenBy { it.version })
        if (options.isNotEmpty()) {
            powerNukkitXVersionCache = options.associateBy { it.version.lowercase(Locale.ROOT) }
            return options
        }
        powerNukkitXVersionCache = emptyMap()
        termux.emitLog("[download] PowerNukkitX 核心版本信息获取失败，使用 latest 兜底")
        return listOf(CoreVersionOption("latest", "官方未标注"))
    }

    private fun fetchPowerNukkitXLegacyMavenOptions(): List<CoreVersionOption> {
        val metadataUrls = listOf(
            "https://repo1.maven.org/maven2/cn/powernukkitx/powernukkitx/maven-metadata.xml",
            "https://maven.aliyun.com/repository/central/cn/powernukkitx/powernukkitx/maven-metadata.xml",
            "https://repo.huaweicloud.com/repository/maven/cn/powernukkitx/powernukkitx/maven-metadata.xml"
        )
        val xml = metadataUrls.asSequence().mapNotNull { url -> runCatching { fetchText(url) }.getOrNull() }.firstOrNull()
            ?: return emptyList()
        return Regex("<version>\\s*([^<]+?)\\s*</version>")
            .findAll(xml)
            .map { it.groupValues[1].trim() }
            .filter { it.isNotBlank() }
            .map { version ->
                CoreVersionOption(
                    version = version,
                    supportedGameVersion = Regex("^([0-9]+(?:\\.[0-9]+){1,3})(?:-r\\d+|-PNX)?$").find(version)?.groupValues?.get(1),
                    sourceRepository = "PowerNukkitX/PowerNukkitX-Legacy",
                    releaseTag = version,
                    assetUrl = "https://repo1.maven.org/maven2/cn/powernukkitx/powernukkitx/$version/powernukkitx-$version-shaded.jar"
                )
            }
            .toList()
            .also { termux.emitLog("[download] PowerNukkitX-Legacy Maven 版本: ${it.size} 个") }
    }

    private fun parsePowerNukkitXGameVersion(body: String, assetNames: List<String> = emptyList()): String? {
        val patterns = listOf(
            Regex("(?i)(?:support(?:s|ed)?|compatible with)\\s*(?:bedrock|minecraft)?\\s*(?:version|v)?\\s*[:：-]?\\s*([0-9]+(?:\\.[0-9]+)+)"),
            Regex("(?i)update\\s+to\\s+(?:bedrock\\s+)?([0-9]+(?:\\.[0-9]+)+)"),
            Regex("(?i)(?:bedrock|minecraft)\\s*(?:version|v)?\\s*[|:：-]?\\s*([0-9]+(?:\\.[0-9]+)+)")
        )
        val text = if (assetNames.isEmpty()) body else body + "\n" + assetNames.joinToString("\n")
        return patterns.firstNotNullOfOrNull { pattern -> pattern.find(text)?.groupValues?.get(1) }
    }

    private fun resolvePowerNukkitXDigest(version: String): String? {
        val cached = powerNukkitXVersionCache[version.trim().removePrefix("v").removePrefix("V").lowercase(Locale.ROOT)]
        if (cached != null) return cached.assetDigest?.takeIf { it.isNotBlank() }
        return powerNukkitXAsset(findPowerNukkitXRelease(version))
            ?.get("digest")?.jsonPrimitive?.content
            ?.removePrefix("sha256:")
    }

    private fun findPowerNukkitXRelease(version: String): JsonObject {
        var last: Exception? = null
        for (repoName in powerNukkitXRepos) {
            try {
                val normalized = version.trim().removePrefix("v").removePrefix("V")
                val endpoint = if (normalized == "latest") {
                    "https://api.github.com/repos/$repoName/releases/latest"
                } else {
                    val tag = java.net.URLEncoder.encode(normalized, "UTF-8")
                    "https://api.github.com/repos/$repoName/releases/tags/$tag"
                }
                val release = runCatching { fetchGithubJson(endpoint) }.getOrNull()
                    ?: if (normalized == "latest") null else {
                        val tag = java.net.URLEncoder.encode("v$normalized", "UTF-8")
                        runCatching { fetchGithubJson("https://api.github.com/repos/$repoName/releases/tags/$tag") }.getOrNull()
                    }
                if (release != null && powerNukkitXAsset(release) != null) return release
            } catch (e: Exception) {
                last = e
                termux.emitLog("[download] PowerNukkitX 仓库回退: $repoName")
            }
        }
        throw last ?: RuntimeException("PowerNukkitX release unavailable")
    }

    private fun powerNukkitXAsset(release: JsonObject): JsonObject? = release["assets"]?.jsonArray
        ?.map { it.jsonObject }
        ?.firstOrNull { it["name"]?.jsonPrimitive?.content.equals("powernukkitx.jar", true) }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun fetchGithubJson(url: String): JsonObject = fetchGithubJsonElement(url).jsonObject

    private fun fetchGithubJsonElement(url: String): JsonElement {
        val candidates = listOf(
            url,
            "https://ghfast.top/$url",
            "https://gh-proxy.com/$url",
            "https://mirror.ghproxy.com/$url",
            "https://ghproxy.net/$url",
            "https://github.moeyy.xyz/$url"
        ).distinct()
        var last: Exception? = null
        for (candidate in candidates) {
            try {
                return fetchJsonElement(candidate)
            } catch (e: Exception) {
                last = e
                termux.emitLog("[download] GitHub 版本源失败，尝试下一个镜像: $candidate")
            }
        }
        throw last ?: RuntimeException("GitHub API unavailable")
    }

    private fun fetchNeoForgeVersions(): List<String> = DEFAULT_MC_VERSIONS

    private fun fetchQuiltVersions(): List<String> = DEFAULT_MC_VERSIONS

    private fun fetchPaperVersions(): List<String> {
        // PaperMC v3 API：返回 {versions: {major: [subversions]}}，需平铺
        val resp = fetchJson("https://fill.papermc.io/v3/projects/paper")
        val versionsObj = resp["versions"]?.jsonObject
            ?: return DEFAULT_MC_VERSIONS
        val allVersions = mutableListOf<String>()
        versionsObj.forEach { (_, subVersions) ->
            subVersions.jsonArray.forEach { v ->
                val verStr = v.jsonPrimitive.content
                // 过滤掉 rc/pre 等非正式版
                if (verStr.isNotEmpty() && !verStr.contains("rc") && !verStr.contains("pre")) {
                    allVersions.add(verStr)
                }
            }
        }
        return allVersions.sortedDescending()
    }

    private fun fetchVanillaVersions(): List<String> {
        val manifest = fetchJson("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json")
        val versions = manifest["versions"]?.jsonArray
            ?: return DEFAULT_MC_VERSIONS
        return versions.map { it.jsonObject["id"]?.jsonPrimitive?.content ?: "" }
            .filter { it.isNotEmpty() && !it.contains("experimental") && !it.contains("pre") && !it.contains("rc") }
            .take(30)  // 只取前30个正式版
    }

    private fun fetchFabricVersions(): List<String> {
        // Fabric 不提供 MC 版本列表 API，返回主流版本
        return DEFAULT_MC_VERSIONS
    }

    private fun fetchForgeVersions(): List<String> {
        // Forge promotions API 不直接返回 MC 版本列表
        // 返回 Forge 支持的主流 MC 版本
        return DEFAULT_MC_VERSIONS
    }

    /**
     * 下载服务端核心到指定路径（独立方法，供 DownloadScreen 调用）。
     * 使用 Java HTTP 直接下载，不依赖 Termux 的 wget 命令。
     * @param dirName 核心文件夹名（仅用于日志）
     */
    suspend fun downloadCoreTo(jarPath: String, config: McConfig, dirName: String = "default") = withContext(Dispatchers.IO) {
        downloadCoreTo(jarPath, config, dirName) { _, _, _ -> }
    }

    /**
     * 下载服务端核心到指定路径（带进度回调版本）。
     * 使用 Java HTTP 直接下载，不依赖 Termux 的 wget 命令。
     * @param dirName 核心文件夹名（仅用于日志）
     * @param onProgress 回调参数：(已下载字节, 总字节, 速度 bytes/s)，总字节为 -1 表示未知
     */
    suspend fun downloadCoreTo(jarPath: String, config: McConfig, dirName: String = "default", onProgress: (Long, Long, Long) -> Unit) = withContext(Dispatchers.IO) {
        val urls = resolveDownloadUrls(config.selectedCore, config.mcVersion)
        val expectedDigest = if (config.selectedCore == ServerCore.PowerNukkitX) {
            runCatching { resolvePowerNukkitXDigest(config.mcVersion) }.getOrNull()
        } else null
        Log.i(TAG, "downloadCoreTo: core=${config.selectedCore}, version=${config.mcVersion}, urls=$urls")
        termux.emitLog("[download] 开始下载 ${config.selectedCore.displayName} ${config.mcVersion}")
        termux.emitLog("[download] 保存路径: $jarPath")

        val outFile = File(jarPath)
        outFile.parentFile?.mkdirs()

        var lastError: Exception? = null
        // Every candidate source is tried once before retrying the first source.
        val maxAttempts = maxOf(3, urls.size)
        repeat(maxAttempts) { attempt ->
            val url = urls[attempt % urls.size]
            try {
                termux.emitLog("[download] 通道 ${attempt + 1}/${urls.size}: $url")
                MultiThreadDownloader.download(
                    url = url,
                    target = outFile,
                    onProgress = { downloaded, totalBytes, speedBytesPerSec ->
                        onProgress(downloaded, totalBytes, speedBytesPerSec)
                    },
                    onLog = { msg -> termux.emitLog("[download] $msg") }
                )

                // 校验文件大小
                val fileSize = outFile.length()
                if (fileSize < 1024) {
                    throw RuntimeException("下载文件过小 ($fileSize 字节)，可能下载失败")
                }
                if (config.selectedCore == ServerCore.PowerNukkitX) {
                    JarFile(outFile).use { jar ->
                        check(
                            jar.getEntry("org/powernukkitx/Server.class") != null ||
                                jar.getEntry("org/powernukkitx/JarStart.class") != null ||
                                jar.getEntry("cn/nukkit/Server.class") != null
                        ) {
                            "PowerNukkitX 下载内容不是有效核心 JAR"
                        }
                    }
                    if (expectedDigest != null) {
                        check(sha256(outFile).equals(expectedDigest, ignoreCase = true)) {
                            "PowerNukkitX SHA-256 校验失败"
                        }
                    }
                }
                termux.emitLog("[download] 下载完成: ${fileSize / 1024 / 1024}MB")
                Log.i(TAG, "downloadCoreTo: success, saved to $jarPath (${fileSize} bytes)")
                return@withContext} catch (e: Exception) {
                Log.w(TAG, "downloadCoreTo attempt ${attempt + 1} failed: ${e.message}")
                termux.emitLog("[download] 第 ${attempt + 1} 次失败: ${e.message}")
                lastError = e
                outFile.delete()
                if (attempt < 2) {
                    termux.emitLog("[download] 等待 ${1500L * (attempt + 1)}ms 后重试...")
                    delay(1500L * (attempt + 1))
                }
            }
        }
        throw RuntimeException("下载失败（已重试3次）: ${lastError?.message}")
    }

    private fun fetchJsonElement(urlStr: String): JsonElement {
        var lastError: Exception? = null
        // 重试 3 次，每次增加超时时间
        repeat(3) { attempt ->
            val conn = URL(urlStr).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000 + attempt * 10_000
                conn.readTimeout = 30_000 + attempt * 10_000
                conn.setRequestProperty("User-Agent", "MineServeMobile/1.0 (https://github.com/MineServe-Mobile)")
                conn.setRequestProperty("Accept", "application/json")
                conn.instanceFollowRedirects = true

                val code = conn.responseCode
                if (code != 200) {
                    val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: Exception) { null }
                    Log.w(TAG, "fetchJsonElement attempt ${attempt + 1}: HTTP $code for $urlStr, body=$errBody")
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}")
                }
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                return json.parseToJsonElement(body)
            } catch (e: Exception) {
                Log.w(TAG, "fetchJsonElement attempt ${attempt + 1} failed: ${e.message}")
                lastError = e
                if (attempt < 2) {
                    try { Thread.sleep(1500L * (attempt + 1)) } catch (_: InterruptedException) {}
                }
            } finally {
                conn.disconnect()
            }
        }
        throw lastError ?: RuntimeException("fetchJsonElement failed: $urlStr")
    }

    /**
     * 启动 MC 服务
     * - 从 config.activeCoreName 找到对应的已安装核心
     * - 如果核心不存在或 jar 缺失，抛出异常
     * - 支持崩溃自动重启（config.autoRestartOnCrash）
     */
    suspend fun start(config: McConfig) = withContext(Dispatchers.IO) {
        // 先确保 Termux 环境已初始化
        if (!termux.isReady()) {
            throw RuntimeException("Termux 环境未初始化，请等待初始化完成")
        }
        // 首次安装依赖
        if (!repo.serverState.value.isInstallComplete) {
            val ok = installDependencies()
            if (!ok) {
                throw RuntimeException("依赖安装失败，请先安装依赖后再启动服务器")
            }
        } else {
            termux.fixRootfsPermissions()
        }
        if (!termux.isJavaInstalled(config.selectedJavaVersion)) {
            throw RuntimeException("${config.selectedJavaVersion.displayName} 未安装，请先在 Java 管理卡片中安装")
        }
        // 找到当前选用的核心
        val activeCore = config.installedCores.find { it.name == config.activeCoreName }
            ?: throw RuntimeException("未选择要启动的服务端核心，请先在「下载」Tab 下载或选择")
        val (launchConfig, launchCore) = migrateToAsciiServerDir(config, activeCore)
        val serverDir = File(termux.serversDir, launchCore.dirName)
        val configuredEntry = launchCore.serverFile
            ?.trim()
            ?.substringAfterLast('/')
            ?.substringAfterLast('\\')
            ?.takeIf { it.isNotBlank() }
        val jarFile = configuredEntry
            ?.let { File(serverDir, it).takeIf(File::isFile) }
            ?: ServerCoreDetector.detect(serverDir).serverFile
                ?.let { File(serverDir, it).takeIf(File::isFile) }
        if (jarFile == null && launchCore.core !in setOf(ServerCore.Forge, ServerCore.NeoForge)) {
            throw RuntimeException("核心 ${launchCore.name} 未找到可启动的 JAR；导入内容未被修改，请在服务器目录中补充入口文件")
        }
        if (launchCore.core == ServerCore.PowerNukkitX && isLegacyPowerNukkitXVersion(launchCore.version)) {
            validateLegacyPowerNukkitXJar(jarFile ?: error("PowerNukkitX 核心入口缺失"))
        }
        // 启动时重置崩溃重试计数
        restartAttempts = 0
        launchMc(launchConfig, launchCore.dirName, jarFile?.absolutePath)
    }

    /** Forge's ZipFileSystem cannot parse percent-encoded non-ASCII working paths on Java 25. */
    private suspend fun migrateToAsciiServerDir(
        config: McConfig,
        activeCore: InstalledCore
    ): Pair<McConfig, InstalledCore> {
        val migratedDirName = sanitizeDirName(activeCore.dirName)
        if (migratedDirName == activeCore.dirName) return config to activeCore

        val source = File(termux.serversDir, activeCore.dirName)
        val target = File(termux.serversDir, migratedDirName)
        if (!source.isDirectory) throw RuntimeException("核心目录不存在：${source.absolutePath}")
        if (target.exists()) throw RuntimeException("无法迁移核心目录，目标目录已存在：${target.name}")
        if (!source.renameTo(target)) throw RuntimeException("无法迁移核心目录，请检查存储空间后重试")

        val migratedCore = activeCore.copy(dirName = migratedDirName)
        val migratedConfig = config.copy(
            installedCores = config.installedCores.map {
                if (it.name == activeCore.name && it.dirName == activeCore.dirName) migratedCore else it
            }
        )
        repo.saveConfig(migratedConfig)
        termux.emitLog("[startMc] 已迁移核心目录到 ASCII 名称：$migratedDirName")
        return migratedConfig to migratedCore
    }

    /**
     * 实际启动 MC 进程（内部方法，供 start 和崩溃重启调用）
     */
    /**
     * 按 Minecraft 版本推荐最低 Java 版本（服务端启动用，不覆盖基岩版）。
     *  - MC 26.1+ → Java 25（Paper 官方要求）
     *  - MC 1.21+ → Java 21；MC 1.17–1.20.4 → Java 17；MC ≤ 1.16 → Java 8
     * 解析失败返回 null（沿用用户选择）。
     */
    private fun recommendedJavaForMinecraft(mcVersion: String, core: ServerCore): JavaVersion? {
        if (core == ServerCore.PowerNukkitX || core == ServerCore.Unknown) return null
        val parts = mcVersion.trim().removePrefix("v").split('.').mapNotNull { it.toIntOrNull() }
        if (parts.isEmpty()) return null
        val major = parts[0]
        val minor = parts.getOrNull(1)
        return when {
            major >= 26 -> JavaVersion.Java25
            major == 1 && minor != null && minor >= 21 -> JavaVersion.Java21
            major == 1 && minor != null && minor >= 17 -> JavaVersion.Java17
            major == 1 && minor != null -> JavaVersion.Java8
            else -> null
        }
    }

    private fun isLegacyPowerNukkitXVersion(version: String): Boolean =
        version.trim().removePrefix("v").removePrefix("V").contains("-r", ignoreCase = true) ||
            version.trim().endsWith("-PNX", ignoreCase = true)

    /** Legacy Maven also publishes an unbundled ~7 MB JAR; it cannot run alone. */
    private fun validateLegacyPowerNukkitXJar(jarFile: File) {
        try {
            JarFile(jarFile).use { jar ->
                val hasEntryPoint = jar.getEntry("cn/nukkit/JarStart.class") != null
                val hasBundledDependency = jar.getEntry("io/netty/channel/Channel.class") != null ||
                    jar.getEntry("com/google/gson/Gson.class") != null
                check(hasEntryPoint && hasBundledDependency) {
                    "PowerNukkitX Legacy 当前是未打包依赖的普通 JAR（约 7MB），请删除后重新下载；应用将下载 shaded 核心（约 83MB）"
                }
            }
        } catch (e: RuntimeException) {
            throw e
        } catch (e: Exception) {
            throw RuntimeException("无法校验 PowerNukkitX Legacy 核心：${e.message}", e)
        }
    }

    private suspend fun launchMc(config: McConfig, dirName: String, jarPath: String?) {
        val serverDir = termux.serverDirFor(dirName)
        val activeCore = config.installedCores.find { it.name == config.activeCoreName }
        val coreType = activeCore?.core ?: config.selectedCore

        // ── 完全自定义启动命令模式 ──
        if (config.advancedCustomCommandEnabled && config.advancedCustomCommand.isNotBlank()) {
            termux.emitLog("[startMc] 使用完全自定义启动命令")
            startupDeadlineMs = System.currentTimeMillis() + 20_000L
            processStartedAtMs = System.currentTimeMillis()
            termux.startMcCustom(
                command = config.advancedCustomCommand,
                dirName = dirName,
                onExit = createExitHandler(config, dirName, jarPath)
            )
            repo.updateServerState { markRunningIfAlive(it) }
            return
        }

        if (coreType == ServerCore.PowerNukkitX) {
            val properties = File(serverDir, "server.properties")
            val current = if (properties.exists()) properties.readText() else ""
            properties.writeText(updatePowerNukkitXProperties(current, config.localPort))
        }
        // 按 MC 版本自动匹配所需 Java（如 Paper 26.1+ 要求 Java 25）：当前选择不足时自动升级并安装
        val mcVersion = activeCore?.version?.takeIf { it.isNotBlank() } ?: config.mcVersion
        val recommended = recommendedJavaForMinecraft(mcVersion, coreType)
        val launchJava = if (recommended != null && config.selectedJavaVersion.ordinal < recommended.ordinal) {
            termux.emitLog("[startMc] $mcVersion 需要 Java ${recommended.displayName} 及以上（当前选择 ${config.selectedJavaVersion.displayName}），已自动切换")
            runCatching { repo.saveConfig(config.copy(selectedJavaVersion = recommended)) }
            recommended
        } else config.selectedJavaVersion
        termux.autoRepairRuntime(
            javaVersion = launchJava,
            needsFonts = coreType == ServerCore.Forge || coreType == ServerCore.NeoForge
        )
        if (coreType == ServerCore.NeoForge && config.selectedJavaVersion != JavaVersion.Java8) {
            termux.emitLog("[startMc] NeoForge 提示：Android/Termux 原生 Java 不提供 glibc 的 libc.so.6，JNA/OSHI 系统信息警告无法通过字体修复消除")
            termux.emitLog("[startMc] ReferenceOpenHashSet 或 DistanceManager 异常属于 NeoForge/Minecraft 运行期崩溃，请以 crash-reports 的首个异常为准")
        }
        if ((coreType == ServerCore.Forge || coreType == ServerCore.NeoForge) &&
            config.selectedJavaVersion != JavaVersion.Java8 && !termux.repairFontRuntime()) {
            // Forge/NeoForge 初始化 Minecraft 字体配置；旧环境可能在安装依赖前已下载核心。
            termux.emitLog("[startMc] 字体运行库仍未通过校验；已保留无图形模式启动参数")
        }
        val launchArgs = when (coreType) {
            ServerCore.Spigot, ServerCore.CraftBukkit -> {
                val coreJar = jarPath?.let(::File)
                    ?: throw RuntimeException("${coreType.displayName} 核心入口缺失，请检查导入目录")
                bukkitLaunchArguments(serverDir, coreJar)
            }
            ServerCore.Forge -> forgeLaunchArguments(serverDir, config.selectedJavaVersion)
            ServerCore.NeoForge -> File(serverDir, "libraries/net/neoforged/neoforge")
                .walkTopDown().firstOrNull { it.name == "unix_args.txt" }
                ?.let { "@${it.absolutePath}" }
                ?: throw RuntimeException("NeoForge 启动文件缺失，请重新下载安装核心")
            ServerCore.Quilt -> {
                val launchJar = File(serverDir, "quilt-server-launch.jar")
                if (launchJar.exists()) "-jar ${launchJar.absolutePath}"
                else throw RuntimeException("Quilt 启动文件缺失，请重新下载安装核心")
            }
            ServerCore.PowerNukkitX -> {
                val coreJar = jarPath?.let(::File)
                    ?: throw RuntimeException("PowerNukkitX 核心入口缺失，请检查导入目录")
                val mainClass = powerNukkitXMainClass(coreJar)
                    ?: throw RuntimeException("PowerNukkitX 核心缺少可识别的启动入口（org.powernukkitx.Server/cn.nukkit.Nukkit），请重新下载")
                val languageArg = if (mainClass == "cn.nukkit.Nukkit") " --language=chs" else ""
                "--add-opens=java.base/java.lang=ALL-UNNAMED -cp '${coreJar.absolutePath}:${File(serverDir, "libs").absolutePath}/*' $mainClass$languageArg"
            }
            else -> null
        }
        if (launchArgs == null && jarPath == null) {
            throw RuntimeException("未找到可启动的 JAR；导入内容未被修改，请检查服务器目录")
        }
        startupDeadlineMs = System.currentTimeMillis() + 20_000L
        processStartedAtMs = System.currentTimeMillis()
        termux.startMc(
            jarPath = jarPath.orEmpty(),
            maxHeapMb = config.maxHeapMb,
            dirName = dirName,
            javaVersion = launchJava,
            launchArgs = launchArgs,
            appendNogui = coreType != ServerCore.PowerNukkitX,
            onExit = createExitHandler(config, dirName, jarPath)
        )
        repo.updateServerState { markRunningIfAlive(it) }
    }

    /**
     * 启动返回的瞬间进程可能已退出：此时退出回调的停止/失败状态优先，
     * 不能在这里把 ServerState 覆盖回“运行中”。
     */
    private fun markRunningIfAlive(state: com.mineserve.mobile.data.ServerState): com.mineserve.mobile.data.ServerState {
        if (!termux.isMcRunning()) return state
        return if (state.startupPhase.progress <= StartupPhase.PreparingEnvironment.progress) {
            state.copy(isRunning = true, runningSinceMs = 0L, startupPhase = StartupPhase.PreparingEnvironment)
        } else state.copy(isRunning = true, runningSinceMs = 0L)
    }

    /** 提取 onExit 处理器，供 launchMc 和 startMcCustom 共用。 */
    private fun createExitHandler(config: McConfig, dirName: String, jarPath: String?): (Int) -> Unit = { code ->
        val failedDuringStartup = System.currentTimeMillis() <= startupDeadlineMs
        repo.updateServerState {
            it.copy(
                isRunning = false,
                startupPhase = if (code == 0) StartupPhase.Idle else StartupPhase.Failed
            )
        }
        Log.w(TAG, "MC process exited code=$code, autoRestart=${config.autoRestartOnCrash}")
        // 启动后极短时间内退出（即使 exit=0）不是正常停止：测试核心/配置错误常表现为退出码 0。
        val quickCleanExit = code == 0 && System.currentTimeMillis() - processStartedAtMs < 5_000L
        if (code == 0 && !quickCleanExit) {
            val app = McApplication.get()
            ServerEventNotifier.notify(
                app,
                app.getString(R.string.notif_server_stopped_title),
                app.getString(R.string.notif_server_stopped_text),
                ServerEventNotifier.ID_STOPPED, 1
            )
        }
        if (code != 0 || quickCleanExit) {
            // stdout writer 在进程退出哨兵后完成 flush，稍候再读文件避免报告缺失尾部。
            try { Thread.sleep(200) } catch (e: InterruptedException) { Thread.currentThread().interrupt() }
            var reportPath: String? = null
            try {
                reportPath = crashReportManager.captureCrash(
                    code, wasRunningBefore = true, dirName = dirName, allowCleanExit = quickCleanExit
                )
                if (reportPath != null) {
                    Log.i(TAG, "崩溃报告已保存: $reportPath")
                    termux.emitLog("[crash] 检测到异常退出(exit=$code)，崩溃报告已保存: ${File(reportPath).name}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "捕获崩溃报告失败: ${e.message}", e)
            }
            val willAutoRestart = config.autoRestartOnCrash && restartAttempts < maxRestartAttempts
            val detail = if (failedDuringStartup) {
                "服务端启动后立即退出 (exit=$code)"
            } else if (quickCleanExit) {
                "服务端异常快速退出 (exit=$code)"
            } else if (willAutoRestart) {
                "服务端异常退出 (exit=$code)，稍后自动重启"
            } else {
                "服务端异常退出 (exit=$code)"
            }
            val failure = StartupFailure(code, reportPath, detail)
            lastStartupFailure = failure
            lastStartupFailureAtMs = System.currentTimeMillis()
            _startupFailures.tryEmit(failure)
            val app = McApplication.get()
            ServerEventNotifier.notify(
                app,
                app.getString(R.string.notif_server_crashed_title),
                app.getString(R.string.notif_server_crashed_text, code),
                ServerEventNotifier.ID_CRASH, 1
            )
        }
        if (config.autoRestartOnCrash && (code != 0 || quickCleanExit)) {
            if (restartAttempts < maxRestartAttempts) {
                restartAttempts++
                Log.i(TAG, "崩溃自动重启中... (attempt $restartAttempts/$maxRestartAttempts)")
                Thread {
                    try {
                        Thread.sleep(3000)
                        kotlinx.coroutines.runBlocking {
                            launchMc(config, dirName, jarPath)
                            repo.updateServerState { markRunningIfAlive(it) }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "崩溃重启失败: ${e.message}", e)
                    }
                }.start()
            } else {
                Log.e(TAG, "已达到最大重试次数($maxRestartAttempts)，停止重启")
            }
        }
    }

    /**
     * Forge 1.17+ writes unix_args.txt, while Forge 1.12.2 and older writes a
     * top-level forge-*.jar.  The installer jar itself remains server.jar.
     */
    private suspend fun forgeLaunchArguments(serverDir: File, javaVersion: JavaVersion): String {
        serverDir.walkTopDown()
            .firstOrNull { it.name == "unix_args.txt" }
            ?.let {
                termux.emitLog("[startMc] Forge 启动方式: unix_args.txt")
                return "@${it.absolutePath}"
            }

        // Forge 1.12 installers leave the real server jar in libraries/ while
        // server.jar remains the installer. Prefer the verified library jar so
        // a headless Android process never launches SimpleInstaller again.
        if (javaVersion == JavaVersion.Java8) {
            findJava8ForgeLibraryJar(serverDir)?.let { jar ->
                val relativePath = jar.relativeTo(serverDir).invariantSeparatorsPath
                termux.emitLog("[startMc] Java 8 Forge: 使用 ServerLaunchWrapper classpath 模式: $relativePath")
                return "-jar '$relativePath'"
            }
        }

        findLegacyForgeServerJar(serverDir, javaVersion)?.let { jar ->
            termux.emitLog("[startMc] Forge 旧版启动方式: ${jar.name}")
            // Java 8 runs after `cd /srv/mineserve` inside PRoot. Keep this
            // target relative so Android's host path cannot resolve to a stale
            // file outside the container bind mount.
            return if (javaVersion == JavaVersion.Java8) {
                "-jar '${jar.relativeTo(serverDir).invariantSeparatorsPath}'"
            } else {
                "-jar ${jar.absolutePath}"
            }
        }

        // Java 8 downloads may leave the installer under its original forge-*.jar
        // name. Java 17/25 keep the existing server.jar-only recovery behavior.
        val installerCandidates = if (javaVersion == JavaVersion.Java8) {
            buildList {
                add(File(serverDir, "server.jar"))
                serverDir.listFiles()
                    ?.filter { it.isFile && it.name.startsWith("forge-") && it.name.endsWith(".jar") }
                    ?.forEach(::add)
            }
        } else {
            listOf(File(serverDir, "server.jar"))
        }
        val installerJar = installerCandidates.firstOrNull {
            isForgeInstallerJar(it, serverDir, javaVersion)
        }
        if (installerJar != null) {
            termux.emitLog("[startMc] Forge 旧版安装产物不完整，正在重新部署服务端文件...")
            val code = runInstallerWithRetry(
                installerJar.absolutePath,
                serverDir,
                "Forge",
                File(termux.installer.rootDir, "tmp").apply { mkdirs() },
                javaVersion
            )
            if (code == 0) {
                findLegacyForgeServerJar(serverDir, javaVersion)?.let { jar ->
                    termux.emitLog("[startMc] Forge 旧版服务端文件已修复: ${jar.name}")
                    return if (javaVersion == JavaVersion.Java8) {
                        "-jar '${jar.relativeTo(serverDir).invariantSeparatorsPath}'"
                    } else {
                        "-jar ${jar.absolutePath}"
                    }
                }
            }
            throw RuntimeException("Forge 旧版服务端部署不完整，请检查网络后重新下载安装核心")
        }

        throw RuntimeException("Forge 启动文件缺失：未找到 unix_args.txt 或 forge-*.jar，请重新下载安装核心")
    }

    private fun requireInstallerLaunchArtifacts(core: ServerCore, serverDir: File) {
        val ready = when (core) {
            ServerCore.Forge -> serverDir.walkTopDown().any { it.name == "unix_args.txt" } ||
                serverDir.walkTopDown().any { it.isFile && it.name.startsWith("forge-") && it.name.endsWith(".jar") && !hasForgeInstallerMainClass(it) }
            ServerCore.NeoForge -> File(serverDir, "libraries/net/neoforged/neoforge")
                .walkTopDown().any { it.name == "unix_args.txt" }
            ServerCore.Quilt -> File(serverDir, "quilt-server-launch.jar").isFile
            else -> true
        }
        check(ready) { "${core.displayName} installer 未生成启动文件，请检查安装日志和网络后重试" }
    }

    private fun findLegacyForgeServerJar(serverDir: File, javaVersion: JavaVersion): File? =
        serverDir.listFiles()
            ?.filter { file ->
                file.isFile && file.name.startsWith("forge-") && file.name.endsWith(".jar") &&
                    isLegacyForgeServerJar(file, serverDir, javaVersion)
            }
            ?.maxByOrNull { it.lastModified() }

    private fun findJava8ForgeLibraryJar(serverDir: File): File? =
        File(serverDir, "libraries/net/minecraftforge/forge")
            .walkTopDown()
            .filter { file ->
                file.isFile && file.name.startsWith("forge-") && file.name.endsWith(".jar")
            }
            .sortedByDescending { it.lastModified() }
            .firstOrNull { file ->
                isLegacyForgeServerJar(file, serverDir, JavaVersion.Java8)
            }

    private fun isLegacyForgeServerJar(file: File, serverDir: File, javaVersion: JavaVersion): Boolean {
        if (javaVersion == JavaVersion.Java8) {
            val guestJar = "/srv/mineserve/${file.relativeTo(serverDir).invariantSeparatorsPath}"
            val hasServerWrapper = termux.runJava8Command(
                serverDir,
                "jar tf '$guestJar' 2>/dev/null | grep -q 'net/minecraftforge/fml/relauncher/ServerLaunchWrapper.class'",
                60_000
            ) == 0
            if (!hasServerWrapper) return false
            // Some Forge installers contain launcher classes too; SimpleInstaller
            // is the decisive marker that this file must never be launched.
            return !isForgeInstallerJar(file, serverDir, javaVersion)
        }
        return runCatching {
            JarFile(file).use { jar ->
                jar.getEntry("net/minecraftforge/fml/relauncher/ServerLaunchWrapper.class") != null &&
                    jar.manifest?.mainAttributes?.getValue("Main-Class")
                        ?.contains("net.minecraftforge.installer", ignoreCase = true) != true
            }
        }.getOrDefault(false)
    }

    private fun isForgeInstallerJar(file: File, serverDir: File, javaVersion: JavaVersion): Boolean {
        if (javaVersion == JavaVersion.Java8) {
            if (hasForgeInstallerMainClass(file)) return true
            val guestJar = "/srv/mineserve/${file.relativeTo(serverDir).invariantSeparatorsPath}"
            return termux.runJava8Command(
                serverDir,
                "jar tf '$guestJar' 2>/dev/null | grep -q 'net/minecraftforge/installer/SimpleInstaller.class'",
                60_000
            ) == 0
        }
        return hasForgeInstallerMainClass(file)
    }

    private fun hasForgeInstallerMainClass(file: File): Boolean = runCatching {
        JarFile(file).use { jar ->
            jar.manifest?.mainAttributes?.getValue("Main-Class")
                ?.contains("net.minecraftforge.installer", ignoreCase = true) == true
        }
    }.getOrDefault(false)

    suspend fun stop() = withContext(Dispatchers.IO) {
        termux.stopMc()
        repo.updateServerState {
            it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Idle)
        }
    }


    fun sendCommand(line: String) {
        termux.sendCommand(if (line.startsWith("/")) line.substring(1) else line)
    }

    companion object {
        private const val TAG = "McServerController"
        /** 默认 MC 版本列表（当 API 获取失败时回退使用） */
        private val DEFAULT_MC_VERSIONS = listOf(
            "1.21.4", "1.21", "1.20.6", "1.20.4", "1.20.1",
            "1.19.4", "1.19.2", "1.18.2", "1.17.1", "1.16.5"
        )

        /** 服务端工作目录必须是 ASCII，避免 Forge/Java 25 解析 file URI 时出现 Bad escape。 */
        fun sanitizeDirName(name: String): String {
            val normalized = name
                .replace(Regex("[^a-zA-Z0-9_-]"), "_")
                .replace(Regex("_+"), "_")
                .trimEnd('_')
                .ifEmpty { "server" }
            return if (normalized == name) normalized else {
                val suffix = MessageDigest.getInstance("SHA-256")
                    .digest(name.toByteArray(Charsets.UTF_8))
                    .take(6)
                    .joinToString("") { "%02x".format(it.toInt() and 0xff) }
                "$normalized-$suffix"
            }
        }
    }
}
