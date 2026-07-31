package com.mcserver.manager.server.tunnel

import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * 隧道二进制管理：下载、缓存、校验。
 *
 * 当前只支持 frp（Termux apt 安装）。
 */
class BinaryManager(private val termux: TermuxRuntime) {

    /** 二进制存放目录 */
    private val binDir: File
        get() = File(termux.installer.rootDir, "home/tunnel/bin").apply { mkdirs() }

    /**
     * frp 通过 apt-get 安装
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
}
