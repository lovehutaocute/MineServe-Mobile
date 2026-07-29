package com.mcserver.manager.server

import android.util.Log
import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 内网穿透管理（重构版，参考 fatedier/frp、cloudflare/cloudflared、ngrok 架构）：
 *
 * 核心改进：
 *  1. 进程状态跟踪：暴露 [state] StateFlow，UI 实时感知运行状态和公网地址
 *  2. 公网地址提取：解析 cloudflared/ngrok 输出，自动捕获分配的 URL
 *  3. 二进制管理：启动前校验 frpc/cloudflared/ngrok 是否存在，缺失时自动下载
 *  4. 精准停止：只停止当前类型的隧道进程，不误杀其他类型
 *  5. 日志集成：所有隧道输出通过 [consoleFlow] 推送，带 [tunnel] 前缀
 *  6. 配置校验：启动前校验必填字段，避免无效配置导致进程立即退出
 *
 * 参考项目：
 *  - frp (fatedier/frp): TOML 配置，frps/frpc 架构，auth.token 认证
 *  - cloudflared (cloudflare/cloudflared): Quick Tunnel 零配置，输出 *.trycloudflare.com
 *  - ngrok: authtoken 认证，输出 Forwarding tcp://xxx:port
 *
 * 配置文件写入 home/tunnel/ 目录。
 */
class TunnelManager(private val termux: TermuxRuntime) {

    companion object {
        private const val TAG = "TunnelManager"

        /** cloudflared GitHub releases 下载路径模板（arm64） */
        private const val CLOUDFLARED_URL_ARM64 =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-arm64"
        private const val CLOUDFLARED_URL_X86 =
            "https://github.com/cloudflare/cloudflared/releases/latest/download/cloudflared-linux-386"

        /** ngrok v3 稳定版下载路径（arm64 / 386） */
        private const val NGROK_URL_ARM64 =
            "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-arm64.tgz"
        private const val NGROK_URL_X86 =
            "https://bin.equinox.io/c/bNyj1mQVY4c/ngrok-v3-stable-linux-386.tgz"
    }

    /** 隧道运行状态，UI 层订阅展示 */
    data class TunnelState(
        val isRunning: Boolean = false,
        /** 隧道分配到的公网地址（cloudflared/ngrok），frp 为空（直接用服务端 IP） */
        val publicUrl: String = "",
        val status: TunnelStatus = TunnelStatus.Idle,
        val errorMessage: String = "",
        val activeType: TunnelType? = null
    )

    enum class TunnelStatus {
        Idle,       // 未启动
        Starting,   // 正在启动（下载二进制 / 连接服务端）
        Running,    // 运行中
        Failed,     // 启动失败或运行中异常退出
        Stopped     // 已手动停止
    }

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    /** 当前隧道进程（null 表示未启动） */
    @Volatile
    private var tunnelProcess: Process? = null

    /** 当前隧道类型（用于精准 stop） */
    @Volatile
    private var activeType: TunnelType? = null

    /** 隧道配置文件目录（home/tunnel/） */
    private val tunnelDir: File
        get() = File(termux.installer.rootDir, "home/tunnel").apply { mkdirs() }

    /** 二进制存放目录（home/tunnel/bin/） */
    private val binDir: File
        get() = File(tunnelDir, "bin").apply { mkdirs() }

    /** 设备架构对应的 frp/cloudflared/ngrok 下载 URL */
    private val archSuffix: String
        get() = if (android.os.Build.SUPPORTED_ABIS.any { it.startsWith("arm64") }) "arm64" else "386"

    suspend fun start(config: McConfig) = withContext(Dispatchers.IO) {
        // 1. 停止已有隧道
        stopInternal()

        // 2. 校验配置
        val validation = validateConfig(config)
        if (validation != null) {
            _state.value = TunnelState(
                status = TunnelStatus.Failed,
                errorMessage = validation,
                activeType = config.tunnelType
            )
            termux.emitLog("[tunnel] 配置校验失败: $validation")
            return@withContext
        }

        activeType = config.tunnelType
        _state.value = TunnelState(
            status = TunnelStatus.Starting,
            activeType = config.tunnelType
        )
        termux.emitLog("[tunnel] 正在启动 ${config.tunnelType.displayName} 隧道...")

        try {
            // 3. 确保二进制可用
            val binary = ensureBinary(config.tunnelType)
            if (binary == null) {
                _state.value = TunnelState(
                    status = TunnelStatus.Failed,
                    errorMessage = "${config.tunnelType.displayName} 二进制未安装且自动下载失败，请手动安装",
                    activeType = config.tunnelType
                )
                termux.emitLog("[tunnel] 错误: 无法获取 ${config.tunnelType.displayName} 二进制")
                return@withContext
            }

            // 4. 生成配置并启动进程
            when (config.tunnelType) {
                TunnelType.Frp -> startFrp(config, binary)
                TunnelType.Cloudflared -> startCloudflared(config, binary)
                TunnelType.Ngrok -> startNgrok(config, binary)
            }
        } catch (e: Exception) {
            Log.e(TAG, "start tunnel failed", e)
            _state.value = TunnelState(
                status = TunnelStatus.Failed,
                errorMessage = e.message ?: "启动失败",
                activeType = config.tunnelType
            )
            termux.emitLog("[tunnel] 启动异常: ${e.message}")
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        stopInternal()
        _state.value = TunnelState(status = TunnelStatus.Stopped)
    }

    private fun stopInternal() {
        tunnelProcess?.let { proc ->
            if (proc.isAlive) {
                proc.destroyForcibly()
                termux.emitLog("[tunnel] 已终止隧道进程")
            }
            tunnelProcess = null
        }
        // 兜底：pkill 残留进程（仅针对当前类型）
        activeType?.let { type ->
            val procName = when (type) {
                TunnelType.Frp -> "frpc"
                TunnelType.Cloudflared -> "cloudflared"
                TunnelType.Ngrok -> "ngrok"
            }
            termux.execOnce("pkill", "-f", procName)
        }
        activeType = null
    }

    // ── 配置校验 ──────────────────────────────────────────────

    private fun validateConfig(config: McConfig): String? {
        return when (config.tunnelType) {
            TunnelType.Frp -> {
                if (config.tunnelServerAddr.isBlank()) "请填写 frp 服务端地址"
                else if (config.tunnelServerPort <= 0) "frp 服务端端口无效"
                else null
            }
            TunnelType.Cloudflared -> {
                if (!config.cloudflareQuickTunnel && config.cloudflareDomain.isBlank())
                    "非 Quick Tunnel 模式需填写 Cloudflare 域名"
                else null
            }
            TunnelType.Ngrok -> {
                if (config.ngrokAuthtoken.isBlank()) "请填写 ngrok Authtoken"
                else null
            }
        }
    }

    // ── 二进制管理 ────────────────────────────────────────────

    /**
     * 确保指定隧道类型的二进制可用。
     * - frp: 由 Termux apt 安装（$PREFIX/bin/frpc）
     * - cloudflared: 从 GitHub releases 下载到 home/tunnel/bin/cloudflared
     * - ngrok: 从 equinox.io 下载 tgz 解压到 home/tunnel/bin/ngrok
     *
     * @return 二进制路径，无法获取时返回 null
     */
    private suspend fun ensureBinary(type: TunnelType): String? {
        val prefix = termux.installer.rootDir.absolutePath

        // frp 由 apt 安装，直接返回命令名（PATH 中可找到）
        if (type == TunnelType.Frp) {
            val frpcPath = File("$prefix/bin/frpc")
            if (frpcPath.exists() && frpcPath.canExecute()) {
                return frpcPath.absolutePath
            }
            // frp 未安装，尝试 apt 安装
            termux.emitLog("[tunnel] frp 未安装，尝试通过 apt 安装...")
            val code = termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "frp")
            if (code == 0 && frpcPath.exists()) {
                return frpcPath.absolutePath
            }
            termux.emitLog("[tunnel] apt 安装 frp 失败 (code=$code)")
            return null
        }

        val binaryName = when (type) {
            TunnelType.Cloudflared -> "cloudflared"
            TunnelType.Ngrok -> "ngrok"
            else -> return null
        }
        val target = File(binDir, binaryName)

        // 已存在且可执行，直接返回
        if (target.exists() && target.canExecute()) {
            return target.absolutePath
        }

        // 下载二进制
        termux.emitLog("[tunnel] 正在下载 $binaryName (${archSuffix})...")
        val ok = downloadBinary(type, target)
        return if (ok && target.exists()) {
            // 设置可执行权限
            termux.execOnce("chmod", "755", target.absolutePath)
            termux.emitLog("[tunnel] $binaryName 下载完成: ${target.absolutePath}")
            target.absolutePath
        } else {
            termux.emitLog("[tunnel] $binaryName 下载失败")
            null
        }
    }

    /** 下载二进制文件，支持 cloudflared（直链）和 ngrok（tgz 解压） */
    private suspend fun downloadBinary(type: TunnelType, target: File): Boolean = withContext(Dispatchers.IO) {
        val url = when (type) {
            TunnelType.Cloudflared ->
                if (archSuffix == "arm64") CLOUDFLARED_URL_ARM64 else CLOUDFLARED_URL_X86
            TunnelType.Ngrok ->
                if (archSuffix == "arm64") NGROK_URL_ARM64 else NGROK_URL_X86
            else -> return@withContext false
        }

        try {
            // ngrok 需要先下载 tgz 再解压
            if (type == TunnelType.Ngrok) {
                val tgzFile = File(binDir, "ngrok.tgz")
                if (!downloadFile(url, tgzFile)) return@withContext false
                // 用系统 tar 解压
                val code = termux.execOnce("tar", "-xzf", tgzFile.absolutePath, "-C", binDir.absolutePath)
                tgzFile.delete()
                if (code != 0) {
                    termux.emitLog("[tunnel] ngrok 解压失败 (tar code=$code)")
                    return@withContext false
                }
                // 解压后二进制名为 ngrok
                val extracted = File(binDir, "ngrok")
                if (extracted.exists() && target.absolutePath != extracted.absolutePath) {
                    extracted.renameTo(target)
                }
            } else {
                // cloudflared 直链下载
                if (!downloadFile(url, target)) return@withContext false
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "downloadBinary failed", e)
            termux.emitLog("[tunnel] 下载异常: ${e.message}")
            false
        }
    }

    /** HTTP 下载文件（带 3 次重试和进度日志），参考 BootstrapInstaller 下载逻辑 */
    private fun downloadFile(urlStr: String, target: File): Boolean {
        val mirrors = listOf(
            urlStr,
            "https://gh-proxy.com/$urlStr",
            "https://mirror.ghproxy.com/$urlStr",
            "https://ghfast.top/$urlStr"
        )
        for (mirror in mirrors) {
            for (attempt in 1..3) {
                try {
                    Log.i(TAG, "downloadFile: $mirror (attempt $attempt)")
                    val conn = URL(mirror).openConnection() as HttpURLConnection
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 30_000
                    conn.instanceFollowRedirects = true
                    conn.setRequestProperty("User-Agent", "Mozilla/5.0")
                    if (conn.responseCode != 200) {
                        termux.emitLog("[tunnel] 下载 HTTP ${conn.responseCode}: ${mirror.take(60)}")
                        conn.disconnect()
                        continue
                    }
                    val total = conn.contentLengthLong
                    var downloaded = 0L
                    var lastLog = 0L
                    FileOutputStream(target).use { out ->
                        conn.inputStream.use { input ->
                            val buf = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                downloaded += n
                                // 每 5MB 输出一次进度
                                if (total > 0 && downloaded - lastLog >= 5 * 1024 * 1024) {
                                    val pct = (downloaded * 100 / total).toInt()
                                    termux.emitLog("[tunnel] 下载进度: $pct% (${downloaded / 1024}KB)")
                                    lastLog = downloaded
                                }
                            }
                        }
                    }
                    conn.disconnect()
                    if (target.length() > 1024) {
                        return true
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "download attempt $attempt failed: ${e.message}")
                    termux.emitLog("[tunnel] 下载重试 $attempt: ${e.message}")
                }
            }
        }
        return false
    }

    // ── frp 启动 ──────────────────────────────────────────────

    private suspend fun startFrp(config: McConfig, binary: String) {
        val serverAddr = config.tunnelServerAddr
        val serverPort = config.tunnelServerPort
        val token = config.tunnelToken

        // 生成 frpc.toml（参考 fatedier/frp v0.62+ TOML 新格式）
        val toml = buildString {
            appendLine("serverAddr = \"$serverAddr\"")
            appendLine("serverPort = $serverPort")
            if (token.isNotBlank()) {
                appendLine("auth.method = \"token\"")
                appendLine("auth.token = \"$token\"")
            }
            appendLine()
            appendLine("[[proxies]]")
            appendLine("name = \"mc-server\"")
            appendLine("type = \"tcp\"")
            appendLine("localIP = \"127.0.0.1\"")
            appendLine("localPort = ${config.localPort}")
            appendLine("remotePort = ${config.localPort}")
        }

        val configFile = File(tunnelDir, "frpc.toml")
        configFile.writeText(toml)
        Log.i(TAG, "frp 配置已写入: ${configFile.absolutePath}")
        termux.emitLog("[tunnel] frpc 配置已写入，连接 $serverAddr:$serverPort")

        // 使用 execRaw：自行读取输出以解析 URL 和检测退出，避免 execStream reader 线程竞争
        val process = termux.execRaw("tunnel", binary, "-c", configFile.absolutePath)
        tunnelProcess = process
        // frp 无公网 URL，地址为服务端 IP:remotePort
        val publicAddr = "$serverAddr:${config.localPort}"
        startMonitorThread(process, config.tunnelType, publicAddr)
    }

    // ── cloudflared 启动 ──────────────────────────────────────

    private suspend fun startCloudflared(config: McConfig, binary: String) {
        if (config.cloudflareQuickTunnel) {
            // Quick Tunnel: 零配置，获得随机 *.trycloudflare.com 地址
            // 参考 cloudflare/cloudflared: cloudflared tunnel --url <local-url>
            termux.emitLog("[tunnel] 启动 cloudflared Quick Tunnel，本地端口 ${config.localPort}")
            val process = termux.execRaw(
                "tunnel", binary, "tunnel", "--url", "tcp://localhost:${config.localPort}"
            )
            tunnelProcess = process
            // URL 需从输出解析
            startMonitorThread(process, config.tunnelType, "")
        } else {
            // Named Tunnel: 需域名托管在 Cloudflare + credentials-file
            // 注意：Android 无浏览器无法完成 cloudflared tunnel login，
            //       需用户从 PC 端导出凭证 JSON 放到 home/tunnel/mc-tunnel.json
            val domain = config.cloudflareDomain
            val configYaml = buildString {
                appendLine("tunnel: mc-tunnel")
                appendLine("credentials-file: ${tunnelDir.absolutePath}/mc-tunnel.json")
                appendLine("ingress:")
                appendLine("  - hostname: $domain")
                appendLine("    service: tcp://localhost:${config.localPort}")
                appendLine("  - service: http_status:404")
            }
            val configFile = File(tunnelDir, "cloudflared.yml")
            configFile.writeText(configYaml)
            termux.emitLog("[tunnel] cloudflared Named Tunnel 配置已写入，域名: $domain")
            termux.emitLog("[tunnel] 注意: 需将凭证文件 mc-tunnel.json 放到 ${tunnelDir.absolutePath}/")

            val process = termux.execRaw(
                "tunnel", binary, "tunnel", "--config", configFile.absolutePath, "run"
            )
            tunnelProcess = process
            startMonitorThread(process, config.tunnelType, domain)
        }
    }

    // ── ngrok 启动 ────────────────────────────────────────────

    private suspend fun startNgrok(config: McConfig, binary: String) {
        val authtoken = config.ngrokAuthtoken
        // 设置 authtoken（幂等操作，重复设置无副作用）
        termux.emitLog("[tunnel] 配置 ngrok authtoken...")
        val configCode = termux.execOnce(binary, "config", "add-authtoken", authtoken)
        if (configCode != 0) {
            termux.emitLog("[tunnel] 警告: ngrok authtoken 设置返回非零 (code=$configCode)")
        }

        // 启动 TCP 隧道
        termux.emitLog("[tunnel] 启动 ngrok TCP 隧道，端口 ${config.localPort}")
        val process = termux.execRaw("tunnel", binary, "tcp", config.localPort.toString())
        tunnelProcess = process
        // URL 需从输出解析
        startMonitorThread(process, config.tunnelType, "")
    }

    // ── 进程监控线程 ──────────────────────────────────────────

    /**
     * 后台线程监控隧道进程：
     *  - 读取 stdout 推送到 consoleFlow（已由 execStream 完成，这里补充 URL 解析）
     *  - 解析公网地址（cloudflared / ngrok）
     *  - 进程退出时更新状态为 Failed
     *
     * @param fallbackUrl frp 直接使用服务端地址作为公网地址
     */
    private fun startMonitorThread(process: Process, type: TunnelType, fallbackUrl: String) {
        Thread({
            try {
                // frp 无需解析 URL，直接使用 fallbackUrl
                if (fallbackUrl.isNotBlank()) {
                    _state.value = _state.value.copy(
                        isRunning = true,
                        publicUrl = fallbackUrl,
                        status = TunnelStatus.Running
                    )
                    termux.emitLog("[tunnel] 隧道运行中，公网地址: $fallbackUrl")
                }

                val reader = BufferedReader(InputStreamReader(process.inputStream))
                var line = reader.readLine()
                while (line != null) {
                    // 推送到 consoleFlow 供 UI 日志页展示（execRaw 不启动 reader 线程，需自行 emit）
                    termux.emitLog("[tunnel] $line")
                    // 解析公网 URL
                    val extracted = parsePublicUrl(type, line)
                    if (extracted != null && extracted.isNotBlank()) {
                        _state.value = _state.value.copy(
                            isRunning = true,
                            publicUrl = extracted,
                            status = TunnelStatus.Running
                        )
                        termux.emitLog("[tunnel] 获取到公网地址: $extracted")
                    }
                    line = reader.readLine()
                }
                // 进程输出结束（退出）
                val exitCode = process.waitFor()
                Log.i(TAG, "tunnel process exited: $exitCode")
                if (_state.value.status == TunnelStatus.Running) {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        status = TunnelStatus.Failed,
                        errorMessage = "隧道进程已退出 (code=$exitCode)"
                    )
                    termux.emitLog("[tunnel] 隧道进程异常退出 (code=$exitCode)")
                } else {
                    _state.value = _state.value.copy(isRunning = false)
                }
            } catch (e: Exception) {
                Log.e(TAG, "monitor thread error", e)
                _state.value = _state.value.copy(
                    isRunning = false,
                    status = TunnelStatus.Failed,
                    errorMessage = "监控异常: ${e.message}"
                )
            }
        }, "tunnel-monitor").start()
    }

    /**
     * 解析隧道输出中的公网地址：
     *  - cloudflared: 匹配 https://xxx.trycloudflare.com
     *  - ngrok: 匹配 Forwarding tcp://0.tcp.ngrok.io:12345
     */
    private fun parsePublicUrl(type: TunnelType, line: String): String? {
        return when (type) {
            TunnelType.Cloudflared -> {
                // cloudflared 输出格式：INF | https://xxx.trycloudflare.com |
                val regex = Regex("https://[a-z0-9-]+\\.trycloudflare\\.com")
                regex.find(line)?.value
            }
            TunnelType.Ngrok -> {
                // ngrok 输出格式：Forwarding tcp://0.tcp.ngrok.io:12345 -> 127.0.0.1:25565
                val regex = Regex("Forwarding\\s+(tcp://[a-z0-9.]+:\\d+)")
                regex.find(line)?.groupValues?.getOrNull(1)
            }
            TunnelType.Frp -> null // frp 无 URL 输出
        }
    }
}
