package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File

/**
 * Cloudflare Tunnel (cloudflared) 隧道后端。
 *
 * 支持两种模式：
 *  - Quick Tunnel: 零配置，自动分配 *.trycloudflare.com 地址（每次重启地址变化）
 *  - Named Tunnel: 绑定自有域名（需将域名 DNS 托管到 Cloudflare）
 *
 * cloudflared 有两个来源：
 *  - apt 版 (GOOS=android): DNS 正常，无需 --edge
 *  - GitHub 版 (GOOS=linux): Android 无法解析 DNS，需用 --edge 指定 Cloudflare IP
 */
class CloudflaredBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.Cloudflared) {

    /** Cloudflare 边缘节点 IP（GitHub 版绕过 DNS 用） */
    private val edgeIps = listOf(
        "198.41.192.7:80",
        "198.41.192.47:80",
        "198.41.200.7:80",
        "198.41.200.47:80"
    )

    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    override suspend fun ensureBinary(): String? {
        return binaryManager.ensureCloudflared()
    }

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        val needEdge = !binary.startsWith("${termux.installer.rootDir.absolutePath}/bin/")
        val args = mutableListOf<String>()

        if (config.cloudflareQuickTunnel) {
            // Quick Tunnel: 零配置
            if (needEdge) {
                args.addAll(listOf("--edge", edgeIps.joinToString(",")))
            }
            args.addAll(listOf("tunnel", "--url", "tcp://localhost:${config.localPort}"))
            log("Quick Tunnel 模式，本地端口 ${config.localPort}")
        } else {
            // Named Tunnel: 需配置文件
            val domain = config.cloudflareDomain
            val configFile = File(tunnelDir, "cloudflared.yml")
            configFile.writeText(buildString {
                appendLine("tunnel: mc-tunnel")
                appendLine("credentials-file: ${tunnelDir.absolutePath}/mc-tunnel.json")
                appendLine("ingress:")
                appendLine("  - hostname: $domain")
                appendLine("    service: tcp://localhost:${config.localPort}")
                appendLine("  - service: http_status:404")
            })
            if (needEdge) {
                args.addAll(listOf("--edge", edgeIps.joinToString(",")))
            }
            args.addAll(listOf("tunnel", "--config", configFile.absolutePath, "run"))
            log("Named Tunnel 模式，域名: $domain")
        }

        return args
    }

    override fun buildEnv(config: McConfig): Map<String, String> {
        return mapOf("GODEBUG" to "netdns=go")
    }

    override fun parsePublicUrl(line: String): String? {
        // Quick Tunnel 输出: "INF | https://xxx.trycloudflare.com |"
        val regex = Regex("https://([a-z0-9-]+)\\.trycloudflare\\.com")
        val m = regex.find(line)
        val sub = m?.groupValues?.getOrNull(1)
        if (m != null && sub != null && sub != "api" && sub != "www") return m.value
        return null
    }

    override fun killProcess() {
        termux.execOnce("pkill", "-f", "cloudflared")
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("lookup") || output.contains("connection refused") ->
                "cloudflared DNS 解析失败。建议使用 Quick Tunnel 模式或换用 frp"
            output.contains("trycloudflare.com") && output.contains("failed to request") ->
                "cloudflared 请求 Quick Tunnel 失败，请检查网络后重试"
            output.contains("credentials") ->
                "凭证文件缺失：请将 mc-tunnel.json 放到 ${tunnelDir.absolutePath}/"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
