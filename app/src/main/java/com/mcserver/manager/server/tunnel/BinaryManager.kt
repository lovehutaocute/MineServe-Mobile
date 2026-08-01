package com.mcserver.manager.server.tunnel

import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 隧道二进制管理。
 * frpc 优先级：GitHub 最新版下载（缓存） > apt 兜底
 * 说明：旧版 frpc 不识别 autoTLS 等新配置字段，故优先从 GitHub 下载最新版。
 */
class BinaryManager(private val termux: TermuxRuntime) {

    private val binDir: File
        get() = File(termux.installer.rootDir, "home/tunnel/bin").apply { mkdirs() }

    private val arch: String
        get() = if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) "arm64" else "amd64"

    /** GitHub API 不可达时的回退版本 */
    private val fallbackFrpVersion = "0.61.2"

    /** 最新 frp 版本（惰性获取：首次经 GitHub API 查询，失败回退固定版本） */
    private val latestFrpVersion: String by lazy {
        runBlocking { fetchLatestFrpVersion() } ?: fallbackFrpVersion
    }

    private fun frpReleaseUrl(version: String): String =
        "https://github.com/fatedier/frp/releases/download/v$version/frp_${version}_linux_$arch.tar.gz"

    /** 从 GitHub API 获取最新 release tag（如 0.61.2），失败返回 null */
    private suspend fun fetchLatestFrpVersion(): String? = withContext(Dispatchers.IO) {
        try {
            val conn = (URL("https://api.github.com/repos/fatedier/frp/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                setRequestProperty("Accept", "application/vnd.github+json")
                connect()
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().readText()
                conn.disconnect()
                Regex("\"tag_name\"\\s*:\\s*\"v?([\\d.]+)\"").find(body)?.groupValues?.get(1)
            } else {
                conn.disconnect()
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** GitHub 镜像源（依次尝试） */
    private val mirrors = listOf(
        "",  // 直连
        "https://gh-proxy.com/",
        "https://mirror.ghproxy.com/",
        "https://ghproxy.net/",
        "https://ghfast.top/"
    )

    /**
     * 确保 frpc 可用：
     * 1) 缓存版（GitHub 最新版）存在 → 直接返回
     * 2) 都没有 → GitHub 下载最新版（走镜像） → 失败回退 apt-get install
     */
    fun ensureFrp(): String? {
        // 1. 缓存版（GitHub 最新版）
        val cached = File(binDir, "frpc")
        if (cached.exists() && cached.canExecute()) return cached.absolutePath

        // 2. 下载最新版（同步阻塞调用内部使用 runBlocking 等待并行结果）
        termux.emitLog("[tunnel] frpc 未安装，正在获取最新版并下载...")
        return runBlocking { downloadLatestFrp() } ?: installViaApt()
    }

    /** 检查是否已安装 GitHub 缓存版 frpc（apt 旧版不算，确保替换为最新版） */
    fun isFrpInstalled(): Boolean {
        return File(binDir, "frpc").canExecute()
    }

    /**
     * 一键依赖安装 frp（幂等：已缓存最新版则跳过）。
     * 供 McServerController 调用。
     */
    fun installFrpOnce(): Boolean {
        if (isFrpInstalled()) {
            termux.emitLog("[tunnel] frpc 最新版已安装，跳过")
            return true
        }
        termux.emitLog("[tunnel] 正在下载最新版 frpc...")
        return ensureFrp() != null
    }

    // ── 内部实现 ──────────────────────────────────────────────

    private suspend fun downloadLatestFrp(): String? {
        val version = latestFrpVersion
        termux.emitLog("[tunnel] 最新 frpc 版本: v$version")
        val url = frpReleaseUrl(version)
        // 镜像并行下载，首个成功即返回（避免直连超时时串行等待 ~200s）
        return coroutineScope {
            val results = mirrors.map { mirror ->
                val u = if (mirror.isEmpty()) url else "$mirror$url"
                async(Dispatchers.IO) { tryDownloadFrp(u, mirror, version) }
            }
            // 依次等待结果，返回第一个成功的
            for (deferred in results) {
                val path = try { deferred.await() } catch (_: Exception) { null }
                if (path != null) return@coroutineScope path
            }
            null
        }
    }

    private fun tryDownloadFrp(url: String, mirror: String, version: String): String? {
        termux.emitLog("[tunnel] 尝试下载 frpc: ${url.take(80)}...")
        try {
            val tgz = File(binDir, "frp-${Integer.toHexString(mirror.hashCode())}.tar.gz")
            if (downloadFile(url, tgz)) {
                termux.execOnce("tar", "-xzf", tgz.absolutePath, "-C", binDir.absolutePath,
                    "--strip-components=1", "frp_${version}_linux_$arch/frpc")
                tgz.delete()
                val frpc = File(binDir, "frpc")
                if (frpc.exists()) {
                    termux.execOnce("chmod", "755", frpc.absolutePath)
                    termux.emitLog("[tunnel] frpc 下载完成: ${frpc.absolutePath}")
                    return frpc.absolutePath
                }
            }
            tgz.delete()
        } catch (e: Exception) {
            termux.emitLog("[tunnel] $mirror 下载失败: ${e.message}")
        }
        return null
    }

    private fun installViaApt(): String? {
        termux.emitLog("[tunnel] GitHub 下载失败，尝试 apt 安装...")
        termux.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
        termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "frp")
        return findAptBinary(termux.installer.rootDir.absolutePath, "frpc")
    }

    private fun findAptBinary(prefix: String, binaryName: String): String? {
        val candidates = listOf(
            File("$prefix/bin/$binaryName"),
            File("$prefix/data/data/com.termux/files/usr/bin/$binaryName"),
            File("$prefix/usr/bin/$binaryName")
        )
        val found = candidates.firstOrNull { it.exists() && it.canExecute() } ?: return null
        val link = File("$prefix/bin/$binaryName")
        if (found.absolutePath != link.absolutePath) {
            link.parentFile?.mkdirs()
            if (!link.exists()) {
                try { termux.execOnce("ln", "-sf", found.absolutePath, link.absolutePath) }
                catch (_: Exception) { termux.execOnce("cp", found.absolutePath, link.absolutePath); termux.execOnce("chmod", "755", link.absolutePath) }
            }
        }
        return if (link.exists() && link.canExecute()) link.absolutePath else found.absolutePath
    }

    private fun downloadFile(urlStr: String, target: File): Boolean {
        var currentUrl = urlStr
        repeat(5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 8000
                readTimeout = 30000
                connect()
            }
            when (conn.responseCode) {
                in 301..308 -> { currentUrl = conn.getHeaderField("Location") ?: return false; conn.disconnect() }
                200 -> {
                    conn.inputStream.use { i -> FileOutputStream(target).use { o -> i.copyTo(o) } }
                    conn.disconnect()
                    return target.exists() && target.length() > 1000
                }
                else -> { conn.disconnect(); return false }
            }
        }
        return false
    }
}
