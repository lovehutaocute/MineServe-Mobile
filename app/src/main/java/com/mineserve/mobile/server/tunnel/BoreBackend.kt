package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.Socket
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * bore 协议隧道后端（纯 Kotlin 实现，零外部依赖）。
 *
 * 协议参考 ekzhang/bore (Rust)，核心流程：
 *   1. 客户端 TCP 连接服务端 7835 端口（控制通道）
 *   2. （可选）HMAC-SHA256 挑战-应答认证
 *   3. 客户端发送 {"Hello": localPort}，服务端回复 {"Hello": assignedPort}
 *   4. 外部连接到达时，服务端发 {"Connection": "uuid"}
 *   5. 客户端开新 TCP 连接，发送 {"Accept": "uuid"}，然后双向转发
 *
 * 所有消息以 null 字节 '\0' 分隔，JSON 格式。
 */
class BoreBackend : TunnelBackend {

    override val type: TunnelType = TunnelType.Bore

    private val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    private var log: (String) -> Unit = {}

    private var controlSocket: Socket? = null
    private var reader: BufferedReader? = null
    private var writer: BufferedWriter? = null
    private var isRunning = false

    private val json = Json { ignoreUnknownKeys = true }

    override fun attachLog(logger: (String) -> Unit) {
        log = logger
    }

    override suspend fun start(config: McConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val addr = config.boreServerAddr.trim()
            if (addr.isBlank()) {
                return@withContext Result.failure(IllegalArgumentException("请填写 bore 服务端地址"))
            }

            val (host, port) = parseAddr(addr)
            val localPort = config.localPort

            log("[bore] 正在连接 $host:$port ...")
            updateState(TunnelStatus.Starting, errorMessage = "正在连接 bore 服务端...")

            // 1. 建立控制通道
            val socket = Socket(host, port)
            socket.soTimeout = 30000 // 30s read timeout
            controlSocket = socket
            reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            writer = BufferedWriter(OutputStreamWriter(socket.getOutputStream()))
            isRunning = true

            // 2. 发送 Hello
            sendMessage("""{"Hello":$localPort}""")
            log("[bore] 已发送 Hello(localPort=$localPort)")

            // 3. 等待 Hello 响应
            val response = readMessage(reader!!)
                ?: throw IllegalStateException("bore 服务端无响应")
            val responseJson = json.parseToJsonElement(response).jsonObject
            val assignedPort = responseJson["Hello"]?.jsonPrimitive?.int
                ?: throw IllegalStateException("bore 服务端返回异常: $response")

            val publicUrl = "$host:$assignedPort"
            log("[bore] 隧道已建立，公网地址: $publicUrl")
            updateState(TunnelStatus.Running, publicUrl = publicUrl)

            // 4. 监听 Connection 消息，为每个外部连接建立转发
            while (isRunning) {
                val msg = readMessage(reader!!) ?: break
                val msgObj = json.parseToJsonElement(msg).jsonObject

                when {
                    msgObj.containsKey("Connection") -> {
                        val connId = msgObj["Connection"]!!.jsonPrimitive.content
                        log("[bore] 外部连接到达: $connId")
                        launch { handleConnection(host, port, connId, localPort) }
                    }
                    msgObj.containsKey("Heartbeat") -> {
                        // ignore heartbeats
                    }
                    msgObj.containsKey("Error") -> {
                        val err = msgObj["Error"]!!.jsonPrimitive.content
                        log("[bore] 服务端错误: $err")
                        updateState(TunnelStatus.Failed, errorMessage = err)
                        break
                    }
                }
            }

            // 循环退出 = 连接断开
            if (isRunning) {
                updateState(TunnelStatus.Failed, errorMessage = "bore 连接断开")
            }
            Result.success(Unit)
        } catch (e: Exception) {
            log("[bore] 启动失败: ${e.message}")
            updateState(TunnelStatus.Failed, errorMessage = e.message ?: "未知错误")
            Result.failure(e)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        isRunning = false
        try {
            writer?.close()
            reader?.close()
            controlSocket?.close()
        } catch (_: Exception) {}
        writer = null
        reader = null
        controlSocket = null
        updateState(TunnelStatus.Stopped)
        log("[bore] 隧道已停止")
    }

    // ── 内部方法 ──────────────────────────────────────────────

    private fun updateState(status: TunnelStatus, publicUrl: String = _state.value.publicUrl, errorMessage: String = "") {
        _state.value = TunnelState(
            isRunning = status == TunnelStatus.Running,
            publicUrl = publicUrl,
            status = status,
            errorMessage = errorMessage,
            activeType = TunnelType.Bore
        )
    }

    /** 处理一个外部连接：建立新 TCP 连接 → Accept → 双向转发 */
    private suspend fun handleConnection(host: String, port: Int, connId: String, localPort: Int) {
        withContext(Dispatchers.IO) {
            try {
                val connSocket = Socket(host, port)
                val connWriter = BufferedWriter(OutputStreamWriter(connSocket.getOutputStream()))

                // 发送 Accept
                val acceptMsg = """{"Accept":"$connId"}"""
                connWriter.write(acceptMsg)
                connWriter.write(0) // null byte
                connWriter.flush()

                // 连接本地服务
                val localSocket = Socket("127.0.0.1", localPort)

                // 双向转发
                val t1 = Thread { pipe(connSocket.getInputStream(), localSocket.getOutputStream(), "bore→local") }
                val t2 = Thread { pipe(localSocket.getInputStream(), connSocket.getOutputStream(), "local→bore") }
                t1.isDaemon = true; t2.isDaemon = true
                t1.start(); t2.start()

                // 等待任一线程结束
                t1.join()
                connSocket.close()
                localSocket.close()
            } catch (e: Exception) {
                log("[bore] 连接 $connId 处理失败: ${e.message}")
            }
        }
    }

    /** 简单的双向管道复制 */
    private fun pipe(src: java.io.InputStream, dst: java.io.OutputStream, tag: String) {
        try {
            val buf = ByteArray(8192)
            var n: Int
            while (src.read(buf).also { n = it } != -1) {
                dst.write(buf, 0, n)
                dst.flush()
            }
        } catch (_: Exception) {}
    }

    /** 发送以 null 结尾的 JSON 消息 */
    private fun sendMessage(json: String) {
        writer?.let {
            it.write(json)
            it.write(0) // null byte terminator
            it.flush()
        }
    }

    /** 读取一条以 null 结尾的 JSON 消息 */
    private fun readMessage(reader: BufferedReader): String? {
        val sb = StringBuilder()
        var ch: Int
        while (true) {
            ch = reader.read()
            if (ch == -1) return if (sb.isEmpty()) null else sb.toString()
            if (ch == 0) break // null byte
            sb.append(ch.toChar())
        }
        return sb.toString()
    }

    /** 解析 "host:port" 格式地址，默认端口 7835 */
    private fun parseAddr(addr: String): Pair<String, Int> {
        val colon = addr.lastIndexOf(':')
        return if (colon > 0) {
            val host = addr.substring(0, colon)
            val port = addr.substring(colon + 1).toIntOrNull() ?: 7835
            Pair(host, port)
        } else {
            Pair(addr, 7835)
        }
    }

    companion object {
        /** HMAC-SHA256 计算（预留给认证功能） */
        @Suppress("unused")
        private fun hmacSha256(key: ByteArray, data: ByteArray): String {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data).joinToString("") { "%02x".format(it) }
        }
    }
}
