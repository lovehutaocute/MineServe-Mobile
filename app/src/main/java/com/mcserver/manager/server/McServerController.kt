package com.mcserver.manager.server

import android.util.Log
import com.mcserver.manager.data.InstallStep
import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.ServerCore
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.data.StepStatus
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var isInstalling = false

    /** 崩溃自动重启的最大重试次数，防止无限循环 */
    private val maxRestartAttempts = 3
    @Volatile
    private var restartAttempts = 0

    /**
     * 一键安装依赖
     */
    suspend fun installDependencies() = withContext(Dispatchers.IO) {
        // 防止并发调用（ViewModel 和 start() 可能同时调用）
        if (isInstalling) return@withContext false
        isInstalling = true
        try {
            // 先确保 Termux 环境已初始化
            if (!termux.isReady()) {
                throw RuntimeException("Termux 环境未初始化，请等待初始化完成")
            }
            val steps = InstallStep.values()
            steps.forEachIndexed { idx, step ->
                repo.markStep(step, StepStatus.Active, idx * 33)
                val code = when (step) {
                    InstallStep.Jdk -> termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "openjdk-25")
                    InstallStep.Wget -> termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "wget")
                    InstallStep.Frp -> termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "frp")
                }
                if (code == 0) {
                    repo.markStep(step, StepStatus.Done, (idx + 1) * 33)
                } else {
                    repo.markStep(step, StepStatus.Wait, idx * 33)
                    return@withContext false
                }
            }
            true
        } finally {
            isInstalling = false
        }
    }

    /**
     * 下载服务端核心（生产化：4 种核心动态解析 API）
     * 下载到 serverJarFile（home/server/server.jar），使用 Java HTTP 直下载，不依赖 wget
     */
    suspend fun downloadCore(config: McConfig) = withContext(Dispatchers.IO) {
        // 在 APP 层（Java HTTP）解析真实下载 URL，直接下载文件
        val jarPath = termux.serverJarFile.absolutePath
        downloadCoreTo(jarPath, config)
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
            ServerCore.Fabric -> resolveFabricUrl(version)
            ServerCore.Forge -> resolveForgeUrl(version)
            ServerCore.Vanilla -> resolveVanillaUrl(version)
        }
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
        when (core) {
            ServerCore.Paper -> fetchPaperVersions()
            ServerCore.Vanilla -> fetchVanillaVersions()
            ServerCore.Fabric -> fetchFabricVersions()
            ServerCore.Forge -> fetchForgeVersions()
        }
    }

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
     * 成功后更新 config.downloadedCore 和 downloadedVersion。
     */
    suspend fun downloadCoreTo(jarPath: String, config: McConfig) = withContext(Dispatchers.IO) {
        val url = resolveDownloadUrl(config.selectedCore, config.mcVersion)
        Log.i(TAG, "downloadCoreTo: core=${config.selectedCore}, version=${config.mcVersion}, url=$url")
        termux.emitLog("[download] 开始下载 ${config.selectedCore.displayName} ${config.mcVersion}")
        termux.emitLog("[download] URL: $url")
        termux.emitLog("[download] 保存路径: $jarPath")

        val outFile = File(jarPath)
        outFile.parentFile?.mkdirs()

        var lastError: Exception? = null
        // 重试 3 次
        repeat(3) { attempt ->
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 30_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "MCServerManager/1.0 (https://github.com/mcserver-manager)")

                val code = conn.responseCode
                if (code != 200) {
                    val errBody = try { conn.errorStream?.bufferedReader()?.use { it.readText() }?.take(200) } catch (_: Exception) { null }
                    Log.w(TAG, "downloadCoreTo attempt ${attempt + 1}: HTTP $code, body=$errBody")
                    termux.emitLog("[download] 第 ${attempt + 1} 次尝试失败: HTTP $code")
                    throw RuntimeException("HTTP $code: ${conn.responseMessage}")
                }

                val totalBytes = conn.contentLengthLong
                var downloadedBytes = 0L
                var lastProgressLog = 0L
                val startTime = System.currentTimeMillis()

                conn.inputStream.use { input ->
                    FileOutputStream(outFile).use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            downloadedBytes += read

                            // 每 5MB 输出一次进度日志
                            if (downloadedBytes - lastProgressLog >= 5 * 1024 * 1024) {
                                lastProgressLog = downloadedBytes
                                val percent = if (totalBytes > 0) "${(downloadedBytes * 100 / totalBytes)}%" else "?%"
                                val speedMB = if (System.currentTimeMillis() > startTime) {
                                    String.format("%.2f", downloadedBytes / 1024.0 / 1024.0 / ((System.currentTimeMillis() - startTime) / 1000.0))
                                } else "0"
                                termux.emitLog("[download] 进度: $percent (${downloadedBytes / 1024 / 1024}MB / ${totalBytes / 1024 / 1024}MB, ${speedMB}MB/s)")
                            }
                        }
                    }
                }

                // 校验文件大小
                val fileSize = outFile.length()
                if (fileSize < 1024) {
                    throw RuntimeException("下载文件过小 ($fileSize 字节)，可能下载失败")
                }
                termux.emitLog("[download] 下载完成: ${fileSize / 1024 / 1024}MB")

                // 持久化已下载信息
                repo.saveConfig(config.copy(
                    downloadedCore = config.selectedCore,
                    downloadedVersion = config.mcVersion
                ))
                Log.i(TAG, "downloadCoreTo: success, saved to $jarPath (${fileSize} bytes)")
                return@withContext
            } catch (e: Exception) {
                Log.w(TAG, "downloadCoreTo attempt ${attempt + 1} failed: ${e.message}")
                termux.emitLog("[download] 第 ${attempt + 1} 次失败: ${e.message}")
                lastError = e
                if (attempt < 2) {
                    termux.emitLog("[download] 等待 ${1500L * (attempt + 1)}ms 后重试...")
                    try { Thread.sleep(1500L * (attempt + 1)) } catch (_: InterruptedException) {}
                }
            } finally {
                conn.disconnect()
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
                conn.setRequestProperty("User-Agent", "MCServerManager/1.0 (https://github.com/mcserver-manager)")
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
     * - 首次启动或核心/版本变化时重新下载 server.jar
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
        // 检测核心或版本是否变化，变化则重新下载 server.jar
        val needRedownload = config.downloadedCore != config.selectedCore ||
            config.downloadedVersion != config.mcVersion
        if (needRedownload) {
            Log.i(TAG, "核心或版本变化(${config.downloadedCore}/${config.downloadedVersion} -> " +
                "${config.selectedCore}/${config.mcVersion})，重新下载 server.jar")
            downloadCore(config)
            // 持久化已下载的核心信息
            repo.saveConfig(config.copy(
                downloadedCore = config.selectedCore,
                downloadedVersion = config.mcVersion
            ))
        }
        // 启动时重置崩溃重试计数
        restartAttempts = 0
        launchMc(config)
    }

    /**
     * 实际启动 MC 进程（内部方法，供 start 和崩溃重启调用）
     * 使用 termux.serverJarFile 的绝对路径，避免相对路径解析错误
     */
    private suspend fun launchMc(config: McConfig) {
        // 使用 Termux 沙盒内的绝对路径
        val jarPath = termux.serverJarFile.absolutePath
        // 检查 server.jar 是否存在
        if (!termux.serverJarFile.exists()) {
            throw RuntimeException("server.jar 不存在，请先在「下载」Tab 下载服务端核心")
        }
        termux.startMc(
            jarPath = jarPath,
            maxHeapMb = config.maxHeapMb,
            onExit = { code ->
                repo.updateServerState { it.copy(isRunning = false) }
                Log.w(TAG, "MC process exited code=$code, autoRestart=${config.autoRestartOnCrash}")
                // 崩溃自动重启（exit code 非 0 且用户开启）
                if (config.autoRestartOnCrash && code != 0) {
                    if (restartAttempts < maxRestartAttempts) {
                        restartAttempts++
                        Log.i(TAG, "崩溃自动重启中... (attempt $restartAttempts/$maxRestartAttempts)")
                        // 延迟 3 秒后重启，避免快速崩溃循环
                        Thread {
                            try {
                                Thread.sleep(3000)
                                // 用 runBlocking 启动协程重启
                                kotlinx.coroutines.runBlocking {
                                    launchMc(config)
                                    repo.updateServerState { it.copy(isRunning = true) }
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
        repo.updateServerState { it.copy(isRunning = true) }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        termux.stopMc()
        repo.updateServerState { it.copy(isRunning = false) }
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
    }
}
