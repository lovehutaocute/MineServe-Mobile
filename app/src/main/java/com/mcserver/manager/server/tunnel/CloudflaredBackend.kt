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
 * 固定域名模式流程：一键登录 → 浏览器认证 → 创建 Tunnel → 启动
 */
class CloudflaredBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.Cloudflared) {

    override val watchdogTimeoutMs: Long = 60_000

    private val edgeIp = "198.41.192.7:7844"

    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    /** Cloudflare cert.pem 路径（tunnel login 成功后生成） */
    private val certPem: File
        get() = File(termux.installer.rootDir, "home/.cloudflared/cert.pem")

    /** Tunnel 凭证 JSON 路径（tunnel create 后生成） */
    private val credFile: File
        get() = File(tunnelDir, "mc-tunnel.json")

    /** 当前 Tunnel 名称（从 McConfig 读取） */
    private var tunnelName: String = "mc-tunnel"

    override suspend fun ensureBinary(): String? {
        return binaryManager.ensureCloudflared()
    }

    // ── 固定域名认证流程 ─────────────────────────────────────

    /** 是否已完成 Cloudflare 认证（cert.pem 存在） */
    fun isAuthenticated(): Boolean = certPem.exists()

    /** 是否已创建 Tunnel（mc-tunnel.json 存在） */
    fun isTunnelCreated(): Boolean = credFile.exists()

    /**
     * 一键登录：运行 cloudflared tunnel login，捕获授权 URL。
     * 用户在浏览器中打开 URL 完成认证后 cert.pem 自动生成。
     */
    suspend fun loginTunnel(): String? = withContext(Dispatchers.IO) {
        val binary = ensureBinary() ?: return@withContext null
        log("正在启动 cloudflared tunnel login...")
        try {
            val proc = termux.execRaw("tunnel-login", binary, "tunnel", "login")
            val reader = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream))
            var url: String? = null
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
            if (url != null) log("登录 URL: $url，请在浏览器中打开")
            else { log("未能获取登录 URL，请重试"); proc.destroy() }
            url
        } catch (e: Exception) {
            log("tunnel login 失败: ${e.message}")
            null
        }
    }

    /**
     * 创建 Cloudflare Tunnel（固定域名模式第二步）。
     * 前置条件：已通过 loginTunnel 完成认证（cert.pem 已生成）。
     * 创建成功后自动添加 DNS 路由（cloudflared tunnel route dns）。
     */
    suspend fun createTunnel(config: McConfig): Boolean = withContext(Dispatchers.IO) {
        val binary = ensureBinary() ?: return@withContext false
        val name = config.cloudflareTunnelName.ifBlank { "mc-tunnel" }
        val domain = config.cloudflareDomain
        if (!isAuthenticated()) {
            log("未完成 Cloudflare 认证，请先点击「一键登录」")
            return@withContext false
        }
        if (isTunnelCreated()) {
            log("Tunnel 已创建: ${credFile.absolutePath}")
            return@withContext true
        }
        log("正在创建 Cloudflare Tunnel '$name'...")
        try {
            // 1. 先查是否已有同名 Tunnel
            val existingId = findExistingTunnel(binary, name)
            if (existingId != null) {
                val defaultCred = File(termux.installer.rootDir,
                    "home/.cloudflared/$existingId.json")
                if (defaultCred.exists()) {
                    defaultCred.copyTo(credFile, overwrite = true)
                    log("已恢复现有 Tunnel 凭证 (ID=$existingId)")
                } else {
                    log("Tunnel 已存在 (ID=$existingId)，正在重新获取凭证...")
                    val proc = termux.execRaw("tunnel-token", binary, "tunnel", "token", name)
                    val output = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream)).readText()
                    proc.waitFor()
                    if (output.contains("\"id\"")) {
                        credFile.writeText(output.trim())
                        log("Tunnel 凭证已保存")
                    } else {
                        log("获取凭证失败: $output")
                        return@withContext false
                    }
                }
            } else {
                // 2. 不存在 → 创建新 Tunnel
                val proc = termux.execRaw("tunnel-create", binary, "tunnel", "create", name)
                val output = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream)).readText()
                proc.waitFor()
                if (output.contains("\"id\"")) {
                    // JSON 格式输出
                    credFile.writeText(output.trim())
                    log("Tunnel 已创建，凭证已保存")
                } else if (output.contains("Created tunnel")) {
                    // 纯文本格式: "Created tunnel NAME with id UUID"
                    log("Tunnel 已创建: ${output.trim().take(200)}")
                    // 凭证保存在 ~/.cloudflared/<id>.json
                    if (!recoverCredentials(binary)) return@withContext false
                } else if (output.contains("already exists")) {
                    log("Tunnel 已存在于云端，尝试自动恢复...")
                    if (!recoverCredentials(binary)) return@withContext false
                } else {
                    log("创建 Tunnel 失败: $output")
                    return@withContext false
                }
            }

            // 3. 自动添加 DNS 路由
            if (domain.isNotBlank()) {
                val subdomain = domain.substringBefore(".")
                log("正在添加 DNS 路由: $subdomain → $name")
                val routeProc = termux.execRaw("tunnel-route-dns", binary,
                    "tunnel", "route", "dns", name, subdomain)
                val routeOutput = java.io.BufferedReader(java.io.InputStreamReader(routeProc.inputStream)).readText()
                routeProc.waitFor()
                log("DNS 路由结果: ${routeOutput.take(200)}")
            }
            true
        } catch (e: Exception) {
            log("创建 Tunnel 失败: ${e.message}")
            false
        }
    }

    /** 查询 cloudflared tunnel list，返回 mc-tunnel 的 ID 或 null */
    private fun findExistingTunnel(binary: String, name: String): String? {
        try {
            val proc = termux.execRaw("tunnel-list", binary, "tunnel", "list")
            val output = java.io.BufferedReader(java.io.InputStreamReader(proc.inputStream)).readText()
            proc.waitFor()
            // 输出格式: "<id>  <name>  <created>  <connections>"
            // 例: "a1b2c3d4-...  mc-tunnel  ..."
            val regex = Regex("""([a-f0-9-]{30,})\s+${Regex.escape(name)}\s""")
            return regex.find(output)?.groupValues?.get(1)
        } catch (_: Exception) { return null }
    }

    /** 尝试恢复凭证：遍历 ~/.cloudflared/ 下所有 .json 文件 */
    private suspend fun recoverCredentials(binary: String): Boolean {
        val cfDir = File(termux.installer.rootDir, "home/.cloudflared")
        cfDir.listFiles()?.filter { it.extension == "json" && it.name != "cert.pem" }?.forEach { f ->
            try {
                val content = f.readText()
                if (content.contains("\"id\"") && content.contains("\"secret\"")) {
                    credFile.writeText(content)
                    log("已从 ${f.name} 恢复凭证")
                    return true
                }
            } catch (_: Exception) {}
        }
        return false
    }

    /**
     * 撤销 Cloudflare Tunnel（删除云端 Tunnel + 本地凭证）。
     */
    suspend fun deleteTunnel(): Boolean = withContext(Dispatchers.IO) {
        val binary = ensureBinary() ?: return@withContext false
        try {
            // 删除云端 Tunnel
            termux.execOnce(binary, "tunnel", "delete", "-f", tunnelName)
            // 删除本地凭证
            if (credFile.exists()) credFile.delete()
            log("Tunnel '$tunnelName' 已撤销")
            true
        } catch (e: Exception) {
            log("撤销 Tunnel 失败: ${e.message}")
            false
        }
    }

    /**
     * 取消 Cloudflare 认证（删除 cert.pem 和所有本地凭证）。
     */
    suspend fun revokeAuth(): Boolean = withContext(Dispatchers.IO) {
        try {
            if (certPem.exists()) certPem.delete()
            if (credFile.exists()) credFile.delete()
            File(termux.installer.rootDir, "home/.cloudflared")
                .listFiles()?.forEach { it.delete() }
            log("Cloudflare 认证已取消")
            true
        } catch (e: Exception) {
            log("取消认证失败: ${e.message}")
            false
        }
    }

    // ── 启动参数 ─────────────────────────────────────────────

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        if (config.cloudflareQuickTunnel) {
            log("Quick Tunnel 模式，端口 ${config.localPort}，--edge $edgeIp")
            return listOf(
                "--edge", edgeIp,
                "tunnel", "--url", "tcp://localhost:${config.localPort}"
            )
        } else {
            // 固定域名模式：检测凭证状态
            if (!isAuthenticated()) {
                throw IllegalStateException(
                    "未完成 Cloudflare 认证。请先点击「一键登录」在浏览器中完成认证，然后点击「创建 Tunnel」"
                )
            }
            if (!isTunnelCreated()) {
                throw IllegalStateException(
                    "Tunnel 尚未创建。请先点击「创建 Tunnel」生成凭证文件"
                )
            }
            val domain = config.cloudflareDomain
            tunnelName = config.cloudflareTunnelName.ifBlank { "mc-tunnel" }
            val configFile = File(tunnelDir, "cloudflared.yml")
            configFile.writeText(buildString {
                appendLine("tunnel: $tunnelName")
                appendLine("credentials-file: ${credFile.absolutePath}")
                appendLine("ingress:")
                appendLine("  - hostname: $domain")
                appendLine("    service: tcp://localhost:${config.localPort}")
                appendLine("  - hostname: '*'")
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
        return mapOf("GODEBUG" to "netdns=go")
    }

    override fun parsePublicUrl(line: String): String? {
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

    override fun onProcessStarted(config: McConfig) {
        if (!config.cloudflareQuickTunnel) {
            // Named Tunnel: 公网地址就是用户配置的域名，不输出 trycloudflare.com
            updateState(
                com.mcserver.manager.data.TunnelStatus.Running,
                publicUrl = config.cloudflareDomain
            )
            log("Named Tunnel 已启动，域名: ${config.cloudflareDomain}")
        }
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("cert.pem") || output.contains("origin cert") ->
                "缺少 Cloudflare 认证凭证。请先点击「一键登录」完成浏览器认证"
            output.contains("connection refused") ->
                "cloudflared 连接被拒，请检查网络"
            output.contains("trycloudflare.com") && output.contains("failed to request") ->
                "cloudflared 请求 Quick Tunnel 失败，请重试"
            output.contains("credentials") || output.contains("mc-tunnel.json") ->
                "Tunnel 凭证缺失：请先点击「创建 Tunnel」"
            output.contains("failed to resolve") ->
                "cloudflared 无法发现边缘节点，请尝试使用 frp"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
