package com.mineserve.mobile.runtime

import android.content.Context
import android.util.Log
import android.system.Os
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.JavaVersion
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
    private val executor = CommandExecutor(installer)

    private val java8Rootfs: File
        get() = File(installer.rootDir, "var/lib/mineserve/java8-ubuntu-rootfs")

    private val java8ReadyMarker: File
        get() = File(installer.rootDir, "java-8-ubuntu-ready")

    private val ubuntuJava8Home = "/usr/lib/jvm/java-8-openjdk-arm64"

    /** MC 服务器进程（null 表示未启动） */
    @Volatile
    private var mcProcess: Process? = null

    /** MC 进程的 stdin，用于发送命令 */
    @Volatile
    private var mcStdin: OutputStream? = null

    val consoleFlow: SharedFlow<String> get() = executor.consoleFlow

    /** 设置日志回调，bootstrap 过程的日志会通过此回调输出 */
    fun setBootstrapLogCallback(cb: (String) -> Unit) {
        installer.onLog = cb
    }

    /** 向 consoleFlow 推送一条日志 */
    fun emitLog(line: String) {
        executor.emit(line)
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

    suspend fun installJava(version: JavaVersion): Boolean = withContext(Dispatchers.IO) {
        if (!isReady()) throw RuntimeException("Termux 环境未初始化")
        if (version == JavaVersion.Java8) {
            return@withContext installJava8Ubuntu()
        }
        emitLog("[java] 正在安装 ${version.displayName}")
        emitLog("[java] ${version.displayName} 正在下载并配置，首次安装可能需要数分钟")
        val code = execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "install", "--allow-unauthenticated", "-y", version.packageName)
        if (code == 0) fixJavaSymlinks(version)
        val installed = code == 0 && isJavaInstalled(version)
        emitLog(if (installed) "[java] ${version.displayName} 安装完成" else "[java] ${version.displayName} 安装失败")
        installed
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
        install_openjdk8 http://mirrors.tuna.tsinghua.edu.cn/ubuntu-ports || \
          install_openjdk8 http://mirrors.ustc.edu.cn/ubuntu-ports || \
          install_openjdk8 http://ports.ubuntu.com/ubuntu-ports
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
            "https://mirrors.ustc.edu.cn/ubuntu-cdimage/ubuntu-base/releases/20.04/release/$archiveName",
            "https://cdimage.ubuntu.com/ubuntu-base/releases/20.04/release/$archiveName"
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
            "--installServer '$guestDir'"
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

    private fun repairProotLibraries(): Boolean {
        val prefix = installer.rootDir
        val targetDir = File(prefix, "lib").apply { mkdirs() }
        val sources = listOf(
            File(prefix, "usr/lib/libtalloc.so.2"),
            File(prefix, "data/data/com.termux/files/usr/lib/libtalloc.so.2"),
            File(prefix, "usr/lib/libtalloc.so.2.4.3"),
            File(prefix, "data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3")
        )
        val target = File(targetDir, "libtalloc.so.2")
        if (!target.isFile) {
            val source = sources.firstOrNull { it.isFile }
            if (source != null) runCatching { source.copyTo(target, overwrite = false) }
        }
        val repaired = target.isFile
        emitLog(
            if (repaired) "[bootstrap] proot 依赖已就绪: libtalloc.so.2"
            else "[bootstrap] 警告: proot 依赖 libtalloc.so.2 未找到"
        )
        return repaired
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
        launcher.writeText(
            "#!/system/bin/sh\n" +
                "export PROOT_TMP_DIR='$prefix/tmp'\n" +
                "export TMPDIR='$prefix/tmp'\n" +
                "export PROOT_LOADER='$prefix/libexec/proot/loader'\n" +
                "export PROOT_LOADER_32='$prefix/libexec/proot/loader32'\n" +
                "export LD_LIBRARY_PATH='$prefix/lib:$prefix/usr/lib:$compatUsrLib:/system/lib64'\n" +
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

    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int =
        executor.execOnce(*command, env = env)

    fun execOnceWithTimeout(
        timeoutMs: Long,
        vararg command: String,
        env: Map<String, String> = emptyMap()
    ): Int = executor.execOnceWithTimeout(timeoutMs, *command, env = env)

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
            // jvm 不完整（杀后台/覆盖安装后 libjli.so 缺失）→ 自动重装 openjdk
            ensureJvmComplete()
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
        repaired += ensureJvmComplete()
        fixJavaSymlinks(javaVersion)

        if (needsFonts) {
            val fontConfig = File(prefix, "etc/fonts/fonts.conf")
            val fcCache = listOf(
                File(prefix, "bin/fc-cache"),
                File(prefix, "usr/bin/fc-cache"),
                File(prefix, "data/data/com.termux/files/usr/bin/fc-cache")
            ).firstOrNull { it.exists() && it.canExecute() }
            if (!fontConfig.exists() || fcCache == null) {
                emitLog("[repair] 正在补齐字体运行库...")
                execOnce(
                    "apt-get", "-o", "DPkg::Lock::Timeout=60", "install",
                    "--allow-unauthenticated", "-y", "fontconfig", "ttf-dejavu"
                )
                repaired += fixUsrBin() + ensureRootfsExecutable()
            }
            if (execOnce("fc-cache", "-f") == 0) repaired++
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
                    appendLine("APT::Sandbox::User \"root\";")
                    appendLine("APT::Sandbox::Seccomp \"false\";")
                })
                fixed++
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
    fun ensureJvmComplete(): Int {
        var repaired = 0
        JavaVersion.values().forEach { version ->
            if (!isJavaInstalled(version) || isJavaComplete(version)) return@forEach

            installer.onLog?.invoke("[bootstrap] ${version.displayName} 运行环境不完整，正在修复...")
            if (version == JavaVersion.Java8) {
                val repairedJava8 = runBlocking { installJava8Ubuntu() }
                if (repairedJava8) repaired++
                else installer.onLog?.invoke("[bootstrap] Java 8 Ubuntu ARM64 运行时自动修复失败")
                return@forEach
            }
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
        if (f.isFile && !isLink) {
            try {
                val head = f.bufferedReader().use { it.readLine() }?.take(100) ?: ""
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
        executor.execOnce("apt-get", "-o", "DPkg::Lock::Timeout=60", "update", "--allow-insecure-repositories", "-y")
        // 安装 openjdk-25：Paper 26.x / MC 26.1+ 要求 Java 25+，openjdk-17 已不够
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
                val isMc = cmdline.contains("java") &&
                    ((target != null && cmdline.contains(target)) ||
                        (cmdline.contains(".jar") && cmdline.contains("nogui")))
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
        logFile: File
    ): Process {
        if (!java8UbuntuReady()) {
            emitLog("[startMc] Java 8 Ubuntu ARM64 运行环境未安装或不完整")
            throw RuntimeException("Java 8 Ubuntu runtime is not ready")
        }
        val guestDir = "/srv/mineserve"
        val guestJar = "$guestDir/${File(jarPath).relativeTo(serverDir).invariantSeparatorsPath}"
        val guestLaunchArgs = launchArgs?.replace(serverDir.absolutePath, guestDir)
        val javaArguments = guestLaunchArgs ?: "-jar '$guestJar'"
        val command = "export JAVA_HOME=$ubuntuJava8Home; " +
            "export PATH=\"/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\"; " +
            "export TMPDIR=/tmp; export HOME=/root; export FONTCONFIG_PATH=/etc/fonts; " +
            "cd '$guestDir' && exec /usr/bin/java " +
            "-Djava.awt.headless=true -Djava.io.tmpdir=/tmp " +
            "-Doshi.util.use.jna=false -Djna.nosys=true " +
            "-Dio.netty.transport.noNative=true -Dio.netty.transport.epoll.enabled=false " +
            "-Dio.netty.transport.kqueue.enabled=false -Djava.net.preferIPv4Stack=true " +
            "-Xmx${maxHeapMb}m -Xms${maxHeapMb / 2}m $javaArguments nogui"
        val rootfs = java8Rootfs
        val sharedMemory = File(rootfs, "tmp").apply { mkdirs() }
        val resolver = prepareUbuntuDns(rootfs)
        val proot = listOf(
            File(installer.rootDir, "bin/proot"),
            File(installer.rootDir, "usr/bin/proot")
        ).firstOrNull { it.isFile && it.canExecute() }
            ?: throw RuntimeException("proot is not available")
        val process = ProcessBuilder(
            proot.absolutePath, "--kill-on-exit", "--link2symlink", "--sysvipc", "-L", "--change-id=0:0",
            "--rootfs=${rootfs.absolutePath}", "--cwd=/root",
            "--bind=/dev", "--bind=/proc", "--bind=/sys", "--bind=/dev/urandom:/dev/random",
            "--bind=${sharedMemory.absolutePath}:/dev/shm", "--bind=${resolver.absolutePath}:/etc/resolv.conf",
            "--bind=${serverDir.absolutePath}:$guestDir",
            "/usr/bin/env", "-i",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "HOME=/root", "TMPDIR=/tmp", "LANG=C.UTF-8", "DEBIAN_FRONTEND=noninteractive",
            "/bin/sh", "-lc", command
        ).apply {
            redirectErrorStream(true)
            directory(serverDir)
            environment().putAll(executor.termuxEnv())
        }.start()
        Log.i(TAG, "startMc Ubuntu Java 8 command: $command")
        emitLog("[startMc] java 路径: Ubuntu:/usr/bin/java (openjdk-8-jdk)")
        emitLog("[startMc] 正在启动 Java 8 服务端...")
        mcProcess = process
        mcStdin = process.outputStream
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
            mcProcess = null
            mcStdin = null
            onExit(code)
        }, "mc-ubuntu-watch").start()
        return process
    }

    /**
     * 直接用 ProcessBuilder 启动 MC 服务（不再依赖 tmux）。
     * stdout/stderr 实时推送到 consoleFlow，同时写入日志文件。
     * onExit 回调在 MC 进程退出时触发。
     */
    fun startMc(jarPath: String, maxHeapMb: Int, dirName: String, javaVersion: JavaVersion = JavaVersion.Java17, onExit: (Int) -> Unit, launchArgs: String? = null): Process {
        Log.i(TAG, "startMc: jar=$jarPath heap=${maxHeapMb}m dirName=$dirName")

        // 如果已有进程在运行，先停止
        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = serverDirFor(dirName)
        val logFile = File(serverDir, "logs/latest.log")
        logFile.parentFile?.mkdirs()
        logFile.createNewFile()

        // 清理占用该服务器目录的孤儿 java 进程（app 重启后旧 MC 进程成孤儿，
        // 占着 world/session.lock → 新实例启动报 already locked）
        killOrphanMcProcess(serverDir)

        if (javaVersion == JavaVersion.Java8) {
            return startMcInUbuntu(jarPath, maxHeapMb, serverDir, onExit, launchArgs, logFile)
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
        val allJvmLibs = JavaVersion.values().flatMap { version ->
            javaCandidates(version).flatMap { candidate ->
                listOf("$candidate/lib", "$candidate/lib/server")
            }
        }.joinToString(":")
        val javaCmd = "export PATH='$jvmBinDir:$prefix/bin:$prefix/usr/bin:$compatUsr/bin:$prefix/bin/applets:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$prefix/lib:$compatUsr/lib:$prefix/usr/lib:$jvmLibDir:$jvmLibDir/server:$allJvmLibs:/system/lib64'; " +
            "export FONTCONFIG_PATH='$prefix/etc/fonts'; " +
            "export FONTCONFIG_FILE='$prefix/etc/fonts/fonts.conf'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; " +
            "export JAVA_HOME='${File(javaPath).parentFile?.parent}'; " +
            "cd '$serverDir' && " +
            "'$javaPath' -Djava.awt.headless=true -Djava.io.tmpdir='$prefix/tmp' " +
            "-Doshi.util.use.jna=false -Djna.nosys=true " +
            "-Dio.netty.transport.noNative=true -Dio.netty.transport.epoll.enabled=false " +
            "-Dio.netty.transport.kqueue.enabled=false -Djava.net.preferIPv4Stack=true " +
            "-Xmx${maxHeapMb}m -Xms${maxHeapMb / 2}m " + (launchArgs ?: "-jar $jarPath") + " nogui"

        Log.i(TAG, "startMc command: $javaCmd")

        val pb = ProcessBuilder("/system/bin/sh", "-c", javaCmd).apply {
            redirectErrorStream(true)
            directory(serverDir)
            // 环境变量
            environment().putAll(executor.termuxEnv())
        }
        val process = pb.start()
        mcProcess = process
        mcStdin = process.outputStream

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
            mcProcess = null
            mcStdin = null
            onExit(code)
        }, "mc-watch").start()

        return process
    }

    /** 停止 MC：向 stdin 发送 stop 命令，等待最多 5 秒后强制 destroy */
    suspend fun stopMc(): Boolean = withContext(Dispatchers.IO) {
        val proc = mcProcess ?: return@withContext true
        if (!proc.isAlive) {
            mcProcess = null
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
        mcProcess = null
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
     * java 进程，优先匹配 cmdline 含 ".jar" 的 MC 服务端进程，否则取第一个
     * java 进程兜底；读取其 stat 中 rss 字段（剥离 comm 后 0-based 索引 21）。
     * 常规权限即可读取（app 自有子进程），兼容性好。
     * @return MB 值；未找到/读取失败时返回 0
     */
    fun mcProcessMemoryMb(): Long {
        return try {
            val procDir = java.io.File("/proc")
            var fallbackRss = 0L
            procDir.listFiles()?.forEach { f ->
                val name = f.name
                if (name.isEmpty() || !name.all { it.isDigit() }) return@forEach
                val statFile = java.io.File(f, "stat")
                if (!statFile.exists()) return@forEach
                val content = statFile.readText()
                // /proc/pid/stat 格式：pid (comm) state ...；comm 可能含空格/括号
                val openParen = content.indexOf('(')
                val closeParen = content.indexOf(')', openParen + 1)
                if (openParen <= 0 || closeParen <= openParen) return@forEach
                val comm = content.substring(openParen + 1, closeParen)
                if (comm != "java") return@forEach
                // closeParen 后从 state 开始：state(1) ... vsize(23) rss(24)，rss 0-based 索引 21
                val rssPages = content.substring(closeParen + 1)
                    .trim().split(Regex("\\s+")).getOrNull(21)?.toLongOrNull() ?: 0L
                if (rssPages <= 0) return@forEach
                // 优先匹配 MC 服务端进程（cmdline 含 .jar），否则记录首个 java 进程兜底
                val cmdlineFile = java.io.File(f, "cmdline")
                val isMcJar = cmdlineFile.exists() && cmdlineFile.readText().contains(".jar")
                if (isMcJar) return rssPages * 4096 / (1024 * 1024)
                if (fallbackRss == 0L) fallbackRss = rssPages * 4096 / (1024 * 1024)
            }
            fallbackRss
        } catch (e: Exception) {
            0L
        }
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
        val worldDir = File(serverDir, "world")
        val snapshotDir = File(installer.rootDir, "home/snapshots").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(snapshotDir, "world_$ts.zip")

        if (!worldDir.exists()) {
            Log.w(TAG, "createSnapshot: world 目录不存在")
            return null
        }
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                // 同步备份主世界、地狱、末地三个维度目录（存在的才打包，zip 保留目录层级）
                val dirs = listOf(worldDir, File(serverDir, "world_nether"), File(serverDir, "world_the_end"))
                    .filter { it.exists() }
                dirs.forEach { root ->
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
