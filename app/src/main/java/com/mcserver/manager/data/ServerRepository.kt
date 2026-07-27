package com.mcserver.manager.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import com.mcserver.manager.runtime.TermuxRuntime

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
        prefs[CONFIG_KEY]?.let { json.decodeFromString<McConfig>(it) } ?: McConfig()
    }

    suspend fun saveConfig(config: McConfig) {
        context.configDataStore.edit { prefs ->
            prefs[CONFIG_KEY] = json.encodeToString(McConfig.serializer(), config)
        }
    }

    private val _serverState = MutableStateFlow(ServerState())
    val serverState: StateFlow<ServerState> = _serverState.asStateFlow()

    fun updateServerState(transform: (ServerState) -> ServerState) {
        _serverState.value = transform(_serverState.value)
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

    /** 已安装插件列表（持久化简化版：保存在 DataStore 单 key） */
    private val PLUGINS_KEY = stringPreferencesKey("plugins_json")
    val pluginsFlow: Flow<List<PluginInfo>> = context.configDataStore.data.map { prefs ->
        prefs[PLUGINS_KEY]?.let { json.decodeFromString<List<PluginInfo>>(it) } ?: defaultPlugins()
    }

    suspend fun setPlugins(list: List<PluginInfo>) {
        context.configDataStore.edit { prefs ->
            prefs[PLUGINS_KEY] = json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(PluginInfo.serializer()),
                list
            )
        }
    }

    private fun defaultPlugins() = listOf(
        PluginInfo("luckperms", "LuckPerms", "权限与用户组管理", "LP", installed = false),
        PluginInfo("essentialsx", "EssentialsX", "基础指令与传送", "EX", installed = false),
        PluginInfo("vault", "Vault", "经济系统接口", "VZ", installed = false),
        PluginInfo("worldedit", "WorldEdit", "世界编辑神器", "WE", installed = false),
        PluginInfo("coreprotect", "CoreProtect", "方块日志与回滚", "CP", installed = false)
    )
}
