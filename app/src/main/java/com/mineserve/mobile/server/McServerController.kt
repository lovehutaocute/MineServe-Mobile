package com.mineserve.mobile.server

import android.util.Log
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.InstalledCore
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.MultiThreadDownloader
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
import java.util.jar.JarFile

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

    data class CoreVersionOption(
        val version: String,
        val supportedGameVersion: String? = null
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
                        "which frpc >/dev/null 2>&1 || apt-get -o DPkg::Lock::Timeout=60 install --allow-unauthenticated -y frp")
                    InstallStep.Rclone -> termux.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "rclone")
                    InstallStep.Proot -> termux.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "proot")
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
        if (config.selectedCore.needsInstaller && !termux.isJavaInstalled(config.selectedJavaVersion)) {
            throw RuntimeException("${config.selectedJavaVersion.displayName} is not installed. Install and verify it before running ${config.selectedCore.displayName} installer.")
        }
        val dirName = sanitizeDirName(customName)
        val jarPath = termux.serverJarFileFor(dirName).absolutePath
        downloadCoreTo(jarPath, config, dirName, onProgress)
        // 在新核心目录下创建 eula.txt 和 plugins/ 目录
        val serverDir = termux.serverDirFor(dirName)
        if (config.selectedCore == ServerCore.PowerNukkitX) {
            val properties = File(serverDir, "server.properties")
            val current = if (properties.exists()) properties.readText() else ""
            val portLine = "server-port=${config.localPort}"
            val updated = if (Regex("(?m)^server-port=.*$").containsMatchIn(current)) {
                current.replace(Regex("(?m)^server-port=.*$"), portLine)
            } else "$current${if (current.isNotEmpty() && !current.endsWith("\n")) "\n" else ""}$portLine\n"
            properties.writeText(updated)
        }
        // NeoForge/Quilt：下载的是 installer.jar，执行安装命令生成启动环境（首次需下载依赖）
        if (config.selectedCore.needsInstaller) {
            termux.emitLog("[install] 正在执行 ${config.selectedCore.displayName} installer，首次安装需下载依赖，请耐心等待...")
            val installerTempDir = File(termux.installer.rootDir, "tmp").apply { mkdirs() }
            when (config.selectedCore) {
                ServerCore.Forge -> {
                    val code = runInstallerWithRetry(
                        jarPath, serverDir, config.selectedCore.displayName, installerTempDir,
                        config.selectedJavaVersion
                    )
                    if (code != 0) throw RuntimeException("Forge installer 执行失败 (exit=$code)")
                }
                ServerCore.NeoForge -> {
                    val code = runInstallerWithRetry(
                        jarPath, serverDir, config.selectedCore.displayName, installerTempDir,
                        config.selectedJavaVersion
                    )
                    if (code != 0) throw RuntimeException("NeoForge installer 执行失败 (exit=$code)")
                }
                ServerCore.Quilt -> {
                    val code = if (config.selectedJavaVersion == JavaVersion.Java8) {
                        val guestJar = "/srv/mineserve/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
                        termux.runJava8Command(
                            serverDir,
                            "export JAVA_HOME=/usr/lib/jvm/java-8-openjdk-arm64; cd /srv/mineserve && " +
                                "exec /usr/bin/java -Djava.io.tmpdir=/tmp -jar '$guestJar' " +
                                "install server '${config.mcVersion}' --install-dir=/srv/mineserve --download-server"
                        )
                    } else {
                        termux.execOnce(
                            "java", "-jar", jarPath, "install", "server", config.mcVersion,
                            "--install-dir=${serverDir.absolutePath}", "--download-server"
                        )
                    }
                    if (code != 0) throw RuntimeException("Quilt installer 执行失败 (exit=$code)")
                }
                else -> {}
            }
            termux.emitLog("[install] ${config.selectedCore.displayName} 安装完成")
        }
        val eula = File(serverDir, "eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
        File(serverDir, "plugins").mkdirs()
        // 添加到已安装列表
        val newCore = InstalledCore(
            name = customName,
            core = config.selectedCore,
            version = config.mcVersion,
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
        return if (core == ServerCore.PowerNukkitX) {
            // ponytail: three fixed channels cover mainland acceleration and the official fallback.
            listOf("https://ghfast.top/$official", "https://gh-proxy.com/$official", official).distinct()
        } else listOf(official)
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
        val endpoint = if (version == "latest") {
            "https://api.github.com/repos/PowerNukkitX/PowerNukkitX/releases/latest"
        } else {
            "https://api.github.com/repos/PowerNukkitX/PowerNukkitX/releases/tags/${java.net.URLEncoder.encode(version, "UTF-8")}"
        }
        val release = fetchJson(endpoint)
        return release["assets"]?.jsonArray
            ?.map { it.jsonObject }
            ?.firstOrNull { it["name"]?.jsonPrimitive?.content == "powernukkitx.jar" }
            ?.get("browser_download_url")?.jsonPrimitive?.content
            ?: throw RuntimeException("PowerNukkitX $version: release has no powernukkitx.jar asset")
    }

    // ── NeoForge：maven-metadata 按所选 MC 版本匹配 NeoForge 版本 installer ──

    private fun resolveNeoForgeUrl(version: String): String {
        val xml = fetchText("https://maven.neoforged.net/releases/net/neoforged/neoforge/maven-metadata.xml")
        // NeoForge 版本号与 MC 版本对应（MC 1.20.4 → NeoForge 20.4.x）：取去点后以 MC 短号开头的最大版本
        val mcShort = version.removePrefix("1.").replace(".", "") // "1.20.4" → "204"
        val versions = Regex("<version>([^<]+)</version>").findAll(xml).map { it.groupValues[1] }.toList()
        val matched = versions.filter { it.replace(".", "").startsWith(mcShort) }
        val ver = matched.maxOrNull()
            ?: Regex("<release>([^<]+)</release>").find(xml)?.groupValues?.get(1)
            ?: throw RuntimeException("NeoForge: no version for MC $version")
        return "https://maven.neoforged.net/releases/net/neoforged/neoforge/$ver/neoforge-$ver-installer.jar"
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

    private fun fetchVelocityVersions(): List<String> {
        // PaperMC v3：velocity versions 数组
        val resp = fetchJson("https://fill.papermc.io/v3/projects/velocity")
        return resp["versions"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?.sortedDescending() ?: DEFAULT_MC_VERSIONS
    }

    private fun fetchBungeeVersions(): List<String> = listOf("latest")

    private fun fetchPowerNukkitXVersionOptions(): List<CoreVersionOption> {
        val releaseObjects = runCatching {
            fetchJsonElement("https://api.github.com/repos/PowerNukkitX/PowerNukkitX/releases?per_page=30")
                .jsonArray.map { it.jsonObject }
        }.getOrElse {
            listOf(runCatching {
                fetchJson("https://api.github.com/repos/PowerNukkitX/PowerNukkitX/releases/latest")
            }.getOrNull() ?: return listOf(CoreVersionOption("latest")))
        }
        val options = releaseObjects
            .filter {
                !(it["draft"]?.jsonPrimitive?.boolean ?: false) &&
                    !(it["prerelease"]?.jsonPrimitive?.boolean ?: false)
            }
            .mapNotNull { release ->
                val tag = release["tag_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
                CoreVersionOption(tag, parsePowerNukkitXGameVersion(release["body"]?.jsonPrimitive?.content.orEmpty()))
            }
            .distinctBy { it.version }
        if (options.isNotEmpty()) return options
        termux.emitLog("[download] PowerNukkitX 核心版本信息获取失败，使用 latest 兜底")
        return listOf(CoreVersionOption("latest"))
    }

    private fun parsePowerNukkitXGameVersion(body: String): String? {
        val patterns = listOf(
            Regex("(?i)update\\s+to\\s+([0-9]+(?:\\.[0-9]+)+)"),
            Regex("(?i)(?:bedrock|minecraft)\\s*(?:version|v)?\\s*[|:：-]?\\s*([0-9]+(?:\\.[0-9]+)+)")
        )
        return patterns.firstNotNullOfOrNull { pattern -> pattern.find(body)?.groupValues?.get(1) }
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
        Log.i(TAG, "downloadCoreTo: core=${config.selectedCore}, version=${config.mcVersion}, urls=$urls")
        termux.emitLog("[download] 开始下载 ${config.selectedCore.displayName} ${config.mcVersion}")
        termux.emitLog("[download] 保存路径: $jarPath")

        val outFile = File(jarPath)
        outFile.parentFile?.mkdirs()

        var lastError: Exception? = null
        // 普通核心保持 3 次重试；PowerNukkitX 依次尝试加速通道和官方源。
        repeat(3) { attempt ->
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
                        check(jar.getEntry("cn/nukkit/Server.class") != null) {
                            "PowerNukkitX 下载内容不是有效核心 JAR"
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
                    try { Thread.sleep(1500L * (attempt + 1)) } catch (_: InterruptedException) {}
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
        }
        if (!termux.isJavaInstalled(config.selectedJavaVersion)) {
            throw RuntimeException("${config.selectedJavaVersion.displayName} 未安装，请先在 Java 管理卡片中安装")
        }
        // 找到当前选用的核心
        val activeCore = config.installedCores.find { it.name == config.activeCoreName }
            ?: throw RuntimeException("未选择要启动的服务端核心，请先在「下载」Tab 下载或选择")
        if (activeCore.core == ServerCore.PowerNukkitX && config.selectedJavaVersion != JavaVersion.Java21) {
            throw RuntimeException("PowerNukkitX 需要 Java 21，请先安装 Java 21 运行环境")
        }
        val jarFile = termux.serverJarFileFor(activeCore.dirName)
        if (!jarFile.exists()) {
            throw RuntimeException("核心 ${activeCore.name} 的 server.jar 不存在，请重新下载")
        }
        // 启动时重置崩溃重试计数
        restartAttempts = 0
        launchMc(config, activeCore.dirName, jarFile.absolutePath)
    }

    /**
     * 实际启动 MC 进程（内部方法，供 start 和崩溃重启调用）
     */
    private suspend fun launchMc(config: McConfig, dirName: String, jarPath: String) {
        val serverDir = termux.serverDirFor(dirName)
        // 检查 server.jar 是否存在
        if (!File(jarPath).exists()) {
            throw RuntimeException("server.jar 不存在，请先在「下载」Tab 下载服务端核心")
        }
        // NeoForge/Quilt：使用 installer 生成的启动方式（unix_args.txt / quilt-server-launch.jar），
        // 产物缺失时明确报错，避免回退到不可启动的 installer.jar。
        // 注意：必须按「实际激活核心」的类型判断——selectedCore 只是下载页的临时选择，
        // 若用户在下载页选过 Quilt 后切到 Paper 启动，会误判启动方式导致失败。
        val activeCore = config.installedCores.find { it.name == config.activeCoreName }
        val coreType = activeCore?.core ?: config.selectedCore
        termux.autoRepairRuntime(
            javaVersion = config.selectedJavaVersion,
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
            else -> null
        }
        termux.startMc(
            jarPath = jarPath,
            maxHeapMb = config.maxHeapMb,
            dirName = dirName,
            javaVersion = config.selectedJavaVersion,
            launchArgs = if (config.selectedJavaVersion == JavaVersion.Java8) launchArgs else null,
            onExit = { code ->
                repo.updateServerState { it.copy(isRunning = false) }
                Log.w(TAG, "MC process exited code=$code, autoRestart=${config.autoRestartOnCrash}")
                // 捕获崩溃报告（非正常退出时收集最近日志 + MC 原生崩溃报告）
                if (code != 0) {
                    try {
                        val reportPath = crashReportManager.captureCrash(code, wasRunningBefore = true, dirName = dirName)
                        if (reportPath != null) {
                            Log.i(TAG, "崩溃报告已保存: $reportPath")
                            termux.emitLog("[crash] 检测到异常退出(exit=$code)，崩溃报告已保存: ${File(reportPath).name}")
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "捕获崩溃报告失败: ${e.message}", e)
                    }
                }
                // 崩溃自动重启（exit code 非 0 且用户开启）
                if (config.autoRestartOnCrash && code != 0) {
                    if (restartAttempts < maxRestartAttempts) {
                        restartAttempts++
                        Log.i(TAG, "崩溃自动重启中... (attempt $restartAttempts/$maxRestartAttempts)")
                        // 延迟 3 秒后重启，避免快速崩溃循环
                        Thread {
                            try {
                                Thread.sleep(3000)
                                kotlinx.coroutines.runBlocking {
                                    launchMc(config, dirName, jarPath)
                                    repo.updateServerState { it.copy(isRunning = true, runningSinceMs = 0L) }
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
        )
        repo.updateServerState { it.copy(isRunning = true, runningSinceMs = 0L) }
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
        repo.updateServerState { it.copy(isRunning = false, runningSinceMs = 0L) }
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

        /** 将用户自定义名称转换为安全的文件夹名：只保留字母数字汉字和连字符，其余替换为下划线 */
        fun sanitizeDirName(name: String): String {
            return name
                .replace(Regex("[^a-zA-Z0-9\\u4e00-\\u9fa5\\-]"), "_")
                .replace(Regex("_+"), "_")
                .trimEnd('_')
                .ifEmpty { "default" }
        }
    }
}
