package com.mcserver.manager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mcserver.manager.McApplication
import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.PluginInfo
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.data.ServerState
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.server.McServerController
import com.mcserver.manager.server.PluginManager
import com.mcserver.manager.server.TunnelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 顶层共享 ViewModel：
 *  - 暴露 McConfig / ServerState / Plugins / ConsoleLog
 *  - 转发用户操作到 Controller / Manager
 */
class McViewModel(
    private val repo: ServerRepository,
    private val controller: McServerController,
    private val pluginManager: PluginManager,
    private val tunnelManager: TunnelManager
) : ViewModel() {

    val config: StateFlow<McConfig> = repo.configFlow.stateIn(
        viewModelScope, SharingStarted.Eagerly, McConfig()
    )

    val serverState: StateFlow<ServerState> = repo.serverState

    val plugins: StateFlow<List<PluginInfo>> = repo.pluginsFlow.stateIn(
        viewModelScope, SharingStarted.Eagerly, emptyList()
    )

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    init {
        // 订阅 consoleFlow 并缓存最近 1000 行供 LogsPage 展示
        viewModelScope.launch {
            repo.termuxRuntime.consoleFlow.collect { line ->
                _consoleLines.value = (_consoleLines.value + line).takeLast(1000)
            }
        }
    }

    fun updateConfig(transform: (McConfig) -> McConfig) {
        viewModelScope.launch {
            repo.saveConfig(transform(config.value))
        }
    }

    fun selectCore(core: com.mcserver.manager.data.ServerCore) =
        updateConfig { it.copy(selectedCore = core) }

    fun selectTunnel(tunnel: TunnelType) =
        updateConfig { it.copy(tunnelType = tunnel) }

    fun setLocalPort(port: Int) = updateConfig { it.copy(localPort = port) }
    fun setDomain(d: String) = updateConfig { it.copy(customDomain = d) }
    fun setMaxHeap(mb: Int) = updateConfig { it.copy(maxHeapMb = mb) }
    fun setAutoRestart(v: Boolean) = updateConfig { it.copy(autoRestartOnCrash = v) }
    fun setKeepWifiLock(v: Boolean) = updateConfig { it.copy(keepWifiLock = v) }
    fun setKeepCpuWakelock(v: Boolean) = updateConfig { it.copy(keepCpuWakelock = v) }

    fun installDependencies() {
        viewModelScope.launch { controller.installDependencies() }
    }

    fun startServer() {
        viewModelScope.launch { controller.start(config.value) }
    }

    fun stopServer() {
        viewModelScope.launch { controller.stop() }
    }

    fun sendCommand(line: String) = controller.sendCommand(line)

    fun installPlugin(p: PluginInfo) {
        viewModelScope.launch { pluginManager.install(p) }
    }

    fun uninstallPlugin(p: PluginInfo) {
        viewModelScope.launch { pluginManager.uninstall(p) }
    }

    fun startTunnel() {
        viewModelScope.launch { tunnelManager.start(config.value) }
    }

    fun stopTunnel() {
        viewModelScope.launch { tunnelManager.stop() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = McApplication.get()
                val repo = app.repository
                McViewModel(
                    repo = repo,
                    controller = McServerController(repo.termuxRuntime, repo),
                    pluginManager = PluginManager(repo.termuxRuntime, repo),
                    tunnelManager = TunnelManager(repo.termuxRuntime)
                )
            }
        }

        private fun viewModelFactory(initializer: () -> McViewModel) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = initializer() as T
            }
    }
}
