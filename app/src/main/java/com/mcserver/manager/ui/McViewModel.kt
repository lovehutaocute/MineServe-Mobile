package com.mcserver.manager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mcserver.manager.McApplication
import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.PluginInfo
import com.mcserver.manager.data.ServerCore
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.data.ServerState
import com.mcserver.manager.server.McServerController
import com.mcserver.manager.server.PluginManager
import com.mcserver.manager.server.TunnelManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 顶层共享 ViewModel：
 *  - 暴露 McConfig / ServerState / Plugins / ConsoleLog
 *  - 转发用户操作到 Controller / Manager
 *  - 所有操作捕获异常，通过 errorFlow 传递给 UI，不崩溃
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

    /** Termux 环境是否初始化完成 */
    val isBootstrapped: StateFlow<Boolean> = McApplication.get().isBootstrapped

    /** Termux 环境初始化错误信息 */
    val bootstrapError: StateFlow<String?> = McApplication.get().bootstrapError

    /** 重试 Termux 环境初始化 */
    fun retryBootstrap() {
        McApplication.get().startBootstrap()
    }

    /** 删除 Termux 运行环境（会自动重新初始化） */
    fun deleteBootstrap() {
        McApplication.get().deleteBootstrap()
    }

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    /** 错误消息流，UI 层收集后用 Snackbar 显示 */
    private val _errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorFlow = _errorFlow.asSharedFlow()

    /** 操作结果消息流，UI 层收集后用 Snackbar 显示 */
    private val _messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messageFlow = _messageFlow.asSharedFlow()

    /** 依赖安装中状态，UI 层据此控制按钮和加载动画 */
    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    init {
        // 订阅 consoleFlow 并缓存最近 1000 行供 LogsPage 展示，同时解析运行时状态
        viewModelScope.launch {
            repo.termuxRuntime.consoleFlow.collect { line ->
                _consoleLines.value = (_consoleLines.value + line).takeLast(1000)
                parseConsoleLine(line)
            }
        }
    }

    /**
     * 解析 MC 控制台输出，提取 TPS / 玩家数 / 启动状态等运行时信息。
     * - "joined the game" / "left the game" → 实时玩家计数
     * - "There are X of a max of Y players online" → list 命令输出
     * - "TPS from last 1m..." → Paper tps 命令输出
     * - "Done (...)" → 启动完成
     */
    private fun parseConsoleLine(line: String) {
        try {
            when {
                line.contains("joined the game") -> {
                    repo.updateServerState {
                        it.copy(onlinePlayers = (it.onlinePlayers + 1).coerceAtLeast(0))
                    }
                }
                line.contains("left the game") -> {
                    repo.updateServerState {
                        it.copy(onlinePlayers = (it.onlinePlayers - 1).coerceAtLeast(0))
                    }
                }
                line.contains("players online") -> {
                    val m = Regex("There are (\\d+) of a max of (\\d+) players online").find(line)
                    if (m != null) {
                        val online = m.groupValues[1].toIntOrNull() ?: return
                        val max = m.groupValues[2].toIntOrNull() ?: return
                        repo.updateServerState { it.copy(onlinePlayers = online, maxPlayers = max) }
                    }
                }
                line.contains("TPS from last 1m") -> {
                    val m = Regex("TPS from last 1m.*?:\\s*([\\d.]+)").find(line)
                    if (m != null) {
                        val tps = m.groupValues[1].toDoubleOrNull() ?: return
                        val health = ((tps / 20.0) * 100).toInt().coerceIn(0, 100)
                        repo.updateServerState {
                            it.copy(tps = tps, healthPercent = health,
                                maxMemoryMb = config.value.maxHeapMb.toLong())
                        }
                    }
                }
                line.contains("Done (") && line.contains("For help") -> {
                    // 服务器启动完成
                    repo.updateServerState {
                        it.copy(tps = 20.0, healthPercent = 100,
                            maxMemoryMb = config.value.maxHeapMb.toLong())
                    }
                }
            }
        } catch (e: Exception) {
            // 解析失败不影响正常运行
        }
    }

    fun updateConfig(transform: (McConfig) -> McConfig) {
        viewModelScope.launch {
            try {
                repo.saveConfig(transform(config.value))
            } catch (e: Exception) {
                _errorFlow.tryEmit("配置保存失败: ${e.message}")
            }
        }
    }

    fun selectCore(core: ServerCore) =
        updateConfig { it.copy(selectedCore = core) }

    fun setMcVersion(version: String) =
        updateConfig { it.copy(mcVersion = version) }

    fun setLocalPort(port: Int) = updateConfig { it.copy(localPort = port) }
    fun setDomain(d: String) = updateConfig { it.copy(customDomain = d) }
    fun setMaxHeap(mb: Int) = updateConfig { it.copy(maxHeapMb = mb) }
    fun setAutoRestart(v: Boolean) = updateConfig { it.copy(autoRestartOnCrash = v) }
    fun setKeepWifiLock(v: Boolean) = updateConfig { it.copy(keepWifiLock = v) }
    fun setKeepCpuWakelock(v: Boolean) = updateConfig { it.copy(keepCpuWakelock = v) }

    fun installDependencies() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        if (_isInstalling.value) return
        _isInstalling.value = true
        viewModelScope.launch {
            try {
                val ok = controller.installDependencies()
                if (ok) {
                    _messageFlow.tryEmit("依赖安装完成")
                } else {
                    _errorFlow.tryEmit("依赖安装失败，请查看日志")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("依赖安装失败: ${e.message}")
            } finally {
                _isInstalling.value = false
            }
        }
    }

    fun startServer() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        viewModelScope.launch {
            try {
                controller.start(config.value)
                _messageFlow.tryEmit("服务器启动指令已发送")
            } catch (e: Exception) {
                _errorFlow.tryEmit("服务器启动失败: ${e.message}")
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            try {
                controller.stop()
                _messageFlow.tryEmit("服务器停止指令已发送")
            } catch (e: Exception) {
                _errorFlow.tryEmit("服务器停止失败: ${e.message}")
            }
        }
    }

    fun sendCommand(line: String) {
        try {
            controller.sendCommand(line)
        } catch (e: Exception) {
            _errorFlow.tryEmit("命令发送失败: ${e.message}")
        }
    }

    fun installPlugin(p: PluginInfo) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        viewModelScope.launch {
            try {
                pluginManager.install(p)
                _messageFlow.tryEmit("${p.name} 安装完成")
            } catch (e: Exception) {
                _errorFlow.tryEmit("${p.name} 安装失败: ${e.message}")
            }
        }
    }

    fun uninstallPlugin(p: PluginInfo) {
        viewModelScope.launch {
            try {
                pluginManager.uninstall(p)
                _messageFlow.tryEmit("${p.name} 卸载完成")
            } catch (e: Exception) {
                _errorFlow.tryEmit("${p.name} 卸载失败: ${e.message}")
            }
        }
    }

    fun startTunnel() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        viewModelScope.launch {
            try {
                tunnelManager.start(config.value)
                _messageFlow.tryEmit("内网穿透已启动")
            } catch (e: Exception) {
                _errorFlow.tryEmit("内网穿透启动失败: ${e.message}")
            }
        }
    }

    fun stopTunnel() {
        viewModelScope.launch {
            try {
                tunnelManager.stop()
                _messageFlow.tryEmit("内网穿透已停止")
            } catch (e: Exception) {
                _errorFlow.tryEmit("内网穿透停止失败: ${e.message}")
            }
        }
    }

    /** 创建 world 目录快照（zip 打包），返回快照文件路径或 null */
    suspend fun createSnapshot(): String? {
        if (!isBootstrapped.value) return null
        return withContext(Dispatchers.IO) {
            repo.termuxRuntime.createSnapshot()
        }
    }

    // ── 服务端核心下载相关 ──────────────────────────────────────────

    /** 可用版本列表（从 API 获取，供 DownloadScreen 选择） */
    private val _availableVersions = MutableStateFlow<List<String>>(emptyList())
    val availableVersions: StateFlow<List<String>> = _availableVersions.asStateFlow()

    /** 版本列表加载中 */
    private val _isLoadingVersions = MutableStateFlow(false)
    val isLoadingVersions: StateFlow<Boolean> = _isLoadingVersions.asStateFlow()

    /** 服务端核心下载中 */
    private val _isDownloadingCore = MutableStateFlow(false)
    val isDownloadingCore: StateFlow<Boolean> = _isDownloadingCore.asStateFlow()

    /** 加载指定核心的可用版本列表 */
    fun loadVersions(core: ServerCore) {
        viewModelScope.launch {
            _isLoadingVersions.value = true
            try {
                val versions = controller.fetchVersions(core)
                _availableVersions.value = versions
            } catch (e: Exception) {
                _errorFlow.tryEmit("获取版本列表失败: ${e.message}")
                _availableVersions.value = emptyList()
            } finally {
                _isLoadingVersions.value = false
            }
        }
    }

    /** 下载服务端核心（使用 Controller 内部统一路径），成功返回 true */
    fun downloadCore() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        if (_isDownloadingCore.value) return
        _isDownloadingCore.value = true
        viewModelScope.launch {
            try {
                controller.downloadCore(config.value)
                _messageFlow.tryEmit("服务端核心下载完成")
            } catch (e: Exception) {
                _errorFlow.tryEmit("服务端核心下载失败: ${e.message}")
            } finally {
                _isDownloadingCore.value = false
            }
        }
    }

    companion object {
        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = McApplication.get()
                val repo = app.repository
                return McViewModel(
                    repo = repo,
                    controller = McServerController(repo.termuxRuntime, repo),
                    pluginManager = PluginManager(repo.termuxRuntime, repo),
                    tunnelManager = TunnelManager(repo.termuxRuntime)
                ) as T
            }
        }
    }
}
