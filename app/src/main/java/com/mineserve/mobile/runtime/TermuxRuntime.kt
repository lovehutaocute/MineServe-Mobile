package com.mineserve.mobile.runtime

import android.content.Context
import android.util.Log
import android.system.Os
import com.mineserve.mobile.runtime.NativeLibraryBundler.PACKAGED_LIBRARIES
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.PhpVersion
import com.mineserve.mobile.data.AptMirror
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.StepState
import com.mineserve.mobile.data.StepStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.BufferedInputStream
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.gzip.GzipCompressorInputStream

/**
 * Termux 运行时（去 tmux 化，参考 MC-Minder 思路直接管理进程）：
 *  - bootstrap()：下载 + 解压 Termux bootstrap rootfs，安装 JDK/wget
 *  - startMc()：用 ProcessBuilder 直接启动 java 进程，stdout 推送到 consoleFlow
 *  - stopMc()：向 stdin 发送 stop 命令，超时后 destroy
 *  - sendCommand()：向 MC 进程 stdin 写入命令
 *  - isMcRunning()：检查 Process.isAlive
 *
 * 不再依赖 tmux，避免 tmux 未安装时整个应用不可用的问题。
 */
class TermuxRuntime(context: Context) {

    private val appContext = context.applicationContext
    internal val installer = BootstrapInstaller(context)

    /**
     * 随 APK 打包的 Termux 共享库在设备上的可读路径（nativeLibraryDir）。
     *
     * 缺库会导致 Termux 侧二进制在动态链接阶段直接失败：
     *   F linker: CANNOT LINK EXECUTABLE ".../apt-get": library "libandroid-glob.so" not found
     * 一旦 apt-get 起不来，apt/dpkg/proot/Java 8 全部连锁失效。
     *
     * targetSdk = 28 < 29，系统会把 jniLibs 下的 .so 解压到 nativeLibraryDir，
     * 因此这里拿到的是真实可读文件路径（可直接 cp / 走 ELF 搜索路径），
     * 而不是 targetSdk ≥ 29 时那种仅供 dlopen 的不可读映射。
     *
     * 见 NativeLibraryBundler：目录不存在或文件缺失时为 null，调用方需容忍。
     */
    private val bundledLibDir: String? by lazy {
        NativeLibraryBundler.nativeLibraryDir(context)
    }

    private val executor = CommandExecutor(installer, bundledLibDir)

    /** 关闭堆指针标签的注入库文件名（随 APK 打包在 jniLibs 下）。 */
    private val HEAP_TAG_FIX_LIB = "libheaptagfix.so"

    /** 应用侧日志落盘队列上限：异常刷屏时丢弃多余条目，避免内存膨胀。 */
    private val APP_LOG_QUEUE_LIMIT = 4096

    /** 库缺失只提示一次，避免每次启动命令都刷日志。 */
    private val heapTagFixWarned = java.util.concurrent.atomic.AtomicBoolean(false)

    private val java8Rootfs: File
        get() = File(installer.rootDir, "var/lib/mineserve/java8-ubuntu-rootfs")

    private val java8ReadyMarker: File
        get() = File(installer.rootDir, "java-8-ubuntu-ready")

    private val ubuntuJava8Home = "/usr/lib/jvm/java-8-openjdk-arm64"

    /** MC 服务器进程（null 表示未启动） */
    @Volatile
    private var mcProcess: Process? = null
    @Volatile private var mcPid: Int? = null
    /** Java PID identified for this launch; never rescan into an installer or older server. */
    @Volatile private var mcServerPid: Int? = null
    @Volatile private var mcServerDir: File? = null
    @Volatile private var lastMcLogFile: File? = null
    @Volatile private var lastMcLogStartOffset: Long = 0L
    private val mcProcessLock = Any()

    init {
        // 把打包的 libandroid-glob.so 落到 Termux 库目录，必须在任何命令执行前完成。
        // 这里只做落盘，具体路径由 repairProotLibraries() 统一汇报。
        provisionBundledLibraries()
    }

    /** MC 进程的 stdin，用于发送命令 */
    @Volatile
    private var mcStdin: OutputStream? = null

    // 进程级 CPU 采样基线：只在服务器启动/重启后建立，避免把停机时间计入使用率。
    @Volatile private var cpuBaselinePid: Int? = null
    @Volatile private var cpuBaselineJiffies: Long = 0L
    @Volatile private var cpuBaselineAtElapsedMs: Long = 0L

    val consoleFlow: SharedFlow<String> get() = executor.consoleFlow

    /**
     * 应用侧日志文件（`home/logs/mineserve.log`）。
     *
     * ## 为什么需要它
     * [emitLog] 原先只把日志推给 [consoleFlow]（纯内存），**从不落盘**。
     * 于是启动前那一大段关键诊断——Java 路径解析、依赖安装、库体检、
     * 以及拼好的完整启动命令——只存在于内存里。一旦服务端启动失败，
     * `logs/latest.log` 里只有「MC 服务端自己输出的内容」，必然是空的，
     * 崩溃报告因此拿不出任何证据（实测出现过「最近日志 共 0 行」）。
     *
     * 现在所有 [emitLog] 同时追加到这里，即便服务端零输出，
     * 也能看到 MineServe 自己走到了哪一步、启动命令长什么样。
     */
    private val appLogFile: File
        get() = File(installer.rootDir, "home/logs/mineserve.log")

    /**
     * 日志落盘队列。
     *
     * [emitLog] 可能被高频调用（stdout 逐行、安装进度），**不能同步做 IO**，
     * 否则会拖慢启动。这里用无界队列 + 单线程消费：
     *   - `trySend` 是非阻塞的，调用方零等待
     *   - 只有 WARN/ERROR 级或有明确诊断价值的行才入队，过滤掉噪声
     *   - 队列上限保护：超过 [APP_LOG_QUEUE_LIMIT] 时丢弃，避免异常刷屏把内存撑爆
     */
    private val appLogQueue = java.util.concurrent.LinkedBlockingQueue<String>(APP_LOG_QUEUE_LIMIT)
    @Volatile private var appLogWriterStarted = false
    private val appLogLock = Any()

    /** 需要落盘的日志特征（其余高频噪声不入队，避免日志膨胀） */
    private val APP_LOG_KEEP_MARKERS = listOf(
        "[startMc]", "[bootstrap]", "[java]", "[crash]", "[restart]", "[core]",
        "[repair]", "[jvm]", "[proot]", "错误", "失败", "警告", "异常"
    )

    private fun persistAppLog(line: String) {
        // 只留诊断价值高的行；其余（例如进度百分比）不入队
        if (APP_LOG_KEEP_MARKERS.none { line.contains(it) }) return
        startAppLogWriterIfNeeded()
        // offer 不阻塞：队列满时直接丢弃，绝不反压调用方
        appLogQueue.offer(line)
    }

    private fun startAppLogWriterIfNeeded() {
        if (appLogWriterStarted) return
        synchronized(appLogLock) {
            if (appLogWriterStarted) return
            appLogWriterStarted = true
            Thread({
                while (true) {
                    val line = try {
                        // 带超时的取，便于在无日志时也能定期 flush
                        appLogQueue.poll(1, TimeUnit.SECONDS)
                    } catch (e: InterruptedException) {
                        return@Thread
                    }
                    try {
                        if (line != null) {
                            val file = appLogFile
                            file.parentFile?.mkdirs()
                            // 单条即 flush：崩溃场景下缓冲区里的内容很容易随进程消失
                            file.appendText(line + "\n")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "写入 mineserve.log 失败: ${e.message}")
                    }
                }
            }, "mineserve-app-log").apply { isDaemon = true }.start()
        }
    }

    /**
     * 读取应用侧日志的尾部若干行，供崩溃报告补充「MineServe 视角」的上下文。
     * 文件不存在或为空时返回空列表（调用方据此区分「没有」和「空」）。
     */
    fun readAppLogTail(maxLines: Int = 400): List<String> {
        val file = appLogFile
        if (!file.isFile) return emptyList()
        return runCatching {
            file.useLines { lines -> lines.toList().takeLast(maxLines) }
        }.getOrDefault(emptyList())
    }

    /** 清空应用侧日志（避免无限增长）。 */
    fun clearAppLog() {
        runCatching { appLogFile.takeIf { it.isFile }?.writeText("") }
    }

    /** 设置日志回调，bootstrap 过程的日志会通过此回调输出 */
    fun setBootstrapLogCallback(cb: (String) -> Unit) {
        installer.onLog = cb
    }

    /** 向 consoleFlow 推送一条日志，并异步落盘到 mineserve.log */
    fun emitLog(line: String) {
        executor.emit(line)
        // 落盘是「尽力而为」：任何异常都不能影响主流程
        runCatching { persistAppLog(line) }
    }

    fun isReady(): Boolean = installer.isReady()

    /** 删除整个 Termux 运行环境；force=true 为强制彻底删除（不自动重新初始化） */
    suspend fun deleteBootstrap(force: Boolean = false) {
        stopMc()
        installer.deleteBootstrap(force)
    }

    /** 多核心支持：按文件夹名获取对应核心的 jar 路径（home/servers/{dirName}/server.jar） */
    fun serverJarFileFor(dirName: String): File =
        File(installer.rootDir, "home/servers/$dirName/server.jar").apply { parentFile?.mkdirs() }

    /** 多核心支持：按文件夹名获取对应核心的工作目录（home/servers/{dirName}/） */
    fun serverDirFor(dirName: String): File =
        File(installer.rootDir, "home/servers/$dirName").apply { mkdirs() }

    /** 读取当前服务端日志文件的最近内容，供终端页快速恢复历史输出。 */
    suspend fun readRecentMcLog(dirName: String, maxLines: Int = 300): List<String> = withContext(Dispatchers.IO) {
        val file = File(serverDirFor(dirName), "logs/latest.log")
        if (!file.isFile || file.length() == 0L) return@withContext emptyList()
        file.useLines(Charsets.UTF_8) { lines -> lines.toList().takeLast(maxLines) }
    }

    /** 读取最近一次服务端运行从启动到退出产生的完整日志。 */
    suspend fun readLastMcRunLog(maxBytes: Long = 4L * 1024L * 1024L): String = withContext(Dispatchers.IO) {
        val file = lastMcLogFile ?: return@withContext ""
        if (!file.isFile) return@withContext ""
        val start = lastMcLogStartOffset.coerceAtMost(file.length())
        val end = file.length()
        val readStart = if (end - start > maxBytes) end - maxBytes else start
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.seek(readStart)
            val length = (end - readStart).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            val bytes = ByteArray(length)
            raf.readFully(bytes)
            String(bytes, Charsets.UTF_8)
        }
    }

    private fun prepareMcLogFile(serverDir: File): File {
        val file = File(serverDir, "logs/latest.log")
        file.parentFile?.mkdirs()
        file.createNewFile()
        lastMcLogFile = file
        lastMcLogStartOffset = file.length()
        return file
    }

    /** 多核心基础目录（home/servers/） */
    val serversDir: File get() = File(installer.rootDir, "home/servers").apply { mkdirs() }

    /**
     * 确保 java 命令可用（wrapper 脚本方案）。
     * 在 startMc 之前主动调用，避免每次启动都找不到 java。
     * 返回 java 命令路径，找不到返回 null。
     *
     * 关键：如果 $PREFIX/bin/java 是旧的 cp 复制的二进制（非 wrapper 脚本），
     * 需要删除并用 wrapper 脚本替换，否则会因 libjli.so 缺失而启动失败。
     */
    fun ensureJavaReady(): String? {
        val prefix = installer.rootDir.absolutePath
        val binJava = File(prefix, "bin/java")

        // 1. 检查是否已存在 wrapper 脚本
        if (binJava.exists() && binJava.canExecute()) {
            val isWrapper = try {
                FileInputStream(binJava).use { fis ->
                    val header = ByteArray(14)
                    val read = fis.read(header)
                    read >= 14 && String(header, Charsets.US_ASCII).startsWith("#!/system/bin/sh")
                }
            } catch (e: Exception) { false }
            if (isWrapper) {
                return binJava.absolutePath
            }
            // 旧版本 cp 复制的二进制，需替换为 wrapper 脚本
            Log.i(TAG, "ensureJavaReady: $prefix/bin/java is old binary, replacing with wrapper")
            binJava.delete()
        }

        // 2. 创建 wrapper 脚本
        fixJavaSymlinks()

        // 3. 修复后再次检查
        if (binJava.exists() && binJava.canExecute()) {
            return binJava.absolutePath
        }

        // 4. 探测实际路径
        val resolved = resolveJavaPath(prefix)
        return if (resolved != "java") resolved else null
    }

    fun isJavaInstalled(version: JavaVersion): Boolean = if (version == JavaVersion.Java8) {
        java8UbuntuReady()
    } else {
        javaCandidates(version).any {
            File(it, "bin/java").exists() && File(it, "bin/java").canExecute()
        }
    }

    fun installedJavaVersions(): Set<JavaVersion> = JavaVersion.values().filter(::isJavaInstalled).toSet()

    /** 直接删除指定 Java 版本的文件（Java 8 为独立 PRoot 来宾根文件系统）。返回是否有文件被删除。 */
    fun deleteJava(version: JavaVersion): Boolean {
        if (isMcRunning()) return false
        val roots: List<File> = if (version == JavaVersion.Java8) {
            listOf(java8Rootfs, File(installer.rootDir, "java-8-ubuntu-ready"))
        } else {
            javaCandidates(version).map(::File)
        }
        var deleted = false
        roots.forEach { root ->
            if (root.exists()) deleted = root.deleteRecursively() || deleted
        }
        // 被删版本的 bin/java 包装器可能悬空：若无任何已安装版本则移除包装器
        if (deleted && installedJavaVersions().isEmpty()) {
            File(installer.rootDir, "bin/java").delete()
        }
        return deleted
    }

    // ── PocketMine-MP 专用 PHP 运行时 ─────────────────────────────
    //
    // PMMP 官方不发布 Linux ARM64 的 PHP 构建，首次启动时会下载社区 Android
    // 原生构建（ItzxDwi/AndroidPHP，aarch64，含 chunkutils2 / encoding /
    // leveldb / pmmpthread 等必需扩展）到共享目录，多个 PocketMine 服务器复用。
    //
    // 目录按版本区分（home/<PhpVersion.runtimeDirName>）。当前上游只发布一个
    // 版本，故只有一个目录；结构上预留多版本，无需改动调用方。

    /** 指定 PHP 版本的运行时目录：home/{runtimeDirName} */
    fun phpRuntimeDirFor(version: PhpVersion): File =
        File(installer.rootDir, "home/${version.runtimeDirName}")

    /** 指定 PHP 版本的可执行文件 */
    fun phpBinaryFor(version: PhpVersion): File = File(phpRuntimeDirFor(version), "php")

    /** 默认（PocketMine 使用）PHP 运行时目录，等价于 phpRuntimeDirFor(Default) */
    val phpRuntimeDir: File get() = phpRuntimeDirFor(PhpVersion.Default)

    /** 默认 PHP 可执行文件 */
    val phpBinary: File get() = phpBinaryFor(PhpVersion.Default)

    /** 指定 PHP 版本是否已就绪 */
    fun isPhpInstalled(version: PhpVersion = PhpVersion.Default): Boolean =
        phpBinaryFor(version).let { it.isFile && it.canExecute() }

    /** 已安装的 PHP 版本集合（两处 UI 共用同一探测来源） */
    fun installedPhpVersions(): Set<PhpVersion> =
        PhpVersion.entries.filter { isPhpInstalled(it) }.toSet()

    /**
     * 下载并解压指定版本的 PHP 运行环境。
     *
     * 依次尝试多个 GitHub 镜像；成功后 chmod 755 并校验可执行位。
     * 返回是否安装成功，失败原因通过 [onLog] 输出。
     */
    fun installPhp(version: PhpVersion = PhpVersion.Default, onLog: (String) -> Unit = {}): Boolean {
        val target = phpBinaryFor(version)
        if (isPhpInstalled(version)) return true
        val runtimeDir = phpRuntimeDirFor(version).apply { mkdirs() }
        onLog("[php] 正在下载 ${version.displayName} 运行环境（Android 原生构建，约 10MB）...")
        val tarball = File(installer.rootDir, "tmp/php-${version.releaseTag}.tar.gz").apply { parentFile?.mkdirs() }
        val url = "https://github.com/ItzxDwi/AndroidPHP/releases/download/${version.releaseTag}/${version.tarballAsset}"
        val mirrors = listOf(
            "https://ghfast.top/",
            "https://gh-proxy.com/",
            "https://mirror.ghproxy.com/",
            "https://ghproxy.net/",
            "https://github.moeyy.xyz/",
            ""
        ).map { it + url }
        var downloaded = false
        for (mirror in mirrors) {
            if (downloadFile(mirror, tarball)) { downloaded = true; break }
            onLog("[php] 镜像下载失败，尝试下一个源...")
        }
        if (!downloaded || tarball.length() < 1_000_000L) {
            tarball.delete()
            onLog("[php] ${version.displayName} 运行环境下载失败，请检查网络后重试")
            return false
        }
        try {
            extractTarGz(tarball, runtimeDir)
        } catch (e: Exception) {
            tarball.delete()
            onLog("[php] 解压失败: ${e.message}")
            return false
        }
        tarball.delete()
        execOnce("chmod", "-R", "755", runtimeDir.absolutePath)
        val ok = isPhpInstalled(version)
        onLog(
            if (ok) "[php] ${version.displayName} 运行环境就绪"
            else "[php] ${version.displayName} 运行环境不完整，请删除 ${version.runtimeDirName} 目录后重试"
        )
        return ok
    }

    /** 直接删除指定版本的 PHP 运行环境。返回是否有文件被删除。 */
    fun deletePhp(version: PhpVersion = PhpVersion.Default): Boolean {
        if (isMcRunning()) return false
        val dir = phpRuntimeDirFor(version)
        return if (dir.exists()) dir.deleteRecursively() else false
    }

    /** 下载文件到指定路径（跟随重定向），返回是否成功且文件大于 1KB */
    private fun downloadFile(url: String, target: File): Boolean = runCatching {
        val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", "MineServeMobile/1.0 (Android)")
            if (conn.responseCode !in 200..299) return false
            conn.inputStream.use { input ->
                target.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
            }
        } finally {
            conn.disconnect()
        }
        target.length() > 1024
    }.getOrElse { false }

    /** 解压 tar.gz 到目标目录（防目录逃逸） */
    private fun extractTarGz(tarball: File, destDir: File) {
        java.util.zip.GZIPInputStream(tarball.inputStream().buffered()).use { gzip ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gzip).use { tar ->
                while (true) {
                    val entry = tar.nextTarEntry ?: break
                    val out = File(destDir, entry.name)
                    if (!out.canonicalPath.startsWith(destDir.canonicalFile.path + File.separator) &&
                        out.canonicalPath != destDir.canonicalFile.path
                    ) continue
                    if (entry.isDirectory) {
                        out.mkdirs()
                        continue
                    }
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output -> tar.copyTo(output, 64 * 1024) }
                }
            }
        }
    }

    /** 直接删除依赖文件（wget/frpc/rclone/proot）。返回删除的组件数。 */
    fun deleteDependencies(): Int {        val prefix = installer.rootDir
        val targets = listOf(
            listOf("bin/wget", "usr/bin/wget", "data/data/com.termux/files/usr/bin/wget"),
            listOf("bin/frpc", "usr/bin/frpc", "data/data/com.termux/files/usr/bin/frpc"),
            listOf("bin/rclone", "usr/bin/rclone", "data/data/com.termux/files/usr/bin/rclone"),
            listOf("bin/proot", "usr/bin/proot")
        )
        var removed = 0
        targets.forEach { paths ->
            var hit = false
            paths.forEach { rel ->
                val f = File(prefix, rel)
                if (f.exists() && f.delete()) hit = true
            }
            if (hit) removed++
        }
        return removed
    }

    fun installedDependencySteps(): List<StepState> = InstallStep.values().map { step ->
        StepState(step, if (isDependencyInstalled(step)) StepStatus.Done else StepStatus.Wait)
    }

    fun isDependencyInstalled(step: InstallStep): Boolean {
        val prefix = installer.rootDir
        fun hasExecutable(vararg paths: String) = paths.any { path ->
            File(prefix, path).let { it.isFile && it.canExecute() }
        }
        return when (step) {
            InstallStep.Jdk -> isJavaInstalled(JavaVersion.Java17)
            InstallStep.Wget -> hasExecutable("bin/wget", "usr/bin/wget", "data/data/com.termux/files/usr/bin/wget") &&
                File(prefix, "etc/fonts/fonts.conf").isFile
            InstallStep.Frp -> hasExecutable("bin/frpc", "usr/bin/frpc", "data/data/com.termux/files/usr/bin/frpc")
            InstallStep.Rclone -> hasExecutable("bin/rclone", "usr/bin/rclone", "data/data/com.termux/files/usr/bin/rclone")
            InstallStep.Proot -> hasExecutable("bin/proot", "usr/bin/proot")
        }
    }

    fun isCommandInstalled(command: String): Boolean {
        val prefix = installer.rootDir
        return listOf(
            File(prefix, "bin/$command"),
            File(prefix, "usr/bin/$command"),
            File(prefix, "data/data/com.termux/files/usr/bin/$command")
        ).any { it.isFile && it.canExecute() }
    }

    /** Read-only checks used by the dashboard diagnostic screen. */
    fun javaRuntimeDiagnostic(version: JavaVersion): Pair<Boolean, String> = when (version) {
        JavaVersion.Java8 -> {
            val rootfsReady = hasUsableUbuntuShell(java8Rootfs)
            val ready = java8UbuntuReady()
            when {
                ready -> true to "Ubuntu ARM64 container and openjdk-8-jdk are ready"
                !rootfsReady -> false to "Ubuntu ARM64 container shell is unavailable"
                else -> false to "openjdk-8-jdk is incomplete or has not passed verification"
            }
        }
        else -> {
            val ready = isJavaInstalled(version) && isJavaComplete(version)
            ready to if (ready) "${version.displayName} runtime is executable" else "${version.displayName} runtime is missing or incomplete"
        }
    }

    fun needsFontRuntime(core: ServerCore): Boolean = core == ServerCore.Forge || core == ServerCore.NeoForge

    fun fontRuntimeReady(version: JavaVersion): Boolean = if (version == JavaVersion.Java8) {
        hasUsableUbuntuShell(java8Rootfs) && runUbuntu(
            "test -f /etc/fonts/fonts.conf && test -x /usr/bin/fc-cache",
            15_000
        ) == 0
    } else {
        File(installer.rootDir, "etc/fonts/fonts.conf").isFile && isCommandInstalled("fc-cache")
    }

    /** Rebuild fontconfig with the resolved executable path rather than PATH lookup. */
    private fun rebuildTermuxFontCache(): Boolean {
        val prefix = installer.rootDir
        val cache = listOf(
            File(prefix, "bin/fc-cache"),
            File(prefix, "usr/bin/fc-cache"),
            File(prefix, "data/data/com.termux/files/usr/bin/fc-cache")
        ).firstOrNull { it.isFile && it.canExecute() } ?: run {
            emitLog("[repair] 字体缓存命令 fc-cache 未安装或不可执行")
            return false
        }
        val code = execOnce(cache.absolutePath, "-f")
        val ready = code == 0 && File(prefix, "etc/fonts/fonts.conf").isFile
        emitLog(if (ready) "[repair] 字体缓存已生成" else "[repair] 字体缓存生成失败(exit=$code)，请在运行诊断中查看命令状态")
        return ready
    }

    fun repairFontRuntime(): Boolean {
        if (!isReady()) return false
        val prefix = installer.rootDir
        if (!File(prefix, "etc/fonts/fonts.conf").isFile || !isCommandInstalled("fc-cache")) {
            emitLog("[repair] 正在补齐字体运行库...")
            if (!prepareAptPackages("fontconfig", "ttf-dejavu")) return false
            execOnce(
                "apt-get", "-o", "DPkg::Lock::Timeout=60", "install",
                "--allow-unauthenticated", "-y", "fontconfig", "ttf-dejavu"
            )
            repairInstalledCommands()
        }
        return rebuildTermuxFontCache()
    }

    suspend fun installJava(version: JavaVersion): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) throw RuntimeException("Termux 环境未初始化")
        if (version == JavaVersion.Java8) {
            return@withContext installJava8Ubuntu()
        }
        emitLog("[java] 正在安装 ${version.displayName}")
        emitLog("[java] ${version.displayName} 正在下载并配置，首次安装可能需要数分钟")
        if (version == JavaVersion.Java11 && !ensureTurRepo()) {
            emitLog("[java] ${version.displayName} 安装失败：TUR 社区软件源不可用，请检查网络后重试")
            return@withContext false
        }
        if (!prepareAptPackages(version.packageName)) {
            emitLog("[java] ${version.displayName} 安装失败：Termux 软件源或包索引不可用")
            return@withContext false
        }
        // 安装前再次校验 apt 可执行（防 dpkg 操作后权限被改）
        ensureAptWorking()
        val code = execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", version.packageName)
        if (code == 0) fixJavaSymlinks(version)
        val installed = code == 0 && isJavaInstalled(version)
        emitLog(if (installed) "[java] ${version.displayName} 安装完成" else "[java] ${version.displayName} 安装失败")
        installed
    }

    /**
     * TUR（Termux User Repository）提供 Termux 原生 openjdk-11；
     * tur-repo 包本身来自主仓库，安装后写入独立的 sources.list.d 条目。
     */
    private fun ensureTurRepo(): Boolean {
        val marker = File(installer.rootDir, "var/lib/dpkg/status")
        val hasTur = runCatching { marker.exists() && marker.useLines { lines ->
            lines.any { it == "Package: tur-repo" }
        } }.getOrDefault(false)
        if (hasTur) return true
        val code = execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "tur-repo")
        if (code != 0) emitLog("[java] tur-repo 安装失败（TUR 软件源不可用）")
        return code == 0
    }

    /** Install Java 8 inside the Ubuntu ARM64 glibc container. */
    private suspend fun installJava8Ubuntu(): Boolean {
        val marker = java8ReadyMarker
        if (java8UbuntuReady()) {
            emitLog("[java] Ubuntu ARM64 glibc Java 8 已安装，跳过重复下载")
            return true
        }
        emitLog("[java] 注意：正在 Ubuntu ARM64 glibc 环境安装 Java 8，非 Termux 官方源")
        emitLog("[java] Java 8 仅在 Ubuntu 内运行，不会修改 Java 17/25")
        marker.delete()
        return try {
            if (!prepareUbuntuRuntime()) {
                throw IllegalStateException("Ubuntu 容器无法启动，请先修复运行环境")
            }
            emitLog("[java] 正在 Ubuntu 中安装 ARM64 glibc Java 8，首次安装可能需要数分钟")
            val code = runUbuntu(
                ubuntuJava8InstallCommand(),
                timeoutMs = 900_000
            )
            if (code != 0) throw IllegalStateException("Ubuntu 内 Java 8 校验失败（exit=$code）")
            marker.writeText("ubuntu-focal-arm64-openjdk8\n")
            emitLog("[java] Ubuntu ARM64 glibc Java 8 安装并校验完成")
            true
        } catch (e: Exception) {
            marker.delete()
            emitLog("[java] Ubuntu Java 8 安装失败：${e.message}")
            false
        }
    }

    private fun ubuntuJava8InstallCommand(): String = """
        echo "[java] 正在恢复 Ubuntu 未完成的软件包事务..."
        if ! dpkg --configure -a || ! apt-get -o DPkg::Lock::Timeout=60 -f install -y; then
          echo "[java] Ubuntu dpkg 恢复失败，未继续切换软件源；请稍后重试运行环境修复"
          exit 1
        fi
        install_openjdk8() {
          mirror="${'$'}1"
          echo "[java] Ubuntu APT 源: ${'$'}mirror"
          printf '%s\n' \
            "deb ${'$'}mirror focal main restricted universe multiverse" \
            "deb ${'$'}mirror focal-updates main restricted universe multiverse" \
            "deb ${'$'}mirror focal-security main restricted universe multiverse" \
            > /etc/apt/sources.list
          rm -rf /var/lib/apt/lists/*
          if apt-get update -o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 && \
            apt-get install -y --no-install-recommends openjdk-8-jdk ca-certificates fontconfig fonts-dejavu-core; then
            test -x /usr/bin/java && /usr/bin/java -version 2>&1 | grep -q '1\.8\.'
            return ${'$'}?
          fi
          return 1
        }
        # The dedicated Ubuntu rootfs may not have CA certificates yet. Use
        # domestic HTTP only for this bootstrap transaction, then install
        # ca-certificates together with Java for subsequent HTTPS use.
        install_openjdk8 http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports || \
          install_openjdk8 http://mirrors.ustc.edu.cn/ubuntu-ports
    """.trimIndent()

    private fun java8UbuntuReady(): Boolean {
        val rootfs = java8Rootfs
        val javaHome = File(rootfs, ubuntuJava8Home.removePrefix("/"))
        val jvmLibrary = listOf(
            File(javaHome, "jre/lib/aarch64/server/libjvm.so"),
            File(javaHome, "lib/server/libjvm.so")
        ).any { it.isFile }
        return java8ReadyMarker.isFile &&
            listOf(File(javaHome, "jre/bin/java"), File(javaHome, "bin/java")).any { it.isFile } &&
            jvmLibrary
    }

    private fun prepareUbuntuRuntime(): Boolean {
        if (!prepareAptPackages("proot")) {
            emitLog("[java] Java 8 前置依赖 proot 无法从 Termux 软件源获取")
            return false
        }
        var code = execOnce(
            "apt-get", "-o", "DPkg::Lock::Timeout=60", "install",
            "--allow-unauthenticated", "-y", "proot"
        )
        if (code != 0) return false
        fixUsrBin()
        fixScriptsOnce()
        ensureRootfsExecutable()
        repairProotLibraries()
        installProotLauncher()
        ensureRootfsExecutable()
        if (!verifyProot()) return false

        if (runUbuntu("test -x /bin/sh && exit 0", 60_000) == 0) return true
        if (!installUbuntuBaseRootfs()) {
            emitLog("[java] Java 8 专用 Ubuntu rootfs 无法重建；旧的 Debian/Ubuntu 容器和服务器数据未被修改")
            return false
        }
        emitLog("[java] 正在部署 Ubuntu ARM64 rootfs，首次安装需要较长时间")
        return runUbuntu("test -x /bin/sh && exit 0", 60_000) == 0
    }

    /**
     * Uses the Ubuntu Base image instead of proot-distro's Docker Hub image.
     * Docker Hub commonly returns HTTP 429 on mobile networks; the two URLs
     * below are ordinary Ubuntu archive endpoints and do not require an account.
     */
    private fun installUbuntuBaseRootfs(): Boolean {
        if (hasUsableUbuntuShell(java8Rootfs)) return true

        val archiveName = "ubuntu-base-20.04.5-base-arm64.tar.gz"
        val archive = File(installer.tmpDir, archiveName)
        val urls = listOf(
            "https://mirrors.tuna.tsinghua.edu.cn/ubuntu-cdimage/ubuntu-base/releases/20.04/release/$archiveName",
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/20.04/release/$archiveName"
        )
        return try {
            emitLog("[java] 正在下载 Ubuntu 20.04 ARM64 基础 rootfs（非 Docker Hub）")
            archive.delete()
            var lastError: Exception? = null
            for (url in urls) {
                try {
                    emitLog("[java] Ubuntu rootfs source: $url")
                    downloadUbuntuRootfs(url, archive)
                    if (isPlausibleUbuntuArchive(archive)) break
                    throw IllegalStateException("Ubuntu rootfs download is incomplete")
                } catch (e: Exception) {
                    lastError = e
                    archive.delete()
                    emitLog("[java] Ubuntu rootfs source failed: ${e.message}")
                }
            }
            if (!isPlausibleUbuntuArchive(archive)) {
                throw lastError ?: IllegalStateException("Ubuntu rootfs download failed")
            }

            // This is the dedicated Java 8 rootfs only.  Never touch legacy
            // proot-distro containers, server files, worlds, plugins, or config.
            java8Rootfs.deleteRecursively()
            java8Rootfs.mkdirs()
            extractUbuntuRootfs(archive, java8Rootfs)
            repairUbuntuRootfs()
            if (!hasUsableUbuntuShell(java8Rootfs)) {
                throw IllegalStateException("Ubuntu rootfs is missing an executable shell")
            }
            emitLog("[java] Ubuntu ARM64 rootfs is ready")
            true
        } catch (e: Exception) {
            java8Rootfs.deleteRecursively()
            emitLog("[java] Ubuntu rootfs installation failed: ${e.message}")
            false
        } finally {
            archive.delete()
        }
    }

    private fun hasUsableUbuntuShell(rootfs: File): Boolean =
        listOf("bin/sh", "bin/bash", "usr/bin/bash")
            .map { File(rootfs, it) }
            .any { it.isFile && it.canExecute() }

    private fun isPlausibleUbuntuArchive(archive: File): Boolean {
        if (!archive.isFile || archive.length() < 20L * 1024 * 1024) return false
        return FileInputStream(archive).use { input ->
            input.read() == 0x1f && input.read() == 0x8b
        }
    }

    private fun downloadUbuntuRootfs(url: String, target: File) {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000
            readTimeout = 60_000
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", "MineServeMobile/1.0")
        }
        try {
            connection.connect()
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("HTTP ${connection.responseCode}")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastPercent = -1
            connection.inputStream.use { input ->
                FileOutputStream(target).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        if (total > 0) {
                            val percent = (downloaded * 100 / total).toInt()
                            if (percent / 10 != lastPercent / 10) {
                                lastPercent = percent
                                emitLog("[java] Ubuntu rootfs download: $percent%")
                            }
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun extractUbuntuRootfs(archive: File, destination: File) {
        FileInputStream(archive).use { fileInput ->
            GzipCompressorInputStream(BufferedInputStream(fileInput)).use { gzip ->
                TarArchiveInputStream(gzip).use { tar ->
                    var entry = tar.nextTarEntry
                    while (entry != null) {
                        val output = File(destination, entry.name)
                        val rootPath = destination.canonicalPath + File.separator
                        if (!output.canonicalPath.startsWith(rootPath)) {
                            throw IllegalStateException("Unsafe Ubuntu rootfs path: ${entry.name}")
                        }
                        when {
                            entry.isDirectory -> output.mkdirs()
                            entry.isSymbolicLink -> {
                                output.parentFile?.mkdirs()
                                output.delete()
                                Os.symlink(entry.linkName, output.absolutePath)
                            }
                            entry.isLink -> {
                                val source = File(destination, entry.linkName)
                                output.parentFile?.mkdirs()
                                output.delete()
                                if (source.isFile) source.copyTo(output, overwrite = true)
                            }
                            else -> {
                                output.parentFile?.mkdirs()
                                FileOutputStream(output).use { tar.copyTo(it) }
                                if ((entry.mode and 0b001_001_001) != 0) output.setExecutable(true, false)
                            }
                        }
                        entry = tar.nextTarEntry
                    }
                }
            }
        }
    }

    private fun prootEnvironment(): Map<String, String> {
        val prefix = installer.rootDir.absolutePath
        val tmp = File(installer.rootDir, "tmp").apply { mkdirs() }.absolutePath
        return mapOf(
            "PROOT_TMP_DIR" to tmp,
            "TMPDIR" to tmp,
            "TERMUX_PREFIX" to prefix,
            "TERMUX_HOME" to "$prefix/home",
            "TERMUX__PREFIX" to prefix,
            "TERMUX__HOME" to "$prefix/home",
            "TERMUX_APP_PACKAGE" to "com.mineserve.mobile",
            "TERMUX_APP__PACKAGE_NAME" to "com.mineserve.mobile",
            "PROOT_LOADER" to "$prefix/libexec/proot/loader",
            "PROOT_LOADER_32" to "$prefix/libexec/proot/loader32",
            "TERMUX_VERSION" to "mineServe"
        )
    }

    private fun runUbuntu(
        command: String,
        timeoutMs: Long = 120_000,
        extraBinds: List<String> = emptyList()
    ): Int =
        if (!repairUbuntuRootfs()) {
            emitLog("[java] Ubuntu rootfs 缺少可执行 shell，无法启动")
            126
        } else {
            val rootfs = java8Rootfs
            val shm = File(rootfs, "tmp").apply { mkdirs() }
            val resolver = prepareUbuntuDns(rootfs)
            val args = mutableListOf(
                "proot",
                "--kill-on-exit",
                "--link2symlink",
                "--sysvipc",
                "-L",
                "--change-id=0:0",
                "--rootfs=${rootfs.absolutePath}",
                "--cwd=/root",
                "--bind=/dev",
                "--bind=/proc",
                "--bind=/sys",
                "--bind=/dev/urandom:/dev/random",
                "--bind=${shm.absolutePath}:/dev/shm",
                "--bind=${resolver.absolutePath}:/etc/resolv.conf"
            )
            extraBinds.forEach { args += "--bind=$it" }
            args += listOf(
                "/usr/bin/env",
                "-i",
                "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                "HOME=/root",
                "TMPDIR=/tmp",
                "LANG=C.UTF-8",
                "DEBIAN_FRONTEND=noninteractive",
                "/bin/sh",
                "-c",
                command
            )
            execOnceWithTimeout(timeoutMs, *args.toTypedArray(), env = prootEnvironment())
        }

    /** Give the isolated Ubuntu rootfs Android's active DNS servers. */
    private fun prepareUbuntuDns(rootfs: File): File {
        val addresses = (1..4).mapNotNull { index ->
            readAndroidDns("net.dns$index").takeIf { it.isNotBlank() && it.none(Char::isWhitespace) }
        }.distinct()
        val nameservers = if (addresses.isNotEmpty()) addresses else listOf("223.5.5.5", "119.29.29.29")
        val content = nameservers.joinToString(separator = "\n", postfix = "\n") { "nameserver $it" }
        val resolver = File(installer.tmpDir, "java8-resolv.conf")
        resolver.parentFile?.mkdirs()
        resolver.writeText(content)

        // Ubuntu Base ships /etc/resolv.conf as a systemd-resolved symlink.
        // The target does not exist in this PRoot container, so replace it with
        // a regular file before binding the same resolver for every invocation.
        val guestResolver = File(rootfs, "etc/resolv.conf")
        guestResolver.delete()
        guestResolver.parentFile?.mkdirs()
        guestResolver.writeText(content)
        return resolver
    }

    private fun readAndroidDns(property: String): String = runCatching {
        val process = ProcessBuilder("/system/bin/getprop", property)
            .redirectErrorStream(true)
            .start()
        val value = process.inputStream.bufferedReader().use { it.readText().trim() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroyForcibly()
        value
    }.getOrDefault("")

    /** Restore executable bits lost while proot-distro applies a rootfs layer. */
    private fun repairUbuntuRootfs(): Boolean {
        val rootfs = java8Rootfs
        if (!rootfs.isDirectory) return false
        var fixed = 0
        val visited = mutableSetOf<String>()
        val commandDirs = listOf("bin", "sbin", "usr/bin", "usr/sbin")
        commandDirs.forEach { relative ->
            File(rootfs, relative).listFiles()?.forEach { file ->
                if (!file.isFile || !visited.add(file.canonicalPath)) return@forEach
                if (!file.canExecute() && file.setExecutable(true, false)) fixed++
            }
        }
        val chmodTargets = commandDirs.map { File(rootfs, it) }.filter { it.exists() }
        if (chmodTargets.isNotEmpty()) {
            val targetArgs = chmodTargets.joinToString(" ") { "'${it.absolutePath.replace("'", "'\\''")}'" }
            runCatching {
                val chmod = ProcessBuilder("/system/bin/sh", "-c", "chmod -R 755 $targetArgs")
                    .redirectErrorStream(true)
                    .start()
                val output = chmod.inputStream.bufferedReader().use { it.readText() }
                if (chmod.waitFor(30, TimeUnit.SECONDS) && chmod.exitValue() == 0) {
                    if (output.isNotBlank()) Log.d(TAG, "repairUbuntuRootfs chmod: $output")
                } else {
                    chmod.destroyForcibly()
                    Log.w(TAG, "repairUbuntuRootfs: chmod failed")
                }
            }.onFailure { Log.w(TAG, "repairUbuntuRootfs: chmod exception: ${it.message}") }
        }
        listOf(
            "lib/ld-linux-aarch64.so.1",
            "lib/aarch64-linux-gnu/ld-linux-aarch64.so.1"
        ).forEach { relative ->
            val loader = File(rootfs, relative)
            if (loader.isFile && !loader.canExecute() && loader.setExecutable(true, false)) fixed++
        }
        if (fixed > 0) emitLog("[bootstrap] 修复 $fixed 个 Ubuntu rootfs 命令可执行权限")
        return listOf("bin/sh", "bin/bash", "usr/bin/bash")
            .map { File(rootfs, it) }
            .any { it.isFile && it.canExecute() }
    }

    /** Run a Java 8 installer inside Ubuntu with only the selected server bound in. */
    fun runJava8Installer(jarPath: String, serverDir: File, timeoutMs: Long = 900_000): Int {
        val guestDir = "/srv/mineserve"
        val guestJar = "$guestDir/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
        val command = "export JAVA_HOME=$ubuntuJava8Home; " +
            "export TMPDIR=/tmp; export HOME=/root; cd '$guestDir' && " +
            "exec /usr/bin/java -Djava.io.tmpdir=/tmp -jar '$guestJar' " +
            "--installServer"
        return execUbuntuBound(
            serverDir,
            command,
            timeoutMs
        )
    }

    /** Execute a caller-provided Java 8 setup command inside the Ubuntu container. */
    fun runJava8Command(serverDir: File, command: String, timeoutMs: Long = 900_000): Int =
        execUbuntuBound(serverDir, command, timeoutMs)

    private fun execUbuntuBound(serverDir: File, command: String, timeoutMs: Long): Int {
        return runUbuntu(
            command,
            timeoutMs,
            extraBinds = listOf("${serverDir.absolutePath}:/srv/mineserve")
        )
    }

    suspend fun clearAndReinstallJava(): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) throw RuntimeException("Termux 环境未初始化")
        val versionsToRestore = installedJavaVersions()
        if (versionsToRestore.isEmpty()) return@withContext true
        emitLog("[java] 正在清除并重装 ${versionsToRestore.joinToString { it.displayName }}")
        versionsToRestore.forEach { version ->
            if (version != JavaVersion.Java8) {
                execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "remove", "-y", version.packageName)
            }
            javaCandidates(version).forEach { File(it).deleteRecursively() }
            if (version == JavaVersion.Java8) {
                java8ReadyMarker.delete()
                File(installer.rootDir, "java-8-android-ready").delete()
                File(installer.rootDir, "lib/jvm/java-8-android").deleteRecursively()
                java8Rootfs.deleteRecursively()
            }
        }
        File(installer.rootDir, "bin/java").delete()
        versionsToRestore.sortedBy { it.ordinal }.all { installJava(it) }
    }

    private fun javaCandidates(version: JavaVersion): List<String> {
        val prefix = installer.rootDir.absolutePath
        if (version == JavaVersion.Java8) {
            return emptyList()
        }
        val candidates = mutableListOf(
            "$prefix/lib/jvm/${version.directoryName}",
            "$prefix/data/data/com.termux/files/usr/lib/jvm/${version.directoryName}"
        )
        return candidates
    }

    /**
     * Termux 侧被 apt/dpkg/proot 依赖的共享库清单。
     *
     * 每一项：库文件名（运行时名）→ 历史曾出现过的落点（按优先级）。
     * 前者是 Termux 标准库目录，后者是 dpkg-deb -x 解包时 compat 符号链接被覆盖后的实际落点。
     *
     * 清单由 [PACKAGED_LIBRARIES] 派生，保证「打包的库」与「修复时就位的库」永远一致，
     * 不会出现「打包了但没落盘」或「落盘了但没打包」的错位。
     *
     * 刻意**不用** [BUNDLED_LIBRARIES]：它还含 libtalloc / libandroid-shmem 这两个
     * 只可能由 proot-distro 提供的库，拿它们做「是否已就位」的检查会在每次初始化
     * 稳定报出两条永远消不掉的「未找到」警告（详见 NativeLibraryBundler 的说明）。
     */
    private val termuxLibraryCandidates: Map<String, List<String>> =
        PACKAGED_LIBRARIES.values.distinct().associateWith { name ->
            listOf(
                "usr/lib/$name",
                "data/data/com.termux/files/usr/lib/$name"
            )
        }

    /** 把随 APK 打包的共享库落到 Termux 能加载到的位置（幂等）。 */
    private fun provisionBundledLibraries(): List<String> {
        val prefix = installer.rootDir
        // BootstrapInstaller 会把 `data/data/com.termux/files/usr` 建成指向 rootDir 的
        // 符号链接（见 BootstrapInstaller 里创建兼容符号链接那段），于是它的 lib/
        // 就是 rootDir/lib —— 与第一个落点是同一个目录。
        //
        // 符号链接已存在时重复落盘毫无意义；而在符号链接建立**之前**执行的话，
        // 往这个路径写会把它从「链接」变成一个真实目录，回头 BootstrapInstaller
        // 还得先 deleteRecursively 再重建链接（它已经为此写了兜底逻辑）。
        // 所以：只有它确实是一个独立目录时才当作落点。
        val compatUsr = File(prefix, "data/data/com.termux/files/usr")
        val compatIsSymlink = runCatching {
            java.nio.file.Files.isSymbolicLink(compatUsr.toPath())
        }.getOrDefault(false)
        return NativeLibraryBundler.provision(
            bundledDir = bundledLibDir,
            targetDirs = buildList {
                add(File(prefix, "lib"))
                add(File(prefix, "usr/lib"))
                if (!compatIsSymlink) add(File(compatUsr, "lib"))
            }
        )
    }

    private fun repairProotLibraries(): Boolean {
        val prefix = installer.rootDir
        // 打包的库先落盘，再走下面统一的就位/汇报逻辑
        provisionBundledLibraries()

        val allReady = termuxLibraryCandidates.map { (name, relativeCandidates) ->
            val sources = relativeCandidates.map { File(prefix, it) }
            val target = File(File(prefix, "lib").apply { mkdirs() }, name)

            // 1. 先补 canonical 位置（proot 启动包装脚本的 LD_LIBRARY_PATH 首项）
            if (!target.isFile) {
                sources.firstOrNull { it.isFile }
                    ?.let { source -> runCatching { source.copyTo(target, overwrite = false) } }
            }
            // 2. 再把 canonical 副本回填到各历史落点，保证无论从哪条路径搜索都能命中
            if (target.isFile) {
                sources.filterNot { it.absolutePath == target.absolutePath }.forEach { dest ->
                    if (!dest.isFile) {
                        runCatching {
                            dest.parentFile?.mkdirs()
                            target.copyTo(dest, overwrite = false)
                        }
                    }
                }
            }

            val ready = target.isFile || sources.any { it.isFile }
            emitLog(
                if (ready) "[bootstrap] 依赖已就绪: $name"
                else "[bootstrap] 警告: 依赖 $name 未找到"
            )
            ready
        }.all { it }

        // apt-get 缺库是硬失败，必须探测出来而不是继续往下走流程
        auditTermuxLibraries()
        return allReady
    }

    /**
     * 对 Termux 侧关键命令做一次「缺库体检」。
     *
     * 背景：`F linker: CANNOT LINK EXECUTABLE ".../apt-get": library "libandroid-glob.so"
     * not found` 之后，既有兜底链路会把链接失败伪装成「权限问题」，反复输出
     * Permission denied / exec 探针 / 复制副本执行等无关诊断，把真因完全盖住。
     * 这里在 apt 真正动手之前，用 linker 自己把每个关键命令的依赖解析一遍，
     * 缺什么直接列出来 —— 结论明确，不再需要用户猜。
     *
     * ## 只警告，不拦截（重要）
     * 早期版本把这里的返回值当作**门禁**：一旦探测到任何「缺库」就 `return false`，
     * 直接放弃 apt 安装。这个设计是错的，理由是：
     *  1. 探测本身可能误报 —— 例如 bash 依赖 `libreadline.so.8`（仅 Tab 补全用），
     *     但 apt-get 根本不依赖 readline。把非关键命令的瑕疵当成致命错误，
     *     会让一个**完全正常的环境**被判为不可用。
     *  2. 探测是在 App 侧拼 LD_LIBRARY_PATH 做的静态解析，与 apt 实际执行时的
     *     环境（proot / 包装脚本 / shebang 解释器）并不完全一致，本就存在偏差。
     *  3. 代价极不对称：放行最多是 apt 自己报错（信息更准确）；拦错则整个初始化失败。
     *
     * 因此现在**永远返回 true**，仅把结果写进日志。真正的失败交给 apt 自己反馈。
     *
     * @return 恒为 true（保留返回值是为了兼容既有调用点）
     */
    private fun auditTermuxLibraries(): Boolean {
        val prefix = installer.rootDir
        val (blocking, advisory) = NativeLibraryBundler.auditIfEnabled(prefix, bundledLibDir)

        advisory.forEach { (binary, libs) ->
            // 非致命：这些命令即使缺库也不影响包管理
            emitLog("[bootstrap] 提示: $binary 依赖 ${libs.joinToString()}（非关键，不影响安装）")
        }
        blocking.forEach { (binary, libs) ->
            emitLog("[bootstrap] 警告: $binary 可能缺少 ${libs.joinToString()}")
        }
        if (blocking.isNotEmpty()) {
            emitLog("[bootstrap] 将继续尝试安装；若确实失败，请把上面的清单连同 apt 报错一起反馈")
        }
        return true
    }

    /**
     * 在 apt 确实失败后，给出「是否因为缺库」的明确结论。
     *
     * 与 [auditTermuxLibraries] 的区别：那个是**事前**猜测（只写日志），
     * 这个是**事后**归因 —— 已经确认失败了，此时把缺库清单摆出来才有意义，
     * 也不会再有机会误伤正常流程。
     */
    private fun reportLinkingFailure() {
        runCatching {
            val missing = NativeLibraryBundler.auditTermuxBinaries(installer.rootDir, bundledLibDir)
            if (missing.isEmpty()) return
            emitLog("[apt] 结论: 以下命令的依赖库在设备上找不到，这通常就是失败原因：")
            missing.forEach { (binary, libs) ->
                emitLog("[apt]   - $binary 需要 ${libs.joinToString()}")
            }
            emitLog("[apt] 这些是随 APK 打包的补充库；若清单里有打包清单未覆盖的项，请反馈这一行")
        }.onFailure { emitLog("[apt] 缺库归因失败: ${it.message}") }
    }

    /**
     * proot-distro sanitizes LD_LIBRARY_PATH before it invokes proot.  The app's
     * rootfs is relocated, so proot cannot rely on Termux's normal ELF rpath.
     * Keep the actual binary aside and expose a tiny launcher which restores the
     * required library path for both direct and proot-distro invocations.
     */
    private fun installProotLauncher() {
        val prefix = installer.rootDir.absolutePath
        val launcher = File(prefix, "bin/proot")
        val binary = File(prefix, "bin/proot.bin")
        if (!launcher.isFile && !launcher.exists()) return

        // A prior install may replace the launcher with the package's ELF again.
        // Rename only ELF files; never rename our shell launcher on repeated runs.
        val isElf = runCatching {
            launcher.inputStream().use { input ->
                val header = ByteArray(4)
                input.read(header) == 4 && header.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))
            }
        }.getOrDefault(false)
        if (isElf) {
            if (binary.exists()) binary.delete()
            if (!launcher.renameTo(binary)) {
                emitLog("[bootstrap] 警告: 无法创建 proot 启动包装")
                return
            }
        }
        if (!binary.isFile) return
        binary.setExecutable(true, false)
        val compatUsrLib = "$prefix/data/data/com.termux/files/usr/lib"
        // 打包的库目录一并注入：修复过旧版本的设备上 usr/lib 可能仍缺 libandroid-glob.so
        val libPath = listOfNotNull(
            "$prefix/lib",
            "$prefix/usr/lib",
            compatUsrLib,
            bundledLibDir,
            "/system/lib64"
        ).joinToString(":")
        launcher.writeText(
            "#!/system/bin/sh\n" +
                "export PROOT_TMP_DIR='$prefix/tmp'\n" +
                "export TMPDIR='$prefix/tmp'\n" +
                "export PROOT_LOADER='$prefix/libexec/proot/loader'\n" +
                "export PROOT_LOADER_32='$prefix/libexec/proot/loader32'\n" +
                "export LD_LIBRARY_PATH='$libPath'\n" +
                "exec '$binary' \"${'$'}@\"\n"
        )
        launcher.setExecutable(true, false)
        emitLog("[bootstrap] 已固定 proot 动态库启动环境")
    }

    private fun verifyProot(): Boolean {
        val code = execOnce("proot", "--version")
        if (code == 0) {
            emitLog("[bootstrap] proot 启动校验通过")
        }
        return code == 0
    }

    /** Rewrite paths embedded in proot-distro metadata/scripts after relocation. */
    private fun repairProotDistroPaths() {
        val prefix = installer.rootDir.absolutePath
        val oldPrefix = "/data/data/com.termux/files/usr"
        val roots = listOf(
            File(prefix, "bin"),
            File(prefix, "usr/bin"),
            File(prefix, "etc/proot-distro"),
            File(prefix, "usr/etc/proot-distro"),
            File(prefix, "usr/share/proot-distro")
        )
        var fixed = 0
        roots.filter { it.exists() }.forEach { root ->
            val isConfigTree = root.path.contains("${File.separator}proot-distro")
            root.walkTopDown().filter { it.isFile && it.length() <= 512 * 1024 }.forEach { file ->
                runCatching {
                    // ELF files can contain the old path in RPATH/debug strings.
                    // Never read/write them as text: doing so corrupts proot.bin,
                    // apt-get and every other native executable.
                    val bytes = file.inputStream().use { input ->
                        val head = ByteArray(4)
                        val count = input.read(head)
                        if (count == 4 && head.contentEquals(byteArrayOf(0x7f, 'E'.code.toByte(), 'L'.code.toByte(), 'F'.code.toByte()))) {
                            return@runCatching
                        }
                        head
                    }
                    val content = file.readText()
                    if (!isConfigTree && !content.startsWith("#!")) return@runCatching
                    if (content.indexOf('\u0000') >= 0) return@runCatching
                    if (content.contains(oldPrefix)) {
                        file.writeText(content.replace(oldPrefix, prefix))
                        if (!isConfigTree) file.setExecutable(true, false)
                        fixed++
                    }
                }
            }
        }
        // proot itself probes this path even when TMPDIR is inherited.
        File(prefix, "tmp").mkdirs()
        File(prefix, "data/data/com.termux/files/usr/tmp").mkdirs()
        if (fixed > 0) emitLog("[bootstrap] 修复 $fixed 个 proot-distro 路径")
    }

    /**
     * 绕过脚本自身 exec 位/标签限制，直接用其 shebang 解释器执行（如 perl apt-get）。
     * 适用于脚本 exec 被拒（Permission denied）但解释器可正常执行的环境。
     */
    /**
     * 直接 exec 探测，捕获精确的 IOException 信息（含 errno），用于区分
     * Permission denied(13) / No such file(2) / Exec format(8) 等不同拒绝原因。
     */
    /**
     * 决定性测试：把系统原生二进制复制到 app 数据目录再 exec。
     *  - 复制副本可执行 → app_data 执行未被设备禁止，问题在 bootstrap ELF（页大小/加载器）；
     *  - 复制副本同样 EACCES → 设备禁止执行 app 数据目录原生程序（SELinux/策略层面，需换机制或 targetSdk）。
     */
    fun probeSystemCopyExec(): String {
        val src = File("/system/bin/toybox")
        if (!src.isFile) return "系统二进制 /system/bin/toybox 不存在"
        val copy = File(installer.rootDir, "tmp/system-toybox-copy")
        return try {
            copy.parentFile?.mkdirs()
            src.copyTo(copy, overwrite = true)
            copy.setExecutable(true, false)
            val pb = ProcessBuilder(copy.absolutePath, "--help").redirectErrorStream(true).start()
            val out = pb.inputStream.bufferedReader().use { it.readText() }.take(120)
            val done = pb.waitFor(3, TimeUnit.SECONDS)
            if (!done) pb.destroyForcibly()
            val tag = if (done && pb.exitValue() == 0) "可执行" else "启动异常(exit=${if (done) pb.exitValue() else "timeout"})"
            "系统二进制副本 $tag | $out"
        } catch (e: Exception) {
            "系统二进制副本 exec 失败: ${e.javaClass.simpleName}: ${e.message}"
        } finally {
            runCatching { copy.delete() }
        }
    }

    /** 设备内存页大小（16KB 页设备无法执行 4KB 页对齐的旧 ELF） */
    fun pageSize(): String = try {
        val pb = ProcessBuilder("/system/bin/getconf", "PAGE_SIZE").redirectErrorStream(true).start()
        val out = pb.inputStream.bufferedReader().use { it.readText() }.trim()
        if (pb.waitFor(2, TimeUnit.SECONDS)) out else "未知"
    } catch (_: Exception) { "未知" }

    fun probeExecErrno(path: String): String {
        return try {
            val pb = ProcessBuilder(path, "--version").redirectErrorStream(true).start()
            val out = pb.inputStream.bufferedReader().use { it.readText() }.take(200)
            val code = pb.waitFor(3, TimeUnit.SECONDS)
            if (!code) pb.destroyForcibly()
            "exec 成功 (exit=${if (code) pb.exitValue() else "timeout"}) ${out.trim()}"
        } catch (e: Exception) {
            "exec 失败: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    fun execScriptViaInterpreter(script: File, vararg args: String): Int {
        val head = try { script.bufferedReader().use { it.readLine() } } catch (_: Exception) { null } ?: return 1
        if (!head.startsWith("#!")) return 1
        val interpLine = head.removePrefix("#!").trim()
        val interpPath = interpLine.split(Regex("\\s+")).firstOrNull() ?: return 1
        val prefix = installer.rootDir.absolutePath
        val interp = when {
            interpPath.startsWith(prefix) -> File(interpPath)
            interpPath.startsWith("/") -> File(prefix, interpPath.removePrefix("/"))
            else -> File(prefix, "usr/bin/$interpPath")
        }
        if (interp.exists() && !interp.canExecute()) runCatching { interp.setExecutable(true, false) }
        if (interp.exists()) {
            emitLog("[apt] 改用解释器 ${interp.name} 直接执行 apt-get（绕过脚本 exec 限制）")
            return execOnce(interp.absolutePath, script.absolutePath, *args)
        }
        emitLog("[apt] 解释器 ${interpPath} 不存在，无法绕过执行")
        return 1
    }

    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int {
        // Some older installations reached apt through a direct internal path
        // that did not carry -y. Keep the shared apt config non-interactive so
        // a closed stdin cannot turn a valid install into "Abort".
        if (command.firstOrNull()?.let { File(it).name } in setOf("apt", "apt-get", "pkg")) {
            ensureAptConfigs()
        }
        return executor.execOnce(*command, env = env)
    }

    fun execOnceWithTimeout(
        timeoutMs: Long,
        vararg command: String,
        env: Map<String, String> = emptyMap()
    ): Int = executor.execOnceWithTimeout(timeoutMs, *command, env = env)


    /**
     * apt-get 执行前强制自愈 + 校验（幂等，毫秒级）。
     *
     * 背景：安装 Java 等依赖时偶发 `/system/bin/sh: .../bin/apt-get: Permission denied`（退出码 126）。
     * 多为 bin/apt-get 链接目标丢失 exec 位、链接失效，或 shebang 解释器（apt 为 perl 脚本）
     * 缺失/无 exec 位；旧版本环境还可能是 shebang 仍指向 /data/data/com.termux 被系统拒绝。
     *
     * 修复：强制重跑 归位+exec位+shebang 改写 → 校验并重建 bin/apt-get 链接 →
     * 校验解释器存在且可执行 → 仍失败时输出 diagnoseCommand("apt-get") 便于定位。
     */
    fun ensureAptWorking(): Boolean {
        val prefix = installer.rootDir.absolutePath
        // 1. 强制自愈（幂等；顺序：先归位再补权限与 shebang）
        runCatching { fixUsrBin() }
        ensureRootfsExecutable()
        fixScriptsOnce()
        // 2. 校验并重建 bin/apt-get 链接（目标缺失时从 usr/bin 重建）
        val link = File(prefix, "bin/apt-get")
        val real = File(prefix, "usr/bin/apt-get")
        if (!link.exists() && real.isFile) {
            runCatching {
                java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get("../usr/bin/apt-get"))
                emitLog("[apt] 已重建 bin/apt-get 链接")
            }
        }
        // 3. 确保 apt-get 本体与链接目标可执行
        listOf(link, real).forEach { f ->
            if (f.exists() && !f.canExecute()) runCatching { f.setExecutable(true, false) }
        }
        // 4. 校验 shebang 解释器（缺失时尝试从 compat 路径补全）
        ensureScriptInterpreter(real)
        // 5. 先排掉「缺库」这个更靠前的原因：missing lib 会让二进制在任何权限检查前
        //    就被 linker 拒绝，现象酷似 Permission denied，会把后续兜底链路带偏
        val missingLibs = if (real.isFile) {
            NativeLibraryBundler.missingLibrariesFor(real, bundledLibDir)
        } else emptyList()
        // 6. 终检：跟随链接判定可执行
        val ok = link.canExecute()
        if (!ok) {
            if (missingLibs.isNotEmpty()) {
                emitLog("[apt] 根因: apt-get 缺少动态库 ${missingLibs.joinToString()}（非权限问题）")
            }
            emitLog("[apt] apt-get 仍不可执行，诊断如下：\n" + diagnoseCommand("apt-get"))
        } else if (missingLibs.isNotEmpty()) {
            // canExecute 只看权限位，缺库要到 exec 时才炸，这里提前告警
            emitLog("[apt] 警告: apt-get 权限正常但缺少动态库 ${missingLibs.joinToString()}，执行会失败")
        }
        return ok
    }

    /** 解析脚本 shebang 并确保解释器存在且可执行（缺失时尝试从 compat 路径补全到 usr/bin） */
    private fun ensureScriptInterpreter(script: File) {
        if (!script.exists() || !script.isFile) return
        val head = try { script.bufferedReader().use { it.readLine() } } catch (_: Exception) { null } ?: return
        if (!head.startsWith("#!")) return
        val interp = head.removePrefix("#!").trim().split(Regex("\\s+")).firstOrNull() ?: return
        val prefix = installer.rootDir.absolutePath
        val name = File(interp).name
        val candidates = listOfNotNull(
            if (interp.startsWith(prefix)) File(interp) else null,
            if (!interp.startsWith(prefix)) File(prefix, interp.removePrefix("/")) else null,
            File(prefix, "usr/bin/$name"),
            File(prefix, "bin/$name"),
            File(prefix, "data/data/com.termux/files/usr/bin/$name")
        ).distinct()
        val found = candidates.firstOrNull { it.exists() }
        if (found != null) {
            if (!found.canExecute()) runCatching { found.setExecutable(true, false) }
            return
        }
        // 解释器缺失：从 compat 补全到 usr/bin（保留 exec 位）
        val compat = File(prefix, "data/data/com.termux/files/usr/bin/$name")
        val dest = File(prefix, "usr/bin/$name")
        if (compat.isFile && !dest.exists()) {
            runCatching { compat.copyTo(dest); dest.setExecutable(true, false) }
        }
        if (!File(prefix, "usr/bin/$name").exists()) {
            emitLog("[apt] shebang 解释器 $interp 缺失（usr/bin/$name 不存在），apt-get 可能仍无法执行")
        }
    }

    /** Refresh the package index and verify every requested package before apt install. */
    fun prepareAptPackages(vararg packages: String): Boolean {
        if (!isReady()) return false
        repairInstalledCommands()
        // 缺库体检：只写日志，不拦截。
        // 早期版本这里会 return false 中止安装，导致「bash 缺 readline」这种
        // 无关紧要的瑕疵把整个初始化搞挂。现在先补库、再记录，然后一切照常往下走。
        auditTermuxLibraries()
        provisionBundledLibraries()
        // apt-get 偶发 Permission denied（126）：先强制自愈并校验 bin/usr/bin 下的可执行位与 shebang 解释器
        if (!ensureAptWorking()) return false
        var update = execOnce(
            "apt-get", "-o", "DPkg::Lock::Timeout=60", "update",
            "--allow-insecure-repositories", "-y"
        )
        if (update != 0) {
            // 自愈后重试（dpkg/软件源操作可能刚改动了命令权限）
            emitLog("[apt] 软件源更新失败，正在自愈后重试...")
            ensureRootfsExecutable()
            fixScriptsOnce()
            update = execOnce(
                "apt-get", "-o", "DPkg::Lock::Timeout=60", "update",
                "--allow-insecure-repositories", "-y"
            )
            if (update != 0) {
                // 兜底：绕过 bin/ 链接，直接用 usr/bin 真实路径执行（bin 链接异常时可恢复）
                emitLog("[apt] 仍失败，改用 usr/bin/apt-get 真实路径重试...")
                val real = java.io.File(installer.rootDir, "usr/bin/apt-get")
                if (real.isFile || real.canExecute()) {
                    update = execOnce(
                        real.absolutePath, "-o", "DPkg::Lock::Timeout=60", "update",
                        "--allow-insecure-repositories", "-y"
                    )
                }
            }
            if (update != 0) {
                // 兜底2：直接用 shebang 解释器（perl/bash 等）执行，绕过脚本 exec 位/SELinux 限制
                val real2 = java.io.File(installer.rootDir, "usr/bin/apt-get")
                if (real2.isFile) {
                    update = execScriptViaInterpreter(
                        real2, "-o", "DPkg::Lock::Timeout=60", "update",
                        "--allow-insecure-repositories", "-y"
                    )
                }
            }
            if (update != 0) {
                // 兜底3：精确探测 exec 失败原因（errno）
                emitLog("[apt] 探测: " + probeExecErrno(java.io.File(installer.rootDir, "bin/apt-get").absolutePath))
                // 兜底4：复制到全新 inode（新 SELinux 标签/去掉可能损坏的元数据）后执行
                val copy = java.io.File(installer.rootDir, "tmp/apt-get-copy")
                runCatching {
                    java.io.File(installer.rootDir, "usr/bin/apt-get").copyTo(copy, overwrite = true)
                    copy.setExecutable(true, false)
                }
                if (copy.isFile && copy.canExecute()) {
                    emitLog("[apt] 尝试复制副本执行...")
                    update = execOnce(
                        copy.absolutePath, "-o", "DPkg::Lock::Timeout=60", "update",
                        "--allow-insecure-repositories", "-y"
                    )
                }
            }
            if (update != 0) {
                // 兜底5：尝试 apt 原生二进制（apt-get 的兄弟命令）
                emitLog("[apt] 尝试 apt 命令...")
                update = execOnce(
                    "apt", "-o", "DPkg::Lock::Timeout=60", "update",
                    "--allow-insecure-repositories", "-y"
                )
            }
            if (update != 0) {
                // 决定性诊断：页大小 + 系统二进制副本测试（区分设备策略 vs bootstrap ELF 问题）
                emitLog("[apt] 内存页大小: " + pageSize() + " (16KB 页设备无法执行 4KB 对齐的旧版 Termux ELF)")
                emitLog("[apt] 系统二进制副本测试: " + probeSystemCopyExec())
            }
        }
        if (update != 0) {
            emitLog("[apt] 软件源更新失败，未继续安装；请切换软件源或检查网络后重试")
            emitLog(diagnoseCommand("apt-get"))
            // 链接失败（而非网络/权限）时，把缺失库清单直接摆在最后，避免被淹没在上面一长串兜底日志里
            reportLinkingFailure()
            return false
        }
        val missing = packages.filter {
            execOnce("apt-cache", "show", it) != 0
        }
        if (missing.isNotEmpty()) {
            emitLog("[apt] 软件源索引中找不到：${missing.joinToString()}；未继续安装，请切换软件源后重试")
            return false
        }
        return true
    }

    /**
     * 执行 Termux shell 命令，输出逐行回调（Termux 终端面板专用，不与 MC 日志混流）。
     * 命令通过 sh -c 执行（含 Termux 环境 PATH/LD_LIBRARY_PATH），阻塞至命令结束。
     *
     * 注意：内层解释器必须显式用 /system/bin/sh，不能依赖 PATH 解析 "sh"——
     * PATH 中的 sh 会命中 $PREFIX/bin/sh（Termux bash ELF），bash 依赖的共享库
     * （readline/ncurses 等）不在 LD_LIBRARY_PATH 内会导致启动失败（退出码 126）。
     * MC 服务器启动链（startMc）从不经过 Termux bash，因此能正常运行。
     */
    fun execTermux(command: String, onLine: (String) -> Unit): Int =
        executor.execWithOutput("/system/bin/sh", "-c", command, onLine = onLine)

    fun sendTermuxInput(input: String): Boolean = executor.sendInteractiveInput(input)

    /**
     * 修复 rootfs 命令可执行权限（幂等，毫秒级）。
     *
     * 背景：Termux bootstrap zip 解压时（extractZipToDir）只对 bin/、libexec/ 前缀及
     * 少量文件名设置 exec 位，usr/bin/ 下的真实脚本/二进制（pkg、apt 等）初始无 exec 位；
     * 依赖 bin/ 符号链接 chmod 跟随的链路在部分环境下失效，导致执行时报
     * "Permission denied"（退出码 126）。
     *
     * 修复：遍历 usr/bin、bin、libexec、lib/apt/methods 下所有文件直接 setExecutable。
     * 对 bin/ 下的符号链接，java.io.File.setExecutable 跟随链接修改目标文件权限。
     * 每次应用启动调用（幂等，已装环境也生效），不依赖重新初始化。
     *
     * @return 本次修复的文件数
     */
    fun ensureRootfsExecutable(): Int {
        val prefix = installer.rootDir.absolutePath
        if (!File(prefix).isDirectory) return 0
        val dirs = listOf("usr/bin", "bin", "libexec", "lib/apt/methods")
        var fixed = 0
        dirs.forEach { rel ->
            File(prefix, rel).listFiles()?.forEach { f ->
                if (f.isFile && !f.canExecute()) {
                    try {
                        if (f.setExecutable(true, false)) fixed++
                    } catch (e: Exception) {
                        Log.w(TAG, "ensureRootfsExecutable: chmod failed ${f.absolutePath}: ${e.message}")
                    }
                }
            }
        }
        if (fixed > 0) {
            Log.i(TAG, "ensureRootfsExecutable: fixed $fixed files")
            installer.onLog?.invoke("[bootstrap] 修复 $fixed 个命令可执行权限")
        }
        return fixed
    }

    /** 组合修复：命令可执行位 + 脚本路径（启动时幂等调用，每次启动全量自愈） */
    fun fixRootfsPermissions(): Int {
        // 顺序关键：先归位 compat（fixUsrBin）再重建 java wrapper（fixJavaSymlinks），
        // 否则 apt 装完 openjdk 后 wrapper 指向归位前的 jvm 路径 → libjli.so not found 崩溃；
        // ensureAptConfigs 放最后（归位可能动到 etc/，最后统一重建 apt 配置）
        val n = fixDpkgWrapper() + fixUsrBin() + ensureRootfsExecutable() +
            fixScriptsOnce() + fixAptSources() + ensureAptConfigs()
        try {
            fixJavaSymlinks()
        } catch (e: Exception) {
            Log.w(TAG, "fixRootfsPermissions: fixJavaSymlinks failed: ${e.message}")
        }
        return n
    }

    /** Repair commands unpacked by dpkg after the bootstrap startup pass. */
    fun repairInstalledCommands(): Int =
        fixUsrBin() + fixScriptsOnce() + ensureRootfsExecutable()

    /**
     * 升级已装环境的 dpkg 包装脚本到新版（幂等）。
     * 新版 wrapper 在解压后：归位 compat 链接 + 补 bin 链接 + --configure 时改写新装脚本路径。
     * 旧版（无 compat 归位逻辑）→ 用 ensureDpkgWrapper() 重写。
     */
    /**
     * 启动服务端前的幂等环境修复，只处理缺失或损坏的运行时文件。
     */
    fun autoRepairRuntime(javaVersion: JavaVersion, needsFonts: Boolean): Int {
        if (!isReady()) return 0
        val prefix = installer.rootDir
        if (javaVersion == JavaVersion.Java8) {
            val ready = java8UbuntuReady() || runBlocking { installJava8Ubuntu() }
            if (!ready) {
                emitLog("[repair] Java 8 Ubuntu ARM64 运行环境不可用")
                return 0
            }
            val fontsReady = !needsFonts || runUbuntu(
                "test -f /etc/fonts/fonts.conf && test -x /usr/bin/fc-cache",
                60_000
            ) == 0
            if (!fontsReady) {
                emitLog("[repair] 正在补全 Ubuntu 字体运行库...")
                val code = runUbuntu(
                    "export DEBIAN_FRONTEND=noninteractive; " +
                        "apt-get update -o Acquire::Retries=2 -o Acquire::http::Timeout=30 -o Acquire::https::Timeout=30 && " +
                        "apt-get install -y ca-certificates fontconfig fonts-dejavu-core && fc-cache -f",
                    300_000
                )
                if (code != 0) emitLog("[repair] Ubuntu 字体运行库修复失败，将继续尝试无图形模式启动")
            }
            return 0
        }
        listOf(
            File(prefix, "tmp"),
            File(prefix, "home"),
            File(prefix, "usr/bin"),
            File(prefix, "etc/fonts"),
            File(prefix, "var/lib/dpkg"),
            File(prefix, "var/cache/apt/archives")
        ).forEach { it.mkdirs() }

        var repaired = fixDpkgWrapper() + fixUsrBin() + ensureRootfsExecutable()
        repaired += fixScriptsOnce() + fixAptSources() + ensureAptConfigs()
        repaired += ensureJvmComplete(javaVersion)
        fixJavaSymlinks(javaVersion)

        if (needsFonts) {
            if (!fontRuntimeReady(javaVersion)) {
                emitLog("[repair] 正在补齐字体运行库...")
                if (!prepareAptPackages("fontconfig", "ttf-dejavu")) return repaired
                execOnce(
                    "apt-get", "-o", "DPkg::Lock::Timeout=60", "install",
                    "--allow-unauthenticated", "-y", "fontconfig", "ttf-dejavu"
                )
                repaired += fixUsrBin() + ensureRootfsExecutable()
            }
            if (repairFontRuntime()) repaired++
            else emitLog("[repair] 字体缓存生成失败，将继续使用无图形模式启动")
        }

        if (repaired > 0) emitLog("[repair] 自动修复完成，共处理 $repaired 项")
        return repaired
    }

    fun fixDpkgWrapper(): Int {
        val dpkg = File(installer.rootDir, "bin/dpkg")
        if (!dpkg.exists()) return 0
        val isV2 = try {
            dpkg.readText().contains("commands linked (fixUsrBin will relocate)")
        } catch (_: Exception) { false }
        return if (!isV2) {
            installer.ensureDpkgWrapper()
            Log.i(TAG, "fixDpkgWrapper: upgraded dpkg wrapper to v2")
            1
        } else 0
    }

    /**
     * 修复脚本 shebang 解释器路径 + 内容中硬编码的 Termux 绝对路径（幂等，单次遍历）。
     *
     * 背景：Termux 官方打包的脚本（pkg、apt-get、termux-* 等）shebang 与内容里都硬编码
     * /data/data/com.termux/files/usr/...（如 pkg 第 11 行调用 termux-setup-package-manager）。
     * 这些路径指向其他 app（com.termux）目录，MineServe 进程跨 app 执行被拒 →
     * "Permission denied"（退出码 126 / 1）。
     *
     * 修复：把 shebang（第一行）与内容中的 Termux 路径改写为自身 rootfs 路径 $PREFIX；
     * /usr/bin/env 形式兜底为自身 coreutils env。只处理以 #! 开头的文本脚本（跳过 ELF）。
     *
     * 性能：单次遍历 + 只读前 4KB 预检（已修复文件不命中即跳过全量 IO），
     * 替代此前 fixScriptShebangs + fixScriptPaths 两次遍历的重复读写。
     *
     * @return 本次修复的脚本数
     */
    fun fixScriptsOnce(): Int {
        val prefix = installer.rootDir.absolutePath
        if (!File(prefix).isDirectory) return 0
        val termuxUsr = "/data/data/com.termux/files/usr"
        var fixed = 0
        listOf(
            "usr/bin", "bin", "libexec", "lib/apt/methods",
            "usr/lib/apt/methods", "etc/profile.d", "etc/apt/apt.conf.d"
        ).forEach { rel ->
            File(prefix, rel).listFiles()?.forEach { f ->
                if (!f.isFile) return@forEach
                try {
                    val len = f.length()
                    val buf = ByteArray(minOf(len, 4096L).toInt())
                    java.io.RandomAccessFile(f, "r").use { raf -> raf.readFully(buf) }
                    val preview = String(buf, Charsets.UTF_8)
                    val head = preview.substringBefore("\n")
                    if (!head.startsWith("#!")) return@forEach
                    // shebang 改写
                    var newHead = head
                    if (head.contains(termuxUsr)) {
                        newHead = head
                            .replace("$termuxUsr/bin/", "$prefix/usr/bin/")
                            .replace("$termuxUsr/lib/", "$prefix/usr/lib/")
                            .replace(termuxUsr, prefix)
                    }
                    if (newHead.contains("/usr/bin/env ")) {
                        newHead = newHead.replace("#!/usr/bin/env ", "#!$prefix/usr/bin/env ")
                    }
                    // 内容预检：前 4KB 是否含 Termux 路径（未命中跳过全量 IO）
                    val needFull = preview.contains(termuxUsr)
                    if (newHead != head || needFull) {
                        val content = f.readText()
                        var newContent = content
                        if (needFull) newContent = content.replace(termuxUsr, prefix)
                        if (newHead != head) newContent = newContent.replaceFirst(head, newHead)
                        if (newContent != content) {
                            f.writeText(newContent)
                            f.setExecutable(true, false)
                            fixed++
                        }
                    }
                } catch (_: Exception) {}
            }
        }
        if (fixed > 0) {
            Log.i(TAG, "fixScriptsOnce: fixed $fixed scripts")
            installer.onLog?.invoke("[bootstrap] 修复 $fixed 个脚本路径")
        }
        return fixed
    }

    /**
     * 已装环境 apt 源 http → https（幂等）。
     * 安装时 sources.list 已用 https（postSetup 重写），此处兜底老环境：
     * 把 sources.list 中 deb http:// 全部替换为 deb https://。
     */
    fun fixAptSources(): Int {
        val prefix = installer.rootDir.absolutePath
        val f = File(prefix, "etc/apt/sources.list")
        if (!f.exists()) return 0
        return try {
            val content = f.readText()
            val newContent = content.replace("deb http://", "deb https://")
            if (newContent != content) {
                f.writeText(newContent)
                Log.i(TAG, "fixAptSources: http -> https")
                1
            } else 0
        } catch (e: Exception) {
            Log.w(TAG, "fixAptSources: ${e.message}")
            0
        }
    }

    /** Applies the selected domestic APT mirror immediately for later Java/dependency installs. */
    fun setAptMirror(mirror: AptMirror) {
        if (!installer.isReady()) return
        val effective = if (mirror == AptMirror.Official) AptMirror.Tuna else mirror
        File(installer.rootDir, "etc/apt/sources.list").apply {
            parentFile?.mkdirs()
            writeText("deb ${effective.url} stable main\n")
        }
        emitLog("[apt] 已切换至 ${effective.displayName}")
    }

    /**
     * 补全 usr/bin 缺失的解释器（幂等）。
     *
     * 背景：Termux bootstrap 解压后，bin/ 下脚本的 shebang 指向 $PREFIX/usr/bin/bash 等
     * 真实解释器；但部分环境下 usr/bin/ 真实文件缺失（usr/bin/pkg、usr/bin/bash 不存在），
     * 而真实文件落在 compat 路径 $PREFIX/data/data/com.termux/files/usr/bin/（Termux
     * 完整目录结构）——脚本 execve 解释器时 ENOENT → "No such file or directory"(126)。
     *
     * 修复：若 usr/bin 缺文件而 compat 路径有对应文件，复制补全（含 exec 位）。
     * 只补缺失文件，不覆盖已有内容。
     *
     * @return 本次补全的文件数
     */
    fun fixUsrBin(): Int {
        val prefix = installer.rootDir.absolutePath
        if (!File(prefix).isDirectory) return 0
        var fixed = 0
        val usrBin = File(prefix, "usr/bin")
        val binDir = File(prefix, "bin")
        val compatDir = File(prefix, "data/data/com.termux/files")
        val compatUsr = File(compatDir, "usr")
        val compatUsrBin = File(compatUsr, "bin")

        // 1. compat usr 若是真实目录（链接被 dpkg 覆盖/失效）→ 安全移动到 rootfs 对应位置
        //    再重建符号链接。用 rename（同分区元数据操作，毫秒级）而非复制。
        //    安全关键：必须用 NOFOLLOW_LINKS 判断目录（File.isDirectory/walkTopDown 会
        //    跟随符号链接，compat 若是链接会遍历整个 rootfs 并误删/误移文件——
        //    曾导致 etc/apt/apt.conf 等配置丢失、usr/bin 大量缺失）。
        val compatIsLink = try {
            java.nio.file.Files.isSymbolicLink(compatUsr.toPath())
        } catch (_: Exception) { false }
        if (compatUsr.exists() && !compatIsLink && isRealDir(compatUsr)) {
            Log.w(TAG, "fixUsrBin: compat usr is real dir, moving then relinking")
            // 归位前清理占用 jvm 的孤儿 MC 进程（服务器运行中杀后台/覆盖安装后，
            // 旧 java 进程仍 mmap jvm 文件，此时移动 jvm 会导致 libjli.so 缺失）
            killOrphanMcProcess(null)
            try {
                // 特殊处理 jvm：目标不存在**或损坏**（libjli.so/libjava.so 缺失）时，
                // 用 compat 的完整 jvm 整体原子替换（先删目标再 rename，绝不逐文件）；
                // 目标完整则跳过——避免反复移动 jvm 导致 libjli.so 缺失
                val compatJvm = File(compatUsr, "lib/jvm")
                val prefixJvm = File(prefix, "lib/jvm")
                if (isRealDir(compatJvm) && !jvmRootHasCompleteJava(prefixJvm)) {
                    prefixJvm.deleteRecursively()
                    prefixJvm.parentFile?.mkdirs()
                    runCatching { compatJvm.renameTo(prefixJvm) }
                        .onSuccess { Log.i(TAG, "fixUsrBin: jvm atomically replaced at $prefixJvm") }
                }
                // 只处理 compat 下的已知子目录，手动递归移动（不跟随链接）
                listOf("bin", "lib", "usr", "etc", "share", "include", "libexec", "opt", "var", "libexec").forEach { sub ->
                    val srcSub = File(compatUsr, sub)
                    if (isRealDir(srcSub)) {
                        moveTreeInto(srcSub, File(prefix, sub))
                    }
                }
                // 补可执行位（rename 不改变权限，dpkg-deb -x 解压文件权限来自默认）
                listOf("bin", "usr/bin", "libexec", "lib/apt/methods", "usr/lib/apt/methods").forEach { rel ->
                    File(prefix, rel).listFiles()?.forEach { it.setExecutable(true, false) }
                }
            } catch (e: Exception) {
                Log.w(TAG, "fixUsrBin: move compat tree failed: ${e.message}")
            }
            try {
                compatUsr.deleteRecursively()
                compatDir.mkdirs()
                android.system.Os.symlink(prefix, compatUsr.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "fixUsrBin: relink compat failed: ${e.message}")
            }
        }

        // 2+3. 同步 compat 缺失文件 + 补 bin 链接（不归位，命令前自愈可用）
        fixed += syncCompatAndLinks()

        if (fixed > 0) {
            installer.onLog?.invoke("[bootstrap] 补全 $fixed 个缺失命令/链接")
        }
        return fixed
    }

    /**
     * 轻量自愈（命令执行前调用）：从 compat 同步缺失命令 + 为 usr/bin 补 bin 链接。
     * **不触发归位**（不动 jvm/大目录）——避免服务器运行中移动 jvm 导致 libjli.so 缺失。
     */
    private fun syncCompatAndLinks(): Int {
        val prefix = installer.rootDir.absolutePath
        var fixed = 0
        val usrBin = File(prefix, "usr/bin")
        val binDir = File(prefix, "bin")
        val compatUsrBin = File(prefix, "data/data/com.termux/files/usr/bin")
        // 从 compat（链接 → usr/bin）同步缺失文件到 usr/bin（兜底，只补缺失不覆盖）
        usrBin.mkdirs()
        if (compatUsrBin.isDirectory) {
            compatUsrBin.listFiles()?.forEach { src ->
                val dst = File(usrBin, src.name)
                if (src.isFile && !dst.exists()) {
                    try {
                        src.copyTo(dst)
                        dst.setExecutable(true, false)
                        fixed++
                        Log.i(TAG, "syncCompatAndLinks: copied ${src.name} -> usr/bin/")
                    } catch (e: Exception) {
                        Log.w(TAG, "syncCompatAndLinks: copy ${src.name} failed: ${e.message}")
                    }
                }
            }
        }
        // 为 usr/bin 下每个命令补 bin/ 符号链接：dpkg-wrapper 跳过 configure，
        // postinst 未执行，apt 新装包（tree 等）没有 bin/ 链接 → PATH 找不到 → 127
        binDir.mkdirs()
        usrBin.listFiles()?.forEach { f ->
            if (!f.isFile) return@forEach
            val link = File(binDir, f.name)
            try {
                val noFollowExists = java.nio.file.Files.exists(
                    link.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS
                )
                if (noFollowExists) {
                    // 断链则删除重建
                    if (java.nio.file.Files.isSymbolicLink(link.toPath())) {
                        val targetOk = try {
                            val t = java.nio.file.Files.readSymbolicLink(link.toPath()).toString()
                            File(binDir, t).canonicalFile.exists()
                        } catch (_: Exception) { false }
                        if (!targetOk) {
                            link.delete()
                            android.system.Os.symlink("../usr/bin/${f.name}", link.absolutePath)
                            fixed++
                        }
                    }
                } else {
                    android.system.Os.symlink("../usr/bin/${f.name}", link.absolutePath)
                    fixed++
                    Log.i(TAG, "syncCompatAndLinks: created bin/${f.name} -> usr/bin/${f.name}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "syncCompatAndLinks: link bin/${f.name} failed: ${e.message}")
            }
        }
        return fixed
    }

    /** 用 NOFOLLOW_LINKS 判断是否为真实目录（不跟随符号链接，防遍历逃逸破坏 rootfs） */
    private fun isRealDir(f: File): Boolean = try {
        java.nio.file.Files.isDirectory(f.toPath(), java.nio.file.LinkOption.NOFOLLOW_LINKS)
    } catch (_: Exception) { false }

    /** 把 srcDir 的内容安全移动到 dstDir（递归合并，rename 同分区瞬时，不跟随符号链接） */
    private fun moveTreeInto(srcDir: File, dstDir: File) {
        dstDir.mkdirs()
        srcDir.listFiles()?.forEach { entry ->
            // 跳过符号链接条目：链接一般指向 rootfs 自身（如 etc/apt 等），
            // 移动链接并按 dst.deleteRecursively 处理会误删整个目标目录（apt.conf 丢失）
            val isLink = try {
                java.nio.file.Files.isSymbolicLink(entry.toPath())
            } catch (_: Exception) { false }
            if (isLink) return@forEach
            val dst = File(dstDir, entry.name)
            if (isRealDir(entry)) {
                // 目录：目标不存在时**整体 rename**（原子移动，防止 jvm 等几千文件
                // 逐文件移动中途中断导致部分归位 → libjli.so 缺失）；目标已存在才递归合并
                if (!dst.exists()) {
                    try {
                        if (entry.renameTo(dst)) return@forEach
                    } catch (_: Exception) {}
                }
                moveTreeInto(entry, dst)
            } else {
                // 文件：优先 rename 移动（同分区瞬时）；失败则复制兜底
                try {
                    if (dst.exists()) dst.deleteRecursively()
                    if (!entry.renameTo(dst)) {
                        entry.copyRecursively(dst, overwrite = true)
                        entry.deleteRecursively()
                    }
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * 重建缺失的 apt 关键配置（apt.conf / sources.list / dpkg status）。
     * 背景：fixUsrBin 误删曾导致 apt 报 "Unable to determine a suitable packaging system type"。
     * 幂等：仅当文件缺失或为空时重建。
     */
    fun ensureAptConfigs(): Int {
        val prefix = installer.rootDir.absolutePath
        var fixed = 0
        try {
            File(prefix, "etc/apt").mkdirs()
            File(prefix, "var/lib/dpkg").mkdirs()
            val aptConf = File(prefix, "etc/apt/apt.conf")
            if (!aptConf.exists() || aptConf.length() == 0L) {
                aptConf.writeText(buildString {
                    appendLine("Dir \"$prefix\";")
                    appendLine("Dir::Prefix \"$prefix\";")
                    appendLine("Dir::Etc \"$prefix/etc/apt\";")
                    appendLine("Dir::State \"$prefix/var\";")
                    appendLine("Dir::State::status \"$prefix/var/lib/dpkg/status\";")
                    appendLine("Dir::Cache \"$prefix/var/cache\";")
                    appendLine("Dir::Bin \"$prefix/bin\";")
                    appendLine("Dir::Bin::dpkg \"$prefix/bin/dpkg\";")
                    appendLine("DPkg \"$prefix/bin/dpkg\";")
                    appendLine("Acquire::AllowInsecureRepositories \"true\";")
                    appendLine("Acquire::https::Verify-Peer \"false\";")
                    appendLine("Acquire::https::Verify-Host \"false\";")
                    appendLine("APT::Get::AllowUnauthenticated \"true\";")
                    appendLine("APT::Get::Assume-Yes \"true\";")
                    appendLine("APT::Sandbox::User \"root\";")
                    appendLine("APT::Sandbox::Seccomp \"false\";")
                })
                fixed++
            }
            // Existing app versions may already have an apt.conf without the
            // non-interactive default. Add only the missing directive and
            // preserve all user/source settings.
            if (aptConf.isFile) {
                val content = aptConf.readText()
                if (!Regex("(?m)^\\s*APT::Get::Assume-Yes\\s+").containsMatchIn(content)) {
                    aptConf.appendText("\nAPT::Get::Assume-Yes \"true\";\n")
                    fixed++
                }
            }
            val sources = File(prefix, "etc/apt/sources.list")
            if (!sources.exists() || sources.length() == 0L) {
                sources.writeText("deb https://mirrors.tuna.tsinghua.edu.cn/termux/apt/termux-main stable main\n")
                fixed++
            }
            val status = File(prefix, "var/lib/dpkg/status")
            if (!status.exists()) { status.writeText(""); fixed++ }
        } catch (e: Exception) {
            Log.w(TAG, "ensureAptConfigs: ${e.message}")
        }
        if (fixed > 0) installer.onLog?.invoke("[bootstrap] 重建 $fixed 个 apt 配置")
        return fixed
    }

    /**
     * Termux 会话命令执行前的快速自愈（幂等，毫秒级）：
     * syncCompatAndLinks（补 bin 链接 + compat 同步）+ fixScriptsOnce（新装脚本路径）。
     * 覆盖 apt/pkg 新装包命令立即可用，无需重启应用/服务器。
     * 注意：**不触发归位**（不动 jvm/大目录），避免服务器运行中移动 jvm 导致 libjli.so 缺失。
     */
    fun refreshTermux(): Int = syncCompatAndLinks() + fixScriptsOnce()

    /** 判断指定 Java 版本目录是否完整（bin/java + libjli.so 或 libjava.so 存在）。 */
    private fun isJavaComplete(version: JavaVersion): Boolean {
        if (version == JavaVersion.Java8) return java8UbuntuReady()
        return javaCandidates(version).any { path ->
        val jvmDir = File(path)
        val binJava = File(jvmDir, "bin/java")
        val libJli = File(jvmDir, "lib/jli/libjli.so")
        val libJava = File(jvmDir, "lib/libjava.so")
        binJava.isFile && (libJli.isFile || libJava.isFile)
        }
    }

    /** 用于 compat 归位：任意一个已知 JDK 完整即可避免移动整个 jvm 目录。 */
    private fun jvmRootHasCompleteJava(jvmRoot: File): Boolean =
        JavaVersion.values().filter { it != JavaVersion.Java8 }.any { version ->
            File(jvmRoot, version.directoryName).let { jvmDir ->
                File(jvmDir, "bin/java").isFile &&
                    (File(jvmDir, "lib/jli/libjli.so").isFile || File(jvmDir, "lib/libjava.so").isFile)
            }
        }

    /**
     * 启动时校验已安装的 JDK：不完整（如杀后台/覆盖安装后 libjli.so 缺失）时，
     * 仅重装对应版本。未安装的 JDK 不会被隐式安装。
     */
    fun ensureJvmComplete(version: JavaVersion): Int {
        var repaired = 0
        if (!isJavaInstalled(version) || isJavaComplete(version)) return 0

        installer.onLog?.invoke("[bootstrap] ${version.displayName} 运行环境不完整，正在修复...")
        if (version == JavaVersion.Java8) {
            val repairedJava8 = runBlocking { installJava8Ubuntu() }
            if (repairedJava8) repaired++
            else installer.onLog?.invoke("[bootstrap] Java 8 Ubuntu ARM64 运行时自动修复失败")
            return repaired
        }
        if (!prepareAptPackages(version.packageName)) return 0
        val code = execOnce(
            "apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--reinstall",
            "--allow-unauthenticated", "-y", version.packageName
        )
        if (code == 0 && isJavaComplete(version)) {
            repaired++
            installer.onLog?.invoke("[bootstrap] ${version.displayName} 运行环境已修复")
        } else {
            installer.onLog?.invoke("[bootstrap] 警告: ${version.displayName} 运行环境修复失败")
        }
        fixJavaSymlinks()
        return repaired
    }

    /**
     * 诊断单个命令的执行环境（Termux 会话命令失败时输出，便于定位根因）。
     * 返回多行文本：环境就绪状态 + bin/usr/bin 下命令文件类型/exec位/shebang +
     * /data/data/com.termux（Termux app 数据目录）是否存在。
     */
    fun diagnoseCommand(cmd: String): String {
        val prefix = installer.rootDir.absolutePath
        val sb = StringBuilder()
        sb.append("[诊断] 环境就绪: ${installer.isReady()}")
        sb.append("\n  内存页大小: " + pageSize())
        val first = cmd.trim().split(Regex("\\s+")).firstOrNull { it.isNotEmpty() }
        if (first != null) {
            sb.append("\n  bin/$first: ${fileInfo(File(prefix, "bin/$first"))}")
            sb.append("\n  usr/bin/$first: ${fileInfo(File(prefix, "usr/bin/$first"))}")
        }
        // usr/bin 目录整体状态 + 关键解释器
        val usrBin = File(prefix, "usr/bin")
        val usrBinCount = usrBin.listFiles()?.size ?: -1
        sb.append("\n  usr/bin 文件数: ${if (usrBinCount < 0) "目录不存在" else usrBinCount}")
        listOf("bash", "sh", "perl", "python", "env", "apt-get").forEach { name ->
            val f = File(usrBin, name)
            if (f.exists()) {
                sb.append("\n  usr/bin/$name: ${fileInfo(f)}")
            } else {
                sb.append("\n  usr/bin/$name: 不存在")
            }
        }
        // compat 路径（Termux 结构真实落点）
        val compatUsrBin = File(prefix, "data/data/com.termux/files/usr/bin")
        val compatCount = compatUsrBin.listFiles()?.size ?: -1
        sb.append("\n  compat usr/bin 文件数: ${if (compatCount < 0) "不存在" else compatCount}")
        sb.append("\n  /data/data/com.termux: ${if (File("/data/data/com.termux").exists()) "存在" else "不存在"}")
        // SELinux 上下文探测（部分 ROM 对 app_data 执行限制/标签异常 → chmod 后仍 Permission denied）
        try {
            val lsZ = ProcessBuilder("/system/bin/ls", "-Z", File(prefix, "bin/apt-get").absolutePath, File(prefix, "usr/bin/apt-get").absolutePath)
                .redirectErrorStream(true).start()
            val zout = lsZ.inputStream.bufferedReader().use { it.readText() }.trim()
            if (lsZ.waitFor(3, TimeUnit.SECONDS) && zout.isNotBlank()) {
                sb.append("\n  SELinux: " + zout.replace("\n", "\n  SELinux: "))
            } else lsZ.destroyForcibly()
        } catch (_: Exception) {}
        return sb.toString()
    }

    private fun fileInfo(f: File): String {
        if (!f.exists()) return "不存在"
        val isLink = try { java.nio.file.Files.isSymbolicLink(f.toPath()) } catch (_: Exception) { false }
        val target = if (isLink) {
            " → " + (try { java.nio.file.Files.readSymbolicLink(f.toPath()).toString() } catch (_: Exception) { "?" })
        } else ""
        val exec = if (f.canExecute()) "可执行" else "无exec位"
        var shebang = ""
        // 跟随符号链接读取目标脚本首行（链接目标也是脚本时同样展示 shebang，便于定位解释器问题）
        if (f.isFile) {
            try {
                val head = f.bufferedReader().use { it.readLine() }?.take(120) ?: ""
                if (head.startsWith("#!")) shebang = " | shebang: $head"
            } catch (_: Exception) {}
        }
        val type = if (isLink) "符号链接" else if (f.isDirectory) "目录" else "文件"
        return "$type$target $exec$shebang"
    }

    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execStream(tag, *command, env = env)

    /**
     * 启动长驻进程但不启动 reader 线程（调用方自行读取 stdout）。
     * 用于 TunnelManager 等需要解析进程输出（如提取公网 URL）的场景，
     * 避免 execStream 的 reader 线程与调用方同时读取同一 InputStream 导致数据竞争。
     */
    fun execRaw(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execRaw(tag, *command, env = env)

    /**
     * 完整初始化流程：
     * 1. 下载 + 解压 Termux bootstrap rootfs
     * 2. apt-get install openjdk-25 wget curl（不再安装 tmux）
     * 3. 修复 openjdk 符号链接（dpkg-wrapper 跳过 configure 导致 post-install 未执行）
     *
     * 优化：环境已就绪时跳过依赖安装，避免后台重进应用时重复下载/安装。
     */
    suspend fun bootstrap(onProgress: (BootstrapInstaller.InstallPhase, Int) -> Unit): Boolean {
        // 记录调用前是否已就绪，用于判断是否需要重新安装依赖
        val wasReady = installer.isReady()
        val ok = installer.ensureInstalled(onProgress)
        if (!ok) return false

        // 环境之前已就绪（非首次安装），跳过依赖安装与符号链接修复
        if (wasReady) {
            installer.onLog?.invoke("[bootstrap] 环境已就绪，跳过依赖安装")
            onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
            return true
        }

        onProgress(BootstrapInstaller.InstallPhase.POST_SETUP, 92)
        installer.onLog?.invoke("[bootstrap] 安装依赖包（JDK-25/wget）...")
        // 关键时序：必须先修复 rootfs 可执行权限与脚本 shebang——apt-get 是 perl 脚本，
        // shebang 硬编码指向 /data/data/com.termux/... 解释器，不修复则 apt-get 无法执行
        // （退出码 126），JDK 永远装不完，bootstrap 陷入死循环。
        fixRootfsPermissions()
        if (!prepareAptPackages("wget", "curl")) return false
        executor.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", "wget", "curl")
        executor.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "clean")

        // 修复 openjdk 符号链接：dpkg-wrapper 的 configure 是 no-op，
        // post-install 脚本未执行，导致 $PREFIX/bin/java 符号链接未创建
        fixJavaSymlinks()

        onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
        return true
    }

    /**
     * 修复默认 OpenJDK 命令可用性（wrapper 脚本方案）。
     * Termux OpenJDK 实际安装在 $PREFIX/lib/jvm/java-xx-openjdk/，
     * 但 dpkg-wrapper 跳过了 configure，post-install 脚本未执行，
     * 需要手动在 $PREFIX/bin/ 下创建 java/javac/jar 等命令。
     *
     * 关键：不能直接 cp 复制 java 二进制到 $PREFIX/bin/，因为 java 依赖
     * libjli.so（在 jvm/lib/ 下），脱离原目录后动态链接器找不到该库。
     * 改用 wrapper 脚本：在脚本中设置 LD_LIBRARY_PATH 指向 jvm/lib/，
     * 然后 exec 原始 java 二进制，确保库依赖正确解析。
     *
     * 注意：由于 dpkg-deb -x 解压 deb 时 compat 符号链接被覆盖，
     * 文件实际落在了 $PREFIX/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/。
     * 所以需要从两个位置查找 java。默认 wrapper 选择已安装的最高版本。
     */
    fun fixJavaSymlinks(preferredVersion: JavaVersion? = null) {
        val prefix = installer.rootDir.absolutePath

        val selectedVersion = (preferredVersion?.takeIf(::isJavaComplete)
            ?: JavaVersion.values().sortedByDescending { it.ordinal }.firstOrNull(::isJavaComplete))
            ?: run {
                if (installedJavaVersions().isNotEmpty()) {
                    installer.onLog?.invoke("[bootstrap] 警告: 未找到完整的 OpenJDK 安装目录，跳过 wrapper 修复")
                }
                Log.w(TAG, "fixJavaSymlinks: no complete JDK found")
                return
            }
        if (selectedVersion == JavaVersion.Java8) {
            Log.i(TAG, "fixJavaSymlinks: Java 8 runs inside Ubuntu; no Termux wrapper needed")
            return
        }
        val jvmCandidates = javaCandidates(selectedVersion).map(::File)
        val jvmDir = jvmCandidates.firstOrNull { it.isDirectory }
        if (jvmDir == null) {
            installer.onLog?.invoke("[bootstrap] 警告: 未找到完整的 OpenJDK 安装目录，跳过 wrapper 修复")
            Log.w(TAG, "fixJavaSymlinks: jvmDir not found in candidates: ${jvmCandidates.map { it.absolutePath }}")
            return
        }
        Log.i(TAG, "fixJavaSymlinks: found jvmDir at $jvmDir")

        val jvmBinDir = File(jvmDir, "bin")
        if (!jvmBinDir.exists()) {
            installer.onLog?.invoke("[bootstrap] 警告: $jvmBinDir 不存在")
            return
        }
        val termuxBinDir = File(prefix, "bin").apply { mkdirs() }

        // 为 jvm/bin 下的命令创建 wrapper 脚本到 $PREFIX/bin/
        // 关键：不能用 cp 复制二进制！java 依赖 libjli.so（在 jvm/lib/ 下），
        // 复制后脱离原目录会导致动态链接器找不到 libjli.so。
        // 改用 wrapper 脚本：设置 LD_LIBRARY_PATH 后 exec 原始 java 二进制。
        val jvmLibDir = File(jvmDir, "lib")
        val compatUsrLib = "$prefix/data/data/com.termux/files/usr/lib"
        val libPathEntries = listOf(
            jvmLibDir.absolutePath,
            File(jvmLibDir, "server").absolutePath,
            File(jvmLibDir, "jli").absolutePath,
            compatUsrLib,
            "$prefix/lib",
            "$prefix/usr/lib",
            "/system/lib64"
        ).joinToString(":")
        val javaHome = jvmDir.absolutePath

        val commands = listOf("java", "javac", "jar", "jps", "keytool", "rmic", "rmiregistry")
        var created = 0
        val createdPaths = mutableListOf<String>()
        for (cmd in commands) {
            val target = File(jvmBinDir, cmd)
            val wrapper = File(termuxBinDir, cmd)
            if (!target.exists()) {
                Log.w(TAG, "fixJavaSymlinks: target not found: $target")
                continue
            }
            if (wrapper.exists()) {
                wrapper.delete()
            }
            try {
                val script = StringBuilder().apply {
                    append("#!/system/bin/sh\n")
                    append("export LD_LIBRARY_PATH='$libPathEntries'\n")
                    append("export JAVA_HOME='$javaHome'\n")
                    append("exec '$target' \"$@\"\n")
                }.toString()
                wrapper.writeText(script)
                createdPaths.add(wrapper.absolutePath)
                created++
            } catch (e: Exception) {
                installer.onLog?.invoke("[bootstrap] 警告: 创建 $cmd wrapper 异常: ${e.message}")
            }
        }
        // 一次性 chmod 所有 wrapper 脚本，避免逐个 fork-exec
        if (createdPaths.isNotEmpty()) {
            val chmodCmd = "chmod 755 ${createdPaths.joinToString(" ") { "'$it'" }}"
            val pb = ProcessBuilder("/system/bin/sh", "-c", chmodCmd)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            val exitCode = proc.waitFor()
            if (exitCode == 0) {
                Log.i(TAG, "fixJavaSymlinks: created $created wrappers (batch chmod)")
            } else {
                installer.onLog?.invoke("[bootstrap] 警告: batch chmod 失败: $out")
                Log.w(TAG, "fixJavaSymlinks: batch chmod failed: $out")
            }
        }
        installer.onLog?.invoke("[bootstrap] ${selectedVersion.displayName} wrapper 脚本已创建完成: $created 个命令已就绪")
        Log.i(TAG, "fixJavaSymlinks: created $created wrappers in $termuxBinDir")
    }

    /**
     * 清理占用指定服务器目录的孤儿 java/MC 进程（app 重启后旧 MC 进程成为孤儿，
     * 仍占着 world/session.lock → 新实例启动报 "already locked"）。
     * 只处理同 uid 的 java 进程且 cmdline 匹配该服务器目录或 MC 启动参数。
     */
    private fun killOrphanMcProcess(serverDir: File?) {
        try {
            val target = serverDir?.absolutePath
            File("/proc").listFiles()?.forEach { f ->
                val name = f.name
                if (name.isEmpty() || !name.all { it.isDigit() }) return@forEach
                val cmdline = try {
                    File(f, "cmdline").readText().replace('\u0000', ' ')
                } catch (_: Exception) { return@forEach }
                val isMc = cmdline.contains("java") && if (target != null) {
                    cmdline.contains(target)
                } else {
                    cmdline.contains(".jar") && cmdline.contains("nogui")
                }
                if (isMc) {
                    val pid = name.toIntOrNull() ?: return@forEach
                    Log.w(TAG, "killOrphanMcProcess: killing orphan pid=$pid (${cmdline.take(140)})")
                    emitLog("[startMc] 清理残留服务器进程 pid=$pid")
                    runCatching { android.os.Process.killProcess(pid) }
                    // 等待进程退出
                    runCatching {
                        val deadline = System.currentTimeMillis() + 3000
                        while (System.currentTimeMillis() < deadline) {
                            if (!File("/proc/$pid").exists()) break
                            Thread.sleep(100)
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "killOrphanMcProcess: ${e.message}")
        }
    }

    /** Start Java 8 in Ubuntu/glibc; the host server directory is exposed as /srv/mineserve. */
    private fun startMcInUbuntu(
        jarPath: String,
        maxHeapMb: Int,
        serverDir: File,
        onExit: (Int) -> Unit,
        launchArgs: String?,
        logFile: File,
        appendNogui: Boolean
    ): Process {
        if (!java8UbuntuReady()) {
            emitLog("[startMc] Java 8 Ubuntu ARM64 运行环境未安装或不完整")
            throw RuntimeException("Java 8 Ubuntu runtime is not ready")
        }
        val guestDir = "/srv/mineserve"
        val guestJar = "$guestDir/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
        val guestLaunchArgs = launchArgs?.replace(serverDir.absolutePath, guestDir)
        val isLegacyForgeLaunch = launchArgs?.trim()?.startsWith("-jar") == true &&
            launchArgs.contains("forge-")
        val launchesVerifiedLibraryJar = isLegacyForgeLaunch &&
            launchArgs?.contains("libraries/", ignoreCase = true) == true
        // 注意：不要用 libraries 里 find 到的第一个 jar 覆盖顶层启动 jar——
        // 1.16.5 的 libraries 第一个 jar 是无 Main-Class 的 -server.jar，
        // 而顶层 launcher（ServerMain）才是官方启动入口；launchArgs 指向的
        // jar 已在 App 侧验证过 Main-Class，这里直接使用即可。
        val forgeServerClasspath = if (launchesVerifiedLibraryJar) {
            "forge_jar=\"${'$'}(find libraries/net/minecraftforge/forge -type f -name 'forge-*.jar' -print -quit 2>/dev/null)\"; " +
                "if [ -z \"${'$'}forge_jar\" ]; then echo '[startMc] Java 8 Forge: launch jar not found in libraries'; exit 1; fi; " +
                // 1.12- 的 universal 无 Main-Class，需要 classpath + relauncher 模式；
                // 1.13-1.16 的 server jar 自带 Main-Class（FMLServerTweaker）与 Class-Path 清单，直接 -jar。
                "if jar tf \"${'$'}forge_jar\" 2>/dev/null | grep -q 'net/minecraftforge/fml/relauncher/ServerLaunchWrapper.class'; then " +
                "forge_classpath=\"${'$'}(find libraries -type f -name '*.jar' -printf '%p:' 2>/dev/null)${'$'}(find . -maxdepth 1 -type f -name 'minecraft_server.*.jar' -printf '%p:' 2>/dev/null)\"; " +
                "LAUNCH_LEGACY_CP=1; else LAUNCH_LEGACY_CP=0; fi; "
        } else ""
        val forgeLibraryRepair = ""
        val javaArguments = if (launchesVerifiedLibraryJar) {
            "if [ \"${'$'}LAUNCH_LEGACY_CP\" = 1 ]; then " +
                "-cp \"${'$'}forge_classpath\" net.minecraftforge.fml.relauncher.ServerLaunchWrapper; " +
                "else -jar \"${'$'}forge_jar\"; fi"
        } else {
            guestLaunchArgs ?: "-jar '$guestJar'"
        }
        // 注意：此处**有意不使用** heapTagPreloadEnv()。
        // proot 会把 rootfs 之外的路径映射掉，libheaptagfix.so 位于 App 的
        // nativeLibraryDir，在 guest 内没有对应的可访问路径。若强行设置
        // LD_PRELOAD，linker 找不到该库会直接导致 java 无法启动。
        // 该分支是 Java 8 + proot 场景，本就不依赖这项优化，故留空。
        val command = "export JAVA_HOME=$ubuntuJava8Home; " +
            "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"; " +
            "export TMPDIR=/tmp; export HOME=/root; export FONTCONFIG_PATH=/etc/fonts; " +
            "cd '$guestDir' && $forgeLibraryRepair$forgeServerClasspath exec /usr/bin/java " +
            "-Djava.io.tmpdir=/tmp " +
            baseJvmProperties() +
            "-Xmx${maxHeapMb}m $javaArguments" + if (appendNogui) " nogui" else ""
        val rootfs = java8Rootfs
        // PRoot creates glue files outside the guest rootfs. Keep this path in
        // the app-owned prefix; Termux's compatibility /usr/tmp may be absent
        // or inaccessible on newer Android storage namespaces.
        val prootTmp = File(installer.rootDir, "tmp/java8-proot").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        val sharedMemory = File(rootfs, "tmp").apply {
            mkdirs()
            setReadable(true, false)
            setWritable(true, false)
            setExecutable(true, false)
        }
        val resolver = prepareUbuntuDns(rootfs)
        val proot = listOf(
            File(installer.rootDir, "bin/proot"),
            File(installer.rootDir, "usr/bin/proot")
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: throw RuntimeException("proot is not available")
        val process = ProcessBuilder(
            "/system/bin/sh", "-c",
            "export PROOT_TMP_DIR='${prootTmp.absolutePath}'; " +
                "export TMPDIR='${prootTmp.absolutePath}'; exec " +
                listOf(
                    proot.absolutePath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=0:0",
            "--rootfs=${rootfs.absolutePath}", "--cwd=/root",
            "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
            "--bind=${sharedMemory.absolutePath}:/dev/shm", "--bind=${resolver.absolutePath}:/etc/resolv.conf",
            "--bind=${serverDir.absolutePath}:$guestDir",
            "/usr/bin/env", "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive",
            // Android 无 /etc/localtime，容器内同样需要显式给时区
            (systemTimeZoneId()?.let { "TZ=$it" } ?: "TZ=UTC"),
            "/bin/sh", "-lc", command
                ).joinToString(" ") { shellQuote(it) }
        ).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
            environment()["PROOT_TMP_DIR"] = prootTmp.absolutePath
            environment()["TMPDIR"] = prootTmp.absolutePath
        }.start()
        emitLog("[startMc] Java 8 PRoot 临时目录: ${prootTmp.absolutePath}")
        Log.i(TAG, "startMc Ubuntu Java 8 command: $command")
        assertLaunchCommandComplete(command, "startMcInUbuntu")
        if (isLegacyForgeLaunch) emitLog("[startMc] Java 8 Forge: validating launch jar from verified library")
        if (launchesVerifiedLibraryJar) {
            emitLog("[startMc] Java 8 Forge: using ServerLaunchWrapper classpath mode")
        }
        emitLog("[startMc] java 路径: Ubuntu:/usr/bin/java (openjdk-8-jdk)")
        emitLog("[startMc] 正在启动 Java 8 服务端...")
        trackMcProcess(process, serverDir)
        Thread({
            try {
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.w(TAG, "Ubuntu Java 8 stdout reader error: ${e.message}")
            }
        }, "mc-ubuntu-stdout-reader").start()
        Thread({
            val code = process.waitFor()
            Log.w(TAG, "Ubuntu Java 8 MC process exited code=$code")
            emitLog("[startMc] Java 8 服务端已退出 (exit=$code)")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-ubuntu-watch").start()
        return process
    }

    private fun shellQuote(value: String): String = "'${value.replace("'", "'\\''")}'"

    /**
     * 生成 `export LD_PRELOAD='...libheaptagfix.so'; ` 片段（不可用时返回空串）。
     *
     * ## 为什么需要这个
     *
     * Android 11+ 的 bionic 默认给堆分配打指针标签（TBI/MTE），指针高 8 位被
     * 用作元数据。JVM 内部会改写指针高位做标记，在带标签的堆上会导致：
     *   1. 释放时标签校验失败 → SIGABRT（退出码 134）
     *   2. 分配器无法复用被改写过的内存块 → 常驻内存显著升高
     *   3. 每个分配多出标签元数据，JVM 启动期海量小对象分配累计开销可观
     *
     * 第 2、3 条正是「同一个服务端、同一份 jar，内存却比别人高 100MB 左右」的原因。
     *
     * ## 为什么必须用 LD_PRELOAD
     *
     * `mallopt(M_BIONIC_SET_HEAP_TAGGING_LEVEL, 0)` 只对调用它的进程生效，
     * 且必须在任何 malloc 之前执行。服务端是独立进程，App 自己调用影响不到它。
     *
     * LD_PRELOAD 让 linker 在加载其他库之前先执行 [libheaptagfix.so] 的构造函数，
     * 正好赶在 JVM 所有分配之前。这样无需改动任何启动方式。
     *
     * 库缺失（降级环境）时返回空串 —— 功能不受影响，只是没有这项优化。
     */
    private fun heapTagPreloadEnv(): String {
        val lib = bundledLibDir?.let { File(it, HEAP_TAG_FIX_LIB) }
        if (lib == null || !lib.isFile) {
            // 只在首次缺失时提示一次，避免每条启动命令都刷日志
            if (heapTagFixWarned.compareAndSet(false, true)) {
                emitLog("[startMc] 提示: $HEAP_TAG_FIX_LIB 不可用，将不关闭堆指针标签（可能多占内存）")
            }
            return ""
        }
        return "export LD_PRELOAD='${lib.absolutePath}'; "
    }

    /**
     * 启动命令完整性自检：确认拼出来的 shell 命令里**真的有一个要执行的程序**。
     *
     * ## 为什么需要这个
     * 这些命令是十几段字符串用 `+` 拼起来的，只要**漏掉一个 `+`**，Kotlin 就会把
     * 后半截当成独立语句（以运算符结尾的行会继续到下一行，编译器不报错），
     * 且后续字符串因为「无副作用的纯表达式」被优化阶段直接丢弃。
     *
     * 真实故障（1.2.8 及更早）：`heapTagPreloadEnv()` 后漏了 `+`，
     * 导致 `cd '<serverDir>' && exec '<javaPath>' ... -jar server.jar nogui`
     * **整段消失**。最终命令只剩一串 `export` 赋值：
     *   - `sh -c` 执行完这些赋值后脚本自然结束 → 退出码 0
     *   - java 从未运行 → stdout 为空 → latest.log 0 字节、控制台无输出
     *   - 表现为「点启动秒退、无任何日志」，极难排查
     *
     * 编译期无法发现这类错误，所以改为**运行期断言**：判定逻辑见顶层纯函数
     * [launchCommandProblems]，不满足就直接抛错，并把完整命令打进日志 ——
     * 宁可启动失败且留下明确原因，也不要静默秒退。
     *
     * ## ⚠️ 这条断言只允许对「真的被截断」报警
     * 1.2.6 曾因为判定过严，把两种**合法**启动方式误判成"命令不完整"，
     * 用户侧表现为点启动就弹异常、服务端起不来（回退 1.2.5 即正常）：
     *   1. Forge 1.17+ / NeoForge 用 `@libraries/.../unix_args.txt` 启动（java argfile 语法），
     *      命令里既没有 `-jar` 也没有 `-cp`，但这是官方启动方式；
     *   2. 「完全自定义启动命令」模式由用户自己写命令（如 `java -jar server.jar`），
     *      不会有 `exec `。
     * 因此前者增加 `@参数文件` 标记放行，后者整组检查关闭。
     *
     * @param command 拼好的完整 shell 命令
     * @param tag 调用方标记，用于日志定位
     * @param requireExecPrefix 是否要求执行段带 `exec `（自定义命令模式传 false）
     * @param requireLauncherMarker 是否要求出现启动器特征（自定义命令模式传 false）
     */
    private fun assertLaunchCommandComplete(
        command: String,
        tag: String,
        requireExecPrefix: Boolean = true,
        requireLauncherMarker: Boolean = true
    ) {
        val problems = launchCommandProblems(command, requireExecPrefix, requireLauncherMarker)
        if (problems.isEmpty()) return

        val detail = problems.joinToString("；")
        // 完整命令一并落盘，便于直接对比「应该是什么」和「拼出来是什么」
        emitLog("[startMc] 启动命令不完整（$tag）：$detail")
        emitLog("[startMc] 实际命令: $command")
        Log.e(TAG, "launch command incomplete ($tag): $detail\ncommand=$command")
        throw RuntimeException("启动命令不完整（$tag）：$detail。请反馈此问题，日志中已记录完整命令。")
    }

    /**
     * 组装所有 Java 服务器共用的 JVM 属性。
     *
     * 其中包含一组「对 Android/bionic 更友好」的 OSHI/JNA 属性，用于缓解 OSHI 系模组
     * 因 `libc.so.6` 缺失而崩服的问题。该组属性原由「模组兼容模式」开关控制，现改为
     * **无条件注入**：Android 本就没有 udev/systemd，关掉这些探测只有好处，无需用户判断。
     */
    private fun baseJvmProperties(): String = buildString {
        append("-Djava.awt.headless=true ")
        // Android 没有 /etc/localtime，且未设 TZ 时 JVM 默认时区会回退成 UTC，
        // 导致服务端日志时间戳比本地时间慢 8 小时（东八区）。
        // 显式注入系统时区，JVM 与服务器派生的子命令都能拿到正确时区。
        systemTimeZoneId()?.let { append("-Duser.timezone=$it ") }
        append("-Dio.netty.transport.noNative=true ")
        append("-Dio.netty.transport.epoll.enabled=false ")
        append("-Dio.netty.transport.kqueue.enabled=false ")
        /*
         * 关键修复：把 JLine 的 JNA provider 从候选列表里摘掉。
         *
         * 崩溃实测栈（Forge 服务端日志）：
         *   Launcher.<clinit>
         *     → LogManager.getLogger
         *       → LoggerContext.start → PatternParser.createConverter   ← 还在解析 log4j 日志格式串
         *         → ForgeHighlight.newInstance
         *           → TerminalConsoleAppender.initializeTerminal        ← Forge/NeoForge 自带的控制台组件
         *             → TerminalBuilder.doBuild → checkProvider
         *               → JnaTerminalProvider.<init>
         *                 → LinuxNativePty.<clinit>
         *                   → com.sun.jna.Native.<clinit>               ← 这里炸
         *
         * 也就是说：崩溃发生在「日志系统配置格式」阶段，模组一个都还没加载。
         * 因此删模组、换 Java 版本都无效。
         *
         * JLine 的 TerminalBuilder.checkProvider() 会按系统属性
         * `org.jline.terminal.providers` 过滤可用 provider（逗号分隔的 provider 名称）。
         * 把 `jna` 排除后就不会实例化 JnaTerminalProvider，也就不会触碰
         * com.sun.jna.Native —— 从根上绕开这个必然失败的初始化。
         *
         * 保留 exec / jni / ffi：
         *   - exec 是纯外部命令实现（stty），Android 上没有 stty 时会自行降级；
         *   - jni / ffi 在缺库时 JLine 内部按 provider 名加载失败也只是跳过，
         *     不会抛到调用方（与 JNA 的 <clinit> 硬失败不同）。
         * 我们的服务端一律以 nogui 运行，终端探测失败是安全的。
         */
        append("-Dorg.jline.terminal.providers=exec,jni,ffi ")
        append(memoryFootprintArgs())
        /*
         * 这里的取值来自 OSHI 的 GlobalConfig 常量表，只使用真实存在的键。
         *
         * 历史遗留的两个参数已移除，它们都是无效的：
         *   -Doshi.util.use.jna=false   → OSHI 从未定义过该键，纯死参数
         *   -Djna.nosys=true            → 禁止 JNA 加载「系统库」。libc 在 Android 上
         *                                 就是系统库 libc.so，被禁后 JNA 只能退回按
         *                                 Linux 习惯找 libc.so.6 → 必然失败。
         *                                 这条很可能是崩溃的助推因素，故一并去掉。
         */
        // 不加载 udev：Android 无 systemd/udev，探测只会白白触发原生库加载
        append("-Doshi.os.linux.allowudev=false ")
        // 不探测 systemd 会话：Android 没有
        append("-Doshi.os.linux.allowsystemd=false ")
        // 不做 NFS 可达性探测：默认开启且会发起 TCP:2049 连接、单次最长阻塞 2 秒
        append("-Doshi.os.linux.filesystem.checknfs=false ")
        // 不记录 /proc 读取警告：Android 上大量 /proc 节点受限，告警会刷屏
        append("-Doshi.os.linux.procfs.logwarning=false ")
        // 关掉 Memoizer 缓存：避免 OSHI 缓存住首次探测失败的结果后反复重试
        append("-Doshi.util.memoizer.expiration=0 ")
        /*
         * 历史遗留参数已移除（保留说明以免再次被加回来）：
         *
         *   -Doshi.util.use.jna=false
         *       OSHI 从未定义过该键，纯死参数。
         *
         *   -Djna.nosys=true
         *       禁止 JNA 加载「系统库」。libc 在 Android 上就是系统库 libc.so，
         *       被禁后 JNA 只能退回按 Linux 习惯找 libc.so.6 → 必然失败。
         *       这条是崩溃的助推因素，已去掉。
         *
         *   -Djna.nounpack=true   ← 本次移除
         *       禁止 JNA 从自己的 jar 里解包 libjnidispatch.so。但 jar 解包是
         *       JNA 三层加载顺序里唯一「还可能成功」的一层：
         *         1) jna.boot.library.path 目录
         *         2) 系统库路径（受 jna.nosys 控制）
         *         3) 从 jar 解包（受 jna.nounpack 控制）
         *       关掉它并不会让 JNA 变得可用，只会让失败点后移、报错更难定位。
         *       实测崩溃栈里的 /data/data/com.venti1112.edgecube/cache/jna*.tmp
         *       就是第 3 步解包出来的文件——这个文件至少能被 dlopen 尝试打开，
         *       关掉之后就只剩「找不到库」这种更含糊的报错。
         */
    }

    /**
     * 服务端运行期的临时目录（`<服务器目录>/.mineserve/tmp`）。
     *
     * 临时文件随服务器目录一起清理，不会污染 Termux 的公共 tmp。
     */
    private fun serverTmpDirFor(serverDir: File): File =
        File(serverDir, ".mineserve/tmp")

    /**
     * 降低常驻内存的 `-XX` 参数。
     *
     * 背景：原先一个 `-XX` 参数都没有，全部走 JVM 默认值。而移动端 JVM 的默认值
     * 是按「服务器/桌面」场景设计的，对手机来说普遍偏大。逐项说明：
     *
     * - `+UseSerialGC`：默认 GC 在 Android 上通常是 G1 或 Parallel。G1 会额外维护
     *   并发标记线程、Remembered Set（卡表）、以及约 10% 堆大小的保留区。手机端
     *   服务端通常玩家少、堆不大，Serial GC 单线程回收开销极小且无这些额外结构，
     *   实际表现往往更快也更省内存。
     *
     * - `MaxMetaspaceSize=256m`：类元数据区，默认**无上限**。每个模组的类加载器
     *   都会往这里放东西，不设上限时会只增不减。
     *
     * - `ReservedCodeCacheSize=128m`：JIT 编译后的机器码缓存，默认约 240MB。
     *   手机端服务端长期运行的热点方法有限，砍到一半通常无感知。
     *
     * - `Xss768k`：每线程栈，arm64 默认 1MB。服务端网络/区块/IO 线程数量不少，
     *   每个省 256KB，几十个线程即可省下十几 MB。
     *   ⚠️ 不设得更小（如 512k）是因为部分模组递归较深，栈过小会 StackOverflow。
     *
     * 这些参数都是「只减不增」的安全项：不改变服务端行为，只压缩 JVM 的预留与开销。
     */
    private fun memoryFootprintArgs(): String = buildString {
        append("-XX:+UseSerialGC ")
        append("-XX:MaxMetaspaceSize=256m ")
        append("-XX:ReservedCodeCacheSize=128m ")
        append("-Xss768k ")
    }

    /**
     * 当前设备时区（IANA 格式，如 `Asia/Shanghai`）；取不到时返回 null。
     *
     * Android 没有 /etc/localtime，JVM 无从推断时区，必须由 App 侧显式告知。
     */
    private fun systemTimeZoneId(): String? =
        runCatching { java.util.TimeZone.getDefault().id }
            .getOrNull()
            ?.takeIf { it.isNotBlank() && it != "GMT" }

    /**
     * 直接用 ProcessBuilder 启动 MC 服务（不再依赖 tmux）。
     * stdout/stderr 实时推送到 consoleFlow，同时写入日志文件。
     * onExit 回调在 MC 进程退出时触发。
     */
    fun startMc(
        jarPath: String,
        maxHeapMb: Int,
        dirName: String,
        javaVersion: JavaVersion = JavaVersion.Java17,
        onExit: (Int) -> Unit,
        launchArgs: String? = null,
        appendNogui: Boolean = true
    ): Process {
        Log.i(TAG, "startMc: jar=$jarPath heap=${maxHeapMb}m dirName=$dirName")

        // 如果已有进程在运行，先停止
        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = serverDirFor(dirName)
        val logFile = prepareMcLogFile(serverDir)

        // 清理占用该服务器目录的孤儿 java 进程（app 重启后旧 MC 进程成孤儿，
        // 占着 world/session.lock → 新实例启动报 already locked）
        killOrphanMcProcess(serverDir)

        if (javaVersion == JavaVersion.Java8) {
            return startMcInUbuntu(jarPath, maxHeapMb, serverDir, onExit, launchArgs, logFile, appendNogui)
        }

        // Java 17/25 continue to use the existing Termux-hosted launch path.
        val javaPath = resolveJavaPath(prefix, javaVersion) ?: run {
            emitLog("[startMc] 错误: java 未找到，openjdk 可能未安装。请删除 Termux 环境后重新初始化")
            throw RuntimeException("java not found in Termux environment")
        }
        Log.i(TAG, "startMc: resolved java path = $javaPath")
        emitLog("[startMc] java 路径: $javaPath")

        // 用 /system/bin/sh -c 启动 java，设置环境变量
        // 设置 PATH 包含 jvm 的 bin 目录，确保 java 能找到其依赖（如 jlink）
        val jvmBinDir = File(javaPath).parentFile?.absolutePath ?: "$prefix/bin"
        // compat 路径：dpkg-deb -x 解包时 compat 符号链接被覆盖，文件实际落在此处
        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        // jvmLibDir：从 javaPath 推导其父目录的 lib 子目录（javaPath = .../bin/java → 父=bin → 父=jvm目录 → lib）
        val jvmLibDir = File(javaPath).parentFile?.parentFile?.let { File(it, "lib") }?.absolutePath
            ?: "$prefix/lib/jvm/java-25-openjdk/lib"
        // 兜底：将每个受支持版本及 compat 路径加入库搜索路径。
        // 注意：这里**不再**把所有 Java 版本的 lib 目录都塞进 LD_LIBRARY_PATH。
        // 原因：LD_LIBRARY_PATH 越长，linker 在每次 dlopen 时就要按序查找越多目录，
        // 更重要的是可能命中「同名但属于另一个 JRE」的库，导致两份 JRE 原生库同时驻留，
        // 白白多吃几十 MB 常驻内存。当前进程只会用一个 JRE，因此只保留它的路径。
        val nativeAccessArg = if (javaVersion == JavaVersion.Java25) "--enable-native-access=ALL-UNNAMED " else ""
        // JLine/JNA 会把原生库解包到 java.io.tmpdir；显式指向自己的目录，
        // 避免落到上一个宿主 App 的 cache（实测出现过 edgecube 的路径）。
        val jvmTmpDir = serverTmpDirFor(serverDir).apply { mkdirs() }
        val javaCmd = "export PATH='$jvmBinDir:$prefix/bin:$prefix/usr/bin:$compatUsr/bin:$prefix/bin/applets:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$prefix/lib:$compatUsr/lib:$prefix/usr/lib:$jvmLibDir:$jvmLibDir/server:/system/lib64'; " +
            heapTagPreloadEnv() +
            "export FONTCONFIG_PATH='$prefix/etc/fonts'; " +
            "export FONTCONFIG_FILE='$prefix/etc/fonts/fonts.conf'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$jvmTmpDir'; " +
            "export JAVA_HOME='${File(javaPath).parentFile?.parent}'; " +
            // Android 无 /etc/localtime，不设 TZ 时 JVM 与派生命令都会回退 UTC
            (systemTimeZoneId()?.let { "export TZ='$it'; " } ?: "") +
            "cd '$serverDir' && " +
            "exec '$javaPath' $nativeAccessArg-Djava.io.tmpdir='$jvmTmpDir' -Djna.tmpdir='$jvmTmpDir' " +
            baseJvmProperties() +
            "-Xmx${maxHeapMb}m " + (launchArgs ?: "-jar $jarPath") + if (appendNogui) " nogui" else ""

        Log.i(TAG, "startMc command: $javaCmd")
        assertLaunchCommandComplete(javaCmd, "startMc")

        val pb = ProcessBuilder("/system/bin/sh", "-c", javaCmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            // 环境变量
            environment().putAll(executor.termuxEnv())
        }
        val process = pb.start()
        trackMcProcess(process, serverDir)

        // 后台线程读取 stdout，推送到 consoleFlow 并写入日志文件
        Thread({
            try {
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    // 批量 flush：每 50 行或退出时写入磁盘，避免每行一次 IO
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.w(TAG, "mc stdout reader error: ${e.message}")
            }
        }, "mc-stdout-reader").start()

        // 后台线程等待进程退出
        Thread({
            val code = process.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-watch").start()

        return process
    }

    /**
     * 用 PocketMine 专用 PHP 运行 PocketMine-MP（PHP 为 Android 原生构建，
     * 含 PM5 全部必需扩展；php.ini 通过 PHPRC 环境变量显式指定）。
     * 进程管理与日志处理与 [startMc] 一致。
     */
    fun startPhp(
        phpBinaryPath: String,
        phpIniPath: String,
        pharPath: String,
        dirName: String,
        onExit: (Int) -> Unit
    ): Process {
        Log.i(TAG, "startPhp: php=$phpBinaryPath phar=$pharPath dirName=$dirName")
        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = serverDirFor(dirName)
        val logFile = prepareMcLogFile(serverDir)
        killOrphanMcProcess(serverDir)

        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        val phpCmd = "export PATH='$prefix/bin:$prefix/usr/bin:$compatUsr/bin:/system/bin:/system/xbin'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; " +
            "cd '$serverDir' && exec '$phpBinaryPath' '${File(pharPath).name}' --no-wizard"

        Log.i(TAG, "startPhp command: $phpCmd")
        val pb = ProcessBuilder("/system/bin/sh", "-c", phpCmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
            environment()["PHPRC"] = phpIniPath
        }
        val process = pb.start()
        trackMcProcess(process, serverDir)

        Thread({
            try {
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.w(TAG, "mc stdout reader error: ${e.message}")
            }
        }, "mc-stdout-reader").start()

        Thread({
            val code = process.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-watch").start()
        return process
    }

    /**
     * 使用完全自定义命令启动 MC 服务端（高级选项：完全自定义启动命令模式）。
     * 直接执行用户提供的完整 shell 命令，不进行任何自动拼接。
     */
    fun startMcCustom(
        command: String,
        dirName: String,
        onExit: (Int) -> Unit
    ): Process {
        Log.i(TAG, "startMcCustom: command=$command dirName=$dirName")

        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = serverDirFor(dirName)
        val logFile = prepareMcLogFile(serverDir)

        killOrphanMcProcess(serverDir)

        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        // 同 startMc：只保留用得到的库路径，不把所有 Java 版本都塞进去（见该处注释）。
        val fullCmd = "export PATH='$prefix/bin:$prefix/usr/bin:$compatUsr/bin:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$prefix/lib:$compatUsr/lib:$prefix/usr/lib:/system/lib64'; " +
            heapTagPreloadEnv() +
            "export FONTCONFIG_PATH='$prefix/etc/fonts'; " +
            "export FONTCONFIG_FILE='$prefix/etc/fonts/fonts.conf'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; " +
            "cd '$serverDir' && $command"

        Log.i(TAG, "startMcCustom full command: $fullCmd")
        // 自定义命令由用户自己写（如 `java -jar server.jar`），既不会有 `exec `，
        // 也不保证出现 `-jar`/`-cp` 标记 —— 只保留「执行段存在且非空」这一项检查，
        // 用来兜住「字符串拼接漏了 `+` 导致 `cd … && $command` 整段消失」。
        assertLaunchCommandComplete(
            fullCmd,
            "startMcCustom",
            requireExecPrefix = false,
            requireLauncherMarker = false
        )
        emitLog("[startMc] 自定义启动命令: $command")

        val pb = ProcessBuilder("/system/bin/sh", "-c", fullCmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
        }
        val process = pb.start()
        trackMcProcess(process, serverDir)

        Thread({
            try {
                val writer = java.io.BufferedWriter(java.io.OutputStreamWriter(
                    java.io.FileOutputStream(logFile, true), Charsets.UTF_8), 8192)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                var lineCount = 0
                while (line != null) {
                    executor.emit(line)
                    writer.appendLine(line)
                    if (++lineCount % 50 == 0) writer.flush()
                    line = reader.readLine()
                }
                writer.flush()
                writer.close()
            } catch (e: Exception) {
                Log.w(TAG, "mc stdout reader error: ${e.message}")
            }
        }, "mc-stdout-reader").start()

        Thread({
            val code = process.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            if (clearMcProcessIfCurrent(process)) onExit(code)
        }, "mc-watch").start()

        return process
    }

    /**
     * 最近一次「用户主动停止」请求的时间戳（0 = 从未请求过）。
     *
     * ## 为什么放在这里而不是 McServerController
     * 主动停止的入口不止一个：通知栏停止按钮、删除运行环境、备份前暂停……
     * 它们最终都汇聚到 [stopMc]。把标记打在**汇聚点**，就不会漏掉任何入口。
     *
     * 消费方是 `McServerController.createExitHandler`：它原本只凭
     * "退出发生在 20s 启动窗口内"就判异常退出，导致**用户在启动后 20 秒内
     * 点停止**时会误弹崩溃报告（报告里还会带上启动期间遗留的 JNA 报错日志，
     * 让人误以为是停止操作把服务端搞崩了）。
     */
    @Volatile
    var lastUserStopRequestAtMs: Long = 0L
        private set

    /** 标记"用户主动请求停止"，供崩溃判定区分「主动停」与「真崩溃」。 */
    fun markUserStopRequested() {
        lastUserStopRequestAtMs = System.currentTimeMillis()
    }

    /** 新一次启动时清掉标记，否则本轮真实崩溃会被误判成正常停止而漏报。 */
    fun clearUserStopRequest() {
        lastUserStopRequestAtMs = 0L
    }

    /** 停止 MC：向 stdin 发送 stop 命令，等待最多 5 秒后强制 destroy */
    suspend fun stopMc(): Boolean = withContext(Dispatchers.IO) {
        // 只要走到这里就一定是"主动停止"，先把标记打上，
        // 再发停止命令 —— 否则进程可能在标记写入前就退出了。
        markUserStopRequested()
        val proc = mcProcess ?: return@withContext true
        val serverDir = mcServerDir
        if (!proc.isAlive) {
            // The shell/PRoot wrapper may have exited while its Java child survived.
            killOrphanMcProcess(serverDir)
            clearMcProcessIfCurrent(proc)
            return@withContext true
        }
        try {
            mcStdin?.write("stop\n".toByteArray())
            mcStdin?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "stopMc: stdin write failed: ${e.message}")
        }
        // 轮询等待进程退出，最多 5 秒（不阻塞主线程）
        val deadline = System.currentTimeMillis() + 5000
        while (proc.isAlive && System.currentTimeMillis() < deadline) {
            delay(100)
        }
        if (proc.isAlive) {
            Log.w(TAG, "stopMc: process still alive, destroying")
            proc.destroyForcibly()
            withTimeoutOrNull(3000) { while (proc.isAlive) delay(50) }
        }
        // PRoot can leave its Java child alive after its outer process exits.
        // Clear it before returning so a subsequent start never doubles servers.
        killOrphanMcProcess(serverDir)
        clearMcProcessIfCurrent(proc)
        true
    }

    private fun trackMcProcess(process: Process, serverDir: File) {
        synchronized(mcProcessLock) {
            mcProcess = process
            mcPid = processPid(process) ?: findMcChildPid()
            mcServerPid = null
            mcServerDir = serverDir
            mcStdin = process.outputStream
        }
    }

    /** A late watcher from an older start must not clear a newer server process. */
    private fun clearMcProcessIfCurrent(process: Process): Boolean = synchronized(mcProcessLock) {
        if (mcProcess !== process) return@synchronized false
        mcProcess = null
        mcPid = null
        mcServerPid = null
        mcServerDir = null
        mcStdin = null
        true
    }

    /** 向 MC 控制台发指令（写入 stdin） */
    fun sendCommand(line: String) {
        val cmd = if (line.startsWith("/")) line.substring(1) else line
        try {
            mcStdin?.write("$cmd\n".toByteArray())
            mcStdin?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "sendCommand failed: ${e.message}")
        }
    }

    /** 检查 MC 进程是否存活 */
    fun isMcRunning(): Boolean {
        return mcProcess?.isAlive ?: false
    }

    /**
     * 读取 MC 进程当前真实内存占用（RSS，单位 MB）。
     * Android 的 java.lang.Process 无公开 pid()，改为遍历 /proc 查找
     * Java 子进程，读取其 stat 中 rss 字段（剥离 comm 后 0-based 索引 21）。
     * 常规权限即可读取（app 自有子进程），兼容性好。
     * @return MB 值；服务进程未创建或读取失败时返回 0
     */
    fun mcProcessMemoryMb(): Long {
        return try {
            // 启动器/安装器也可能是 Java 进程，不能在 MC 尚未创建时全局兜底扫描。
            if (mcProcess?.isAlive != true) return 0L
            processStat(mcProcess)?.let { stat ->
                val rssKb = procVmRssKb(statPid(stat))
                if (rssKb > 0L) return rssKb / 1024
                // /proc/<pid>/status 不可读时回退到 RSS 页数；页大小用系统值而非固定 4096。
                val rssPages = stat.getOrNull(21)?.toLongOrNull() ?: 0L
                if (rssPages > 0) {
                    val pageBytes = systemPageBytes()
                    return rssPages * pageBytes / (1024 * 1024)
                }
            }
            0L
        } catch (e: Exception) {
            0L
        }
    }

    /** 进程 CPU 使用率（%）：读取 MC Java 进程自身 utime+stime 增量，按可用核心数归一化到 0..100。
     *  服务器未运行或基线不可用返回 null。 */
    fun mcProcessCpuPercent(): Int? {
        val running = mcProcess?.isAlive == true
        if (!running) {
            // 进程已停止：把基线一并清空，避免下次启动复用陈旧基线算出离谱数字。
            cpuBaselinePid = null
            cpuBaselineAtElapsedMs = 0L
            cpuBaselineJiffies = 0L
            return null
        }
        return try {
            val stat = processStat(mcProcess) ?: return null
            val pid = statPid(stat)
            val jiffies = (stat.getOrNull(11)?.toLongOrNull() ?: 0L) +
                (stat.getOrNull(12)?.toLongOrNull() ?: 0L)
            val now = android.os.SystemClock.elapsedRealtime()
            val baselinePid = cpuBaselinePid
            /*
             * 判定是否需要重新建立基线。
             *
             * 注意这里**不能**用 `cpuBaselineJiffies <= 0L` 作为「基线缺失」的条件：
             * 进程刚启动时 utime+stime 真的可能是 0，把 0 当成「没基线」会让
             * 每次采样都重置基线、永远算不出读数（实测症状：CPU 一直显示 0%）。
             * 改为用 cpuBaselineAtElapsedMs 判断「是否曾建立过基线」。
             */
            if (baselinePid != pid || cpuBaselineAtElapsedMs <= 0L) {
                cpuBaselinePid = pid
                cpuBaselineJiffies = jiffies
                cpuBaselineAtElapsedMs = now
                return null
            }
            val deltaJiffies = jiffies - cpuBaselineJiffies
            val deltaMs = now - cpuBaselineAtElapsedMs
            val percent = McProcessCpuMath.percent(
                jiffiesNow = jiffies,
                jiffiesPrev = cpuBaselineJiffies,
                elapsedMs = deltaMs,
                tickHertz = clockTicksPerSecond(),
                cores = availableProcessorCount()
            )
            // 计数器回退或采样间隔无效时保留旧基线，等下一窗口恢复。
            if (percent == null && deltaJiffies >= 0L && deltaMs > 0L) {
                cpuBaselinePid = pid
                cpuBaselineJiffies = jiffies
                cpuBaselineAtElapsedMs = now
            }
            if (percent != null) {
                cpuBaselinePid = pid
                cpuBaselineJiffies = jiffies
                cpuBaselineAtElapsedMs = now
            }
            percent
        } catch (e: Exception) {
            null
        }
    }

    private var cachedCoreCount: Int = 0

    /** 进程可用的逻辑核心数（cpuset 限制内），至少为 1。 */
    private fun availableProcessorCount(): Int {
        if (cachedCoreCount <= 0) {
            cachedCoreCount = runCatching {
                Runtime.getRuntime().availableProcessors()
            }.getOrDefault(1).coerceAtLeast(1)
        }
        return cachedCoreCount
    }

    private fun statPid(stat: List<String>): Int =
        stat.getOrNull(0)?.toIntOrNull() ?: 0

    /** /proc/<pid>/status 的 VmRSS（kB），避免 16K 页设备上页数×4096 算错内存。 */
    private fun procVmRssKb(pid: Int): Long = runCatching {
        if (pid <= 0) return@runCatching 0L
        File("/proc/$pid/status").takeIf { it.isFile }?.readText()?.lineSequence()?.firstNotNullOfOrNull { line ->
            line.trim().takeIf { it.startsWith("VmRSS:") }?.substringAfter("VmRSS:")
                ?.substringBefore(" kB")?.trim()?.toLongOrNull()
        } ?: 0L
    }.getOrDefault(0L)

    /** 系统页大小（字节），默认按常见 4096 兜底。 */
    private fun systemPageBytes(): Long = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_PAGESIZE)
    }.getOrDefault(4096L)

    /** 系统时钟频率（HZ），进程 stat 的 utime/stime 以其为计数单位。 */
    private fun clockTicksPerSecond(): Long = runCatching {
        android.system.Os.sysconf(android.system.OsConstants._SC_CLK_TCK)
    }.getOrDefault(100L)

    /** Read the launched Java process, which may be several layers below shell/proot. */
    private fun processStat(process: Process?): List<String>? {
        if (process?.isAlive != true) return null
        return try {
            val knownServerPid = mcServerPid
            if (knownServerPid != null && File("/proc/$knownServerPid/stat").isFile) {
                readProcStat(knownServerPid)
            } else {
                val rootPid = mcPid ?: processPid(process)?.also { mcPid = it }
                    ?: findMcChildPid()?.also { mcPid = it }
                if (rootPid == null) {
                    null
                } else {
                    // Only measure the Java server descendant. The shell/PRoot wrapper
                    // is not the server and must not be reported as server memory.
                    val pid = findServerPid(rootPid)
                    if (pid == null) null else {
                        mcServerPid = pid
                        readProcStat(pid)
                    }
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readProcStat(pid: Int): List<String>? {
        val stat = File("/proc/$pid/stat").readText()
        val end = stat.lastIndexOf(')')
        return if (end < 0) null else stat.substring(end + 1).trim().split(Regex("\\s+"))
    }

    /** MC 进程当前 PID（已缓存或现场解析），供 VM/UI 显示采样状态。 */
    fun currentMcPid(): Int? = runCatching {
        val stat = processStat(mcProcess) ?: return@runCatching null
        stat.getOrNull(0)?.toIntOrNull()
    }.getOrNull()

    private data class ProcEntry(val pid: Int, val parentPid: Int, val comm: String, val cmdline: String)

    private fun processPid(process: Process): Int? = runCatching {
        (process.javaClass.getMethod("pid").invoke(process) as? Long)?.toInt()
    }.getOrNull()

    /** Finds the Java descendant instead of measuring the shell or proot wrapper. */
    private fun findServerPid(rootPid: Int): Int? = runCatching {
        val entries = File("/proc").listFiles().orEmpty().mapNotNull { dir ->
            if (!dir.name.all(Char::isDigit)) return@mapNotNull null
            val raw = File(dir, "stat").takeIf { it.isFile }?.readText() ?: return@mapNotNull null
            val open = raw.indexOf('(')
            val close = raw.lastIndexOf(')')
            if (open < 0 || close <= open) return@mapNotNull null
            val fields = raw.substring(close + 1).trim().split(Regex("\\s+"))
            val pid = dir.name.toIntOrNull() ?: return@mapNotNull null
            val parent = fields.getOrNull(1)?.toIntOrNull() ?: return@mapNotNull null
            ProcEntry(pid, parent, raw.substring(open + 1, close), File(dir, "cmdline").readText())
        }
        val byPid = entries.associateBy { it.pid }
        fun isDescendant(pid: Int): Boolean {
            var current = byPid[pid]?.parentPid
            repeat(32) {
                if (current == rootPid) return true
                current = byPid[current]?.parentPid
            }
            return false
        }
        entries.filter { it.pid == rootPid || isDescendant(it.pid) }
            .filter { it.comm == "java" || it.cmdline.substringBefore('\u0000').substringAfterLast('/') == "java" }
            .maxByOrNull { if (it.cmdline.contains(".jar") || it.cmdline.contains(installer.rootDir.absolutePath)) 2 else 1 }
            ?.pid
    }.getOrNull()

    /** Android exposes our app's child relationship in procfs even though Process.pid() is absent. */
    private fun findMcChildPid(): Int? = try {
        val parentPid = android.os.Process.myPid().toString()
        File("/proc").listFiles()?.firstNotNullOfOrNull { dir ->
            if (!dir.name.all(Char::isDigit)) return@firstNotNullOfOrNull null
            val stat = File(dir, "stat").takeIf { it.isFile }?.readText() ?: return@firstNotNullOfOrNull null
            val end = stat.lastIndexOf(')').takeIf { it > 0 } ?: return@firstNotNullOfOrNull null
            val fields = stat.substring(end + 1).trim().split(Regex("\\s+"))
            if (fields.getOrNull(1) != parentPid) return@firstNotNullOfOrNull null
            val cmdline = File(dir, "cmdline").takeIf { it.isFile }?.readText().orEmpty()
            if (cmdline.contains(installer.rootDir.absolutePath)) dir.name.toIntOrNull() else null
        }
    } catch (_: Exception) {
        null
    }

    /**
     * 探测 java 可执行文件路径。
     * 优先级：java-25 > java-21 > java-17，覆盖各 MC 版本需求。
     * 1. $PREFIX/bin/java（dpkg post-install 正常情况下存在）
     * 2. $PREFIX/lib/jvm/java-{25,21,17}-openjdk/bin/java
     * 3. $PREFIX/data/data/com.termux/files/usr/lib/jvm/java-{25,21,17}-openjdk/bin/java
     *    （dpkg-deb -x 解压 deb 包时，由于 compat 符号链接被覆盖，文件落在了此路径）
     * 4. 通过 find 命令查找
     * 找不到时返回 "java"，让 shell 报错（便于诊断）
     */
    private fun resolveJavaPath(prefix: String, version: JavaVersion? = null): String? {
        if (version != null) {
            return javaCandidates(version).map { "$it/bin/java" }
                .firstOrNull {
                    val file = File(it)
                    file.exists() && (version == JavaVersion.Java8 || file.canExecute())
                }
        }
        // 候选路径列表（含 compat 目录下的实际路径）
        val candidates = buildList {
            add("$prefix/bin/java")
            JavaVersion.values().sortedByDescending { it.ordinal }.forEach { supportedVersion ->
                addAll(javaCandidates(supportedVersion).map { "$it/bin/java" })
            }
            // 兼容旧环境中曾安装的 Java 21。
            add("$prefix/lib/jvm/java-21-openjdk/bin/java")
            add("$prefix/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/java")
            add("/system/bin/java")
        }
        for (path in candidates) {
            val f = File(path)
            if (f.exists() && f.canExecute()) {
                Log.i(TAG, "resolveJavaPath: found at $path")
                return path
            }
            if (f.exists()) {
                Log.w(TAG, "resolveJavaPath: $path exists but not executable")
            }
        }

        // 通过 find 命令在整个 $PREFIX 下查找 java
        try {
            val pb = ProcessBuilder("/system/bin/sh", "-c",
                "find '$prefix' -name 'java' -type f 2>/dev/null | head -5")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = proc.inputStream.bufferedReader().readText().trim()
            proc.waitFor()
            Log.i(TAG, "resolveJavaPath: find result = '$out'")
            if (out.isNotEmpty()) {
                val firstPath = out.lineSequence().firstOrNull { it.isNotEmpty() } ?: ""
                if (firstPath.isNotEmpty() && File(firstPath).exists()) {
                    return firstPath
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "resolveJavaPath: find failed: ${e.message}")
        }

        // 兜底：返回 "java"，让 shell 报错
        Log.w(TAG, "resolveJavaPath: java not found in any candidate path, fallback to 'java'")
        emitLog("[startMc] 错误: java 未找到，请检查 openjdk-17 是否已安装")
        return candidates.firstOrNull { File(it).exists() && File(it).canExecute() }
    }

    /**
     * 创建 world 目录快照（zip 打包）。
     * 快照保存到 /home/snapshots/world_yyyyMMdd_HHmmss.zip
     * 创建后按 [maxSnapshots] 清理最旧的快照（0 表示不清理）。
     * 返回快照文件路径，失败返回 null。
     */
    fun createSnapshot(maxSnapshots: Int = 0, dirName: String = "default"): String? {
        val serverDir = File(installer.rootDir, "home/servers/$dirName")
        // MC 1.16+ 维度目录与 world 平级：world / world_nether / world_the_end
        val worlds = listOf("world", "world_nether", "world_the_end", "worlds")
            .map { File(serverDir, it) }
            .filter { it.isDirectory }
        val snapshotDir = File(installer.rootDir, "home/snapshots").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(snapshotDir, "world_$ts.zip")

        if (worlds.isEmpty()) {
            Log.w(TAG, "createSnapshot: world 目录不存在")
            return null
        }
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                // 同步备份主世界、地狱、末地三个维度目录（存在的才打包，zip 保留目录层级）
                worlds.forEach { root ->
                    root.walkTopDown().forEach { file ->
                        val relPath = file.relativeTo(serverDir).path
                        if (file.isDirectory) {
                            zos.putNextEntry(ZipEntry("$relPath/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(ZipEntry(relPath))
                            FileInputStream(file).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
            Log.i(TAG, "快照已创建: ${outFile.absolutePath} (${outFile.length()} 字节)")
            // 按数量上限清理旧快照
            if (maxSnapshots > 0) {
                cleanupOldSnapshots(snapshotDir, maxSnapshots)
            }
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "createSnapshot failed: ${e.message}", e)
            null
        }
    }

    /**
     * 清理旧快照：按文件名时间戳倒序排列，保留最新的 [keepCount] 个，删除其余。
     * 删除失败时记录警告但不影响主流程。
     */
    private fun cleanupOldSnapshots(snapshotDir: File, keepCount: Int) {
        val files = snapshotDir.listFiles { f -> f.isFile && f.name.matches(Regex("world_\\d{8}_\\d{6}\\.zip")) }
            ?: return
        if (files.size <= keepCount) return
        // 按文件名时间戳倒序（最新在前）
        val sorted = files.sortedByDescending { it.name }
        val toDelete = sorted.drop(keepCount)
        var deleted = 0
        for (f in toDelete) {
            try {
                if (f.delete()) deleted++
                else Log.w(TAG, "cleanupOldSnapshots: 删除失败 ${f.name}")
            } catch (e: Exception) {
                Log.w(TAG, "cleanupOldSnapshots: 删除异常 ${f.name}: ${e.message}")
            }
        }
        if (deleted > 0) {
            Log.i(TAG, "cleanupOldSnapshots: 已清理 $deleted 个旧快照（保留 $keepCount 个）")
        }
    }

    companion object {
        private const val TAG = "TermuxRuntime"
    }
}

/**
 * java argfile 引用标记：`@` 后跟一个不含空白的路径。
 *
 * Forge 1.17+ / NeoForge 的官方启动方式是 `java @libraries/.../unix_args.txt`，
 * 命令里不会出现 `-jar` / `-cp`。识别这个标记是为了不把合法启动判成"命令不完整"。
 */
private val ARG_FILE_REFERENCE = Regex("""(^|\s)@[^\s'"]+""")

/**
 * 判断拼好的启动命令是否**完整**（纯函数，便于单元测试）；返回问题列表，空表示通过。
 *
 * 这些命令是十几段字符串用 `+` 拼出来的，漏掉一个 `+` 会让
 * `cd '<serverDir>' && exec '<java>' ... -jar server.jar nogui` **整段消失**，
 * 命令只剩一串 `export` 赋值 → shell 执行完自然退出、java 从未运行 →
 * 表现为「点启动秒退、无任何日志」。见 [TermuxRuntime.assertLaunchCommandComplete]。
 *
 * 判定只针对「被截断」这一种故障，必须放行所有合法启动方式：
 * `-jar` / `-cp` / `@参数文件`（Forge 1.17+、NeoForge）/ 自定义命令（由调用方关掉严格项）。
 *
 * @param command 拼好的完整 shell 命令
 * @param requireExecPrefix 是否要求执行段带 `exec `（自定义命令模式传 false）
 * @param requireLauncherMarker 是否要求出现启动器特征（自定义命令模式传 false）
 */
internal fun launchCommandProblems(
    command: String,
    requireExecPrefix: Boolean = true,
    requireLauncherMarker: Boolean = true
): List<String> {
    val problems = mutableListOf<String>()
    // 执行段 = 最后一个 `&&` 之后的内容。所有自动拼接的命令都以
    // `cd '<serverDir>' && <exec 启动程序>` 收尾；这段消失即为截断。
    val tail = command.substringAfterLast("&&").trim()
    if (!command.contains("&&") || tail.isEmpty()) {
        problems += "缺少 'cd … && …' 执行段（拼接时被截断，只剩下环境变量赋值）"
    } else if (requireExecPrefix && !tail.contains("exec ")) {
        problems += "缺少 'exec '（执行段没有要启动的程序，shell 会执行完赋值后直接退出）"
    }
    if (requireLauncherMarker) {
        val hasJar = command.contains("-jar ")
        val hasClasspath = command.contains(" -cp ") || command.contains(" -classpath ")
        val hasArgFile = ARG_FILE_REFERENCE.containsMatchIn(command)
        if (!hasJar && !hasClasspath && !hasArgFile) {
            problems += "缺少 '-jar' / '-cp' / '@参数文件'（看不出要启动哪个服务端）"
        }
    }
    return problems
}
