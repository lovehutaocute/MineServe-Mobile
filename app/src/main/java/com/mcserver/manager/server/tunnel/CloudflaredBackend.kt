package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Cloudflare Tunnel (cloudflared) 隧道后端。
 *
 * 支持两种模式：
 *  - Quick Tunnel: 零配置，自动分配 *.trycloudflare.com 地址
 *  - Named Tunnel: 绑定自有域名（需 DNS 托管到 Cloudflare）
 *
 * DNS 策略：
 *  Android 下 DNS SRV 查询不可用（[::1]:53 无服务），edge discovery 会失败。
 *  使用单个 --edge IP（QUIC 端口 7844）提供初始连接，绕过 DNS SRV 发现。
 *  单个 IP cloudflared 能正确处理（多 IP 逗号拼接有 cloudflared 2026.7.3 Bug）。
 */
class CloudflaredBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.Cloudflared) {

    /** cloudflared 看门狗超时 */
    override val watchdogTimeoutMs: Long = 60_000

    /** Cloudflare 边缘节点 IP，QUIC 端口 7844，用于 --edge 绕过 DNS SRV */
    private val edgeIp = "198.41.192.7:7844"

    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    override suspend fun ensureBinary(): String? {
        return binaryManager.ensureCloudflared()
    }

    /**
     * Cloudflare Tunnel 一键登录。
     * 执行 `cloudflared tunnel login`，捕获输出的 URL 后立即返回。
     * 进程在后台继续运行直到用户完成浏览器认证。
     *
     * @return 登录 URL，失败返回 null
     */
    suspend fun loginTunnel(): String? = withContext(Dispatchers.IO) {
        val binary = ensureBinary() ?: return@withContext null
        log("正在启动 cloudflared tunnel login...")
        try {
            val proc = termux.execRaw("tunnel-login", binary, "tunnel", "login")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            var url: String? = null

            // 只读前 30 行或 15 秒内获取 URL，不等待进程退出
            val startTime = System.currentTimeMillis()
            var lineCount = 0
            while (url == null && lineCount < 30 &&
                (System.currentTimeMillis() - startTime) < 15_000) {
                val line = reader.readLine() ?: break
                log(line)
                lineCount++
                if (line.contains("https://") && line.contains("cloudflare")) {
                    url = line.substringAfter("https://").let { "https://$it" }.trim()
                }
            }
            reader.close()

            if (url != null) {
                log("登录 URL: $url，请在浏览器中打开")
            } else {
                log("未能获取登录 URL，请重试")
                proc.destroy()
            }
            url
        } catch (e: Exception) {
            log("tunnel login 失败: ${e.message}")
            null
        }
    }

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        if (config.cloudflareQuickTunnel) {
            // Quick Tunnel: --edge 单个 IP 绕过 DNS SRV 发现
            log("Quick Tunnel 模式，端口 ${config.localPort}，--edge $edgeIp")
            return listOf(
                "--edge", edgeIp,
                "tunnel", "--url", "tcp://localhost:${config.localPort}"
            )
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
            return listOf(
                "--edge", edgeIp,
                "tunnel", "--config", configFile.absolutePath, "run"
            )
        }
    }

    override fun buildEnv(config: McConfig): Map<String, String> {
        // netdns=go: 使用 Go 内置 DNS 解析器（读 /etc/resolv.conf）
        // fixDns 已写入 nameserver 8.8.8.8 到多个路径
        return mapOf("GODEBUG" to "netdns=go")
    }

    override fun parsePublicUrl(line: String): String? {
        // Quick Tunnel 输出: "https://xxx.trycloudflare.com"
        // 返回纯域名（Minecraft 直接连接需要格式 xxx.trycloudflare.com）
        val regex = Regex("https://([a-z0-9-]+)\\.trycloudflare\\.com")
        val m = regex.find(line)
        val sub = m?.groupValues?.getOrNull(1)
        if (m != null && sub != null && sub != "api" && sub != "www")
            return "${sub}.trycloudflare.com"
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
            output.contains("failed to resolve") ->
                "cloudflared 无法发现边缘节点，请尝试使用 frp"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
