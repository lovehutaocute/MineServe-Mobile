package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.SharedFlow

/**
 * Termux 运行时入口（生产化）：
 *  - bootstrap()：完整初始化流程（proot → rootfs → apt update → pkg install openjdk-17 tmux）
 *  - startMc()：在 tmux 内启动 MC 服务，进程脱离 APP 进程组
 *  - stopMc()：优雅停止
 *  - consoleFlow：实时日志流（统一来源：CommandExecutor）
 *
 * 日志流统一：ConsoleSocketServer 的日志通过 onLog 回调转发到 CommandExecutor.emit()，
 * UI 只需订阅 TermuxRuntime.consoleFlow 即可获取所有日志。
 */
class TermuxRuntime(context: Context) {

    private val installer = BootstrapInstaller(context)
    private val executor = CommandExecutor(installer)

    /** ConsoleSocketServer 的日志通过回调转发到 executor，统一日志流 */
    private val socketServer = ConsoleSocketServer(installer) { line ->
        executor.emit(line)
    }

    val hasSurvivingProcess: Boolean get() = socketServer.isProcessAlive
    val consoleFlow: SharedFlow<String> get() = executor.consoleFlow

    fun startConsoleServer() = socketServer.start()
    fun stopConsoleServer() = socketServer.stop()

    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int =
        executor.execOnce(*command, env = env)

    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execStream(tag, *command, env = env)

    /**
     * 完整初始化流程：
     * 1. 下载 proot + bootstrap rootfs + 解压
     * 2. apt update
     * 3. 安装 openjdk-17, tmux, wget, curl, frp
     */
    suspend fun bootstrap(onProgress: (BootstrapInstaller.InstallPhase, Int) -> Unit): Boolean {
        // 步骤 1: 基础环境安装
        val ok = installer.ensureInstalled(onProgress)
        if (!ok) return false

        // 步骤 2: 启动 console socket 服务（用于接收 proot 内进程的日志输出）
        startConsoleServer()

        // 步骤 3: apt update + 安装包
        onProgress(BootstrapInstaller.InstallPhase.POST_SETUP, 92)
        execOnce("apt", "update", "-y")
        execOnce("apt", "install", "-y", "openjdk-17", "tmux", "wget", "curl", "frp", "tar", "xz-utils")
        execOnce("apt", "clean")

        onProgress(BootstrapInstaller.InstallPhase.DONE, 100)
        return true
    }

    /** 在 tmux session 中启动 MC 服务 */
    fun startMc(jarPath: String, maxHeapMb: Int, onExit: (Int) -> Unit): Process {
        Log.i(TAG, "startMc: jar=$jarPath heap=${maxHeapMb}m")
        // 用 tmux new-session -d -s mc-server 启动 JVM，进程完全脱离 APP
        val proc = executor.execStream(
            tag = "mc",
            "tmux", "new-session", "-d", "-s", "mc-server",
            "java -Xmx${maxHeapMb}m -Xms${maxHeapMb / 2}m -jar $jarPath nogui"
        )
        Thread({
            val code = proc.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            onExit(code)
        }, "mc-watch").start()
        return proc
    }

    /** 停止 MC：先 stop 优雅退出，3 秒后强制 kill tmux */
    fun stopMc(): Boolean {
        socketServer.broadcastCommand("stop")
        Thread.sleep(3000)
        runCatching { execOnce("tmux", "kill-session", "-t", "mc-server") }
        return true
    }

    /** 向 MC 控制台发指令 */
    fun sendCommand(line: String) = socketServer.broadcastCommand(line)

    /** 检查 tmux session 是否存在 */
    fun isMcRunning(): Boolean {
        val code = runCatching {
            execOnce("tmux", "has-session", "-t", "mc-server")
        }.getOrDefault(1)
        return code == 0
    }

    companion object { private const val TAG = "TermuxRuntime" }
}
