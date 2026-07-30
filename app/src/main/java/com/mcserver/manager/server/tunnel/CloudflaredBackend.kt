package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import java.io.File

/**
 * Cloudflare Tunnel (cloudflared) 隧道后端。
 *
 * 支持两种模式：
 *  - Quick Tunnel: 零配置，自动分配 *.trycloudflare.com 地址
 *  - Named Tunnel: 绑定自有域名（需 DNS 托管到 Cloudflare）
 *
 * DNS 策略：
 *  cgo resolver 对 A/AAAA 查询工作（api.cloudflare.com PASS），
 *  SRV 查询依赖 $PREFIX/etc/resolv.conf（TermuxBackend.fixDns 写入）。
 *  去掉 --edge 参数，让 cloudflared 正常 DNS 发现 edge IP。
 */
class CloudflaredBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.Cloudflared) {

    /** cloudflared 看门狗超时：延长到 60s，DNS 解析可能较慢 */
    override val watchdogTimeoutMs: Long = 60_000

    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    override suspend fun ensureBinary(): String? {
        return binaryManager.ensureCloudflared()
    }

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        if (config.cloudflareQuickTunnel) {
            log("Quick Tunnel 模式，端口 ${config.localPort}")
            return listOf("tunnel", "--url", "tcp://localhost:${config.localPort}")
        } else {
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
            log("Named Tunnel 模式，域名: $domain")
            return listOf("tunnel", "--config", configFile.absolutePath, "run")
        }
    }

    override fun buildEnv(config: McConfig): Map<String, String> {
        // cgo resolver: A/AAAA 查询走 Android 系统 DNS (getaddrinfo)
        // 纯 Go resolver (SRV) 回退读 $PREFIX/etc/resolv.conf
        return mapOf("GODEBUG" to "netdns=cgo")
    }

    override fun parsePublicUrl(line: String): String? {
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
            output.contains("connection refused") ->
                "cloudflared 连接被拒，请检查网络"
            output.contains("trycloudflare.com") && output.contains("failed to request") ->
                "cloudflared 请求 Quick Tunnel 失败，请重试"
            output.contains("credentials") ->
                "凭证文件缺失：请将 mc-tunnel.json 放到 ${tunnelDir.absolutePath}/"
            output.contains("failed to resolve any edge address") ->
                "cloudflared 无法发现边缘节点（DNS 问题），请尝试使用 frp"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
