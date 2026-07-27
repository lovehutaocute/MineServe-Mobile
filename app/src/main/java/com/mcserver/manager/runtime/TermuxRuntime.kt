package com.mcserver.manager.runtime

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.SharedFlow

/**
 * Termux 运行时入口（不暴露任何 Termux UI）。
 * 聚合 BootstrapInstaller + CommandExecutor + ConsoleSocketServer。
 *
 * 关键 API：
 *  - bootstrap()         首次初始化
 *  - execOnce(cmd)       一次性命令（如安装 jdk: pkg install openjdk-17）
 *  - startMc()           启动 MC 进程（在 tmux 内常驻）
 *  - stopMc()            停止 MC 进程
 *  - sendCommand(line)   向 MC 控制台发送 /op 等指令
 *  - consoleFlow         实时日志流（UI 订阅）
 */
class TermuxRuntime(context: Context) {

    private val installer = BootstrapInstaller(context)
    private val executor = CommandExecutor(installer)
    private val socketServer = ConsoleSocketServer(installer)

    /** 是否已检测到上次 APP 被杀后存活的 MC 进程（通过 socket 客户端存在性判断） */
    val hasSurvivingProcess: Boolean get() = socketServer.isProcessAlive

    /** 日志流：UI 端 collect 后渲染到 LogsPage */
    val consoleFlow: SharedFlow<String> get() = executor.consoleFlow

    /** 启动 socket 服务端，监听 Termux 子进程输出 */
    fun startConsoleServer() = socketServer.start()
    fun stopConsoleServer() = socketServer.stop()

    /**
     * 一次性命令：返回退出码
     */
    fun execOnce(vararg command: String, env: Map<String, String> = emptyMap()): Int =
        executor.execOnce(*command, env = env)

    /**
     * 后台长期命令：返回 Process 句柄
     */
    fun execStream(tag: String, vararg command: String, env: Map<String, String> = emptyMap()): Process =
        executor.execStream(tag, *command, env = env)

    /**
     * 首次初始化：释放 native helper、解压 rootfs、apt update、安装 openjdk
     * @param onProgress (phase, percent) UI 进度回调
     */
    suspend fun bootstrap(onProgress: (BootstrapInstaller.InstallPhase, Int) -> Unit): Boolean {
        val ok = installer.ensureInstalled(onProgress)
        if (!ok) return false
        // 实际安装 jdk + tmux + frp 等
        // execOnce("pkg", "install", "-y", "openjdk-17", "tmux", "wget", "curl")
        return true
    }

    /**
     * 在 tmux session 中启动 MC 服务进程。
     * 用 setsid + nohup + tmux new-session -d 形式：
     *  - 进程脱离 APP 进程组（APP 被杀不影响 MC）
     *  - tmux 会话持久化，可重新 attach
     */
    fun startMc(jarPath: String, maxHeapMb: Int, onExit: (Int) -> Unit): Process {
        Log.i(TAG, "startMc: jar=$jarPath heap=${maxHeapMb}m")
        val proc = executor.execStream(
            tag = "mc",
            "tmux", "new-session", "-d", "-s", "mc-server",
            "'java -Xmx${maxHeapMb}m -jar $jarPath nogui'"
        )
        // 启动监控线程：进程退出时回调
        Thread({
            val code = proc.waitFor()
            Log.w(TAG, "MC process exited code=$code")
            onExit(code)
        }, "mc-watch").start()
        return proc
    }

    /**
     * 停止 MC：先通过控制台 stop 优雅退出，3 秒后仍未退出则 kill tmux session
     */
    fun stopMc(): Boolean {
        socketServer.broadcastCommand("stop")
        Thread.sleep(3000)
        runCatching { execOnce("tmux", "kill-session", "-t", "mc-server") }
        return true
    }

    /**
     * 向 MC 控制台发指令（如 /say、/op）
     */
    fun sendCommand(line: String) = socketServer.broadcastCommand(line)

    /** tmux session 是否存在 → MC 是否在运行 */
    fun isMcRunning(): Boolean {
        val code = runCatching {
            executor.execOnce("tmux", "has-session", "-t", "mc-server")
        }.getOrDefault(1)
        return code == 0
    }

    companion object { private const val TAG = "TermuxRuntime" }
}
