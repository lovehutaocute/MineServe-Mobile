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

    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int =
        executor.execOnce(*command, env = env)

    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execStream(tag, *command, env = env)

    /**
     * 完整初始化流程：
     * 1. 下载 + 解压 Termux bootstrap rootfs
     * 2. apt-get install openjdk-17 wget curl（不再安装 tmux）
     */
    suspend fun bootstrap(onProgress: (BootstrapInstaller.InstallPhase, Int) -> Unit): Boolean {
        val ok = installer.ensureInstalled(onProgress)
        if (!ok) return false

        onProgress(BootstrapInstaller.InstallPhase.POST_SETUP, 92)
        installer.onLog?.invoke("[bootstrap] 安装依赖包（JDK/wget）...")
        executor.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
        executor.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "openjdk-17", "wget", "curl")
        executor.execOnce("apt-get", "clean")

        onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
        return true
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

        // 用 /system/bin/sh -c 启动 java，设置环境变量
        // java 路径：$prefix/bin/java（Termux 安装 openjdk-17 后存在）
        val javaCmd = "export PATH='$prefix/bin:$prefix/bin/applets:$prefix/libexec:/system/bin:/system/xbin'; " +
            "export LD_LIBRARY_PATH='$prefix/lib:/system/lib64'; " +
            "export PREFIX='$prefix'; " +
            "export HOME='$prefix/home'; " +
            "export TMPDIR='$prefix/tmp'; " +
            "cd '$serverDir' && " +
            "java -Xmx${maxHeapMb}m -Xms${maxHeapMb / 2}m -jar $jarPath nogui"

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
