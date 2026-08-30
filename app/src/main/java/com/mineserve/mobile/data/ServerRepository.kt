package com.mineserve.mobile.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.consumeAsFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import com.mineserve.mobile.runtime.TermuxRuntime

private val Context.configDataStore: DataStore<Preferences> by preferencesDataStore(name = "mc_config")

/**
 * 单一数据入口：
 *  - McConfig 通过 DataStore 持久化（JSON 序列化到单一 key）
 *  - ServerState 为运行时内存态，由 TermuxRuntime / ForegroundService 推送
 *  - 日志流由 TermuxRuntime.consoleFlow 直接暴露
 */
class ServerRepository(
    private val context: Context,
    val termuxRuntime: TermuxRuntime
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val CONFIG_KEY = stringPreferencesKey("config_json")

    val configFlow: Flow<McConfig> = context.configDataStore.data.map { prefs ->
        val stored = prefs[CONFIG_KEY]?.let { raw ->
            runCatching { json.decodeFromString<McConfig>(raw) }.getOrElse {
                // STUN was removed; migrate only its old enum value without discarding user settings.
                json.decodeFromString<McConfig>(raw.replace(Regex("\\\"tunnelType\\\"\\s*:\\s*\\\"Stun\\\""), "\"tunnelType\":\"Frp\""))
            }
        } ?: McConfig()
        val correctedCores = stored.installedCores.map { core ->
            core.copy(version = MinecraftVersionNormalizer.forCore(core.core, core.version))
        }
        stored.copy(
            mcVersion = MinecraftVersionNormalizer.forCore(stored.selectedCore, stored.mcVersion),
            installedCores = correctedCores
        )
    }

    /** Config 写入 debounce 通道，避免输入框每字符触发磁盘写入 */
    private val saveChannel = Channel<McConfig>(Channel.CONFLATED)
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // 300ms debounce：连续快速修改只写入最后一次
        saveScope.launch {
            saveChannel.consumeAsFlow().debounce(300).collect { config ->
                context.configDataStore.edit { prefs ->
                    prefs[CONFIG_KEY] = json.encodeToString(McConfig.serializer(), config)
                }
            }
        }
    }

    suspend fun saveConfig(config: McConfig) {
        saveChannel.send(config)
    }

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    /** 原子更新服务器状态（CAS），避免多线程并发读改写丢失更新 */
    fun updateServerState(transform: (ServerState) -> ServerState) {
        val before = _serverState.value
        _serverState.update { transform(it) }
        val after = _serverState.value
        // 桌面组件关心的字段变化时才刷新 widget，避免高频日志解析带来的无谓推送
        if (before.isRunning != after.isRunning ||
            before.onlinePlayers != after.onlinePlayers ||
            before.maxPlayers != after.maxPlayers ||
            before.tps != after.tps ||
            before.usedMemoryMb != after.usedMemoryMb ||
            before.cpuPercent != after.cpuPercent ||
            before.startupPhase != after.startupPhase
        ) {
            WidgetUpdater.refresh(context)
        }
    }

    /** 由 ForegroundService 调用：标记安装步骤进度 */
    fun markStep(step: InstallStep, status: StepStatus, progress: Int = _serverState.value.currentProgress) {
        updateServerState { state ->
            state.copy(
                installSteps = state.installSteps.map { if (it.step == step) it.copy(status = status) else it },
                currentProgress = progress
            )
        }
    }
}
