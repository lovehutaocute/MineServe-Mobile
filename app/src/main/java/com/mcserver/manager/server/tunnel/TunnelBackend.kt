package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelState
import com.mcserver.manager.data.TunnelType
import kotlinx.coroutines.flow.StateFlow

/**
 * 隧道后端统一接口。
 * 每种内网穿透方案实现此接口，由 [TunnelManager] 统一调度。
 *
 * 生命周期: attachLog → start → (running) → stop
 */
interface TunnelBackend {
    /** 本后端对应的隧道类型 */
    val type: TunnelType

    /** 隧道运行时状态流，UI 层订阅此 Flow 获取实时状态 */
    val state: StateFlow<TunnelState>

    /**
     * 注入日志回调。
     * TunnelManager 在创建 Backend 后、start 之前调用，
     * Backend 通过此回调将日志推送到全局 consoleFlow。
     */
    fun attachLog(logger: (String) -> Unit)

    /**
     * 启动隧道。
     * @param config 完整用户配置，Backend 只提取自己关心的字段
     * @return Result.success 表示启动成功，Result.failure 包含错误信息
     */
    suspend fun start(config: McConfig): Result<Unit>

    /** 停止隧道，释放所有资源 */
    suspend fun stop()
}
