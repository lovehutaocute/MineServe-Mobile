package com.mcserver.manager.server

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelState
import com.mcserver.manager.data.TunnelStatus
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime
import com.mcserver.manager.server.tunnel.BinaryManager
import com.mcserver.manager.server.tunnel.BoreBackend
import com.mcserver.manager.server.tunnel.FrpBackend
import com.mcserver.manager.server.tunnel.TunnelBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 内网穿透统一管理器。
 *
 * 支持的穿透方式: frp / bore
 */
class TunnelManager(private val termux: TermuxRuntime) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val binaryManager = BinaryManager(termux)

    private val backends: Map<TunnelType, TunnelBackend> = mapOf(
        TunnelType.Frp to FrpBackend(termux, binaryManager),
        TunnelType.Bore to BoreBackend()
    ).onEach { (_, backend) ->
        backend.attachLog { msg -> termux.emitLog("[tunnel] $msg") }
    }

    private var activeBackend: TunnelBackend? = null

    private val _state = MutableStateFlow(TunnelState())
    val state: StateFlow<TunnelState> = _state.asStateFlow()

    init {
        backends.values.forEach { backend ->
            scope.launch {
                backend.state.collect { backendState ->
                    if (backend == activeBackend) {
                        _state.value = backendState
                    }
                }
            }
        }
    }

    suspend fun start(config: McConfig) {
        if (activeBackend != null) {
            activeBackend?.stop()
        }

        val backend = backends[config.tunnelType]
        if (backend == null) {
            _state.value = TunnelState(
                status = TunnelStatus.Failed,
                errorMessage = "${config.tunnelType.displayName} 后端尚未实现",
                activeType = config.tunnelType
            )
            termux.emitLog("[tunnel] 错误: ${config.tunnelType.displayName} 后端尚未实现")
            return
        }

        activeBackend = backend
        _state.value = backend.state.value
        val result = backend.start(config)
        result.onFailure { e ->
            termux.emitLog("[tunnel] ${config.tunnelType.displayName} 启动失败: ${e.message}")
        }
    }

    suspend fun stop() {
        activeBackend?.stop()
        activeBackend = null
        _state.value = TunnelState(status = TunnelStatus.Stopped)
    }

    fun destroy() {
        scope.cancel()
        backends.values.forEach {
            scope.launch { it.stop() }
        }
    }
}
