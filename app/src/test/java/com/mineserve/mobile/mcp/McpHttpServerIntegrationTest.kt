package com.mineserve.mobile.mcp

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.InputStream
import java.net.Socket

/**
 * McpHttpServer 端到端集成测试：真实 socket → HTTP 解析 → MCP 分发 → 响应回读。
 * 覆盖 Content-Length / chunked 请求体、keep-alive、鉴权与状态码。
 * 注意：这些路径不能触发 android.util.Log（本地单测未 mock），失败路径会抛 not-mocked。
 */
class McpHttpServerIntegrationTest {

    private val token = "test-token"

    private lateinit var server: McpHttpServer
    private var port: Int = 0

    @Before
    fun setUp() {
        server = McpHttpServer(port = 0) { request -> dispatch(request) }
        assertTrue(server.start())
        port = server.boundPort ?: error("server not bound")
    }

    @After
    fun tearDown() {
        server.stop()
    }

    /** 与 McpServerManager.onRequest 相同的处理逻辑（去除 Android 依赖） */
    private fun dispatch(request: McpHttpServer.Request): McpHttpServer.Response {
        val path = request.path.substringBefore('?')
        if (request.method == "OPTIONS") {
            return McpHttpServer.Response(204, "No Content", null, ByteArray(0))
        }
        if (path != "/mcp") {
            return McpHttpServer.Response.json(404, "{\"error\":\"not found\"}")
        }
        val header = request.headers["authorization"]?.trim() ?: ""
        if (header != "Bearer $token") {
            return McpHttpServer.Response.json(401, "{\"error\":\"unauthorized\"}")
        }
        return when (request.method) {
            "POST" -> when (val outcome = McpProtocol.handle(
                request.body.toString(Charsets.UTF_8),
                serverVersion = "1.0.0-test",
                executor = { name, _ -> McpToolResult("ok:$name") }
            )) {
                is McpOutcome.Notification -> McpHttpServer.Response(202, "Accepted", null, ByteArray(0))
                is McpOutcome.Response -> McpHttpServer.Response.json(200, outcome.body.toString())
            }
            "GET", "DELETE" -> McpHttpServer.Response.json(
                405, "{\"error\":\"method not allowed\"}", listOf("Allow" to "POST, OPTIONS")
            )
            else -> McpHttpServer.Response.json(405, "{\"error\":\"method not allowed\"}")
        }
    }

    private fun jsonRequest(id: Int, method: String, params: String = ""): String {
        val body = buildString {
            append("""{"jsonrpc":"2.0","id":""")
            append(id)
            append(""","method":""")
            append("\"$method\"")
            if (params.isNotEmpty()) append(""","params":$params""")
            append("}")
        }
        return postRaw(body = body)
    }

    private fun postRaw(body: String, auth: String = "Bearer $token"): String = buildString {
        append("POST /mcp HTTP/1.1\r\n")
        append("Host: localhost\r\n")
        append("Content-Type: application/json\r\n")
        if (auth.isNotEmpty()) append("Authorization: ").append(auth).append("\r\n")
        append("Content-Length: ").append(body.toByteArray(Charsets.UTF_8).size).append("\r\n")
        append("\r\n")
        append(body)
    }

    /** 写入原始请求并完整读取一个响应，返回 (状态码, 响应体) */
    private fun exchange(raw: String, socket: Socket): Pair<Int, String> {
        socket.getOutputStream().write(raw.toByteArray(Charsets.US_ASCII))
        socket.getOutputStream().flush()
        val input = socket.getInputStream()
        val statusLine = input.readLine() ?: error("no response")
        val code = statusLine.split(" ")[1].toInt()
        var contentLength = 0
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Content-Length:", ignoreCase = true)) {
                contentLength = line.substringAfter(':').trim().toInt()
            }
        }
        val body = ByteArray(contentLength)
        var off = 0
        while (off < contentLength) {
            off += input.read(body, off, contentLength - off)
        }
        return code to String(body, Charsets.UTF_8)
    }

    private fun InputStream.readLine(): String? {
        val buf = StringBuilder()
        while (true) {
            val b = read()
            if (b == -1) return if (buf.isEmpty()) null else buf.toString()
            if (b == '\n'.code) break
            if (b != '\r'.code) buf.append(b.toChar())
        }
        return buf.toString()
    }

    private fun newConnection(): Socket {
        val socket = Socket("127.0.0.1", port)
        socket.soTimeout = 5_000
        return socket
    }

    @Test
    fun initializeOverRealSocketReturnsProtocolVersion() {
        newConnection().use { socket ->
            val (code, body) = exchange(jsonRequest(1, "initialize", """{"protocolVersion":"2025-03-26"}"""), socket)
            assertEquals(200, code)
            assertTrue(body.contains("\"protocolVersion\":\"2025-03-26\""))
            assertTrue(body.contains("\"result\""))
        }
    }

    @Test
    fun keepAliveHandlesSequentialRequestsOnSameConnection() {
        newConnection().use { socket ->
            val (code1, body1) = exchange(jsonRequest(1, "tools/list"), socket)
            val (code2, body2) = exchange(jsonRequest(2, "ping"), socket)
            assertEquals(200, code1)
            assertEquals(200, code2)
            assertTrue(body1.contains("\"tools\""))
            assertTrue(body2.contains("\"result\":{}"))
        }
    }

    @Test
    fun chunkedRequestBodyIsDecoded() {
        val body = """{"jsonrpc":"2.0","id":9,"method":"tools/list"}"""
        val chunk = body.toByteArray(Charsets.UTF_8)
        val raw = buildString {
            append("POST /mcp HTTP/1.1\r\n")
            append("Host: localhost\r\n")
            append("Authorization: Bearer $token\r\n")
            append("Transfer-Encoding: chunked\r\n")
            append("\r\n")
            append(chunk.size.toString(16)).append("\r\n")
            append(body)
            append("\r\n0\r\n\r\n")
        }
        newConnection().use { socket ->
            val (code, response) = exchange(raw, socket)
            assertEquals(200, code)
            assertTrue(response.contains("get_server_status"))
        }
    }

    @Test
    fun missingTokenIsUnauthorized() {
        newConnection().use { socket ->
            val (code, body) = exchange(postRaw("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", auth = ""), socket)
            assertEquals(401, code)
            assertTrue(body.contains("unauthorized"))
        }
    }

    @Test
    fun wrongTokenIsUnauthorized() {
        newConnection().use { socket ->
            val (code, _) = exchange(postRaw("""{"jsonrpc":"2.0","id":1,"method":"ping"}""", auth = "Bearer wrong"), socket)
            assertEquals(401, code)
        }
    }

    @Test
    fun unknownPathIsNotFound() {
        val raw = buildString {
            append("GET /other HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\nContent-Length: 0\r\n\r\n")
        }
        newConnection().use { socket ->
            val (code, _) = exchange(raw, socket)
            assertEquals(404, code)
        }
    }

    @Test
    fun getMethodIsNotAllowed() {
        val raw = buildString {
            append("GET /mcp HTTP/1.1\r\nHost: localhost\r\nAuthorization: Bearer $token\r\nContent-Length: 0\r\n\r\n")
        }
        newConnection().use { socket ->
            val (code, _) = exchange(raw, socket)
            assertEquals(405, code)
        }
    }

    @Test
    fun notificationIsAcceptedWith202AndEmptyBody() {
        newConnection().use { socket ->
            val raw = postRaw("""{"jsonrpc":"2.0","method":"notifications/initialized"}""")
            val (code, body) = exchange(raw, socket)
            assertEquals(202, code)
            assertEquals("", body)
        }
    }

    @Test
    fun emptyBodyRequestIsHandledAsParseError() {
        newConnection().use { socket ->
            val (code, body) = exchange(postRaw(body = ""), socket)
            assertEquals(200, code)
            assertTrue(body.contains("-32700"))
        }
    }
}
