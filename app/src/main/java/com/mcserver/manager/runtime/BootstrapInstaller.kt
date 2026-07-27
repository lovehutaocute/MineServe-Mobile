package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream


/**
 * Bootstrap 安装器（Termux 原生模式，不依赖 proot）：
 * 1. 下载 Termux bootstrap rootfs（从 Termux 官方镜像）→ filesDir/tmp/
 * 2. 校验 SHA256，解压到 filesDir/home/
 * 3. 初始化目录结构（server 目录、eula、plugins、apt sources.list）
 *
 * 命令直接用 rootfs 里的 usr/bin/bash 执行，不需要 proot。
 * 所有下载操作均支持断点续传（HTTP Range）+ 进度回调。
 * XZ/tar 解压使用纯 Java 库（org.tukaani:xz + commons-compress），不依赖系统命令。
 */
class BootstrapInstaller(private val context: Context) {

    val rootDir: File get() = File(context.filesDir, "home")
    val nativeDir: File get() = File(context.filesDir, "native")
    val tmpDir: File get() = File(context.filesDir, "tmp").apply { mkdirs() }
    val runtimeDir: File get() = File(context.filesDir, "runtime").apply { mkdirs() }

    /** MC 服务器日志文件路径（startLogWatcher 监视此文件） */
    val logFile: File get() = File(rootDir, "home/server/logs/latest.log")

    val socketFile: File get() = File(runtimeDir, "mc.sock")

    /** ConsoleSocketServer 使用的 socket 文件（位于 rootDir/tmp） */
    fun ensureSocketFile(): File {
        val dir = File(rootDir, "tmp").apply { mkdirs() }
        return File(dir, "mc.sock")
    }

    private val readyFile: File get() = File(context.filesDir, ".bootstrap_ready")

    /** 日志回调，供 UI 显示下载进度 */
    var onLog: ((String) -> Unit)? = null

    private fun log(msg: String) {
        Log.i(TAG, msg)
        onLog?.invoke(msg)
    }

    /** 当前设备 ABI 对应的 Termux 架构名 */
    private val termuxArch: String get() = when {
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") } -> "aarch64"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x86_64"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm") } -> "arm"
        else -> "aarch64" // 兜底
    }

    fun isReady(): Boolean = readyFile.exists() &&
            File(rootDir, "usr/bin/bash").exists()

    /**
     * 完整安装流程。返回是否成功。
     * 所有步骤失败时抛出可恢复异常，由 UI 层捕获并引导用户重试。
     */
    suspend fun ensureInstalled(onProgress: (InstallPhase, Int) -> Unit): Boolean {
        if (isReady()) {
            log("Termux 环境已就绪")
            return true
        }
        return withContext(Dispatchers.IO) {
            try {
                // 步骤 1: 下载 bootstrap rootfs (.zip)
                log("开始下载 Termux 运行环境")
                onProgress(InstallPhase.DOWNLOAD_ROOTFS, 5)
                val rootfsFile = File(tmpDir, "bootstrap-${termuxArch}.zip")
                val expectedSha = bootstrapSha256[termuxArch]
                val needDownload = !rootfsFile.exists() ||
                    (expectedSha != null && !expectedSha.equals(sha256Hex(rootfsFile), ignoreCase = true))
                if (needDownload) {
                    if (rootfsFile.exists()) {
                        log("缓存的 rootfs 损坏，重新下载")
                        rootfsFile.delete()
                    }
                    downloadBootstrap(rootfsFile) { p ->
                        onProgress(InstallPhase.DOWNLOAD_ROOTFS, p)
                    }
                } else {
                    log("已存在缓存的 rootfs，跳过下载")
                }

                // 步骤 2: 解压 rootfs
                log("开始解压...")
                onProgress(InstallPhase.EXTRACT_ROOTFS, 50)
                extractRootfs(rootfsFile)
                log("解压完成")

                // 步骤 3: 后置初始化
                log("初始化配置...")
                onProgress(InstallPhase.POST_SETUP, 90)
                postSetup()

                readyFile.writeText(System.currentTimeMillis().toString())
                onProgress(InstallPhase.DONE, 100)
                log("Termux 环境初始化完成")
                true
            } catch (e: Exception) {
                Log.e(TAG, "bootstrap failed: ${e.message}", e)
                log("初始化失败: ${e.message}")
                false
            }
        }
    }

    // ── 纯 Java ZIP 解压（核心生产化实现）─────────────────────

    /**
     * 解压 .zip 到目标目录（用于 Termux bootstrap rootfs）。
     * 纯 Java 实现，不依赖系统 unzip 命令。
     */
    private fun extractZipToDir(zipFile: File, destDir: File) {
        destDir.mkdirs()
        var fileCount = 0
        FileInputStream(zipFile).buffered().use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.removePrefix("./")
                    val outFile = File(destDir, entryName)

                    // 防 zip-slip / path traversal
                    val canonicalDest = destDir.canonicalPath + File.separator
                    val canonicalEntry = outFile.canonicalPath
                    if (!canonicalEntry.startsWith(canonicalDest)) {
                        Log.w(TAG, "skip unsafe path: $entryName")
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zis.copyTo(out)
                        }
                        // 还原可执行权限（Unix 权限位在 zip entry 的 extra 字段中）
                        // Termux bootstrap 里的二进制需要可执行
                        if (entryName.startsWith("usr/bin/") || entryName.startsWith("usr/libexec/")) {
                            outFile.setExecutable(true, false)
                        }
                        fileCount++
                    }
                    entry = zis.nextEntry
                }
            }
        }
        Log.i(TAG, "extracted $fileCount files from ${zipFile.name} → ${destDir.absolutePath}")
    }

    // ── 下载 bootstrap rootfs ─────────────────────────────────────

    // 各架构对应的 SHA256（来自 GitHub release 页面，硬编码避免下载校验文件 404）
    private val bootstrapSha256 = mapOf(
        "aarch64" to "1f48f4d05da9fab3ce74fb1d9b137fdbc745ba1f7a6f9e8f743fd89b7047d17b",
        "arm" to "99b52156285beffbd79b565b7598ffca2e56fe2ee5e82531c4cdfcfc74d11eb2",
        "i686" to "849417137d11c5665ed4d0ec3385edd4b7acf531d236f478aa78c22e4068891e",
        "x86_64" to "2addf378b964f4258504eb0ac439248b7d261b57efedfbdd6a9a26f82c294875"
    )

    private fun downloadBootstrap(
        rootfsFile: File,
        onProgress: (Int) -> Unit
    ) {
        tmpDir.mkdirs()
        val version = "bootstrap-2026.05.24-r1%2Bapt.android-7"
        val arch = termuxArch

        // GitHub 直连 + 镜像备选
        val githubBase = "https://github.com/termux/termux-packages/releases/download"
        val mirrorBase = "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download"
        val rootfsUrl = "$githubBase/$version/bootstrap-$arch.zip"
        val rootfsMirrorUrl = "$mirrorBase/$version/bootstrap-$arch.zip"

        // 下载 rootfs，GitHub 失败则用镜像
        log("下载 Termux 运行环境 (~30MB)...")
        try {
            httpDownload(rootfsUrl, rootfsFile, 15..45, onProgress) { msg ->
                log(msg)
            }
        } catch (e: Exception) {
            log("GitHub 直连失败: ${e.message}，尝试镜像...")
            rootfsFile.delete()
            httpDownload(rootfsMirrorUrl, rootfsFile, 15..45, onProgress) { msg ->
                log(msg)
            }
        }

        // 校验
        log("校验文件完整性...")
        val expected = bootstrapSha256[arch]
        if (expected != null) {
            val actual = sha256Hex(rootfsFile)
            if (!expected.equals(actual, ignoreCase = true)) {
                rootfsFile.delete()
                throw RuntimeException("SHA256 校验失败，文件可能损坏")
            }
        }
        log("校验通过")
    }

    private fun sha256Hex(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).buffered().use { `in` ->
            val buf = ByteArray(8192)
            var n: Int
            while (`in`.read(buf).also { n = it } != -1) {
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    // ── 解压 rootfs ────────────────────────────────────────────────

    private fun extractRootfs(rootfsFile: File) {
        rootDir.mkdirs()
        Log.i(TAG, "extracting rootfs via Java ZipInputStream")
        extractZipToDir(rootfsFile, rootDir)
    }

    // ── 后置初始化 ─────────────────────────────────────────────────

    private fun postSetup() {
        // 创建 MC 工作目录与 eula 同意
        val serverDir = File(rootDir, "home/server").apply { mkdirs() }
        val eula = File(serverDir, "eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
        // 创建 plugins 目录
        File(serverDir, "plugins").mkdirs()

        // 配置 apt 源
        val etcDir = File(rootDir, "usr/etc")
        etcDir.mkdirs()
        File(etcDir, "apt/sources.list").let { f ->
            f.parentFile?.mkdirs()
            if (!f.exists()) {
                f.writeText("deb https://packages.termux.dev/apt/termux-main stable main\n")
            }
        }
    }

    // ── HTTP 下载工具 ──────────────────────────────────────────────

    private fun httpDownload(
        urlStr: String,
        target: File,
        range: IntRange,
        onProgress: (Int) -> Unit,
        onLog: ((String) -> Unit)? = null
    ) {
        target.parentFile?.mkdirs()
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MCServerManager/1.0")
        // 支持断点续传
        val existing = if (target.exists()) target.length() else 0L
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=$existing-")
        }

        conn.connect()
        val code = conn.responseCode
        if (code !in 200..299 && code != 416) {
            conn.disconnect()
            throw RuntimeException("HTTP $code: $urlStr")
        }

        val contentLength = conn.contentLengthLong
        val total = if (contentLength > 0) contentLength + existing else -1L
        val input = conn.inputStream
        val output = FileOutputStream(target, existing > 0)

        val buf = ByteArray(8192)
        var read: Int
        var downloaded = existing
        var lastLogPct = -1
        while (input.read(buf).also { read = it } != -1) {
            output.write(buf, 0, read)
            downloaded += read

            if (total > 0) {
                val pct = range.first + ((range.last - range.first) * downloaded / total).toInt()
                onProgress(pct.coerceIn(range.first, range.last))

                // 每下载 10% 输出一次日志
                val logPct = (downloaded * 100 / total).toInt() / 10 * 10
                if (logPct != lastLogPct && logPct > 0) {
                    lastLogPct = logPct
                    onLog?.invoke("已下载 $logPct% (${downloaded / 1024 / 1024}MB)")
                }
            } else {
                // chunked encoding: 没有 Content-Length，按已下载字节数估算
                val mb = (downloaded / 1024 / 1024).toInt()
                onProgress(range.first + (mb.coerceAtMost(30) * (range.last - range.first) / 30).toInt())
                if (mb != lastLogPct) {
                    lastLogPct = mb
                    onLog?.invoke("已下载 ${mb}MB")
                }
            }
        }
        output.close()
        input.close()
        conn.disconnect()
    }

    enum class InstallPhase(val label: String) {
        DOWNLOAD_ROOTFS("下载 Linux 文件系统"),
        EXTRACT_ROOTFS("解压文件系统"),
        POST_SETUP("初始化配置"),
        DONE("完成")
    }

    companion object { private const val TAG = "BootstrapInstaller" }
}
