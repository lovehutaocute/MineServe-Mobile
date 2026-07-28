package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.SharedFlow
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Termux 运行时（Termux 原生模式，不依赖 proot）：
 *  - bootstrap()：下载 + 解压 Termux bootstrap rootfs，安装 JDK/tmux 等
 *  - startMc()：在 tmux 中启动 MC 服务，日志重定向到文件
 *  - stopMc()：tmux send-keys 发送 stop，然后 kill-session
 *  - consoleFlow：通过文件监视获取 MC 日志
 */
class TermuxRuntime(context: Context) {

    private val installer = BootstrapInstaller(context)
    private val executor = CommandExecutor(installer)

    /** tmux 可执行文件完整路径，避免 PATH 搜索问题 */
    private val tmuxPath: String get() = "${installer.rootDir.absolutePath}/bin/tmux"

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
        installer.deleteBootstrap()
    }

    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int =
        executor.execOnce(*command, env = env)

    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execStream(tag, *command, env = env)

    /**
     * 完整初始化流程：
     * 1. 下载 + 解压 Termux bootstrap rootfs
     * 2. apt-get install openjdk-17 tmux wget curl
     */
    suspend fun bootstrap(onProgress: (BootstrapInstaller.InstallPhase, Int) -> Unit): Boolean {
        val ok = installer.ensureInstalled(onProgress)
        if (!ok) return false

        onProgress(BootstrapInstaller.InstallPhase.POST_SETUP, 92)
        // 用 apt-get 替代 pkg（pkg 是 bash 脚本，依赖 bash shebang）
        // --allow-insecure-repositories / --allow-unauthenticated: 跳过 GPG 验证（无 ca-certificates）
        installer.onLog?.invoke("[bootstrap] 安装依赖包（JDK/tmux/wget）...")
        executor.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
        executor.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "openjdk-17", "tmux", "wget", "curl")
        executor.execOnce("apt-get", "clean")

        onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
        return true
    }

    /** 在 tmux session 中启动 MC 服务，日志重定向到文件 */
    fun startMc(jarPath: String, maxHeapMb: Int, onExit: (Int) -> Unit): Process {
        Log.i(TAG, "startMc: jar=$jarPath heap=${maxHeapMb}m")

        val logFile = installer.logFile
        logFile.parentFile?.mkdirs()
        logFile.createNewFile()

        // 启动日志文件监视
        executor.startLogWatcher(logFile)

        // 用 tmux 启动 MC，stdout/stderr 重定向到日志文件
        // 注意：tmux 内部用 sh 而非 bash，避免依赖 Termux bash
        val javaCmd = "java -Xmx${maxHeapMb}m -Xms${maxHeapMb / 2}m -jar $jarPath nogui"
        val proc = executor.execStream(
            tag = "mc",
            tmuxPath, "new-session", "-d", "-s", "mc-server",
            "sh", "-c", "$javaCmd 2>&1 | tee $logFile"
        )
        Thread({
            val code = proc.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            onExit(code)
        }, "mc-watch").start()
        return proc
    }

    /** 停止 MC：先 tmux send-keys 发送 stop，3 秒后强制 kill */
    fun stopMc(): Boolean {
        runCatching {
            executor.execOnce(tmuxPath, "send-keys", "-t", "mc-server", "stop", "Enter")
        }
        Thread.sleep(3000)
        runCatching {
            executor.execOnce(tmuxPath, "kill-session", "-t", "mc-server")
        }
        executor.stopLogWatcher()
        return true
    }

    /** 向 MC 控制台发指令（通过 tmux send-keys） */
    fun sendCommand(line: String) {
        val cmd = if (line.startsWith("/")) line.substring(1) else line
        runCatching {
            executor.execOnce(tmuxPath, "send-keys", "-t", "mc-server", cmd, "Enter")
        }
    }

    /** 检查 tmux session 是否存在 */
    fun isMcRunning(): Boolean {
        val code = runCatching {
            executor.execOnce(tmuxPath, "has-session", "-t", "mc-server")
        }.getOrDefault(1)
        return code == 0
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
