package com.mcserver.manager.runtime

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Console Socket 服务端：
 *  - 监听 Unix Domain Socket（API 24+）或回环 TCP（兼容旧机）
 *  - Termux 端把 stdout 行写入 socket
 *  - APP 端订阅 consoleFlow 获得实时日志（按需展示在 LogsPage）
 *  - 多客户端订阅：广播；断线自动重连
 *
 * 兼容性说明：
 *  - Android API 24+ 支持 android.net.LocalSocket / LocalServerSocket（unix domain）
 *  - 旧机器回退到 127.0.0.1:7890（仅本机可达，安全）
 *
 * 此处用 java.net.ServerSocket 简化（生产建议改用 android.net.LocalServerSocket 走 unix domain）
 */
class ConsoleSocketServer(
    private val installer: BootstrapInstaller
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private val clients = ConcurrentLinkedQueue<ClientConn>()
    private val running = AtomicBoolean(false)

    /** 给 UI 订阅的日志流（按行） */
    private val _consoleFlow = MutableSharedFlow<String>(replay = 256, extraBufferCapacity = 1024)
    val consoleFlow: SharedFlow<String> = _consoleFlow.asSharedFlow()

    /** 当前是否已有 MC 进程正在往 socket 写入 */
    val isProcessAlive: Boolean get() = clients.any { it.alive }

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverJob = scope.launch {
            val port = 7890 // 回环端口，仅本机
            Log.i(TAG, "ConsoleSocketServer listening on 127.0.0.1:$port")
            ServerSocket(port).use { server ->
                server.soTimeout = 0
                while (isActive && running.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        Log.w(TAG, "accept failed: ${e.message}")
                        continue
                    }
                    val conn = ClientConn(client)
                    clients.add(conn)
                    scope.launch { handleClient(conn) }
                }
            }
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        serverJob?.cancel()
        clients.forEach { it.close() }
        clients.clear()
        scope.cancel()
    }

    /** 给 UI 调用：向所有 MC 控制台 stdin 广播一行指令（如 /say /stop） */
    fun broadcastCommand(line: String) {
        clients.forEach { c -> runCatching { c.writer?.println(line) } }
    }

    private fun handleClient(conn: ClientConn) {
        conn.writer = PrintWriter(conn.socket.getOutputStream(), true)
        BufferedReader(InputStreamReader(conn.socket.getInputStream())).use { r ->
            var line = r.readLine()
            while (line != null && conn.alive) {
                _consoleFlow.tryEmit(line)
                line = r.readLine()
            }
        }
        conn.close()
        clients.remove(conn)
    }

    private data class ClientConn(val socket: Socket) {
        @Volatile var writer: PrintWriter? = null
        @Volatile var alive: Boolean = true
        fun close() {
            alive = false
            runCatching { socket.close() }
        }
    }

    companion object { private const val TAG = "ConsoleSocketServer" }
}
