package com.mineserve.mobile.runtime

import android.net.LocalServerSocket
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.PrintWriter
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Console Socket 服务端（生产化）：
 *  - 使用 android.net.LocalServerSocket + LocalSocketAddress(FILESYSTEM)
 *  - socket 文件创建在 rootDir/tmp/mc.sock，proot 内通过 /tmp/mc.sock 访问
 *  - Termux/proot 端把 stdout 写入 socket → APP 端通过 onLog 回调推送
 *  - 多客户端广播 + 断线自动清理
 *  - 支持向 MC 控制台 stdin 发送指令（stop / op / say）
 *
 * 日志不再维护独立 SharedFlow，改为通过 onLog 回调转发到 CommandExecutor.consoleFlow，
 * 确保日志流统一。
 */
class ConsoleSocketServer(
    private val installer: BootstrapInstaller,
    private val onLog: (String) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var serverJob: Job? = null
    private val clients = ConcurrentLinkedQueue<ClientConn>()
    private val running = AtomicBoolean(false)

    /** 是否有客户端连接（即 MC 进程是否在写入日志） */
    val isProcessAlive: Boolean get() = clients.any { it.alive }

    private var serverSocket: LocalServerSocket? = null

    fun start() {
        if (!running.compareAndSet(false, true)) return
        serverJob = scope.launch {
            // 使用抽象命名空间 socket（Android LocalServerSocket(String) 的标准方式）
            // APP 与 proot 共享同一 Linux 内核，抽象命名空间 socket 可跨进程访问
            val socketName = "mc-console"
            Log.i(TAG, "ConsoleSocketServer listening on abstract:$socketName")
            LocalServerSocket(socketName).use { server ->
                serverSocket = server
                while (isActive && running.get()) {
                    val client = try {
                        server.accept()
                    } catch (e: Exception) {
                        if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
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
        serverSocket?.close()
        serverSocket = null
        clients.forEach { it.close() }
        clients.clear()
        scope.cancel()
    }

    /** 向所有连接的 MC 控制台 stdin 广播指令 */
    fun broadcastCommand(line: String) {
        clients.forEach { c ->
            runCatching {
                c.writer?.println(line)
                c.writer?.flush()
            }
        }
    }

    private suspend fun handleClient(conn: ClientConn) {
        conn.writer = PrintWriter(conn.socket.outputStream, true)
        try {
            BufferedReader(InputStreamReader(conn.socket.inputStream)).use { r ->
                var line = r.readLine()
                while (line != null && conn.alive && running.get()) {
                    onLog(line)
                    line = r.readLine()
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "client disconnected: ${e.message}")
        } finally {
            conn.close()
            clients.remove(conn)
        }
    }

    private data class ClientConn(val socket: android.net.LocalSocket) {
        @Volatile var writer: PrintWriter? = null
        @Volatile var alive: Boolean = true
        fun close() {
            alive = false
            runCatching { socket.close() }
        }
    }

    companion object { private const val TAG = "ConsoleSocketServer" }
}
