package com.mcserver.manager.server

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelState
import com.mcserver.manager.data.TunnelStatus
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import com.mcserver.manager.server.tunnel.BinaryManager
import com.mcserver.manager.server.tunnel.BoreBackend
import com.mcserver.manager.server.tunnel.CloudflaredBackend
import com.mcserver.manager.server.tunnel.FrpBackend
import com.mcserver.manager.server.tunnel.NgrokBackend
import com.mcserver.manager.server.tunnel.PlayitBackend
import com.mcserver.manager.server.tunnel.TunnelBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * 内网穿透统一管理器（重构版）。
 *
 * 架构：
 *   - 每种穿透方式对应一个 [TunnelBackend] 实现
 *   - [TunnelManager] 负责生命周期调度、Backend 切换、统一状态合并
 *   - 通过 TermuxRuntime 在 Termux 环境中运行外部二进制（frp/cloudflared/ngrok/playit）
 *   - bore 是纯 Kotlin 实现，直接在 Android 网络栈运行
 *
 * 支持的穿透方式: playit.gg / cloudflared / ngrok / frp / bore
 */
class TunnelManager(private val termux: TermuxRuntime) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 二进制管理器（Termux 后端共享） */
    private val binaryManager = BinaryManager(termux)

    /** 所有已注册的 Backend 实例 */
    private val backends: Map<TunnelType, TunnelBackend> = mapOf(
        TunnelType.Playit to PlayitBackend(termux, binaryManager),
        TunnelType.Cloudflared to CloudflaredBackend(termux, binaryManager),
        TunnelType.Ngrok to NgrokBackend(termux, binaryManager),
        TunnelType.Frp to FrpBackend(termux, binaryManager),
        TunnelType.Bore to BoreBackend()
    ).onEach { (_, backend) ->
        backend.attachLog { msg -> termux.emitLog("[tunnel] $msg") }
    }

    /** 当前活跃的 Backend */
    private var activeBackend: TunnelBackend? = null

    /** 合并后的隧道状态（取活跃 Backend 的状态，无活跃时返回 Idle） */
    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    init {
        // 监听所有 Backend 的状态变化，合并为单一状态流
        scope.launch {
            val flows = backends.values.map { it.state }
            combine(flows) { states ->
                states.toList().firstOrNull {
                    (it as TunnelState).status != TunnelStatus.Idle
                } ?: TunnelState()
            }.collect { merged ->
                _state.value = merged as TunnelState
            }
        }
    }

    /**
     * 启动指定类型的隧道。
     * 如果已有隧道运行，先停止再启动新隧道。
     */
    suspend fun start(config: McConfig) {
        // 停止当前运行中隧道
        if (activeBackend != null) {
            activeBackend?.stop()
        }

        val backend = backends[config.tunnelType]
        if (backend == null) {
            termux.emitLog("[tunnel] 错误: ${config.tunnelType.displayName} 后端尚未实现")
            _state.value = TunnelState(
                status = TunnelStatus.Failed,
                errorMessage = "${config.tunnelType.displayName} 后端尚未实现",
                activeType = config.tunnelType
            )
            return
        }

        activeBackend = backend
        val result = backend.start(config)
        result.onFailure { e ->
            termux.emitLog("[tunnel] ${config.tunnelType.displayName} 启动失败: ${e.message}")
        }
    }

    /** 停止当前运行中的隧道 */
    suspend fun stop() {
        activeBackend?.stop()
        activeBackend = null
    }

    /** 释放所有资源 */
    fun destroy() {
        scope.cancel()
        backends.values.forEach {
            scope.launch { it.stop() }
        }
    }
}
