package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File

/**
 * frp 隧道后端 — 自建服务器，功能最全。
 *
 * 支持两种配置方式：
 *  1. 粘贴完整 frpc.toml 文本
 *  2. 导入 frpc.toml 文件
 *
 * 通过 Termux apt 安装 frpc 客户端，直接使用用户提供的 TOML 配置。
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
        val rawToml = config.frpConfigText.trim()
        if (rawToml.isBlank()) {
            throw IllegalArgumentException("请粘贴完整的 frpc.toml 配置文本或导入配置文件")
        }

        // 自动移除 autoTLS 字段（旧版 frpc 不识别该字段会导致启动失败）
        val toml = sanitizeFrpConfig(rawToml)

        val configFile = File(tunnelDir, "frpc.toml")
        configFile.writeText(toml)
        log("frpc 配置已写入 (${toml.length} 字符)")

        return listOf("-c", configFile.absolutePath)
    }

    /**
     * 自动删除配置中的 autoTLS 字段：按行过滤，删除键名为 autoTLS 的配置行
     * （支持 `autoTLS = true` 及前导空格/大小写变体）。
     * 旧版 frpc 不识别该字段会启动报错，删除后保持兼容。
     */
    private fun sanitizeFrpConfig(toml: String): String {
        val lines = toml.lines()
        val kept = lines.filterNot { line ->
            line.trim().substringBefore('=').trim().equals("autoTLS", ignoreCase = true)
        }
        if (kept.size != lines.size) {
            log("已自动移除 autoTLS 字段（旧版 frpc 不兼容）")
        }
        return kept.joinToString("\n")
    }

    override fun parsePublicUrl(line: String): String? = null

    /** 从配置文本解析 serverAddr 作为公网地址显示 */
    override fun onProcessStarted(config: McConfig) {
        val toml = config.frpConfigText
        val addr = Regex("""serverAddr\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
        val port = Regex("""remotePort\s*=\s*(\d+)""").find(toml)?.groupValues?.get(1)
            ?: config.localPort.toString()
        if (addr != null) {
            updateState(
                com.mcserver.manager.data.TunnelStatus.Running,
                publicUrl = "$addr:$port"
            )
        } else {
            updateState(com.mcserver.manager.data.TunnelStatus.Running)
        }
    }

    override fun killProcess() {
        termux.execOnce("pkill", "-f", "frpc")
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("unknown field") || output.contains("unmarshal") ->
                "frpc 版本过旧，不支持配置中的字段（如 autoTLS）。正在自动下载最新版 frpc，请重试。"
            output.contains("connection refused") ->
                "无法连接 frp 服务端，请检查配置中的 serverAddr 和 serverPort 是否正确"
            output.contains("token") || output.contains("auth") ->
                "认证失败：Token 与服务端不匹配"
            output.contains("bind") || output.contains("address already in use") ->
                "端口已被占用，请更换 remotePort"
            output.contains("timeout") ->
                "连接超时，请检查服务端地址是否可达"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
