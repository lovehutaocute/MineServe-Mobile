package com.mcserver.manager.server.tunnel

import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 隧道二进制管理。
 * frpc 优先级：缓存版 > apt 版 > GitHub 下载（走镜像源）
 */
class BinaryManager(private val termux: TermuxRuntime) {

    private val binDir: File
        get() = File(termux.installer.rootDir, "home/tunnel/bin").apply { mkdirs() }

    private val arch: String
        get() = if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) "arm64" else "amd64"

    /** frp GitHub release 固定版本（避免 /latest/download/ 404） */
    private val frpVersion = "0.61.2"
    private val frpReleaseUrl: String
        get() = "https://github.com/fatedier/frp/releases/download/v$frpVersion/frp_${frpVersion}_linux_$arch.tar.gz"

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
     * 1) 缓存版存在 → 直接返回
     * 2) apt 版存在 → 直接返回（不再触发下载）
     * 3) 都没有 → GitHub 下载（走镜像） → 失败回退 apt-get install
     */
    fun ensureFrp(): String? {
        val prefix = termux.installer.rootDir.absolutePath

        // 1. GitHub 缓存版
        val cached = File(binDir, "frpc")
        if (cached.exists() && cached.canExecute()) return cached.absolutePath

        // 2. apt 版
        findAptBinary(prefix, "frpc")?.let {
            termux.emitLog("[tunnel] frpc (apt): $it")
            return it
        }

        // 3. 下载（同步阻塞调用内部使用 runBlocking 等待并行结果）
        termux.emitLog("[tunnel] frpc 未安装，从 GitHub 下载...")
        return kotlinx.coroutines.runBlocking { downloadFrp() } ?: installViaApt()
    }

    /** 检查 frpc 是否已安装（供一键依赖模块幂等判断） */
    fun isFrpInstalled(): Boolean {
        val prefix = termux.installer.rootDir.absolutePath
        return File(binDir, "frpc").canExecute() || findAptBinary(prefix, "frpc") != null
    }

    /**
     * 一键依赖安装 frp（幂等：已安装则跳过）。
     * 供 McServerController 调用。
     */
    fun installFrpOnce(): Boolean {
        if (isFrpInstalled()) {
            termux.emitLog("[tunnel] frpc 已安装，跳过")
            return true
        }
        termux.emitLog("[tunnel] 正在通过 apt 安装 frp...")
        termux.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
        val code = termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "frp")
        return code == 0
    }

    // ── 内部实现 ──────────────────────────────────────────────

    private suspend fun downloadFrp(): String? {
        // 镜像并行下载，首个成功即返回（避免直连超时时串行等待 ~200s）
        return kotlinx.coroutines.coroutineScope {
            val results = mirrors.map { mirror ->
                val url = if (mirror.isEmpty()) frpReleaseUrl else "$mirror$frpReleaseUrl"
                kotlinx.coroutines.async(kotlinx.coroutines.Dispatchers.IO) { tryDownloadFrp(url, mirror) }
            }
            // 依次等待结果，返回第一个成功的
            for (deferred in results) {
                val path = try { deferred.await() } catch (_: Exception) { null }
                if (path != null) return@coroutineScope path
            }
            null
        }
    }

    private fun tryDownloadFrp(url: String, mirror: String): String? {
        termux.emitLog("[tunnel] 尝试下载 frpc: ${url.take(80)}...")
        try {
            val tgz = File(binDir, "frp-${Integer.toHexString(mirror.hashCode())}.tar.gz")
            if (downloadFile(url, tgz)) {
                termux.execOnce("tar", "-xzf", tgz.absolutePath, "-C", binDir.absolutePath,
                    "--strip-components=1", "frp_${frpVersion}_linux_$arch/frpc")
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
