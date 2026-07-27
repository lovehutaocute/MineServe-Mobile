package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.tukaani.xz.XZInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest


/**
 * Bootstrap 安装器（生产化）：
 * 1. 下载 proot 原生二进制（从 Termux APT 仓库）→ filesDir/native/
 * 2. 下载 Termux bootstrap rootfs（从 Termux 官方镜像）→ filesDir/tmp/
 * 3. 校验 SHA256，解压到 filesDir/home/
 * 4. 初始化目录结构
 *
 * 所有下载操作均支持断点续传（HTTP Range）+ 进度回调。
 * XZ/tar 解压使用纯 Java 库（org.tukaani:xz + commons-compress），不依赖系统命令。
 */
class BootstrapInstaller(private val context: Context) {

    val rootDir: File get() = File(context.filesDir, "home")
    val nativeDir: File get() = File(context.filesDir, "native")
    val tmpDir: File get() = File(context.filesDir, "tmp").apply { mkdirs() }
    val runtimeDir: File get() = File(context.filesDir, "runtime").apply { mkdirs() }

    val socketFile: File get() = File(runtimeDir, "mc.sock")

    /** ConsoleSocketServer 使用的 socket 文件（位于 rootDir/tmp，proot 内可通过 /tmp 访问） */
    fun ensureSocketFile(): File {
        val dir = File(rootDir, "tmp").apply { mkdirs() }
        return File(dir, "mc.sock")
    }

    private val readyFile: File get() = File(context.filesDir, ".bootstrap_ready")

    /** 当前设备 ABI 对应的 Termux 架构名 */
    private val termuxArch: String get() = when {
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") } -> "aarch64"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("x86_64") } -> "x86_64"
        android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm") } -> "arm"
        else -> "aarch64" // 兜底
    }

    fun isReady(): Boolean = readyFile.exists() &&
            File(nativeDir, "proot").canExecute() &&
            File(rootDir, "usr/bin/bash").exists()

    /**
     * 完整安装流程。返回是否成功。
     * 所有步骤失败时抛出可恢复异常，由 UI 层捕获并引导用户重试。
     */
    suspend fun ensureInstalled(onProgress: (InstallPhase, Int) -> Unit): Boolean {
        if (isReady()) return true
        return withContext(Dispatchers.IO) {
            try {
                // 步骤 1: 下载 proot 原生二进制
                onProgress(InstallPhase.DOWNLOAD_PROOT, 5)
                val prootFile = File(nativeDir, "proot")
                if (!prootFile.canExecute()) {
                    downloadProot(prootFile) { p -> onProgress(InstallPhase.DOWNLOAD_PROOT, p) }
                }

                // 步骤 2: 下载 bootstrap rootfs
                onProgress(InstallPhase.DOWNLOAD_ROOTFS, 15)
                val rootfsFile = File(tmpDir, "bootstrap-${termuxArch}.tar.xz")
                val sha256File = File(tmpDir, "bootstrap-${termuxArch}.tar.xz.sha256")
                if (!rootfsFile.exists() || !rootfsSha256Matches(rootfsFile, sha256File)) {
                    downloadBootstrap(rootfsFile, sha256File) { p ->
                        onProgress(InstallPhase.DOWNLOAD_ROOTFS, p)
                    }
                }

                // 步骤 3: 解压 rootfs（纯 Java，不依赖系统 tar/xz）
                onProgress(InstallPhase.EXTRACT_ROOTFS, 50)
                extractRootfs(rootfsFile)

                // 步骤 4: 后置初始化
                onProgress(InstallPhase.POST_SETUP, 90)
                postSetup()

                readyFile.writeText(System.currentTimeMillis().toString())
                onProgress(InstallPhase.DONE, 100)
                true
            } catch (e: Exception) {
                Log.e(TAG, "bootstrap failed: ${e.message}", e)
                false
            }
        }
    }

    // ── 下载 proot ────────────────────────────────────────────────

    private fun downloadProot(target: File, onProgress: (Int) -> Unit) {
        nativeDir.mkdirs()
        // Termux APT 仓库 proot 包的 .deb 链接
        // 从 deb 中提取 data.tar.xz → usr/bin/proot
        val debUrl = "https://packages.termux.dev/apt/termux-main/pool/main/p/proot/proot_5.4.2_${termuxArch}.deb"
        val debFile = File(tmpDir, "proot_${termuxArch}.deb")
        httpDownload(debUrl, debFile, 5..30, onProgress)

        // 从 .deb 中提取 proot 二进制（deb 是 ar 归档，内嵌 data.tar.xz）
        extractDeb(debFile, target)
        target.setExecutable(true)
        Log.i(TAG, "proot binary ready: ${target.absolutePath}")
    }

    /** 极简 ar 归档提取：只取 data.tar.xz → 解压 → 取 ./usr/bin/proot */
    private fun extractDeb(debFile: File, prootOut: File) {
        // deb = ar 归档：文件头 "!<arch>"，每项 60 字节 header + 数据
        val tempData = File(tmpDir, "data.tar.xz")
        FileInputStream(debFile).buffered().use { `in` ->
            val header = ByteArray(8)
            `in`.read(header) // "!<arch>\n"
            while (true) {
                val entry = ByteArray(60)
                val bytesRead = `in`.read(entry)
                if (bytesRead < 60) break
                val name = String(entry, 0, 16).trim()
                val sizeStr = String(entry, 48, 10).trim()
                val size = sizeStr.toLongOrNull() ?: 0L
                if (name.startsWith("data.tar")) {
                    tempData.outputStream().use { out ->
                        `in`.copyTo(out, size.toInt())
                    }
                    // 用纯 Java 解压 data.tar.xz 并提取 proot 二进制
                    extractFileFromTarXz(tempData, prootOut, "usr/bin/proot")
                    break
                } else {
                    `in`.skip(size + (size % 2))
                }
            }
        }
    }

    // ── 纯 Java XZ + tar 解压（核心生产化实现）─────────────────────

    /**
     * 从 .tar.xz 中提取单个文件。
     * 使用 org.tukaani:xz 解压 XZ 层，commons-compress 解 tar 层。
     */
    private fun extractFileFromTarXz(tarXz: File, outFile: File, pathInTar: String) {
        outFile.parentFile?.mkdirs()
        FileInputStream(tarXz).buffered().use { fis ->
            XZInputStream(fis).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry as? TarArchiveEntry
                    while (entry != null) {
                        val name = entry.name.removePrefix("./")
                        if (name == pathInTar) {
                            FileOutputStream(outFile).use { out ->
                                tarIn.copyTo(out)
                            }
                            outFile.setExecutable(true)
                            Log.i(TAG, "extracted $pathInTar → ${outFile.absolutePath}")
                            return
                        }
                        entry = tarIn.nextEntry as? TarArchiveEntry
                    }
                }
            }
        }
        throw RuntimeException("File '$pathInTar' not found in tar.xz")
    }

    /**
     * 解压整个 .tar.xz 到目标目录（用于 bootstrap rootfs）。
     * 纯 Java 实现，不依赖系统 tar/xz 命令。
     */
    private fun extractTarXzToDir(tarXz: File, destDir: File) {
        destDir.mkdirs()
        var fileCount = 0
        FileInputStream(tarXz).buffered().use { fis ->
            XZInputStream(fis).use { xzIn ->
                TarArchiveInputStream(xzIn).use { tarIn ->
                    var entry: TarArchiveEntry? = tarIn.nextEntry as? TarArchiveEntry
                    while (entry != null) {
                        if (!tarIn.canReadEntryData(entry)) {
                            entry = tarIn.nextEntry as? TarArchiveEntry
                            continue
                        }
                        val entryName = entry.name.removePrefix("./")
                        val outFile = File(destDir, entryName)

                        // 防 zip-slip / path traversal
                        val canonicalDest = destDir.canonicalPath + File.separator
                        val canonicalEntry = outFile.canonicalPath
                        if (!canonicalEntry.startsWith(canonicalDest)) {
                            Log.w(TAG, "skip unsafe path: $entryName")
                            entry = tarIn.nextEntry as? TarArchiveEntry
                            continue
                        }

                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { out ->
                                tarIn.copyTo(out)
                            }
                            // 还原可执行权限
                            if (entry.mode and 0b001_000_000 != 0) {
                                outFile.setExecutable(true)
                            }
                            fileCount++
                        }
                        entry = tarIn.nextEntry as? TarArchiveEntry
                    }
                }
            }
        }
        Log.i(TAG, "extracted $fileCount files from ${tarXz.name} → ${destDir.absolutePath}")
    }

    // ── 下载 bootstrap rootfs ─────────────────────────────────────

    private fun downloadBootstrap(
        rootfsFile: File,
        sha256File: File,
        onProgress: (Int) -> Unit
    ) {
        tmpDir.mkdirs()
        val baseUrl = "https://packages.termux.dev/apt/termux-main/bootstrap"
        val arch = termuxArch

        // 下载 SHA256 校验文件
        httpDownload("$baseUrl/bootstrap-$arch.tar.xz.sha256", sha256File, 15..20, onProgress)
        // 下载 rootfs
        httpDownload("$baseUrl/bootstrap-$arch.tar.xz", rootfsFile, 20..45, onProgress)

        // 校验
        if (!rootfsSha256Matches(rootfsFile, sha256File)) {
            throw RuntimeException("SHA256 mismatch for bootstrap rootfs")
        }
    }

    private fun rootfsSha256Matches(rootfs: File, sha256File: File): Boolean {
        if (!rootfs.exists() || !sha256File.exists()) return false
        val expected = sha256File.readText().trim().split("\\s+".toRegex()).firstOrNull() ?: return false
        val actual = sha256Hex(rootfs)
        return expected.equals(actual, ignoreCase = true)
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
        // 纯 Java 解压，不再依赖系统 tar/xz
        Log.i(TAG, "extracting rootfs via Java XZInputStream + TarArchiveInputStream")
        extractTarXzToDir(rootfsFile, rootDir)
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
        onProgress: (Int) -> Unit
    ) {
        target.parentFile?.mkdirs()
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        conn.setRequestProperty("User-Agent", "MCServerManager/1.0 (https://github.com/mcserver-manager)")
        // 支持断点续传
        val existing = if (target.exists()) target.length() else 0L
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=$existing-")
        }

        conn.connect()
        val total = conn.contentLengthLong + existing
        val input = conn.inputStream
        val output = FileOutputStream(target, existing > 0)

        val buf = ByteArray(8192)
        var read: Int
        var downloaded = existing
        while (input.read(buf).also { read = it } != -1) {
            output.write(buf, 0, read)
            downloaded += read
            if (total > 0) {
                val pct = range.first + ((range.last - range.first) * downloaded / total).toInt()
                onProgress(pct.coerceIn(range.first, range.last))
            }
        }
        output.close()
        input.close()
        conn.disconnect()
    }

    enum class InstallPhase(val label: String) {
        DOWNLOAD_PROOT("下载 proot 运行环境"),
        DOWNLOAD_ROOTFS("下载 Linux 文件系统"),
        EXTRACT_ROOTFS("解压文件系统"),
        POST_SETUP("初始化配置"),
        DONE("完成")
    }

    companion object { private const val TAG = "BootstrapInstaller" }
}
