package com.mcserver.manager.server.tunnel

import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 隧道二进制管理：下载、缓存、校验。
 *
 * 支持的二进制：
 *  - playit: https://github.com/playit-cloud/playit-agent/releases
 *  - cloudflared: Termux apt 优先，回退 GitHub Release
 *  - ngrok: equinox.io 下载 tgz 解压
 *  - frp: Termux apt 安装
 */
class BinaryManager(private val termux: TermuxRuntime) {

    /** 二进制存放目录 */
    private val binDir: File
        get() = File(termux.installer.rootDir, "home/tunnel/bin").apply { mkdirs() }

    /** 设备架构 */
    val arch: String
        get() = if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) "arm64" else "386"

    /** 下载 URL 映射 */
    private val downloadUrls = mapOf(
        "playit" to if (arch == "arm64")
            "https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-aarch64"
        else "https://github.com/playit-cloud/playit-agent/releases/latest/download/playit-linux-386",

        "cloudflared" to if (arch == "arm64")
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        else "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-386",

        "ngrok" to if (arch == "arm64")
            "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz"
        else "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-386.tgz"
    )

    /**
     * 确保二进制可用，返回可执行文件的绝对路径。
     * 优先查找 Termux apt 安装版本，其次查找本地缓存，最后从网络下载。
     */
    fun ensure(binaryName: String): String? {
        val prefix = termux.installer.rootDir.absolutePath

        // 1. 优先 apt 安装路径
        val aptPath = findAptBinary(prefix, binaryName)
        if (aptPath != null) return aptPath

        // 2. 本地缓存
        val cached = File(binDir, binaryName)
        if (cached.exists() && cached.canExecute()) return cached.absolutePath

        // 3. 网络下载
        val url = downloadUrls[binaryName] ?: return null
        return downloadAndInstall(binaryName, url)
    }

    /**
     * frp 特殊处理：通过 apt-get 安装
     */
    fun ensureFrp(): String? {
        val prefix = termux.installer.rootDir.absolutePath
        findAptBinary(prefix, "frpc")?.let { return it }

        termux.emitLog("[tunnel] frp 未安装，正在通过 apt 自动安装...")
        termux.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
        val code = termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "frp")
        if (code != 0) return null

        return findAptBinary(prefix, "frpc")
    }

    /**
     * cloudflared 特殊处理：优先 apt，失败回退 GitHub
     */
    fun ensureCloudflared(): String? {
        val prefix = termux.installer.rootDir.absolutePath
        // apt 优先
        findAptBinary(prefix, "cloudflared")?.let {
            termux.emitLog("[tunnel] cloudflared (apt): $it")
            return it
        }
        // 尝试 apt 安装
        termux.emitLog("[tunnel] 尝试 apt install cloudflared...")
        termux.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
        termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "cloudflared")
        findAptBinary(prefix, "cloudflared")?.let {
            termux.emitLog("[tunnel] cloudflared apt 安装成功: $it")
            return it
        }
        // 回退 GitHub
        termux.emitLog("[tunnel] apt 失败，从 GitHub 下载 cloudflared...")
        return ensure("cloudflared")
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

    private fun downloadAndInstall(binaryName: String, urlStr: String): String? {
        return try {
            val target = File(binDir, binaryName)
            termux.emitLog("[tunnel] 正在下载 $binaryName ...")

            if (binaryName == "ngrok") {
                // ngrok 是 tgz，需要解压
                downloadNgrok(urlStr)
            } else {
                // 直链下载
                downloadFile(urlStr, target)
                termux.execOnce("chmod", "755", target.absolutePath)
            }

            if (target.exists() && target.canExecute()) target.absolutePath else null
        } catch (e: Exception) {
            termux.emitLog("[tunnel] 下载 $binaryName 失败: ${e.message}")
            null
        }
    }

    private fun downloadNgrok(urlStr: String): String? {
        val tgz = File(binDir, "ngrok.tgz")
        downloadFile(urlStr, tgz)
        termux.execOnce("tar", "-xzf", tgz.absolutePath, "-C", binDir.absolutePath)
        tgz.delete()
        val ngrokBin = File(binDir, "ngrok")
        return if (ngrokBin.exists()) {
            termux.execOnce("chmod", "755", ngrokBin.absolutePath)
            ngrokBin.absolutePath
        } else null
    }

    private fun downloadFile(urlStr: String, target: File): Boolean {
        val url = URL(urlStr)
        // Follow redirects
        var conn: HttpURLConnection = url.openConnection() as HttpURLConnection
        var redirects = 0
        while (redirects < 5) {
            conn = url.openConnection() as HttpURLConnection
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.connect()
            val code = conn.responseCode
            if (code in 301..308) {
                val location = conn.getHeaderField("Location") ?: break
                conn.disconnect()
                val newUrl = URL(location)
                redirects++
                continue
            }
            break
        }

        if (conn.responseCode != 200) {
            termux.emitLog("[tunnel] 下载失败 HTTP ${conn.responseCode}")
            return false
        }

        conn.inputStream.use { input ->
            FileOutputStream(target).use { output ->
                input.copyTo(output)
            }
        }
        conn.disconnect()
        return target.exists() && target.length() > 0
    }
}
