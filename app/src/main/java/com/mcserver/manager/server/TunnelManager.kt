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
            // frp 未安装，尝试 apt 安装（先 update 确保包列表最新）
            termux.emitLog("[tunnel] frp 未安装，正在通过 apt 自动安装...")
            _state.value = _state.value.copy(
                status = TunnelStatus.Starting,
                errorMessage = "正在安装 frp 环境..."
            )
            termux.execOnce("apt-get", "update", "--allow-insecure-repositories", "-y")
            val code = termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "frp")
            if (code == 0 && frpcPath.exists()) {
                termux.emitLog("[tunnel] frp 安装完成")
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
        termux.emitLog("[tunnel] 正在下载 $binaryName (${archSuffix})，首次使用需要从网络获取...")
        _state.value = _state.value.copy(
            status = TunnelStatus.Starting,
            errorMessage = "正在下载 $binaryName 环境..."
        )
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
            termux.emitLog("[tunnel] 启动 cloudflared Quick Tunnel，本地端口 ${config.localPort}")
            val (cmd, env) = buildTunnelCommand(binary,
                "tunnel", "--url", "tcp://localhost:${config.localPort}")
            val process = termux.execRaw("tunnel", env = env, *cmd)
            tunnelProcess = process
            startMonitorThread(process, config.tunnelType, "")
        } else {
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

            val (cmd, env) = buildTunnelCommand(binary,
                "tunnel", "--config", configFile.absolutePath, "run")
            val process = termux.execRaw("tunnel", env = env, *cmd)
            tunnelProcess = process
            startMonitorThread(process, config.tunnelType, domain)
        }
    }

    /**
     * 构造隧道启动命令，解决 Go 二进制的 DNS 问题。
     *
     * 策略（按优先级）：
     * 1. 直接写 /etc/resolv.conf（部分 Android 设备 /etc 可写）→ 直接运行二进制
     * 2. proot 绑定 linker64 + resolv.conf → proot 包装运行
     *    关键：Go PIE 二进制有 PT_INTERP=/lib/ld-linux-aarch64.so.1，
     *    proot 找不到该解释器。绑定 Android 的 /system/bin/linker64 到该路径即可。
     *    （linker64 是 Android 原生链接器，能正确加载 Go PIE 二进制）
     * 3. 无 proot → 直接运行，DNS 可能失败
     *
     * @return (完整命令数组, 环境变量)
     */
    private suspend fun buildTunnelCommand(
        binary: String, vararg args: String
    ): Pair<Array<String>, Map<String, String>> {
        val env = mapOf("GODEBUG" to "netdns=go")
        val resolvContent = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"

        // 方案1：尝试直接写 /etc/resolv.conf
        try {
            File("/etc/resolv.conf").writeText(resolvContent)
            termux.emitLog("[tunnel] DNS: 已直接写入 /etc/resolv.conf")
            return arrayOf(binary, *args) to env
        } catch (e: Exception) {
            termux.emitLog("[tunnel] DNS: /etc/resolv.conf 不可写，尝试 proot 方案")
        }

        // 方案2：proot 绑定 linker64 + resolv.conf
        val prootPath = ensureProot()
        if (prootPath != null) {
            val resolvFile = File(termux.installer.rootDir, "etc/resolv.conf").apply {
                parentFile?.mkdirs()
                writeText(resolvContent)
            }
            // 设备架构对应的 ld-linux 解释器路径
            val linkerPaths = if (archSuffix == "arm64") {
                listOf("/lib/ld-linux-aarch64.so.1", "/lib/ld-musl-aarch64.so.1")
            } else {
                listOf("/lib64/ld-linux-x86-64.so.2", "/lib/ld-linux.so.2")
            }
            val androidLinker = if (archSuffix == "arm64") "/system/bin/linker64" else "/system/bin/linker64"

            val cmd = mutableListOf(prootPath)
            // 绑定 Android linker64 到 Go 二进制期望的 ld-linux 路径
            for (linkerPath in linkerPaths) {
                cmd.addAll(listOf("-b", "$androidLinker:$linkerPath"))
            }
            // 绑定 resolv.conf
            cmd.addAll(listOf("-b", "${resolvFile.absolutePath}:/etc/resolv.conf"))
            // 绑定 /system（提供系统库）
            cmd.addAll(listOf("-b", "/system:/system"))
            // 二进制和参数
            cmd.add(binary)
            cmd.addAll(args)

            termux.emitLog("[tunnel] DNS: 使用 proot 绑定 linker64 + resolv.conf")
            return cmd.toTypedArray() to env
        }

        // 方案3：无 proot，DNS 可能失败
        termux.emitLog("[tunnel] DNS: 无 proot 可用，DNS 解析可能失败（建议 apt install proot 或改用 frp）")
        return arrayOf(binary, *args) to env
    }

    /** 检测 Termux 环境中是否安装了 proot，返回可执行路径或 null */
    private fun detectProot(): String? {
        // proot 可能装在多个位置（参考 openjdk 的安装路径模式）：
        // - $PREFIX/bin/proot（符号链接）
        // - $PREFIX/usr/bin/proot
        // - $PREFIX/data/data/com.termux/files/usr/bin/proot（dpkg 实际解压位置）
        val root = termux.installer.rootDir
        val candidates = listOf(
            File(root, "bin/proot"),
            File(root, "usr/bin/proot"),
            File(root, "data/data/com.termux/files/usr/bin/proot")
        )
        val found = candidates.firstOrNull { it.exists() && it.canExecute() }
        if (found == null) {
            // 调试：列出 bin/ 目录下的 proot* 文件，帮助定位
            val binDir = File(root, "bin")
            val prootFiles = binDir.listFiles()?.filter { it.name.startsWith("proot") }?.map { it.name } ?: emptyList()
            Log.w(TAG, "detectProot: 未找到 proot，bin/ 下相关文件: $prootFiles")
            val usrBin = File(root, "data/data/com.termux/files/usr/bin")
            val usrProotFiles = usrBin.listFiles()?.filter { it.name.startsWith("proot") }?.map { it.name } ?: emptyList()
            Log.w(TAG, "detectProot: usr/bin/ 下相关文件: $usrProotFiles")
        }
        return found?.absolutePath
    }

    /**
     * 确保 proot 已安装。若未检测到则自动通过 apt 安装。
     * @return proot 可执行路径，安装失败返回 null
     */
    private suspend fun ensureProot(): String? {
        detectProot()?.let { return it }
        // 自动安装 proot
        termux.emitLog("[tunnel] 未检测到 proot，正在自动安装...")
        val code = termux.execOnce("apt-get", "install", "--allow-unauthenticated", "-y", "proot")
        if (code != 0) {
            termux.emitLog("[tunnel] proot 自动安装失败 (code=$code)")
            return null
        }
        val path = detectProot()
        if (path == null) {
            termux.emitLog("[tunnel] proot 安装后仍未检测到，正在用 find 搜索...")
            // 用 find 全局搜索 proot 二进制位置
            val findResult = termux.execOnce("find", termux.installer.rootDir.absolutePath, "-name", "proot", "-type", "f")
            termux.emitLog("[tunnel] find 搜索完成 (code=$findResult)，详见日志")
        }
        return path
    }

    // ── ngrok 启动 ────────────────────────────────────────────

    private suspend fun startNgrok(config: McConfig, binary: String) {
        val authtoken = config.ngrokAuthtoken
        if (authtoken.isBlank()) {
            _state.value = _state.value.copy(
                status = TunnelStatus.Failed,
                errorMessage = "ngrok authtoken 未配置，请在设置中填入（从 ngrok.com 获取）"
            )
            termux.emitLog("[tunnel] ngrok 启动失败: authtoken 未配置")
            return
        }
        // 设置 authtoken（幂等操作，重复设置无副作用）
        termux.emitLog("[tunnel] 配置 ngrok authtoken...")
        val configCode = termux.execOnce(binary, "config", "add-authtoken", authtoken)
        if (configCode != 0) {
            termux.emitLog("[tunnel] 警告: ngrok authtoken 设置返回非零 (code=$configCode)")
        }

        val proto = config.ngrokProto
        val domain = config.ngrokDomain.trim()
        val ngrokArgs = mutableListOf<String>()
        when (proto) {
            com.mcserver.manager.data.NgrokProto.Tcp -> {
                termux.emitLog("[tunnel] 启动 ngrok TCP 隧道，端口 ${config.localPort}")
                ngrokArgs.addAll(listOf("tcp", config.localPort.toString()))
            }
            com.mcserver.manager.data.NgrokProto.Http -> {
                if (domain.isNotEmpty()) {
                    termux.emitLog("[tunnel] 启动 ngrok HTTP 隧道，固定域名 $domain，端口 ${config.localPort}")
                    ngrokArgs.addAll(listOf("http", "--url=$domain", config.localPort.toString()))
                } else {
                    termux.emitLog("[tunnel] 启动 ngrok HTTP 隧道，随机域名，端口 ${config.localPort}")
                    ngrokArgs.addAll(listOf("http", config.localPort.toString()))
                }
            }
        }
        // 使用统一的 buildTunnelCommand 处理 DNS（同 cloudflared）
        val (cmd, env) = buildTunnelCommand(binary, *ngrokArgs.toTypedArray())
        val process = termux.execRaw("tunnel", env = env, *cmd)
        tunnelProcess = process
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
                val outputLines = StringBuilder()
                var gotPublicUrl = fallbackUrl.isNotBlank()
                var line = reader.readLine()
                while (line != null) {
                    // 推送到 consoleFlow 供 UI 日志页展示（execRaw 不启动 reader 线程，需自行 emit）
                    termux.emitLog("[tunnel] $line")
                    outputLines.append(line).append('\n')
                    // 解析公网 URL
                    val extracted = parsePublicUrl(type, line)
                    if (extracted != null && extracted.isNotBlank()) {
                        gotPublicUrl = true
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
                // 无论之前状态如何，进程退出且未获取到公网地址，均视为失败
                if (!gotPublicUrl) {
                    val output = outputLines.toString()
                    val errMsg = diagnoseTunnelFailure(type, exitCode, output)
                    _state.value = _state.value.copy(
                        isRunning = false,
                        publicUrl = "",
                        status = TunnelStatus.Failed,
                        errorMessage = errMsg
                    )
                    termux.emitLog("[tunnel] 隧道启动失败: $errMsg")
                } else {
                    _state.value = _state.value.copy(
                        isRunning = false,
                        status = TunnelStatus.Failed,
                        errorMessage = "隧道进程已退出 (code=$exitCode)"
                    )
                    termux.emitLog("[tunnel] 隧道进程异常退出 (code=$exitCode)")
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

        // 超时看门狗：30 秒内进程仍 alive 且未拿到 URL → 销毁进程并标记失败
        Thread({
            try {
                Thread.sleep(30_000)
                if (process.isAlive && _state.value.status == TunnelStatus.Starting) {
                    termux.emitLog("[tunnel] 进程 30 秒内未获取到公网地址，判定超时")
                    process.destroyForcibly()
                    _state.value = _state.value.copy(
                        isRunning = false,
                        publicUrl = "",
                        status = TunnelStatus.Failed,
                        errorMessage = "启动超时（30 秒内未获取到公网地址），可能 authtoken 无效或网络不通"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "watchdog error", e)
            }
        }, "tunnel-watchdog").start()
    }

    /**
     * 解析隧道输出中的公网地址：
     *  - cloudflared: 匹配 https://xxx.trycloudflare.com（排除 api.trycloudflare.com API 端点）
     *  - ngrok: 匹配 Forwarding tcp://0.tcp.ngrok.io:12345
     */
    private fun parsePublicUrl(type: TunnelType, line: String): String? {
        return when (type) {
            TunnelType.Cloudflared -> {
                // cloudflared Quick Tunnel 成功时输出：INF | https://random-words-1234.trycloudflare.com |
                // 失败时错误日志含 https://api.trycloudflare.com（API 端点，非隧道地址），必须排除
                val regex = Regex("https://([a-z0-9-]+)\\.trycloudflare\\.com")
                val m = regex.find(line)
                val sub = m?.groupValues?.getOrNull(1)
                if (m != null && sub != null && sub != "api" && sub != "www") m.value else null
            }
            TunnelType.Ngrok -> {
                // ngrok 输出格式（TCP）：Forwarding tcp://0.tcp.ngrok.io:12345 -> 127.0.0.1:25565
                // ngrok 输出格式（HTTP 固定/随机域名）：Forwarding https://xxx.ngrok-free.dev -> http://localhost:25565
                // 两种都接收，UI 会按地址类型提示用户如何使用
                val tcpRegex = Regex("Forwarding\\s+(tcp://[a-z0-9.]+:\\d+)")
                val httpRegex = Regex("Forwarding\\s+(https?://[a-z0-9.-]+(?:\\.ngrok(?:-free)?\\.io|\\.ngrok\\.app)(?::\\d+)?)")
                tcpRegex.find(line)?.groupValues?.getOrNull(1)
                    ?: httpRegex.find(line)?.groupValues?.getOrNull(1)
            }
            TunnelType.Frp -> null // frp 无 URL 输出
        }
    }

    /**
     * 根据隧道输出诊断失败原因，给出针对性错误提示。
     * 重点识别 cloudflared 在 Android 上的 DNS 限制问题。
     */
    private fun diagnoseTunnelFailure(type: TunnelType, exitCode: Int, output: String): String {
        val base = "隧道进程退出 (code=$exitCode)"
        return when (type) {
            TunnelType.Cloudflared -> {
                when {
                    output.contains("lookup") && output.contains("connection refused") -> {
                        "cloudflared 无法解析 DNS（Android 系统无 /etc/resolv.conf，Go 回退到 [::1]:53 失败）。建议改用 frp（自带服务器，无需外部 DNS）或 ngrok。"
                    }
                    output.contains("trycloudflare.com") && output.contains("failed to request") -> {
                        "cloudflared 请求 Quick Tunnel 失败（网络或 DNS 问题）。建议检查网络后重试，或改用 frp。"
                    }
                    output.contains("credentials") || output.contains("credential") -> {
                        "cloudflared 凭证错误：请将 Named Tunnel 凭证 mc-tunnel.json 放到 ${tunnelDir.absolutePath}/。"
                    }
                    else -> base
                }
            }
            TunnelType.Ngrok -> {
                when {
                    output.contains("authtoken", ignoreCase = true) -> {
                        "ngrok authtoken 无效或未配置，请在设置中填入有效的 authtoken。"
                    }
                    output.isBlank() || output.contains("timeout", ignoreCase = true) -> {
                        "ngrok 30 秒内无输出，可能因缺少 proot 导致 DNS 解析失败。请在「一键安装依赖」中安装 PRoot 后重试。"
                    }
                    else -> base
                }
            }
            TunnelType.Frp -> base
        }
    }
}
