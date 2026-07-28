package com.mcserver.manager.server

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内网穿透管理（仅支持 frp）：
 *  - 生成 frpc.ini 配置文件，用 execStream 启动（长驻进程不阻塞）
 *  - stop：pkill 终止 frpc 进程
 *
 * 注：ngrok 和 CF Tunnel 因未提供二进制安装，已移除。
 */
class TunnelManager(private val termux: TermuxRuntime) {

    suspend fun start(config: McConfig) = withContext(Dispatchers.IO) {
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

    suspend fun stop() = withContext(Dispatchers.IO) {
        termux.execOnce("pkill", "frpc")
        Unit
    }
}
