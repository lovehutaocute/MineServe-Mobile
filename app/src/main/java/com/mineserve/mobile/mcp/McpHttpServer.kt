package com.mineserve.mobile.mcp

import android.util.Log
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 极简 HTTP/1.1 服务器：仅供应用内嵌 MCP 端点使用，不引入第三方依赖。
 *  - ServerSocket 绑定 0.0.0.0:port，线程池每连接一线程，keep-alive 循环
 *  - 支持 Content-Length 与 chunked 请求体（上限 MAX_BODY_BYTES）、Expect: 100-continue
 *  - 所有 I/O 都在独立线程池执行，禁止在主线程调用 start()
 */
class McpHttpServer(
    private val port: Int,
    private val handler: Handler
) {
    fun interface Handler {
        fun handle(request: Request): Response
    }

    class Request(
        val method: String,
        /** 原始 target，含查询串 */
        val path: String,
        /** 头部名统一小写 */
        val headers: Map<String, String>,
        val body: ByteArray,
        val httpVersion: String
    )

    class Response(
        val status: Int,
        val reason: String,
        val contentType: String?,
        val body: ByteArray,
        val extraHeaders: List<Pair<String, String>> = emptyList()
    ) {
        companion object {
            fun json(status: Int, body: String, extraHeaders: List<Pair<String, String>> = emptyList()) = Response(
                status, reasonFor(status), "application/json; charset=utf-8",
                body.toByteArray(Charsets.UTF_8), extraHeaders
            )

            fun reasonFor(status: Int): String = when (status) {
                200 -> "OK"
                202 -> "Accepted"
                204 -> "No Content"
                400 -> "Bad Request"
                401 -> "Unauthorized"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                408 -> "Request Timeout"
                413 -> "Payload Too Large"
                500 -> "Internal Server Error"
                else -> "Unknown"
            }
        }
    }

    private class MalformedRequestException(message: String) : IOException(message)
    private class BodyTooLargeException : IOException("request body too large")

    private val running = AtomicBoolean(false)
    private var serverSocket: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool { r ->
        Thread(r, "mcp-http").apply { isDaemon = true }
    }

    /** 绑定失败原因（端口占用等），供 UI 展示 */
    @Volatile
    var lastBindError: String? = null
        private set

    val isRunning: Boolean get() = running.get()

    /** 实际绑定的端口（port 传 0 时为系统分配的端口） */
    val boundPort: Int? get() = serverSocket?.localPort

    /** 绑定并开始监听；返回 false 表示绑定失败 */
    fun start(): Boolean {
        if (!running.compareAndSet(false, true)) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", port))
            serverSocket = ss
            lastBindError = null
            pool.execute { acceptLoop(ss) }
            true
        } catch (e: Exception) {
            lastBindError = e.message ?: e.javaClass.simpleName
            running.set(false)
            false
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        runCatching { serverSocket?.close() }
        serverSocket = null
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running.get()) {
            val client = try {
                ss.accept()
            } catch (e: Exception) {
                if (running.get()) Log.w(TAG, "accept failed: ${e.message}")
                continue
            }
            pool.execute { serve(client) }
        }
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = READ_TIMEOUT_MS
            socket.tcpNoDelay = true
            val input = BufferedInputStream(socket.getInputStream())
            val output = socket.getOutputStream()
            while (running.get()) {
                val request = try {
                    readRequest(input, output)
                } catch (e: MalformedRequestException) {
                    writeResponse(output, Response.json(400, "{\"error\":\"bad request\"}"), false)
                    break
                } catch (e: BodyTooLargeException) {
                    writeResponse(output, Response.json(413, "{\"error\":\"payload too large\"}"), false)
                    break
                } catch (e: IOException) {
                    break // 读超时 / 对端断开
                }
                if (request == null) break
                val connection = request.headers["connection"]?.lowercase()
                val keepAlive = if (request.httpVersion == "HTTP/1.0") {
                    connection == "keep-alive"
                } else {
                    connection != "close"
                }
                val response = try {
                    handler.handle(request)
                } catch (e: Exception) {
                    Log.e(TAG, "handler error", e)
                    Response.json(500, "{\"error\":\"internal error\"}")
                }
                writeResponse(output, response, keepAlive)
                if (!keepAlive) break
            }
        } catch (e: Exception) {
            Log.d(TAG, "connection ended: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    /** 返回 null 表示连接已结束（EOF） */
    private fun readRequest(input: InputStream, output: OutputStream): Request? {
        // 容忍请求前的空行（RFC 7230 §3.5）
        var requestLine: String? = null
        repeat(4) {
            if (requestLine == null) requestLine = readLine(input)?.takeIf { it.isNotEmpty() }
        }
        val line = requestLine ?: return null
        val parts = line.split(" ")
        if (parts.size != 3 || !parts[2].startsWith("HTTP/")) {
            throw MalformedRequestException("bad request line: $line")
        }
        val headers = HashMap<String, String>()
        var headerBytes = 0
        while (true) {
            val headerLine = readLine(input) ?: throw MalformedRequestException("eof in headers")
            if (headerLine.isEmpty()) break
            headerBytes += headerLine.length
            if (headerBytes > MAX_HEADER_BYTES) throw BodyTooLargeException()
            val idx = headerLine.indexOf(':')
            if (idx > 0) {
                headers[headerLine.substring(0, idx).trim().lowercase()] = headerLine.substring(idx + 1).trim()
            }
        }
        if (headers["expect"]?.contains("100-continue", ignoreCase = true) == true) {
            output.write("HTTP/1.1 100 Continue\r\n\r\n".toByteArray(Charsets.US_ASCII))
            output.flush()
        }
        val body = if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            readChunked(input)
        } else {
            val length = headers["content-length"]?.toLongOrNull() ?: 0L
            if (length > MAX_BODY_BYTES) throw BodyTooLargeException()
            if (length > 0) readFully(input, length.toInt()) else ByteArray(0)
        }
        return Request(parts[0].uppercase(), parts[1], headers, body, parts[2])
    }

    private fun readChunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val sizeLine = readLine(input) ?: throw MalformedRequestException("eof in chunk size")
            val size = sizeLine.substringBefore(';').trim().toIntOrNull(16)
                ?: throw MalformedRequestException("bad chunk size: $sizeLine")
            if (out.size() + size > MAX_BODY_BYTES) throw BodyTooLargeException()
            if (size == 0) {
                while (true) {
                    val trailer = readLine(input) ?: break
                    if (trailer.isEmpty()) break
                }
                return out.toByteArray()
            }
            out.write(readFully(input, size))
            readLine(input) ?: throw MalformedRequestException("eof after chunk data")
        }
    }

    private fun readFully(input: InputStream, count: Int): ByteArray {
        val buf = ByteArray(count)
        var off = 0
        while (off < count) {
            val n = input.read(buf, off, count - off)
            if (n == -1) throw MalformedRequestException("eof in body")
            off += n
        }
        return buf
    }

    private fun readLine(input: InputStream, maxBytes: Int = MAX_HEADER_BYTES): String? {
        val buf = ByteArrayOutputStream(128)
        while (true) {
            val b = input.read()
            if (b == -1) {
                return if (buf.size() == 0) null else buf.toString("UTF-8")
            }
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.write(b)
            if (buf.size() > maxBytes) throw MalformedRequestException("line too long")
        }
        return buf.toString("UTF-8")
    }

    private fun writeResponse(out: OutputStream, response: Response, keepAlive: Boolean) {
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append(' ').append(response.reason).append("\r\n")
            response.contentType?.let { append("Content-Type: ").append(it).append("\r\n") }
            append("Content-Length: ").append(response.body.size).append("\r\n")
            append("Connection: ").append(if (keepAlive) "keep-alive" else "close").append("\r\n")
            response.extraHeaders.forEach { (name, value) -> append(name).append(": ").append(value).append("\r\n") }
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        if (response.body.isNotEmpty()) out.write(response.body)
        out.flush()
    }

    companion object {
        private const val TAG = "McpHttpServer"
        private const val READ_TIMEOUT_MS = 30_000
        private const val MAX_HEADER_BYTES = 32 * 1024

        /**
         * 请求体上限：upload_file 以 base64 分块传输大文件（模组/存档），
         * 单块建议 ≤16MB base64，此上限留出 JSON 开销余量。
         */
        private const val MAX_BODY_BYTES = 64 * 1024 * 1024
    }
}
