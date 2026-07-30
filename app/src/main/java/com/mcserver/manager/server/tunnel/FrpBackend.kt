package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File

/**
 * frp 隧道后端 — 自建服务器，功能最全。
 *
 * 通过 Termux apt 安装 frpc 客户端，动态生成 TOML 配置，
 * 连接用户自建的 frps 服务端。
 *
 * 配置格式 (TOML):
 *   serverAddr = "..."
 *   serverPort = 7000
 *   auth.token = "..."
 *   [[proxies]]
 *   name = "mc-server"
 *   type = "tcp"
 *   localIP = "127.0.0.1"
 *   localPort = 25565
 *   remotePort = 25565
 */
class FrpBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.Frp) {

    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    override suspend fun ensureBinary(): String? {
        return binaryManager.ensureFrp()
    }

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        val serverAddr = config.frpServerAddr.trim()
        val serverPort = config.frpServerPort
        val token = config.frpToken.trim()
        val localPort = config.localPort

        if (serverAddr.isBlank()) {
            throw IllegalArgumentException("请填写 frp 服务端地址")
        }

        // 生成 frpc.toml
        val toml = buildString {
            appendLine("serverAddr = \"$serverAddr\"")
            appendLine("serverPort = $serverPort")
            if (token.isNotEmpty()) {
                appendLine("auth.token = \"$token\"")
            }
            appendLine()
            appendLine("[[proxies]]")
            appendLine("name = \"mc-server\"")
            appendLine("type = \"tcp\"")
            appendLine("localIP = \"127.0.0.1\"")
            appendLine("localPort = $localPort")
            appendLine("remotePort = $localPort")
        }

        val configFile = File(tunnelDir, "frpc.toml")
        configFile.writeText(toml)
        log("frpc 配置已写入: $serverAddr:$serverPort")

        return listOf("-c", configFile.absolutePath)
    }

    override fun parsePublicUrl(line: String): String? {
        // frp 不输出公网 URL，公网地址为 serverAddr:remotePort
        // 已在 onProcessStarted 中设置
        return null
    }

    /** frp 启动后直接设置公网地址 */
    override fun onProcessStarted(config: McConfig) {
        val serverAddr = config.frpServerAddr.trim()
        val localPort = config.localPort
        updateState(
            com.mcserver.manager.data.TunnelStatus.Running,
            publicUrl = "$serverAddr:$localPort"
        )
    }

    override fun killProcess() {
        termux.execOnce("pkill", "-f", "frpc")
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("connection refused") ->
                "无法连接 frp 服务端，请检查服务端地址和端口是否正确，以及防火墙是否放行"
            output.contains("token") || output.contains("auth") ->
                "认证失败：Token 与服务端不匹配，请检查 Token 设置"
            output.contains("bind") || output.contains("address already in use") ->
                "端口已被占用，请更换服务端端口或检查是否有其他 frpc 进程在运行"
            output.contains("timeout") ->
                "连接超时，请检查服务端地址是否可达"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
