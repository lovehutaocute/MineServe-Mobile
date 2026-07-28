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
                    InstallStep.Jdk -> termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "openjdk-17")
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
     * 下载到 /home/server/server.jar
     */
    suspend fun downloadCore(config: McConfig) = withContext(Dispatchers.IO) {
        // 在 APP 层（Java HTTP）解析真实下载 URL，再通过 proot 内的 wget 下载
        val url = resolveDownloadUrl(config.selectedCore, config.mcVersion)
        val code = termux.execOnce(
            "wget", "-q", "--show-progress",
            "-O", "/home/server/server.jar",
            url
        )
        if (code != 0) {
            throw RuntimeException("Failed to download server core: exit code $code")
        }
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

    // ── PaperMC：动态获取最新 SUCCESS build 号 ──────────────────────

    private fun resolvePaperUrl(version: String): String {
        val buildsUrl = "https://api.papermc.io/v2/projects/paper/versions/$version/builds"
        val resp = fetchJson(buildsUrl)
        val builds = resp["builds"]?.jsonArray
            ?: throw RuntimeException("PaperMC: no builds for version $version")

        var latestBuild = -1
        for (entry in builds) {
            val obj = entry.jsonObject
            val buildNum = obj["build"]?.jsonPrimitive?.content?.toIntOrNull() ?: continue
            val result = obj["result"]?.jsonPrimitive?.content
            if (result == "SUCCESS" && buildNum > latestBuild) {
                latestBuild = buildNum
            }
        }
        if (latestBuild < 0) {
            throw RuntimeException("PaperMC: no successful build for version $version")
        }
        return "https://api.papermc.io/v2/projects/paper/versions/$version/builds/$latestBuild/downloads/paper-$version-$latestBuild.jar"
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

    private fun fetchJsonElement(urlStr: String): JsonElement {
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 30_000
        conn.setRequestProperty("User-Agent", "MCServerManager/1.0 (https://github.com/mcserver-manager)")
        conn.setRequestProperty("Accept", "application/json")
        try {
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            return json.parseToJsonElement(body)
        } finally {
            conn.disconnect()
        }
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
     */
    private suspend fun launchMc(config: McConfig) {
        termux.startMc(
            jarPath = "/home/server/server.jar",
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

    companion object { private const val TAG = "McServerController" }
}
