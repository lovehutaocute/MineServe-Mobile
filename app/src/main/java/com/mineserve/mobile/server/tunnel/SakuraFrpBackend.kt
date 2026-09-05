package com.mineserve.mobile.server.tunnel

import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File

/**
 * SakuraFrp 隧道后端。
 *
 * 流程：用用户 Token 调 api.natfrp.com/v4 的 /tunnel/config 拉取官方成品
 * TOML 配置，写入文件后交给本应用自带的上游原版 frpc 运行——无需自建服务器。
 */
class SakuraFrpBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.SakuraFrp) {

    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    private var lastConfigText: String = ""

    override suspend fun ensureBinary(): String? = binaryManager.ensureFrp()

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        val token = config.sakuraToken.trim()
        if (token.isBlank()) {
            throw IllegalArgumentException("请先填写 SakuraFrp 访问令牌（Token），可在 natfrp.com 用户面板获取")
        }
        val tunnelId = config.sakuraTunnelId.trim()
        if (tunnelId.isBlank()) {
            throw IllegalArgumentException("请先在隧道列表中选择要启动的隧道")
        }
        // 拉取官方成品配置（阻塞 HTTP，调用方已在 IO 线程）
        val configText = ensureConsoleLog(SakuraFrpApi.tunnelConfig(token, tunnelId))
        lastConfigText = configText
        val configFile = File(tunnelDir, "frpc-sakura.toml")
        configFile.writeText(configText)
        log("SakuraFrp 配置已写入 (${configText.length} 字符)")
        return listOf("-c", configFile.absolutePath)
    }

    override fun parsePublicUrl(line: String): String? = null

    /** 从配置文本解析 serverAddr + remotePort 作为公网地址显示 */
    override fun onProcessStarted(config: McConfig) {
        val toml = lastConfigText
        val addr = Regex("""serverAddr\s*=\s*"([^"]+)"""").find(toml)?.groupValues?.get(1)
        val port = Regex("""remotePort\s*=\s*(\d+)""").find(toml)?.groupValues?.get(1)
            ?: config.localPort.toString()
        if (addr != null) {
            updateState(
                com.mineserve.mobile.data.TunnelStatus.Running,
                publicUrl = "$addr:$port"
            )
        } else {
            updateState(com.mineserve.mobile.data.TunnelStatus.Running)
        }
    }

    override fun killProcess() {
        termux.execOnce("pkill", "-f", "frpc")
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("登录失败") || output.contains("未登录") || output.contains("token") ->
                "SakuraFrp 认证失败：请检查 Token 是否正确"
            output.contains("tunnel not found") || output.contains("不存在") ->
                "隧道不存在或已被删除，请重新拉取隧道列表"
            output.contains("connection refused") || output.contains("timeout") ->
                "无法连接 SakuraFrp 节点，请稍后重试或更换节点"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }

    /** 成品配置若未指定日志输出，在顶层补 `log.to = "console"`（须在第一个 [[proxies]] 之前）。 */
    private fun ensureConsoleLog(config: String): String {
        if (config.contains("log.to")) return config
        val index = config.indexOf("[[")
        if (index < 0) return "$config\nlog.to = \"console\"\n"
        return config.substring(0, index) + "log.to = \"console\"\n" + config.substring(index)
    }
}
