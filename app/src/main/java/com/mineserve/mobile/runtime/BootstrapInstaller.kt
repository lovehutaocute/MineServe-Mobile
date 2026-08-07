package com.mineserve.mobile.runtime

import android.content.Context
import android.util.Log
import com.mineserve.mobile.data.DownloadPrefs
import com.mineserve.mobile.data.MultiThreadDownloader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.zip.ZipInputStream
import com.mineserve.mobile.R


/**
 * Bootstrap 安装器（Termux 原生模式，不依赖 proot）：
 * 1. 下载 Termux bootstrap rootfs（从 Termux 官方镜像）→ filesDir/tmp/
 * 2. 校验 SHA256，解压到 filesDir/home/
 * 3. 初始化目录结构（server 目录、eula、plugins、apt sources.list）
 *
 * 命令直接用 rootfs 里的 bin/bash 执行，不需要 proot。
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

    /** 速度回调（已下载字节, 速度 bytes/s） */
    var onSpeed: ((Long, Long) -> Unit)? = null

    /** 镜像源列表（公开供 UI 显示名称） */
    val mirrorSources: List<String> = listOf(
        "GitHub 直连",
        "gh-proxy.com",
        "mirror.ghproxy.com",
        "ghproxy.net",
        "github.moeyy.xyz",
        "gh.api.99988866.xyz",
        "ghfast.top"
    )

    /** 镜像源 URL 前缀列表 */
    private val mirrorUrls: List<String> = listOf(
        "https://github.com/termux/termux-packages/releases/download",
        "https://gh-proxy.com/https://github.com/termux/termux-packages/releases/download",
        "https://mirror.ghproxy.com/https://github.com/termux/termux-packages/releases/download",
        "https://ghproxy.net/https://github.com/termux/termux-packages/releases/download",
        "https://github.moeyy.xyz/https://github.com/termux/termux-packages/releases/download",
        "https://gh.api.99988866.xyz/https://github.com/termux/termux-packages/releases/download",
        "https://ghfast.top/https://github.com/termux/termux-packages/releases/download"
    )

    /** 当前正在使用的镜像源索引（-1 表示未在下载中） */
    private val _currentMirrorIndex = MutableStateFlow(-1)
    val currentMirrorIndex: StateFlow<Int> = _currentMirrorIndex.asStateFlow()

    /** 停止下载并切换到下一个镜像源的请求标志 */
    @Volatile
    private var stopAndSwitchRequested: Boolean = false

    /** 请求停止当前镜像源下载并切换到下一个 */
    fun requestStopAndSwitch() {
        val idx = _currentMirrorIndex.value
        val label = mirrorSources.getOrElse(idx) { "未知($idx)" }
        Log.w(TAG, "[切换] requestStopAndSwitch 被调用: 当前镜像源=$label(idx=$idx), 设置 stopAndSwitchRequested=true, 线程=${Thread.currentThread().name}")
        stopAndSwitchRequested = true
    }

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
            File(rootDir, "bin/bash").exists()

    /**
     * 删除整个 Termux 运行环境（rootfs + 缓存 + readyFile）。
     * 用于用户主动重置环境或排查问题。
     */
    fun deleteBootstrap() {
        log("开始删除 Termux 运行环境...")
        readyFile.delete()
        rootDir.deleteRecursively()
        // 清除缓存的 rootfs zip（所有架构）
        tmpDir.listFiles()?.filter { it.name.startsWith("bootstrap-") }?.forEach { it.delete() }
        // 清除 runtime 目录（socket 等）
        runtimeDir.listFiles()?.forEach { it.deleteRecursively() }
        log("Termux 运行环境已删除")
    }

    /**
     * 完整安装流程。返回是否成功。
     * 所有步骤失败时抛出可恢复异常，由 UI 层捕获并引导用户重试。
     */
    suspend fun ensureInstalled(onProgress: (InstallPhase, Int) -> Unit): Boolean {
        if (isReady()) {
            log("Termux 环境已就绪")
            // 检查关键文件是否存在（旧版本可能未生成）
            val caBundle = File(rootDir, "etc/ssl/certs/ca-certificates.crt")
            val gpgvWrapper = File(rootDir, "lib/apt/methods/gpgv")
            val dpkgWrapper = File(rootDir, "bin/dpkg")
            val needPostSetup = !caBundle.exists() || caBundle.length() == 0L ||
                // gpgv 包装脚本需要包含 Capabilities（旧版本是 exit 0）
                (gpgvWrapper.exists() && !gpgvWrapper.readText().contains("Capabilities")) ||
                // dpkg 包装脚本需要包含兼容符号链接（旧版本未创建导致 .deb 解压位置错误）
                (dpkgWrapper.exists() && !dpkgWrapper.readText().contains("compat symlink"))
            if (needPostSetup) {
                log("补充配置（旧版本初始化时未生成）...")
                withContext(Dispatchers.IO) { postSetup() }
            }
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
                if (rootDir.exists() && !File(rootDir, "bin/bash").exists()) {
                    log("清除上次失败的提取...")
                    rootDir.deleteRecursively()
                }

                // 步骤 2: 解压 rootfs（含符号链接处理）
                log("开始解压...")
                onProgress(InstallPhase.EXTRACT_ROOTFS, 50)
                extractRootfs(rootfsFile)
                log("解压完成")

                // 验证关键文件
                val bashFile = File(rootDir, "bin/bash")
                if (!bashFile.exists()) {
                    log("错误: bin/bash 不存在！解压失败")
                    val binDir = File(rootDir, "bin")
                    if (binDir.exists()) {
                        log("bin/ 目录内容: ${binDir.list()?.take(20)?.joinToString(", ")}")
                    } else {
                        log("bin/ 目录不存在")
                        log("rootDir 内容: ${rootDir.list()?.joinToString(", ")}")
                    }
                    throw RuntimeException("解压后 bin/bash 不存在")
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
                        if (entryName.startsWith("bin/") ||
                            entryName.startsWith("libexec/") ||
                            entryName.endsWith("/bash") ||
                            entryName.endsWith("/sh") ||
                            entryName.endsWith("/apt-get") ||
                            entryName.endsWith("/tmux") ||
                            entryName.endsWith("/java") ||
                            entryName.endsWith("/wget") ||
                            entryName.endsWith("/curl")) {
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
        // Termux bootstrap 的符号链接信息在 SYMLINKS.txt 中
        // 格式: <target> ← <link_path> (Unicode 左箭头 U+2190 分隔)
        val symlinksFile = File(destDir, "SYMLINKS.txt")
        if (symlinksFile.exists()) {
            log("处理符号链接 (SYMLINKS.txt)...")
            var linkCount = 0
            var failCount = 0
            symlinksFile.readLines().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                // 用 Unicode 左箭头 ← (U+2190) 分割: target ← link_path
                val arrowIdx = trimmed.indexOf("←")
                if (arrowIdx > 0) {
                    val target = trimmed.substring(0, arrowIdx).trim()
                    val linkPath = trimmed.substring(arrowIdx + 1).trim().removePrefix("./")
                    val linkFile = File(destDir, linkPath)
                    // 删除被提取为普通文件的符号链接（包括非空目录）
                    if (linkFile.exists()) {
                        if (linkFile.isDirectory) {
                            linkFile.deleteRecursively()
                        } else {
                            linkFile.delete()
                        }
                    }
                    // 创建父目录
                    linkFile.parentFile?.mkdirs()
                    try {
                        android.system.Os.symlink(target, linkFile.absolutePath)
                        linkCount++
                    } catch (e: Exception) {
                        failCount++
                        if (failCount <= 5) {
                            log("符号链接失败: $linkPath → $target: ${e.message}")
                        }
                    }
                }
            }
            log("创建了 $linkCount 个符号链接, $failCount 个失败")
        } else {
            log("警告: SYMLINKS.txt 不存在!")
        }

        // 设置 bin/ 下所有文件可执行
        val binDir = File(destDir, "bin")
        if (binDir.isDirectory) {
            binDir.listFiles()?.forEach { f ->
                if (f.isFile) f.setExecutable(true, false)
            }
        }

        // 设置 lib/apt/methods/ 下所有文件可执行（apt 下载驱动 https/http）
        val methodsDir = File(destDir, "lib/apt/methods")
        if (methodsDir.isDirectory) {
            methodsDir.listFiles()?.forEach { f ->
                if (f.isFile) f.setExecutable(true, false)
            }
        }

        // 设置 libexec/ 下所有文件可执行
        val libexecDir = File(destDir, "libexec")
        if (libexecDir.isDirectory) {
            libexecDir.listFiles()?.forEach { f ->
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
        val fileName = "bootstrap-$arch.zip"

        stopAndSwitchRequested = false
        log("下载 Termux 运行环境 (~30MB)...")
        Log.i(TAG, "[下载] 开始 downloadBootstrap, 共 ${mirrorUrls.size} 个镜像源, arch=$arch, 线程=${Thread.currentThread().name}")
        var lastError: Exception? = null
        for ((idx, mirror) in mirrorUrls.withIndex()) {
            _currentMirrorIndex.value = idx
            val url = "$mirror/$version/$fileName"
            val label = mirrorSources[idx]
            Log.i(TAG, "[下载] === 尝试镜像源 $idx/$label ===")
            log("尝试 $label: ${url.take(80)}...")
            try {
                rootfsFile.delete()
                Log.i(TAG, "[下载] 开始下载（多线程=${DownloadPrefs.isEnabled()}），url=${url.take(100)}")
                if (DownloadPrefs.isEnabled()) {
                    // 多线程分片下载（内置模块），保留镜像源切换与 SHA256 校验
                    kotlinx.coroutines.runBlocking {
                        MultiThreadDownloader.download(
                            url = url,
                            target = rootfsFile,
                            onProgress = { downloaded, total, speedBps ->
                                if (total > 0) {
                                    // 将 0-100% 映射到整体进度的 15..45 区间（与单流保持一致）
                                    val pct = 15 + (30 * downloaded / total).toInt()
                                    onProgress(pct.coerceIn(15, 45))
                                }
                                onSpeed?.invoke(downloaded, speedBps)
                            },
                            onLog = ::log
                        )
                    }
                } else {
                    httpDownload(url, rootfsFile, 15..45, onProgress) { msg ->
                        log(msg)
                    }
                }
                Log.i(TAG, "[下载] 下载返回, 已下载 ${rootfsFile.length()} 字节")
                // 检查是否被用户请求停止切换
                if (stopAndSwitchRequested) {
                    Log.w(TAG, "[切换] httpDownload 返回后检测到 stopAndSwitchRequested=true, 跳过 $label")
                    stopAndSwitchRequested = false
                    rootfsFile.delete()
                    log("用户请求切换镜像源，跳过 $label")
                    lastError = RuntimeException("用户切换镜像源")
                    continue
                }
                // 下载成功，校验
                log("校验文件完整性...")
                val expected = bootstrapSha256[arch]
                if (expected != null) {
                    val actual = sha256Hex(rootfsFile)
                    if (!expected.equals(actual, ignoreCase = true)) {
                        Log.e(TAG, "[下载] SHA256 校验失败: expected=${expected.take(16)}.. actual=${actual.take(16)}..")
                        rootfsFile.delete()
                        throw RuntimeException("SHA256 校验失败")
                    }
                }
                Log.i(TAG, "[下载] 镜像源 $label 下载并校验通过")
                log("校验通过")
                _currentMirrorIndex.value = -1
                return
            } catch (e: Exception) {
                Log.e(TAG, "[下载] 镜像源 $label 异常: ${e.javaClass.simpleName}: ${e.message}", e)
                log("$label 失败: ${e.message}")
                lastError = e
                rootfsFile.delete()
                // 如果是用户主动请求切换，重置标志继续下一个
                if (stopAndSwitchRequested) {
                    Log.i(TAG, "[切换] catch 块检测到 stopAndSwitchRequested=true, 重置标志继续下一个镜像源")
                    stopAndSwitchRequested = false
                }
            }
        }
        Log.e(TAG, "[下载] 所有 ${mirrorUrls.size} 个镜像源均失败, lastError=${lastError?.message}")
        _currentMirrorIndex.value = -1
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
        val prefix = rootDir.absolutePath

        // 修复旧版本 dpkg-wrapper 解压位置错误的问题
        // 旧版本 .deb 包被解压到 PREFIX/data/data/com.termux/files/usr/bin/tmux
        // 但正确位置应该是 PREFIX/bin/tmux
        // 如果发现错误位置的目录（非符号链接），将文件移动到 PREFIX，然后删除目录
        val compatUsr = File(rootDir, "data/data/com.termux/files/usr")
        val isSymlink = try {
            java.nio.file.Files.isSymbolicLink(compatUsr.toPath())
        } catch (_: Exception) { false }
        if (compatUsr.exists() && !isSymlink) {
            // compatUsr 是真实目录（不是符号链接），需要修复
            log("发现旧版本解压的错误目录，开始修复...")
            try {
                // 递归复制 compatUsr 内容到 rootDir
                compatUsr.walkTopDown().forEach { src ->
                    val rel = src.relativeTo(compatUsr)
                    val dst = File(rootDir, rel.path)
                    if (src.isDirectory) {
                        dst.mkdirs()
                    } else if (src.isFile) {
                        if (dst.exists() && !dst.isDirectory) {
                            dst.delete()
                        }
                        if (!dst.isDirectory) {
                            src.copyTo(dst, overwrite = true)
                        }
                    }
                }
                // 删除错误目录
                File(rootDir, "data").deleteRecursively()
                log("错误目录已修复")
            } catch (e: Exception) {
                log("修复错误目录失败: ${e.message}")
            }
        }

        // 创建 MC 工作目录与 eula 同意
        val serverDir = File(rootDir, "home/server").apply { mkdirs() }
        val eula = File(serverDir, "eula.txt")
        if (!eula.exists()) eula.writeText("eula=true\n")
        // 创建 plugins 目录
        File(serverDir, "plugins").mkdirs()

        // 创建 apt/dpkg 所需目录结构
        listOf(
            "etc/apt/apt.conf.d",
            "etc/apt/preferences.d",
            "etc/apt/sources.list.d",
            "etc/apt/trusted.gpg.d",
            "etc/dpkg/dpkg.cfg.d",
            "var/lib/apt/lists/partial",
            "var/cache/apt/archives/partial",
            "var/lib/dpkg",
            "var/lib/dpkg/updates",
            "var/lib/dpkg/info",
            "var/lib/dpkg/triggers",
            "var/log/apt"
        ).forEach { File(rootDir, it).mkdirs() }

        // 强制覆盖 apt 源
        // packages.termux.dev 会 301 重定向到 https，导致 https 方法驱动卡在 SSL 握手
        // 使用清华镜像（支持 http 且不重定向），阿里云备选
        File(rootDir, "etc/apt/sources.list").writeText(
            "deb http://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\n"
        )

        // 创建 apt.conf 覆盖所有编译路径（apt/dpkg 默认指向 /data/data/com.termux/files/usr/）
        val aptConf = File(rootDir, "etc/apt/apt.conf")
        aptConf.writeText(buildString {
            appendLine("Dir \"$prefix\";")
            appendLine("Dir::Prefix \"$prefix\";")
            appendLine("Dir::Etc \"$prefix/etc/apt\";")
            appendLine("Dir::Etc::main \"$prefix/etc/apt/apt.conf\";")
            appendLine("Dir::Etc::parts \"$prefix/etc/apt/apt.conf.d\";")
            appendLine("Dir::Etc::sourcelist \"$prefix/etc/apt/sources.list\";")
            appendLine("Dir::Etc::sourceparts \"$prefix/etc/apt/sources.list.d\";")
            appendLine("Dir::Etc::preferencesparts \"$prefix/etc/apt/preferences.d\";")
            appendLine("Dir::Etc::trustedparts \"$prefix/etc/apt/trusted.gpg.d\";")
            appendLine("Dir::Etc::trusted \"/dev/null\";")
            appendLine("Dir::Bin \"$prefix/bin\";")
            appendLine("Dir::Bin::methods \"$prefix/lib/apt/methods\";")
            appendLine("Dir::Bin::dpkg \"$prefix/bin/dpkg\";")
            appendLine("Dir::Bin::apt-key \"$prefix/bin/apt-key\";")
            appendLine("Dir::Bin::gpg \"$prefix/bin/gpg\";")
            appendLine("Dir::Bin::gpgv \"$prefix/bin/gpgv\";")
            appendLine("Dir::State \"$prefix/var\";")
            appendLine("Dir::State::lists \"$prefix/var/lib/apt/lists\";")
            appendLine("Dir::State::status \"$prefix/var/lib/dpkg/status\";")
            appendLine("Dir::Cache \"$prefix/var/cache\";")
            appendLine("Dir::Cache::archives \"$prefix/var/cache/apt/archives\";")
            appendLine("Dir::Log \"$prefix/var/log/apt\";")
            appendLine("Dir::Log::Terminal \"$prefix/var/log/apt/term.log\";")
            appendLine("Dir::Log::History \"$prefix/var/log/apt/history.log\";")
            appendLine("DPkg \"$prefix/bin/dpkg\";")
            appendLine("DPkg::Options:: \"--root=$prefix\";")
            appendLine("DPkg::Options:: \"--admindir=$prefix/var/lib/dpkg\";")
            appendLine("DPkg::Options:: \"--configdir=$prefix/etc/dpkg\";")
            appendLine("DPkg::Pre-Install-Pkgs {\"\";};")
            appendLine("Acquire::AllowInsecureRepositories \"true\";")
            appendLine("Acquire::AllowDowngradeToInsecureRepositories \"true\";")
            appendLine("Acquire::https::Verify-Peer \"false\";")
            appendLine("Acquire::https::Verify-Host \"false\";")
            appendLine("Acquire::Check-Valid-Until \"false\";")
            appendLine("APT::Get::AllowUnauthenticated \"true\";")
            appendLine("APT::Sandbox::User \"root\";")
            appendLine("APT::Sandbox::Seccomp \"false\";")
            // 网络超时：防止 https 方法驱动卡在 SSL 握手时无限等待
            appendLine("Acquire::http::Timeout \"30\";")
            appendLine("Acquire::https::Timeout \"30\";")
            // 禁止 http -> https 重定向跟随（packages.termux.dev 会 301 到 https）
            appendLine("Acquire::http::Redirect::https \"false\";")
        })

        // 创建 dpkg status 文件
        File(rootDir, "var/lib/dpkg/status").writeText("")
        File(rootDir, "var/lib/dpkg/available").writeText("")

        // 创建 dpkg 配置文件
        File(rootDir, "etc/dpkg/dpkg.cfg").writeText("")

        // 创建 Termux 路径兼容符号链接
        // .deb 包内文件路径是 data/data/com.termux/files/usr/bin/tmux
        // 创建符号链接 PREFIX/data/data/com.termux/files/usr -> PREFIX
        // 让 dpkg-deb -x 解压时文件落在正确位置（PREFIX/bin/tmux）
        val compatDir = File(rootDir, "data/data/com.termux/files").apply { mkdirs() }
        val compatUsrLink = File(compatDir, "usr")
        try {
            val isLink = java.nio.file.Files.isSymbolicLink(compatUsrLink.toPath())
            if (!isLink) {
                if (compatUsrLink.exists()) {
                    // 如果是真实目录，删除（前面的修复逻辑应该已经处理了）
                    compatUsrLink.deleteRecursively()
                }
                android.system.Os.symlink(rootDir.absolutePath, compatUsrLink.absolutePath)
                log("创建兼容符号链接: ${compatUsrLink.absolutePath} -> ${rootDir.absolutePath}")
            }
        } catch (e: Exception) {
            log("创建兼容符号链接失败: ${e.message}")
        }

        // 创建 dpkg 包装脚本
        // dpkg 的配置目录在编译时硬编码为 /data/data/com.termux/files/usr/etc/dpkg/
        // 该路径属于另一个 app，无法访问，导致 dpkg 启动即崩溃
        // 包装脚本用 dpkg-deb -x 提取 .deb 包，绕过配置目录问题
        val dpkgReal = File(rootDir, "bin/dpkg.real")
        val dpkgBin = File(rootDir, "bin/dpkg")
        if (dpkgBin.exists() && !dpkgReal.exists()) {
            dpkgBin.renameTo(dpkgReal)
        }
        if (dpkgReal.exists()) {
            dpkgReal.setExecutable(true, false)
            // 使用占位符避免 Kotlin 字符串插值冲突
            val dpkgScript = """#!/system/bin/sh
export PATH="__P__{PREFIX}/bin:__P__{PREFIX}/libexec:/system/bin:/system/xbin"
export LD_LIBRARY_PATH="__P__{PREFIX}/lib:/system/lib64"
PREFIX="__PREFIX__"
STATUS="__P__{PREFIX}/var/lib/dpkg/status"
DPKG_DEB="__P__{PREFIX}/bin/dpkg-deb"

# 日志函数（输出到 stderr，被 apt-get 捕获）
log() { echo "[dpkg-wrapper] __D__*" >&2; }

# Termux .deb 包内文件路径是 data/data/com.termux/files/usr/bin/tmux
# 但我们的 PREFIX 是 /data/data/com.mineserve.mobile/files/home
# 需要创建兼容符号链接：PREFIX/data/data/com.termux/files/usr -> PREFIX
# 这样 dpkg-deb -x 解压时文件会落在正确位置（PREFIX/bin/tmux）
TERMUX_COMPAT="__P__{PREFIX}/data/data/com.termux/files"
if [ ! -e "__P__{TERMUX_COMPAT}/usr" ]; then
    mkdir -p "__P__{TERMUX_COMPAT}"
    ln -sf "__P__{PREFIX}" "__P__{TERMUX_COMPAT}/usr"
    log "created compat symlink: __P__{TERMUX_COMPAT}/usr -> __P__{PREFIX}"
fi

# 提取单个 .deb 文件并记录到 status
extract_deb() {
  deb="__D__1"
  if [ ! -f "__D__deb" ]; then
    log "skip: __D__deb (not a file)"
    return 0
  fi
  log "extracting: __D__deb"
  if "__D__DPKG_DEB" -x "__D__deb" "__D__PREFIX" 2>/dev/null; then
    pkg=$("__D__DPKG_DEB" -f "__D__deb" Package 2>/dev/null)
    ver=$("__D__DPKG_DEB" -f "__D__deb" Version 2>/dev/null)
    if [ -n "__D__pkg" ]; then
      # 移除旧记录
      sed -i "/^Package: __D__pkg__D__/,/^__D__/d" "__D__STATUS" 2>/dev/null
      # 追加新记录
      echo "Package: __D__pkg" >> "__D__STATUS"
      echo "Status: install ok installed" >> "__D__STATUS"
      echo "Version: __D__ver" >> "__D__STATUS"
      echo "Architecture: aarch64" >> "__D__STATUS"
      echo "" >> "__D__STATUS"
      log "installed: __D__pkg __D__ver"
    fi
    # 设置 bin/ 下所有文件可执行权限（dpkg-deb -x 不会保留 Unix 权限位）
    if [ -d "__D__{PREFIX}/bin" ]; then
      chmod 755 "__D__{PREFIX}/bin/"* 2>/dev/null
      log "set executable: __D__{PREFIX}/bin/"
    fi
    if [ -d "__D__{PREFIX}/libexec" ]; then
      chmod 755 "__D__{PREFIX}/libexec/"* 2>/dev/null
    fi
    if [ -d "__D__{PREFIX}/lib/apt/methods" ]; then
      chmod 755 "__D__{PREFIX}/lib/apt/methods/"* 2>/dev/null
    fi
  else
    log "ERROR: extract failed for __D__deb"
  fi
}

# 处理 --recursive 选项（apt-get 可能用此方式批量安装）
RECURSIVE=0
ARGS=""
while [ "__D__#" -gt 0 ]; do
  case "__D__1" in
    --recursive|-R)
      RECURSIVE=1
      shift
      ;;
    --unpack|--install|-i|--configure|-a|--pending|--yet-to-unpack)
      MODE="__D__1"
      shift
      ;;
    --*)
      # 跳过其他选项参数
      shift
      ;;
    *)
      ARGS="__D__ARGS __D__1"
      shift
      ;;
  esac
done

log "called with mode=__D__MODE args=__D__ARGS recursive=__D__RECURSIVE"

case "__D__MODE" in
  --unpack|--install|-i)
    if [ "__D__RECURSIVE" = "1" ]; then
      # 递归处理目录
      for dir in __D__ARGS; do
        if [ -d "__D__dir" ]; then
          log "scanning directory: __D__dir"
          for deb in "__D__dir"/*.deb; do
            [ -f "__D__deb" ] && extract_deb "__D__deb"
          done
        fi
      done
    else
      for deb in __D__ARGS; do
        extract_deb "__D__deb"
      done
    fi
    exit 0
    ;;
  --configure|-a|--pending|--yet-to-unpack)
    # 配置阶段：跳过（文件已提取，无需配置）
    log "configure: skipped (no-op)"
    exit 0
    ;;
  --remove|-r|--purge|-P)
    log "remove: skipped (no-op)"
    exit 0
    ;;
  --list|-l)
    grep "^Package:" "__D__STATUS" 2>/dev/null | sed 's/Package: /ii  /'
    exit 0
    ;;
  --status|-s)
    pkg=$(echo __D__ARGS | awk '{print __D__1}')
    awk -v p="__D__pkg" 'BEGIN{RS=""} __D__0 ~ "Package: "p' "__D__STATUS" 2>/dev/null
    exit 0
    ;;
  *)
    log "unknown mode, no-op"
    exit 0
    ;;
esac
""".replace("__P__", "\$")
            .replace("__D__", "\$")
            .replace("__PREFIX__", prefix)
            File(rootDir, "bin/dpkg").writeText(dpkgScript)
            File(rootDir, "bin/dpkg").setExecutable(true, false)

            // 确认 dpkg-deb 也存在且可执行
            val dpkgDeb = File(rootDir, "bin/dpkg-deb")
            if (dpkgDeb.exists()) {
                dpkgDeb.setExecutable(true, false)
            }

            // 创建 gpgv 包装脚本
            // gpgv 在 Termux rootfs 中可能因缺少 GPG keyring 或权限问题而挂起
            // 创建一个始终返回成功的包装脚本，完全绕过 GPG 验证
            val gpgvReal = File(rootDir, "bin/gpgv.real")
            val gpgvBin = File(rootDir, "bin/gpgv")
            if (gpgvBin.exists() && !gpgvReal.exists()) {
                gpgvBin.renameTo(gpgvReal)
                gpgvReal.setExecutable(true, false)
            }
            if (gpgvReal.exists()) {
                val gpgvScript = """#!/system/bin/sh
# gpgv wrapper: 绕过 GPG 签名验证，始终返回成功
exit 0
"""
                File(rootDir, "bin/gpgv").writeText(gpgvScript)
                File(rootDir, "bin/gpgv").setExecutable(true, false)
            }

            // 处理 lib/apt/methods/gpgv（apt-get update 验证签名时调用此路径，而非 bin/gpgv）
            // 该文件是独立二进制，不跟随 bin/gpgv 的符号链接，需要单独替换
            val methodsGpgvReal = File(rootDir, "lib/apt/methods/gpgv.real")
            val methodsGpgvBin = File(rootDir, "lib/apt/methods/gpgv")
            if (methodsGpgvBin.exists() && !methodsGpgvReal.exists()) {
                methodsGpgvBin.renameTo(methodsGpgvReal)
                methodsGpgvReal.setExecutable(true, false)
            }
            if (methodsGpgvReal.exists()) {
                // gpgv 方法驱动需要通过 stdin/stdout 与 apt-get 通信
                // 使用 apt 方法驱动协议：发送 Capabilities，响应 URI Acquire 请求
                val methodsGpgvScript = """#!/system/bin/sh
# gpgv method wrapper: 模拟 apt 方法驱动协议，跳过签名验证
# apt-get 方法驱动通过 stdin/stdout 通信，不能直接 exit 0

# 1. 发送 Capabilities 消息（方法驱动启动时必须发送）
printf '100 Capabilities\n'
printf 'Version: 1.0\n'
printf 'Single-Instance: true\n'
printf 'Send-Config: true\n'
printf '\n'

# 2. 读取 apt-get 的请求并响应
while IFS= read -r line; do
    case "__D__line" in
        600\ URI\ Acquire*)
            # 解析 URI 和 Filename
            uri=""
            filename=""
            # 读取后续行直到空行
            while IFS= read -r subline; do
                case "__D__subline" in
                    URI:*) uri="__D__{subline#URI: }" ;;
                    Filename:*) filename="__D__{subline#Filename: }" ;;
                    "") break ;;
                esac
            done
            # 返回验证成功
            printf '201 URI Done\n'
            printf 'URI: %s\n' "__D__uri"
            printf 'Filename: %s\n' "__D__filename"
            printf 'GPG-Status: GOODSIG\n'
            printf 'GPG-Output: GOODSIG\n'
            printf '\n'
            ;;
        601\ Configuration*|602\ *)
            # 读取后续行直到空行
            while IFS= read -r subline; do
                [ -z "__D__subline" ] && break
            done
            ;;
        "")
            # 空行，忽略
            ;;
    esac
done
""".replace("__D__", "\$")
                File(rootDir, "lib/apt/methods/gpgv").writeText(methodsGpgvScript)
                File(rootDir, "lib/apt/methods/gpgv").setExecutable(true, false)
            }

            // 创建 apt-key 包装脚本
            // apt-key 调用 gpg 来管理 keyring，但在无 CA 证书的环境中会失败或挂起
            val aptKeyReal = File(rootDir, "bin/apt-key.real")
            val aptKeyBin = File(rootDir, "bin/apt-key")
            if (aptKeyBin.exists() && !aptKeyReal.exists()) {
                aptKeyBin.renameTo(aptKeyReal)
                aptKeyReal.setExecutable(true, false)
            }
            if (aptKeyReal.exists()) {
                val aptKeyScript = """#!/system/bin/sh
# apt-key wrapper: 跳过所有 keyring 操作
case "__D__1" in
  adv|export|list|finger|update|net-update)
    exit 0
    ;;
  add)
    exit 0
    ;;
  del|remove)
    exit 0
    ;;
  *)
    exit 0
    ;;
esac
""".replace("__D__", "\$")
                File(rootDir, "bin/apt-key").writeText(aptKeyScript)
                File(rootDir, "bin/apt-key").setExecutable(true, false)
            }
        }

        // 合并 Android 系统 CA 证书到 Termux 的 ca-certificates.crt
        // apt-get 的 http 方法遇到 301 重定向到 https 时，会启动 https 方法驱动
        // https 方法驱动需要 CA 证书来验证 SSL，Termux rootfs 自带的 ca-certificates 包
        // 可能不完整或路径不对，导致 SSL 握手卡住
        // 解决方案：将 Android 系统 CA 证书（PEM 格式）合并到 Termux 的标准路径
        val sslCertsDir = File(rootDir, "etc/ssl/certs").apply { mkdirs() }
        val tlsDir = File(rootDir, "etc/tls").apply { mkdirs() }
        val caBundle = File(sslCertsDir, "ca-certificates.crt")
        val androidCaDir = File("/system/etc/security/cacerts")
        if (androidCaDir.isDirectory) {
            log("合并 Android 系统 CA 证书...")
            try {
                val sb = StringBuilder()
                androidCaDir.listFiles()?.sortedBy { it.name }?.forEach { certFile ->
                    try {
                        val content = certFile.readText()
                        if (content.contains("BEGIN CERTIFICATE")) {
                            sb.append(content)
                            if (!content.endsWith("\n")) sb.append("\n")
                        }
                    } catch (_: Exception) { }
                }
                caBundle.writeText(sb.toString())
                log("CA 证书合并完成: ${sb.length} 字节")
            } catch (e: Exception) {
                log("CA 证书合并失败: ${e.message}")
            }
        } else {
            log("警告: Android CA 证书目录不存在: ${androidCaDir.absolutePath}")
        }
        // 创建 etc/tls/ca-certificates.crt 符号链接（部分 SSL 库查找此路径）
        val tlsCaBundle = File(tlsDir, "ca-certificates.crt")
        if (!tlsCaBundle.exists() && caBundle.exists()) {
            try {
                android.system.Os.symlink(caBundle.absolutePath, tlsCaBundle.absolutePath)
            } catch (e: Exception) {
                // 符号链接失败时直接复制
                caBundle.copyTo(tlsCaBundle, overwrite = true)
            }
        }

        // 创建 tmp 目录
        File(rootDir, "tmp").mkdirs()
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
        conn.readTimeout = 15_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "MineServeMobile/1.0")
        // 支持断点续传
        val existing = if (target.exists()) target.length() else 0L
        if (existing > 0) {
            conn.setRequestProperty("Range", "bytes=$existing-")
        }

        Log.i(TAG, "[HTTP] 开始连接: url=${urlStr.take(100)}, connectTimeout=10s, readTimeout=15s, 续传起点=$existing 字节")
        val connectStart = System.currentTimeMillis()
        conn.connect()
        val connectElapsed = System.currentTimeMillis() - connectStart
        val code = conn.responseCode
        Log.i(TAG, "[HTTP] 连接完成: 耗时=${connectElapsed}ms, HTTP $code")
        if (code !in 200..299 && code != 416) {
            conn.disconnect()
            throw RuntimeException("HTTP $code: $urlStr")
        }

        val contentLength = conn.contentLengthLong
        val total = if (contentLength > 0) contentLength + existing else -1L
        Log.i(TAG, "[HTTP] 开始读取流: contentLength=$contentLength, total(含续传)=$total")
        val input = conn.inputStream
        val output = FileOutputStream(target, existing > 0)

        val buf = ByteArray(64 * 1024)
        var read: Int
        var downloaded = existing
        var lastLogPct = -1
        var lastProgressTime = 0L
        var lastSpeedCalcTime = System.currentTimeMillis()
        var lastSpeedCalcBytes = downloaded
        var loopCount = 0
        val loopStart = System.currentTimeMillis()
        while (input.read(buf).also { read = it } != -1) {
            loopCount++
            // 响应用户请求切换镜像源：立即中断当前下载
            if (stopAndSwitchRequested) {
                Log.w(TAG, "[切换] 下载循环检测到 stopAndSwitchRequested=true, loopCount=$loopCount, downloaded=$downloaded 字节, 已耗时=${System.currentTimeMillis() - loopStart}ms, 准备关闭流并抛出异常")
                output.close()
                input.close()
                conn.disconnect()
                Log.w(TAG, "[切换] 流已关闭, conn 已 disconnect, 抛出 RuntimeException")
                throw RuntimeException("用户请求切换镜像源")
            }
            output.write(buf, 0, read)
            downloaded += read

            // 每 500ms 计算一次下载速度
            val now = System.currentTimeMillis()
            if (onSpeed != null && now - lastSpeedCalcTime >= 500) {
                val elapsedSec = (now - lastSpeedCalcTime) / 1000.0
                val speedBps = if (elapsedSec > 0) ((downloaded - lastSpeedCalcBytes) / elapsedSec).toLong() else 0L
                onSpeed?.invoke(downloaded, speedBps)
                lastSpeedCalcTime = now
                lastSpeedCalcBytes = downloaded
            }

            // 进度回调节流：每 100ms 一次，避免每 64KB 触发 ServerState.copy
            if (total > 0 && now - lastProgressTime >= 100) {
                lastProgressTime = now
                val pct = range.first + ((range.last - range.first) * downloaded / total).toInt()
                onProgress(pct.coerceIn(range.first, range.last))

                // 每下载 10% 输出一次日志
                val logPct = (downloaded * 100 / total).toInt() / 10 * 10
                if (logPct != lastLogPct && logPct > 0) {
                    lastLogPct = logPct
                    onLog?.invoke("已下载 $logPct% (${downloaded / 1024 / 1024}MB)")
                }
            } else {
                // chunked encoding: 没有 Content-Length，按已下载字节数估算（同样 100ms 节流）
                if (now - lastProgressTime >= 100) {
                    lastProgressTime = now
                    val mb = (downloaded / 1024 / 1024).toInt()
                    onProgress(range.first + (mb.coerceAtMost(30) * (range.last - range.first) / 30).toInt())
                    if (mb != lastLogPct) {
                        lastLogPct = mb
                        onLog?.invoke("已下载 ${mb}MB")
                    }
                }
            }
        }
        Log.i(TAG, "[HTTP] 下载循环正常结束: loopCount=$loopCount, downloaded=$downloaded 字节, 总耗时=${System.currentTimeMillis() - loopStart}ms")
        output.close()
        input.close()
        conn.disconnect()
        // 下载结束清零速度
        onSpeed?.invoke(downloaded, 0L)
    }

    enum class InstallPhase(val labelRes: Int) {
        DOWNLOAD_ROOTFS(R.string.s70),
        EXTRACT_ROOTFS(R.string.s71),
        POST_SETUP(R.string.s72),
        DONE(R.string.s73)
    }

    companion object { private const val TAG = "BootstrapInstaller" }
}
