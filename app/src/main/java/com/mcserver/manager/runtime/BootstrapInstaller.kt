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
        // 清除旧的 readyFile（可能上次提取失败但写了 readyFile）
        readyFile.delete()
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

                // 如果 rootDir 已有旧内容（上次提取失败），先清除
                if (rootDir.exists() && !File(rootDir, "usr/bin/bash").exists()) {
                    log("清除上次失败的提取...")
                    rootDir.deleteRecursively()
                }

                // 步骤 2: 解压 rootfs（含符号链接处理）
                log("开始解压...")
                onProgress(InstallPhase.EXTRACT_ROOTFS, 50)
                extractRootfs(rootfsFile)
                log("解压完成")

                // 验证关键文件
                val bashFile = File(rootDir, "usr/bin/bash")
                if (!bashFile.exists()) {
                    log("错误: usr/bin/bash 不存在！解压失败")
                    val binDir = File(rootDir, "usr/bin")
                    if (binDir.exists()) {
                        log("usr/bin/ 目录内容: ${binDir.list()?.take(20)?.joinToString(", ")}")
                    } else {
                        log("usr/bin/ 目录不存在")
                        val usrDir = File(rootDir, "usr")
                        log("usr 是目录: ${usrDir.isDirectory}, 是文件: ${usrDir.isFile}")
                        log("rootDir 内容: ${rootDir.list()?.joinToString(", ")}")
                    }
                    throw RuntimeException("解压后 usr/bin/bash 不存在")
                }
                log("bash 文件大小: ${bashFile.length()} 字节")
                val magic = ByteArray(4)
                FileInputStream(bashFile).use { it.read(magic) }
                val isElf = magic[0] == 0x7f.toByte() && magic[1] == 'E'.code.toByte() &&
                            magic[2] == 'L'.code.toByte() && magic[3] == 'F'.code.toByte()
                log("bash ELF 魔数: ${if (isElf) "有效" else "无效!"}")
                bashFile.setExecutable(true, false)

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
     * 关键：处理 SYMLINKS.txt 中的符号链接（ZIP 中符号链接存储为普通文件）。
     */
    private fun extractZipToDir(zipFile: File, destDir: File) {
        destDir.mkdirs()
        var fileCount = 0
        val entryNames = mutableListOf<String>()

        FileInputStream(zipFile).buffered().use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.removePrefix("./")
                    entryNames.add(entryName)
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
                        // 如果路径上已有同名文件（符号链接被提取为文件），先删除
                        if (outFile.exists() && !outFile.isDirectory) outFile.delete()
                        outFile.mkdirs()
                    } else {
                        // 如果父路径是文件（符号链接被提取为文件），逐级删除
                        var parent = outFile.parentFile
                        while (parent != null && parent.exists() && !parent.isDirectory) {
                            parent.delete()
                            parent = parent.parentFile
                        }
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            zis.copyTo(out)
                        }
                        // 设置可执行权限
                        if (entryName.startsWith("usr/bin/") ||
                            entryName.startsWith("usr/libexec/") ||
                            entryName.startsWith("bin/") ||
                            entryName.endsWith("/bash") ||
                            entryName.endsWith("/sh")) {
                            outFile.setExecutable(true, false)
                        }
                        fileCount++
                    }
                    entry = zis.nextEntry
                }
            }
        }
        Log.i(TAG, "extracted $fileCount files from ${zipFile.name} → ${destDir.absolutePath}")
        log("ZIP 提取完成: $fileCount 个文件, ${entryNames.size} 个条目")
        log("前20个条目: ${entryNames.take(20).joinToString(", ")}")

        // ── 创建符号链接（SYMLINKS.txt）──
        // Termux bootstrap 中 bin -> usr/bin, lib -> usr/lib 等是符号链接
        // ZIP 中它们被存为普通文件（内容是目标路径），需要转为真正的符号链接
        val symlinksFile = File(destDir, "SYMLINKS.txt")
        if (symlinksFile.exists()) {
            log("处理符号链接 (SYMLINKS.txt)...")
            var linkCount = 0
            symlinksFile.readLines().forEach { line ->
                val parts = line.trim().split("\\s+".toRegex())
                if (parts.size >= 2) {
                    val linkPath = parts[0]
                    val target = parts[1]
                    val linkFile = File(destDir, linkPath)
                    // 删除被提取为普通文件的符号链接
                    if (linkFile.exists()) {
                        linkFile.delete()
                    }
                    try {
                        android.system.Os.symlink(target, linkFile.absolutePath)
                        linkCount++
                    } catch (e: Exception) {
                        log("符号链接失败: $linkPath → $target: ${e.message}")
                    }
                }
            }
            log("创建了 $linkCount 个符号链接")
        } else {
            log("警告: SYMLINKS.txt 不存在!")
        }

        // 设置 usr/bin/ 下所有文件可执行
        val binDir = File(destDir, "usr/bin")
        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { f ->
                if (f.isFile) f.setExecutable(true, false)
            }
        }
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

        // 多镜像源列表，逐个尝试直到成功
        val mirrors = listOf(
            "https://github.com/termux/termux-packages/releases/download",
            "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download",
            "https://mirror.ghproxy.com/https://github.com/termux/termux-packages/releases/download",
            "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download",
            "https://github.moeyy.xyz/https://github.com/termux/termux-packages/releases/download",
            "https://gh.api.99988866.xyz/https://github.com/termux/termux-packages/releases/download",
            "https://ghfast.top/https://github.com/termux/termux-packages/releases/download"
        )
        val fileName = "bootstrap-$arch.zip"

        log("下载 Termux 运行环境 (~30MB)...")
        var lastError: Exception? = null
        for ((idx, mirror) in mirrors.withIndex()) {
            val url = "$mirror/$version/$fileName"
            val label = if (idx == 0) "GitHub 直连" else "镜像${idx}"
            log("尝试 $label: ${url.take(80)}...")
            try {
                rootfsFile.delete()
                httpDownload(url, rootfsFile, 15..45, onProgress) { msg ->
                    log(msg)
                }
                // 下载成功，校验
                log("校验文件完整性...")
                val expected = bootstrapSha256[arch]
                if (expected != null) {
                    val actual = sha256Hex(rootfsFile)
                    if (!expected.equals(actual, ignoreCase = true)) {
                        rootfsFile.delete()
                        throw RuntimeException("SHA256 校验失败")
                    }
                }
                log("校验通过")
                return
            } catch (e: Exception) {
                log("$label 失败: ${e.message}")
                lastError = e
                rootfsFile.delete()
            }
        }
        throw RuntimeException("所有镜像源均下载失败: ${lastError?.message}")
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
