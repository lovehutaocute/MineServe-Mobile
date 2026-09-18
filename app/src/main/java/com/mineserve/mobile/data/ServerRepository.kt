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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
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

    /**
     * 配置的权威内存态：所有 updateConfig 的读改写都以它为基准。
     *
     * 为什么需要它：DataStore 是异步流，写入还带 300ms debounce。
     * 如果每次修改都基于 configFlow 的最新发射值做「读 → 改 → 写」，
     * 在 debounce 窗口内的第二次修改会读到尚未落盘的旧值，
     * 把第一次的改动整体覆盖掉（典型表现：切到 PHP 后 runtimeKind 又被写回 Java）。
     *
     * 有了这个内存态，读改写就是原子的（见 [updateConfig]），
     * 不再依赖"窗口有多长"这类时间假设。
     */
    private val _config = MutableStateFlow(McConfig())
    @Volatile
    private var configLoaded = false

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

    /**
     * 镜像磁盘态到权威内存态 [_config]，**仅在内存态尚未建立时**执行一次。
     *
     * 这段逻辑从 [configFlow] 里挪出来是为了修一个状态撕裂问题：
     * 原先它挂在 `configFlow.map {}` 里，而 `configFlow` 是**冷流**，
     * 每个订阅者（ViewModel 的 `stateIn`、各处 `first()`）都会重新触发一次 map。
     * 一旦有第二个订阅者在 `configLoaded` 之后才第一次收集，它虽然不会改写
     * `_config`，却也让「磁盘 → 内存」的同步时机变得依赖于订阅顺序。
     *
     * 现在改为显式的 [ensureConfigLoaded]，语义清晰：谁来都只同步一次，
     * 之后的修改一律通过 [updateConfig] 的原子读改写进入内存态。
     */
    private fun ensureConfigLoaded() {
        if (configLoaded) return
        val initial = runCatching {
            kotlinx.coroutines.runBlocking { configFlow.firstOrNull() }
        }.getOrNull() ?: McConfig()
        synchronized(this) {
            if (!configLoaded) {
                _config.value = initial
                configLoaded = true
            }
        }
    }

    /**
     * 原子读改写配置。
     *
     * 全程在 [MutableStateFlow.update] 的比较并交换内完成，
     * 因此并发调用不会互相覆盖，也不需要在调用方额外维护"待写入快照"。
     * 返回本次应用后的配置，便于调用方继续链式使用。
     */
    fun updateConfig(transform: (McConfig) -> McConfig): McConfig {
        ensureConfigLoaded()
        return _config.updateAndGet(transform)
    }

    /**
     * 原子读改写配置，**并把结果排入落盘队列**。
     *
     * 这是推荐给业务代码使用的入口：调用方不需要（也不应该）自己先取一份
     * 配置快照再传进来。历史上有不少调用方写成：
     *
     * ```kotlin
     * repo.saveConfig(config.value.copy(installedCores = ...))   // config.value 来自 UI 侧
     * ```
     *
     * 一旦那份快照是陈旧的（例如 UI 侧 `WhileSubscribed(5s)` 已停止收集），
     * 就会把用户此前的修改整体覆盖掉。典型症状：删掉的核心又出现在列表里、
     * 刚下载的核心在列表里找不到。改为本方法后，读改写全程在 [_config] 上
     * 原子完成，不存在「拿旧快照覆盖新状态」的窗口。
     *
     * @return 应用后的配置（已含本次修改，尚未落盘）
     */
    fun updateAndSaveConfig(transform: (McConfig) -> McConfig): McConfig {
        val updated = updateConfig(transform)
        saveChannel.trySend(updated)
        return updated
    }

    /** 当前配置的内存快照（已含尚未落盘的修改）。 */
    fun currentConfig(): McConfig {
        ensureConfigLoaded()
        return _config.value
    }


    /**
     * Config 写入通道，用于合并高频写入。
     *
     * 这里**必须用 [Channel.CONFLATED] 而不是普通缓冲通道**，但要理解它的语义：
     * 它只保留「最后一份」，中间态会被丢弃。对配置写入来说这是安全的 ——
     * 因为每次写入的都是**完整**的 [McConfig]（由 [updateConfig] 从权威内存态
     * 原子算出），丢弃中间态等价于「跳过若干次落盘」，最终态永远正确。
     *
     * 反过来说：**绝不能把「部分字段的补丁」投进这个通道**。
     * 早期有调用方传的是从 UI 侧拿的陈旧快照，CONFLATED 一挤，
     * 就把并发的另一次修改整体丢了 —— 「刚下载的核心消失」正是这么来的。
     * 所有写入都应经由 [updateConfig] / [updateAndSaveConfig] 从 [_config] 生成。
     */
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

    /**
     * 排入配置落盘队列。
     *
     * ⚠️ 传入的必须是**完整且最新**的配置。调用方若持有的是 UI 侧快照
     * （`vm.config.value`），请改用 [updateAndSaveConfig]，
     * 或先经 [updateConfig] 基于权威内存态重新生成。
     */
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
