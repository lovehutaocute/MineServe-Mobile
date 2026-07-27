package com.mcserver.manager.server

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内网穿透管理（生产化）：
 *  - frp：生成 frpc.ini 配置文件，用 execStream 启动（长驻进程不阻塞）
 *  - ngrok：用 execStream 启动
 *  - CF Tunnel：用 execStream 启动
 *  - stop：pkill 终止所有穿透进程
 *
 * 修复点：
 *  - 原实现用 execOnce 执行长驻进程会永久挂起，改用 execStream
 *  - 原实现不生成 frpc.ini，导致 frpc 启动失败
 */
class TunnelManager(private val termux: TermuxRuntime) {

    suspend fun start(config: McConfig) = withContext(Dispatchers.IO) {
        when (config.tunnelType) {
            TunnelType.Frp -> {
                // 1. 生成 frpc.ini 配置文件内容
                val iniContent = """
                    [common]
                    server_addr = ${config.customDomain}
                    server_port = 7000

                    [mc]
                    type = tcp
                    local_ip = 127.0.0.1
                    local_port = ${config.localPort}
                    remote_port = ${config.localPort}
                """.trimIndent()

                // 2. 通过 heredoc 写入配置文件到 /home/server/frpc.ini
                termux.execOnce(
                    "bash", "-c",
                    "cat > /home/server/frpc.ini << 'HEREDOC'\n${iniContent}\nHEREDOC"
                )

                // 3. 用 execStream 启动 frpc（长驻进程，不阻塞）
                termux.execStream("tunnel", "frpc", "-c", "/home/server/frpc.ini")
            }
            TunnelType.Ngrok -> {
                // ngrok 也是长驻进程，用 execStream
                termux.execStream("tunnel", "ngrok", "tcp", config.localPort.toString())
            }
            TunnelType.CloudflareTunnel -> {
                // cloudflared 同样是长驻进程
                termux.execStream(
                    "tunnel", "cloudflared", "tunnel",
                    "--url", "tcp://localhost:${config.localPort}"
                )
            }
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        termux.execOnce("pkill", "frpc")
        termux.execOnce("pkill", "ngrok")
        termux.execOnce("pkill", "cloudflared")
        Unit
    }
}
