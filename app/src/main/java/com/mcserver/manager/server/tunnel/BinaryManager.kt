package com.mcserver.manager.server.tunnel

import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 隧道二进制管理：下载、缓存、校验。
 *
 * frp：优先从 GitHub 下载最新版（apt 版本过旧，不支持 autoTLS 等新字段）。
 */
class BinaryManager(private val termux: TermuxRuntime) {

    private val binDir: File
        get() = File(termux.installer.rootDir, "home/tunnel/bin").apply { mkdirs() }

    /** 设备架构 */
    private val arch: String
        get() = if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) "arm64" else "amd64"

    /** frp GitHub 最新版下载 URL */
    private val frpDownloadUrl: String
        get() = "https://github.com/fatedier/frp/releases/latest/download/frp_0.61.2_linux_$arch.tar.gz"

    /**
     * frp：优先从 GitHub 下载最新版，apt 版本作为回退。
     */
    fun ensureFrp(): String? {
        val prefix = termux.installer.rootDir.absolutePath

        // 1. 尝试 GitHub 下载版
        val githubFrpc = File(binDir, "frpc")
        if (githubFrpc.exists() && githubFrpc.canExecute()) {
            return githubFrpc.absolutePath
        }

        // 2. 尝试 apt 版（检查版本是否过旧）
        val aptFrpc = findAptBinary(prefix, "frpc")
        if (aptFrpc != null) {
            // 检查 apt 版是否支持新字段
            val verCode = termux.execOnce(aptFrpc, "--version")
            if (verCode == 0) {
                termux.emitLog("[tunnel] 使用 apt frpc，若报 unknown field 请等待自动下载最新版")
            }
        }

        // 3. 从 GitHub 下载最新版
        termux.emitLog("[tunnel] 正在从 GitHub 下载最新版 frpc...")
        return downloadFrpFromGitHub()
    }

    private fun downloadFrpFromGitHub(): String? {
        try {
            val tgz = File(binDir, "frp.tar.gz")
            val url = frpDownloadUrl
            termux.emitLog("[tunnel] 下载: $url")

            downloadFile(url, tgz)
            if (!tgz.exists() || tgz.length() < 1000) {
                termux.emitLog("[tunnel] frp 下载失败，回退 apt")
                tgz.delete()
                return findAptBinary(termux.installer.rootDir.absolutePath, "frpc")
            }

            // 解压
            termux.execOnce("tar", "-xzf", tgz.absolutePath, "-C", binDir.absolutePath,
                "--strip-components=1", "frp_0.61.2_linux_$arch/frpc")
            tgz.delete()

            val frpc = File(binDir, "frpc")
            if (frpc.exists()) {
                termux.execOnce("chmod", "755", frpc.absolutePath)
                termux.emitLog("[tunnel] frpc 最新版已就绪: ${frpc.absolutePath}")
                return frpc.absolutePath
            }

            termux.emitLog("[tunnel] frp 解压失败，回退 apt")
            return findAptBinary(termux.installer.rootDir.absolutePath, "frpc")
        } catch (e: Exception) {
            termux.emitLog("[tunnel] frp GitHub 下载异常: ${e.message}，回退 apt")
            return findAptBinary(termux.installer.rootDir.absolutePath, "frpc")
        }
    }

    // ── 内部实现 ──────────────────────────────────────────────

    private fun findAptBinary(prefix: String, binaryName: String): String? {
        val candidates = listOf(
            File("$prefix/bin/$binaryName"),
            File("$prefix/data/data/com.termux/files/usr/bin/$binaryName"),
            File("$prefix/usr/bin/$binaryName")
        )
        val found = candidates.firstOrNull { it.exists() && it.canExecute() }
        if (found != null && found.absolutePath != "$prefix/bin/$binaryName") {
            val link = File("$prefix/bin/$binaryName")
            link.parentFile?.mkdirs()
            if (!link.exists()) {
                try {
                    termux.execOnce("ln", "-sf", found.absolutePath, link.absolutePath)
                } catch (_: Exception) {
                    termux.execOnce("cp", found.absolutePath, link.absolutePath)
                    termux.execOnce("chmod", "755", link.absolutePath)
                }
            }
            return if (link.exists() && link.canExecute()) link.absolutePath else found.absolutePath
        }
        return found?.absolutePath
    }

    private fun downloadFile(urlStr: String, target: File): Boolean {
        var currentUrl = urlStr
        var redirects = 0
        while (redirects < 5) {
            val conn = (URL(currentUrl).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 15000
                readTimeout = 60000
                connect()
            }
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                currentUrl = location
                redirects++
                continue
            }
            if (code != 200) {
                termux.emitLog("[tunnel] 下载失败 HTTP $code")
                conn.disconnect()
                return false
            }
            conn.inputStream.use { input ->
                FileOutputStream(target).use { output -> input.copyTo(output) }
            }
            conn.disconnect()
            return target.exists() && target.length() > 0
        }
        return false
    }
}
