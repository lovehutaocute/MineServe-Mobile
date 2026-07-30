package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelState
import com.mcserver.manager.data.TunnelStatus
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Termux 进程隧道后端基类。
 * 封装进程管理、stdout 解析、公网地址提取、超时看门狗等通用逻辑。
 *
 * 子类只需实现 [buildArgs] 和 [parsePublicUrl]。
 */
abstract class TermuxBackend(
    protected val termux: TermuxRuntime,
    protected val binaryManager: BinaryManager,
    override val type: TunnelType
) : TunnelBackend {

    protected val _state = MutableStateFlow(TunnelState())
    override val state: StateFlow<TunnelState> = _state.asStateFlow()

    protected var log: (String) -> Unit = {}

    @Volatile
    private var process: Process? = null

    @Volatile
    private var isRunning = false

    /** 看门狗超时 ms */
    protected open val watchdogTimeoutMs: Long = 30_000

    override fun attachLog(logger: (String) -> Unit) {
        log = logger
    }

    override suspend fun start(config: McConfig): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            updateState(TunnelStatus.Starting, errorMessage = "正在准备 ${type.displayName}...")

            // 1. 确保二进制可用
            val binary = ensureBinary()
            if (binary == null) {
                val msg = "${type.displayName} 二进制不可用"
                log(msg)
                updateState(TunnelStatus.Failed, errorMessage = msg)
                return@withContext Result.failure(RuntimeException(msg))
            }

            // 2. 修复 Android DNS（写入 resolv.conf，否则 Go 程序无法解析域名）
            fixDns()

            // 3. 构建参数
            val args = buildArgs(config, binary)
            val env = buildEnv(config)

            log("启动命令: $binary ${args.joinToString(" ")}")

            // 4. 启动进程
            val proc = termux.execRaw("tunnel", binary, *args.toTypedArray(), env = env)
            process = proc
            isRunning = true

            // 5. 子类进程启动回调（如 frp 直接设置公网地址）
            onProcessStarted(config)

            // 6. 启动 stdout 解析线程
            startMonitorThread(proc, config)

            Result.success(Unit)
        } catch (e: Exception) {
            log("启动失败: ${e.message}")
            updateState(TunnelStatus.Failed, errorMessage = e.message ?: "未知错误")
            Result.failure(e)
        }
    }

    override suspend fun stop() = withContext(Dispatchers.IO) {
        isRunning = false
        process?.let {
            if (it.isAlive) it.destroyForcibly()
        }
        process = null
        // 兜底 pkill
        killProcess()
        updateState(TunnelStatus.Stopped)
        log("${type.displayName} 已停止")
    }

    // ── 子类需实现 ────────────────────────────────────────────

    /** 确保二进制可用，返回绝对路径或 null */
    protected abstract suspend fun ensureBinary(): String?

    /** 构建启动参数 */
    protected abstract fun buildArgs(config: McConfig, binary: String): List<String>

    /** 构建环境变量 */
    protected open fun buildEnv(config: McConfig): Map<String, String> = emptyMap()

    /** 从 stdout 行中提取公网 URL，返回 null 表示未匹配 */
    protected abstract fun parsePublicUrl(line: String): String?

    /** 进程成功启动后的回调（在 stdout 监控线程启动前），子类可覆盖 */
    protected open fun onProcessStarted(config: McConfig) {}

    /** 兜底 pkill 进程名 */
    protected abstract fun killProcess()

    // ── Android DNS 修复 ──────────────────────────────────────

    /**
     * Android 环境修复：写入 resolv.conf + 创建 /tmp 目录。
     *
     * - DNS：Go 程序（cloudflared/ngrok）的 SRV 查询需要 /etc/resolv.conf
     * - /tmp：playit.gg 等程序需要可写的 IPC socket 目录
     */
    private fun fixDns() {
        val prefix = termux.installer.rootDir.absolutePath
        try {
            // 1. DNS resolver
            val resolvConf = java.io.File("$prefix/etc/resolv.conf")
            val content = "nameserver 8.8.8.8\nnameserver 8.8.4.4\n"
            resolvConf.parentFile?.mkdirs()
            if (!resolvConf.exists() || resolvConf.readText() != content) {
                resolvConf.writeText(content)
                log("DNS 修复: 已写入 $resolvConf")
            }
            // 2. /tmp 目录（playit.gg IPC socket 需要）
            val tmpDir = java.io.File("$prefix/tmp")
            if (!tmpDir.exists()) {
                tmpDir.mkdirs()
                log("tmp 目录已创建: $tmpDir")
            }
        } catch (e: Exception) {
            log("环境修复失败: ${e.message}")
        }
    }

    // ── 公共方法 ──────────────────────────────────────────────

    protected fun updateState(status: TunnelStatus, publicUrl: String = _state.value.publicUrl, errorMessage: String = "") {
        _state.value = TunnelState(
            isRunning = status == TunnelStatus.Running,
            publicUrl = publicUrl,
            status = status,
            errorMessage = errorMessage,
            activeType = type
        )
    }

    // ── stdout 监控线程 ───────────────────────────────────────

    private fun startMonitorThread(proc: Process, config: McConfig) {
        Thread({
            try {
                val reader = BufferedReader(InputStreamReader(proc.inputStream))
                val outputLines = StringBuilder()
                var gotUrl = false

                var line: String? = reader.readLine()
                while (line != null) {
                    log(line)
                    outputLines.append(line).append('\n')

                    if (!gotUrl) {
                        val url = parsePublicUrl(line)
                        if (url != null && url.isNotBlank()) {
                            gotUrl = true
                            updateState(TunnelStatus.Running, publicUrl = url)
                            log("获取到公网地址: $url")
                        }
                    }
                    line = reader.readLine()
                }

                // 进程退出
                val exitCode = proc.waitFor()
                if (isRunning) {
                    val errMsg = if (!gotUrl) {
                        diagnoseFailure(exitCode, outputLines.toString())
                    } else {
                        "隧道进程已退出 (code=$exitCode)"
                    }
                    updateState(TunnelStatus.Failed, errorMessage = errMsg)
                    log(errMsg)
                }
            } catch (e: Exception) {
                if (isRunning) {
                    updateState(TunnelStatus.Failed, errorMessage = "监控异常: ${e.message}")
                }
            }
        }, "tunnel-${type.name}").start()

        // 超时看门狗
        Thread({
            try {
                Thread.sleep(watchdogTimeoutMs)
                if (proc.isAlive && _state.value.status == TunnelStatus.Starting) {
                    log("进程 ${watchdogTimeoutMs}ms 内未获取到公网地址，判定超时")
                    proc.destroyForcibly()
                    updateState(TunnelStatus.Failed, errorMessage = "启动超时，可能网络不通或配置有误")
                }
            } catch (_: Exception) {}
        }, "tunnel-watchdog-${type.name}").start()
    }

    /** 子类可覆盖以提供更详细的错误诊断 */
    protected open fun diagnoseFailure(exitCode: Int, output: String): String {
        return "隧道进程退出 (code=$exitCode)"
    }
}
