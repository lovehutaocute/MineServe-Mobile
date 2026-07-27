package com.mcserver.manager.server

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 内网穿透管理：
 *  - frp：本地启动 frpc，连接用户配置的 frps 服务端
 *  - ngrok：通过 ngrok http 25565 暴露
 *  - CF Tunnel：cloudflared tunnel 走 Cloudflare 网络
 *
 * 此处仅给出启动入口；实际 token/域名等需用户在 UI 填表后注入。
 */
class TunnelManager(private val termux: TermuxRuntime) {

    suspend fun start(config: McConfig) = withContext(Dispatchers.IO) {
        when (config.tunnelType) {
            TunnelType.Frp -> termux.execOnce(
                "frpc", "-c", "/home/server/frpc.ini"
            )
            TunnelType.Ngrok -> termux.execOnce(
                "ngrok", "tcp", config.localPort.toString()
            )
            TunnelType.CloudflareTunnel -> termux.execOnce(
                "cloudflared", "tunnel", "--url", "tcp://localhost:${config.localPort}"
            )
        }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        termux.execOnce("pkill", "frpc")
        termux.execOnce("pkill", "ngrok")
        termux.execOnce("pkill", "cloudflared")
        Unit
    }
}
