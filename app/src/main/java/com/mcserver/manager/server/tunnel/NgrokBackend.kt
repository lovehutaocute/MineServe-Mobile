package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime

/**
 * ngrok 隧道后端。
 *
 * 默认使用 TCP 模式（适合 Minecraft Java 版直连），
 * 地址格式: 0.tcp.ngrok.io:port
 *
 * 首次使用需在 ngrok.com 注册获取 authtoken。
 */
class NgrokBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.Ngrok) {

    override suspend fun ensureBinary(): String? {
        return binaryManager.ensure("ngrok")
    }

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        val authtoken = config.ngrokAuthtoken.trim()
        if (authtoken.isBlank()) {
            throw IllegalArgumentException("ngrok authtoken 未配置，请从 ngrok.com 获取")
        }

        // 先配置 authtoken（幂等操作）
        termux.execOnce(binary, "config", "add-authtoken", authtoken)

        // TCP 模式，MC Java 版直连
        return listOf("tcp", config.localPort.toString())
    }

    override fun buildEnv(config: McConfig): Map<String, String> {
        // cgo resolver: A/AAAA 查询走 Android 系统 DNS
        return mapOf("GODEBUG" to "netdns=cgo")
    }

    override fun parsePublicUrl(line: String): String? {
        // 输出格式: "Forwarding tcp://0.tcp.ngrok.io:12345 -> 127.0.0.1:25565"
        val regex = Regex("Forwarding\\s+(tcp://[a-z0-9.]+:\\d+)")
        return regex.find(line)?.groupValues?.getOrNull(1)
    }

    override fun killProcess() {
        termux.execOnce("pkill", "-f", "ngrok")
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("authtoken", ignoreCase = true) ->
                "ngrok authtoken 无效或未配置，请在设置中填入有效的 authtoken"
            output.isBlank() || output.contains("timeout", ignoreCase = true) ->
                "ngrok 网络超时，请检查网络连接后重试"
            output.contains("limit", ignoreCase = true) ->
                "ngrok 免费层限制已达上限，请稍后重试或升级套餐"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
