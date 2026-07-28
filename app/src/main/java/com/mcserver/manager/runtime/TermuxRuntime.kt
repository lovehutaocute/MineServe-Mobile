package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

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

    private val installer = BootstrapInstaller(context)
    private val executor = CommandExecutor(installer)

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

    /** 删除整个 Termux 运行环境 */
    fun deleteBootstrap() {
        stopMc()
        installer.deleteBootstrap()
    }

    /** MC 服务端 jar 文件路径（home/server/server.jar） */
    val serverJarFile: File get() = File(installer.rootDir, "home/server/server.jar").apply { parentFile?.mkdirs() }

    /** MC 服务端工作目录（home/server） */
    val serverDir: File get() = File(installer.rootDir, "home/server").apply { mkdirs() }

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

    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int =
        executor.execOnce(*command, env = env)

    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execStream(tag, *command, env = env)

    /**
     * 完整初始化流程：
     * 1. 下载 + 解压 Termux bootstrap rootfs
     * 2. apt-get install openjdk-17 wget curl（不再安装 tmux）
     * 3. 修复 openjdk-17 符号链接（dpkg-wrapper 跳过 configure 导致 post-install 未执行）
     */
    suspend fun bootstrap(onProgress: (BootstrapInstaller.InstallPhase, Int) -> Unit): Boolean {
        val ok = installer.ensureInstalled(onProgress)
        if (!ok) return false

        onProgress(BootstrapInstaller.InstallPhase.POST_SETUP, 92)
        installer.onLog?.invoke("[bootstrap] 安装依赖包（JDK/wget）...")
        executor.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
        executor.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "openjdk-17", "wget", "curl")
        executor.execOnce("apt-get", "clean")

        // 修复 openjdk-17 符号链接：dpkg-wrapper 的 configure 是 no-op，
        // openjdk-17 的 post-install 脚本未执行，导致 $PREFIX/bin/java 符号链接未创建
        fixJavaSymlinks()

        onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
        return true
    }

    /**
     * 修复 openjdk-17 命令可用性（wrapper 脚本方案）。
     * Termux openjdk-17 实际安装在 $PREFIX/lib/jvm/java-17-openjdk/，
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
     * 所以需要从两个位置查找 java。
     */
    private fun fixJavaSymlinks() {
        val prefix = installer.rootDir.absolutePath

        // 查找 jvm 实际目录（两个候选位置）
        val jvmCandidates = listOf(
            File(prefix, "lib/jvm/java-17-openjdk"),
            File(prefix, "data/data/com.termux/files/usr/lib/jvm/java-17-openjdk"),
            File(prefix, "lib/jvm/java-21-openjdk"),
            File(prefix, "data/data/com.termux/files/usr/lib/jvm/java-21-openjdk")
        )
        val jvmDir = jvmCandidates.firstOrNull { it.exists() }
        if (jvmDir == null) {
            installer.onLog?.invoke("[bootstrap] 警告: 未找到 openjdk-17 安装目录，跳过符号链接修复")
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
            "/system/lib64"
        ).joinToString(":")
        val javaHome = jvmDir.absolutePath

        val commands = listOf("java", "javac", "jar", "jps", "keytool", "rmic", "rmiregistry")
        var created = 0
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
                // 创建 wrapper 脚本：设置 LD_LIBRARY_PATH 和 JAVA_HOME，exec 原始二进制
                // 注意：$libPathEntries 是 Kotlin 变量（已展开），不用追加 shell 的 $LD_LIBRARY_PATH
                // （单引号内不会被 shell 展开；且 wrapper 已包含所有必需路径）
                val script = StringBuilder().apply {
                    append("#!/system/bin/sh\n")
                    append("export LD_LIBRARY_PATH='$libPathEntries'\n")
                    append("export JAVA_HOME='$javaHome'\n")
                    append("exec '$target' \"$@\"\n")
                }.toString()
                wrapper.writeText(script)
                // 设置可执行权限
                val chmodCmd = "chmod 755 '$wrapper'"
                val pb = ProcessBuilder("/system/bin/sh", "-c", chmodCmd)
                pb.redirectErrorStream(true)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val exitCode = proc.waitFor()
                if (exitCode == 0) {
                    created++
                    Log.i(TAG, "fixJavaSymlinks: wrapper created $cmd -> $target")
                } else {
                    installer.onLog?.invoke("[bootstrap] 警告: chmod $cmd 失败: $out")
                    Log.w(TAG, "fixJavaSymlinks: chmod $cmd failed: $out")
                }
            } catch (e: Exception) {
                installer.onLog?.invoke("[bootstrap] 警告: 创建 $cmd wrapper 异常: ${e.message}")
            }
        }
        installer.onLog?.invoke("[bootstrap] openjdk-17 wrapper 脚本创建完成: $created 个命令已就绪")
        Log.i(TAG, "fixJavaSymlinks: created $created wrappers in $termuxBinDir")
    }

    /**
     * 直接用 ProcessBuilder 启动 MC 服务（不再依赖 tmux）。
     * stdout/stderr 实时推送到 consoleFlow，同时写入日志文件。
     * onExit 回调在 MC 进程退出时触发。
     */
    fun startMc(jarPath: String, maxHeapMb: Int, onExit: (Int) -> Unit): Process {
        Log.i(TAG, "startMc: jar=$jarPath heap=${maxHeapMb}m")

        // 如果已有进程在运行，先停止
        mcProcess?.let { if (it.isAlive) return it }

        val prefix = installer.rootDir.absolutePath
        val serverDir = File(installer.rootDir, "home/server").apply { mkdirs() }
        val logFile = installer.logFile
        logFile.parentFile?.mkdirs()
        logFile.createNewFile()

        // 探测 java 实际路径并自动修复符号链接
        val javaPath = ensureJavaReady() ?: run {
            emitLog("[startMc] 错误: java 未找到，openjdk-17 可能未安装。请删除 Termux 环境后重新初始化")
            throw RuntimeException("java not found in Termux environment")
        }
        Log.i(TAG, "startMc: resolved java path = $javaPath")
        emitLog("[startMc] java 路径: $javaPath")

        // 用 /system/bin/sh -c 启动 java，设置环境变量
        // 设置 PATH 包含 jvm 的 bin 目录，确保 java 能找到其依赖（如 jlink）
        val jvmBinDir = File(javaPath).parentFile?.absolutePath ?: "$prefix/bin"
        // compat 路径：dpkg-deb -x 解包时 compat 符号链接被覆盖，文件实际落在此处
        val compatUsr = "$prefix/data/data/com.termux/files/usr"
        val jvmLibDir = File(javaPath).parentFile?.parentFile?.let { File(it, "lib") }?.absolutePath ?: "$prefix/lib/jvm/java-17-openjdk/lib"
        val javaCmd = "export PATH='$jvmBinDir:$prefix/bin:$compatUsr/bin:$prefix/bin/applets:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$prefix/lib:$compatUsr/lib:$jvmLibDir:$jvmLibDir/server:$prefix/lib/jvm/java-17-openjdk/lib:$prefix/lib/jvm/java-17-openjdk/lib/server:/system/lib64'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; " +
            "export JAVA_HOME='${File(javaPath).parentFile?.parent}'; " +
            "cd '$serverDir' && " +
            "'$javaPath' -Xmx${maxHeapMb}m -Xms${maxHeapMb / 2}m -jar $jarPath nogui"

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
                val fos = FileOutputStream(logFile, true)
                val reader = process.inputStream.bufferedReader()
                var line = reader.readLine()
                while (line != null) {
                    executor.emit(line)
                    fos.write((line + "\n").toByteArray())
                    fos.flush()
                    Log.d(TAG, "  [mc] $line")
                    line = reader.readLine()
                }
                fos.close()
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

    /** 停止 MC：向 stdin 发送 stop 命令，5 秒后强制 destroy */
    fun stopMc(): Boolean {
        val proc = mcProcess ?: return true
        if (!proc.isAlive) {
            mcProcess = null
            return true
        }
        // 先尝试优雅停止：发送 stop 命令到 stdin
        try {
            mcStdin?.write("stop\n".toByteArray())
            mcStdin?.flush()
        } catch (e: Exception) {
            Log.w(TAG, "stopMc: stdin write failed: ${e.message}")
        }
        // 等待最多 5 秒
        Thread.sleep(5000)
        // 如果还在运行，强制销毁
        if (proc.isAlive) {
            Log.w(TAG, "stopMc: process still alive, destroying")
            proc.destroyForcibly()
        }
        mcProcess = null
        mcStdin = null
        return true
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
     * 探测 java 可执行文件路径。
     * 优先级：
     * 1. $PREFIX/bin/java（dpkg post-install 正常情况下存在）
     * 2. $PREFIX/lib/jvm/java-17-openjdk/bin/java（openjdk-17 实际安装位置）
     * 3. $PREFIX/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin/java
     *    （dpkg-deb -x 解压 deb 包时，由于 compat 符号链接被覆盖，文件落在了此路径）
     * 4. 通过 find 命令查找
     * 找不到时返回 "java"，让 shell 报错（便于诊断）
     */
    private fun resolveJavaPath(prefix: String): String {
        // 候选路径列表（含 compat 目录下的实际路径）
        val candidates = listOf(
            "$prefix/bin/java",
            "$prefix/lib/jvm/java-17-openjdk/bin/java",
            "$prefix/lib/jvm/java-21-openjdk/bin/java",
            // deb 解压实际落点：compat 符号链接被 deb 内的目录结构覆盖后，
            // 文件实际解到了 $PREFIX/data/data/com.termux/files/usr/...
            "$prefix/data/data/com.termux/files/usr/lib/jvm/java-17-openjdk/bin/java",
            "$prefix/data/data/com.termux/files/usr/lib/jvm/java-21-openjdk/bin/java",
            "/system/bin/java"
        )
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
        return "java"
    }

    /**
     * 创建 world 目录快照（zip 打包）。
     * 快照保存到 /home/snapshots/world_yyyyMMdd_HHmmss.zip
     * 返回快照文件路径，失败返回 null。
     */
    fun createSnapshot(): String? {
        val worldDir = File(installer.rootDir, "home/server/world")
        val snapshotDir = File(installer.rootDir, "home/snapshots").apply { mkdirs() }
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val outFile = File(snapshotDir, "world_$ts.zip")

        if (!worldDir.exists()) {
            Log.w(TAG, "createSnapshot: world 目录不存在")
            return null
        }
        return try {
            ZipOutputStream(FileOutputStream(outFile)).use { zos ->
                worldDir.walkTopDown().forEach { file ->
                    val relPath = file.relativeTo(worldDir).path
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
            Log.i(TAG, "快照已创建: ${outFile.absolutePath} (${outFile.length()} 字节)")
            outFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "createSnapshot failed: ${e.message}", e)
            null
        }
    }

    companion object { private const val TAG = "TermuxRuntime" }
}
