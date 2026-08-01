package com.mcserver.manager.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mcserver.manager.McApplication
import android.net.Uri
import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.ServerCore
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.data.ServerState
import com.mcserver.manager.data.TunnelState
import com.mcserver.manager.data.TunnelStatus
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.server.BackupManager
import com.mcserver.manager.server.CrashReportManager
import com.mcserver.manager.server.McServerController
import com.mcserver.manager.server.PlayerManager
import com.mcserver.manager.server.PluginManager
import com.mcserver.manager.server.ServerPropertiesManager
import com.mcserver.manager.server.TunnelManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface

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
        viewModelScope, SharingStarted.WhileSubscribed(5_000), McConfig()
    )

    val serverState: StateFlow<ServerState> = repo.serverState

    /** Termux 环境是否初始化完成 */
    val isBootstrapped: StateFlow<Boolean> = McApplication.get().isBootstrapped

    /** Termux 环境初始化错误信息 */
    val bootstrapError: StateFlow<String?> = McApplication.get().bootstrapError

    /** bootstrap 下载速度（bytes/s） */
    val bootstrapSpeed: StateFlow<Long> = McApplication.get().bootstrapSpeed

    /** apt 安装下载速度（bytes/s） */
    private val _installSpeed = MutableStateFlow(0L)
    val installSpeed: StateFlow<Long> = _installSpeed.asStateFlow()

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

    /** 控制台行环形缓冲，避免每行 O(n) 拷贝 */
    private val consoleBuffer = ArrayDeque<String>(1000)
    private var consoleDirty = false

    /** 错误消息流，UI 层收集后用 Snackbar 显示 */
    private val _errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorFlow = _errorFlow.asSharedFlow()

    /** 操作结果消息流，UI 层收集后用 Snackbar 显示 */
    private val _messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messageFlow = _messageFlow.asSharedFlow()

    /** 依赖安装中状态，UI 层据此控制按钮和加载动画 */
    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    /** 局域网 IP（IPv4，非 loopback），用于 Network Tab 展示和一键复制 */
    private val _lanIp = MutableStateFlow("--")
    val lanIp: StateFlow<String> = _lanIp.asStateFlow()

    /** 隧道运行状态，UI 层订阅展示 */
    val tunnelState: StateFlow<TunnelState> = tunnelManager.state

    // ── 设备状态（常规权限可采集，无需 root） ─────────────────────────

    /** 设备级指标：内存/存储/电池 */
    data class DeviceStats(
        val totalMemoryMb: Long = 0L,
        val availMemoryMb: Long = 0L,
        val totalStorageMb: Long = 0L,
        val availStorageMb: Long = 0L,
        val batteryPercent: Int = -1,   // -1 表示未知
        val isCharging: Boolean = false
    )

    private val _deviceStats = MutableStateFlow(DeviceStats())
    val deviceStats: StateFlow<DeviceStats> = _deviceStats.asStateFlow()

    /** 定时采集设备指标（每 3 秒），同时将 MC 进程真实内存写入 usedMemoryMb */
    private fun startDeviceStatsCollection() {
        viewModelScope.launch {
            while (true) {
                withContext(Dispatchers.IO) { collectDeviceStatsOnce() }
                delay(3000)
            }
        }
    }

    private fun collectDeviceStatsOnce() {
        try {
            val app = McApplication.get()
            // 设备内存（ActivityManager.MemoryInfo，无需权限）
            val am = app.getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val mi = android.app.ActivityManager.MemoryInfo()
            am.getMemoryInfo(mi)
            val totalMemMb = mi.totalMem / (1024 * 1024)
            val availMemMb = mi.availMem / (1024 * 1024)

            // 内部存储（StatFs，无需权限）
            val stat = android.os.StatFs(android.os.Environment.getDataDirectory().path)
            val blockSize = stat.blockSizeLong
            val totalStorageMb = stat.blockCountLong * blockSize / (1024 * 1024)
            val availStorageMb = stat.availableBlocksLong * blockSize / (1024 * 1024)

            // 电池电量与充电状态（BatteryManager，无需权限）
            var batteryPercent = -1
            var isCharging = false
            try {
                val bm = app.getSystemService(android.content.Context.BATTERY_SERVICE) as android.os.BatteryManager
                batteryPercent = bm.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY)
                isCharging = bm.isCharging // API 23+，minSdk 26 可用
            } catch (e: Exception) {
                // 部分设备/模拟器不支持电池属性，保持 -1
            }

            val newStats = DeviceStats(
                totalMemoryMb = totalMemMb,
                availMemoryMb = availMemMb,
                totalStorageMb = totalStorageMb,
                availStorageMb = availStorageMb,
                batteryPercent = batteryPercent,
                isCharging = isCharging
            )
            // 值未变化时不推送，避免每 3 秒触发 UI 重组
            if (_deviceStats.value != newStats) _deviceStats.value = newStats

            // 修复 usedMemoryMb：MC 进程真实 RSS（原字段从未被采集，恒为 0）
            if (repo.termuxRuntime.isMcRunning()) {
                val mem = repo.termuxRuntime.mcProcessMemoryMb()
                if (mem > 0) repo.updateServerState { it.copy(usedMemoryMb = mem) }
            }
        } catch (e: Exception) {
            // 采集失败保留上次值，不影响运行
        }
    }

    /** 刷新局域网 IP：在 IO 线程遍历网络接口，取第一个非 loopback 的 IPv4 地址 */
    fun refreshLanIp() {
        viewModelScope.launch(Dispatchers.IO) {
            _lanIp.value = queryLanIp()
        }
    }

    private fun queryLanIp(): String {
        return try {
            NetworkInterface.getNetworkInterfaces()
                .asSequence()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.asSequence() }
                .filterIsInstance<Inet4Address>()
                .filter { !it.isLoopbackAddress }
                .map { it.hostAddress ?: "" }
                .firstOrNull { it.isNotEmpty() }
                ?: "--"
        } catch (e: Exception) {
            "--"
        }
    }

    /** 一键复制服务器连接地址到剪贴板，格式：IP:端口 */
    fun copyServerAddress(context: android.content.Context) {
        val ip = _lanIp.value
        val port = config.value.localPort
        val address = if (ip == "--") {
            _errorFlow.tryEmit("无法获取局域网 IP，请确认已连接 WiFi")
            return
        } else {
            "$ip:$port"
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MC Server", address))
        _messageFlow.tryEmit("已复制：$address")
    }

    companion object {
        // 预编译正则，避免每行重新编译
        private val PLAYERS_REGEX = Regex("There are (\\d+) of a max of (\\d+) players online")
        private val TPS_REGEX = Regex("TPS from last 1m.*?:\\s*([\\d.]+)")

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                val app = McApplication.get()
                val repo = app.repository
                return McViewModel(
                    repo = repo,
                    controller = McServerController(repo.termuxRuntime, repo),
                    pluginManager = PluginManager(repo.termuxRuntime, app),
                    tunnelManager = TunnelManager(repo.termuxRuntime)
                ) as T
            }
        }
    }

    /**
     * 解析 MC 控制台输出，提取 TPS / 玩家数 / 启动状态等运行时信息。
     * 使用快速前缀检查避免不必要的正则匹配。
     */
    private fun parseConsoleLine(line: String) {
        try {
            // 快速前缀检查：只有包含关键子串的行才进一步处理
            when {
                line.contains("joined the game") -> {
                    // 仅当提取到真实玩家名（日志前缀 + 合法名字）时才计数与记录，避免聊天消息误报
                    playerManager.extractPlayerName(line)?.let { name ->
                        repo.updateServerState {
                            it.copy(onlinePlayers = (it.onlinePlayers + 1).coerceAtLeast(0))
                        }
                        addOnlinePlayer(name)
                        recordPlayerEvent(name, "进服")
                    }
                }
                line.contains("left the game") -> {
                    playerManager.extractPlayerName(line)?.let { name ->
                        repo.updateServerState {
                            it.copy(onlinePlayers = (it.onlinePlayers - 1).coerceAtLeast(0))
                        }
                        removeOnlinePlayer(name)
                        recordPlayerEvent(name, "离服")
                    }
                }
                line.contains("players online") -> {
                    val m = PLAYERS_REGEX.find(line)
                    if (m != null) {
                        val online = m.groupValues[1].toIntOrNull() ?: return
                        val max = m.groupValues[2].toIntOrNull() ?: return
                        repo.updateServerState { it.copy(onlinePlayers = online, maxPlayers = max) }
                        // 全量校正在线玩家名单（list 命令响应）
                        playerManager.parseOnlinePlayers(line)?.let { names ->
                            _onlinePlayerNames.value = names
                        }
                    }
                }
                line.contains("TPS from last 1m") -> {
                    val m = TPS_REGEX.find(line)
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
                    repo.updateServerState {
                        it.copy(tps = 20.0, healthPercent = 100,
                            maxMemoryMb = config.value.maxHeapMb.toLong(),
                            runningSinceMs = android.os.SystemClock.elapsedRealtime())
                    }
                    // 启动完成：主动请求一次 list，全量校正在线玩家名单
                    if (repo.termuxRuntime.isMcRunning()) playerManager.requestOnlineList()
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
    fun setTunnelType(type: TunnelType) = updateConfig { it.copy(tunnelType = type) }
    fun setMaxHeap(mb: Int) = updateConfig { it.copy(maxHeapMb = mb) }
    fun setAutoRestart(v: Boolean) = updateConfig { it.copy(autoRestartOnCrash = v) }
    fun setKeepWifiLock(v: Boolean) = updateConfig { it.copy(keepWifiLock = v) }
    fun setKeepCpuWakelock(v: Boolean) = updateConfig { it.copy(keepCpuWakelock = v) }
    fun setDownloadMirror(mirror: com.mcserver.manager.data.DownloadMirror) =
        updateConfig { it.copy(downloadMirror = mirror) }
    fun setAptMirror(mirror: com.mcserver.manager.data.AptMirror) =
        updateConfig { it.copy(aptMirror = mirror) }

    fun installDependencies() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        if (_isInstalling.value) return
        _isInstalling.value = true
        viewModelScope.launch {
            try {
                val ok = controller.installDependencies { speedBps ->
                    _installSpeed.value = speedBps
                }
                _installSpeed.value = 0L
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

    // ── 内网穿透 ──────────────────────────────────────────────

    fun startTunnel() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        if (tunnelState.value.status == TunnelStatus.Starting) {
            _errorFlow.tryEmit("隧道正在启动中，请稍候...")
            return
        }
        viewModelScope.launch {
            try {
                tunnelManager.start(config.value)
                val st = tunnelState.value
                when (st.status) {
                    TunnelStatus.Running -> {
                        val url = st.publicUrl
                        _messageFlow.tryEmit(
                            if (url.isNotBlank()) "隧道已启动，公网地址: $url"
                            else "隧道已启动"
                        )
                    }
                    TunnelStatus.Starting -> _messageFlow.tryEmit("隧道正在启动，请查看日志...")
                    TunnelStatus.Failed -> _errorFlow.tryEmit("隧道启动失败: ${st.errorMessage}")
                    else -> _messageFlow.tryEmit("隧道指令已发送")
                }
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

    fun copyTunnelUrl(context: android.content.Context) {
        val url = tunnelState.value.publicUrl
        if (url.isBlank()) {
            _errorFlow.tryEmit("暂无公网地址，请先启动隧道")
            return
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Tunnel URL", url))
        _messageFlow.tryEmit("已复制：$url")
    }

    fun sendCommand(line: String) {
        try {
            controller.sendCommand(line)
        } catch (e: Exception) {
            _errorFlow.tryEmit("命令发送失败: ${e.message}")
        }
    }

    // ── 插件管理（新版） ──────────────────────────────────────────

    /** 插件下载进度（结构复用 DownloadProgress） */
    data class PluginDownloadProgress(
        val pluginId: String,
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val speedBytesPerSec: Long = 0L
    ) {
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
        val speedText: String
            get() = when {
                speedBytesPerSec <= 0 -> "—"
                speedBytesPerSec >= 1024 * 1024 ->
                    String.format(java.util.Locale.US, "%.2f MB/s", speedBytesPerSec / (1024.0 * 1024.0))
                speedBytesPerSec >= 1024 ->
                    String.format(java.util.Locale.US, "%.1f KB/s", speedBytesPerSec / 1024.0)
                else -> "$speedBytesPerSec B/s"
            }
    }

    /** 精选插件库（直接暴露给 UI） */
    val curatedPlugins: List<PluginManager.CuratedPlugin>
        get() = pluginManager.curatedPlugins

    /** 真实已安装插件列表 */
    private val _installedPlugins = MutableStateFlow<List<PluginManager.InstalledPlugin>>(emptyList())
    val installedPlugins: StateFlow<List<PluginManager.InstalledPlugin>> = _installedPlugins.asStateFlow()

    /** 当前正在下载的插件 id → 进度 */
    private val _pluginDownloadProgress = MutableStateFlow<Map<String, PluginDownloadProgress>>(emptyMap())
    val pluginDownloadProgress: StateFlow<Map<String, PluginDownloadProgress>> = _pluginDownloadProgress.asStateFlow()

    /** 是否有任意插件正在下载 */
    val isPluginDownloading: StateFlow<Boolean> = _pluginDownloadProgress
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** 扫描当前核心的 plugins 目录 */
    fun refreshInstalledPlugins() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择服务端核心")
            return
        }
        viewModelScope.launch {
            try {
                _installedPlugins.value = pluginManager.scan(dirName)
            } catch (e: Exception) {
                _errorFlow.tryEmit("扫描插件目录失败: ${e.message}")
            }
        }
    }

    /** 从精选库下载安装插件 */
    fun installCuratedPlugin(curated: PluginManager.CuratedPlugin) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择服务端核心")
            return
        }
        if (_pluginDownloadProgress.value.containsKey(curated.id)) {
            _errorFlow.tryEmit("${curated.name} 正在下载中，请稍候")
            return
        }
        viewModelScope.launch {
            try {
                pluginManager.installFromUrl(
                    curated.downloadUrl,
                    curated.targetFileName,
                    dirName
                ) { downloaded, total, speed ->
                    _pluginDownloadProgress.value = _pluginDownloadProgress.value + (curated.id to PluginDownloadProgress(
                        pluginId = curated.id,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    ))
                }
                _messageFlow.tryEmit("${curated.name} 安装完成")
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - curated.id
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - curated.id
                _errorFlow.tryEmit("${curated.name} 安装失败: ${e.message}")
            }
        }
    }

    /** 从本地 Uri 上传插件 */
    fun installPluginFromUri(uri: Uri) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择服务端核心")
            return
        }
        viewModelScope.launch {
            try {
                val fileName = pluginManager.installFromUri(uri, dirName)
                _messageFlow.tryEmit("插件 $fileName 上传成功")
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _errorFlow.tryEmit("插件上传失败: ${e.message}")
            }
        }
    }

    /** 删除插件 */
    fun deletePlugin(fileName: String, alsoRemoveDataDir: Boolean = false) {
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择服务端核心")
            return
        }
        viewModelScope.launch {
            try {
                val ok = pluginManager.delete(fileName, dirName, alsoRemoveDataDir)
                if (ok) {
                    val msg = if (alsoRemoveDataDir) "$fileName 已删除（含数据目录）" else "$fileName 已删除"
                    _messageFlow.tryEmit(msg)
                    refreshInstalledPlugins()
                } else {
                    _errorFlow.tryEmit("删除失败：文件不存在或被占用")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("删除失败: ${e.message}")
            }
        }
    }

    /** 切换插件启用/禁用 */
    fun togglePluginEnabled(fileName: String) {
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择服务端核心")
            return
        }
        viewModelScope.launch {
            try {
                val newName = pluginManager.toggleEnabled(fileName, dirName)
                if (newName != null) {
                    val action = if (newName.startsWith("-")) "已禁用" else "已启用"
                    _messageFlow.tryEmit("$action $fileName")
                    refreshInstalledPlugins()
                } else {
                    _errorFlow.tryEmit("切换状态失败：文件不存在")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("切换状态失败: ${e.message}")
            }
        }
    }

    /** 判断精选插件是否已安装（用于 UI 显示徽章） */
    fun isCuratedPluginInstalled(curated: PluginManager.CuratedPlugin): Boolean {
        return pluginManager.isCuratedInstalled(curated, _installedPlugins.value)
    }

    // ── 精选插件更新检测 ──

    /** 更新检测结果（key = curated.id） */
    private val _curatedUpdates = MutableStateFlow<Map<String, PluginManager.CuratedUpdateInfo>>(emptyMap())
    val curatedUpdates: StateFlow<Map<String, PluginManager.CuratedUpdateInfo>> = _curatedUpdates.asStateFlow()

    /** 是否正在检测更新 */
    private val _isCheckingUpdates = MutableStateFlow(false)
    val isCheckingUpdates: StateFlow<Boolean> = _isCheckingUpdates.asStateFlow()

    /** 检测精选插件更新（5 分钟内有缓存不重复请求） */
    fun checkCuratedUpdates() {
        if (_isCheckingUpdates.value) return
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择服务端核心")
            return
        }
        viewModelScope.launch {
            _isCheckingUpdates.value = true
            try {
                val installed = pluginManager.scan(dirName)
                val updates = pluginManager.checkCuratedUpdates(installed)
                _curatedUpdates.value = updates.associateBy { it.curated.id }
                val updateCount = updates.count { it.hasUpdate }
                _messageFlow.tryEmit(
                    if (updateCount > 0) "检测完成：$updateCount 个插件有更新"
                    else "检测完成：所有精选插件均为最新版本"
                )
            } catch (e: Exception) {
                _errorFlow.tryEmit("更新检测失败: ${e.message}")
            } finally {
                _isCheckingUpdates.value = false
            }
        }
    }

    /** 强制重新检测（清空缓存） */
    fun forceRecheckUpdates() {
        pluginManager.invalidateUpdateCache()
        checkCuratedUpdates()
    }

    // ── 自定义 URL 下载 ──

    private val URL_DOWNLOAD_ID = "__custom_url__"

    /** 从自定义 URL 下载插件 */
    fun installPluginFromUrl(url: String, customFileName: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择服务端核心")
            return
        }
        if (url.isBlank() || customFileName.isBlank()) {
            _errorFlow.tryEmit("URL 和文件名不能为空")
            return
        }
        if (_pluginDownloadProgress.value.containsKey(URL_DOWNLOAD_ID)) {
            _errorFlow.tryEmit("已有下载任务进行中，请稍候")
            return
        }
        viewModelScope.launch {
            try {
                pluginManager.installFromUrl(url, customFileName, dirName) { downloaded, total, speed ->
                    _pluginDownloadProgress.value = _pluginDownloadProgress.value + (URL_DOWNLOAD_ID to PluginDownloadProgress(
                        pluginId = URL_DOWNLOAD_ID,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    ))
                }
                _messageFlow.tryEmit("$customFileName 下载安装完成")
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - URL_DOWNLOAD_ID
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - URL_DOWNLOAD_ID
                _errorFlow.tryEmit("自定义 URL 下载失败: ${e.message}")
            }
        }
    }

    /** 判断自定义 URL 是否正在下载 */
    fun isCustomUrlDownloading(): Boolean = _pluginDownloadProgress.value.containsKey(URL_DOWNLOAD_ID)

    /** 获取自定义 URL 下载进度（无则 null） */
    fun customUrlDownloadProgress(): PluginDownloadProgress? =
        _pluginDownloadProgress.value[URL_DOWNLOAD_ID]

    /** 获取当前核心的 plugins 目录路径（用于 UI 显示） */
    fun currentPluginsPath(): String? {
        val dirName = activeDirName() ?: return null
        return java.io.File(repo.termuxRuntime.installer.rootDir, "home/servers/$dirName/plugins").absolutePath
    }


    /** 获取当前选用核心的 dirName，未选择时返回 null */
    private fun activeDirName(): String? {
        return config.value.installedCores.find { it.name == config.value.activeCoreName }?.dirName
    }

    /** 创建 world 目录快照（zip 打包），返回快照文件路径或 null */
    suspend fun createSnapshot(): String? {
        if (!isBootstrapped.value) return null
        val dirName = activeDirName() ?: return null
        val maxSnap = config.value.maxSnapshots
        return withContext(Dispatchers.IO) {
            repo.termuxRuntime.createSnapshot(maxSnapshots = maxSnap, dirName = dirName)
        }
    }

    // ── 备份恢复管理 ────────────────────────────────────────────────

    private val backupManager = BackupManager(repo.termuxRuntime)

    private val _snapshots = MutableStateFlow<List<BackupManager.SnapshotInfo>>(emptyList())
    val snapshots: StateFlow<List<BackupManager.SnapshotInfo>> = _snapshots.asStateFlow()

    /** 加载快照列表 */
    fun loadSnapshots() {
        if (!isBootstrapped.value) return
        viewModelScope.launch {
            try {
                _snapshots.value = withContext(Dispatchers.IO) { backupManager.listSnapshots() }
            } catch (e: Exception) {
                _errorFlow.tryEmit("加载快照列表失败: ${e.message}")
            }
        }
    }

    /** 恢复快照（会先停止服务器） */
    fun restoreSnapshot(name: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择要操作的服务端核心")
            return
        }
        viewModelScope.launch {
            try {
                _messageFlow.tryEmit("正在恢复快照，服务器将停止...")
                val ok = withContext(Dispatchers.IO) { backupManager.restoreSnapshot(name, dirName) }
                if (ok) {
                    _messageFlow.tryEmit("快照恢复成功，请重新启动服务器")
                    loadSnapshots()
                } else {
                    _errorFlow.tryEmit("快照恢复失败")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("恢复快照失败: ${e.message}")
            }
        }
    }

    /** 删除快照 */
    fun deleteSnapshot(name: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.deleteSnapshot(name) }
                if (ok) {
                    _messageFlow.tryEmit("已删除快照: $name")
                    loadSnapshots()
                } else {
                    _errorFlow.tryEmit("删除快照失败: 文件不存在")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("删除快照失败: ${e.message}")
            }
        }
    }

    // ── server.properties 编辑 ─────────────────────────────────────

    private val propertiesManager = ServerPropertiesManager(repo.termuxRuntime)

    private val _serverProperties = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverProperties: StateFlow<Map<String, String>> = _serverProperties.asStateFlow()

    /** 加载 server.properties */
    fun loadServerProperties() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                _serverProperties.value = withContext(Dispatchers.IO) { propertiesManager.readProperties(dirName) }
            } catch (e: Exception) {
                _errorFlow.tryEmit("读取 server.properties 失败: ${e.message}")
            }
        }
    }

    /** 保存 server.properties */
    fun saveServerProperties(props: Map<String, String>) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit("未选择要操作的服务端核心")
            return
        }
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { propertiesManager.writeProperties(props, dirName) }
                if (ok) {
                    _messageFlow.tryEmit("server.properties 保存成功")
                    _serverProperties.value = props
                } else {
                    _errorFlow.tryEmit("server.properties 保存失败")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("保存 server.properties 失败: ${e.message}")
            }
        }
    }

    // ── 玩家管理 ────────────────────────────────────────────────────

    // 注意：playerManager 等属性被 init 中启动的协程（IO 线程）访问，
    // 声明位置在 init 之后，必须用 lazy 延迟初始化，避免构造函数未完成时读到 null
    private val playerManager: PlayerManager by lazy { PlayerManager(repo.termuxRuntime) }

    private val _ops = MutableStateFlow<List<PlayerManager.OpEntry>>(emptyList())
    val ops: StateFlow<List<PlayerManager.OpEntry>> = _ops.asStateFlow()

    private val _whitelist = MutableStateFlow<List<PlayerManager.WhitelistEntry>>(emptyList())
    val whitelist: StateFlow<List<PlayerManager.WhitelistEntry>> = _whitelist.asStateFlow()

    private val _bannedPlayers = MutableStateFlow<List<PlayerManager.BannedEntry>>(emptyList())
    val bannedPlayers: StateFlow<List<PlayerManager.BannedEntry>> = _bannedPlayers.asStateFlow()

    /** 白名单开关状态（由 server.properties 的 white-list 控制） */
    private val _whitelistEnabled = MutableStateFlow(false)
    val whitelistEnabled: StateFlow<Boolean> = _whitelistEnabled.asStateFlow()

    /** 在线玩家名列表（从日志解析） */
    private val _onlinePlayerNames: MutableStateFlow<List<String>> by lazy { MutableStateFlow(emptyList()) }
    val onlinePlayerNames: StateFlow<List<String>> by lazy { _onlinePlayerNames.asStateFlow() }

    /** 玩家进服/离服历史记录（最新在前，上限 500 条），持久化到 app 私有目录 */
    @Serializable
    data class PlayerHistoryEntry(
        val player: String,
        val event: String,
        val time: String
    )

    private val _playerHistory: MutableStateFlow<List<PlayerHistoryEntry>> by lazy { MutableStateFlow(emptyList()) }
    val playerHistory: StateFlow<List<PlayerHistoryEntry>> by lazy { _playerHistory.asStateFlow() }

    private val playerHistoryFile: java.io.File
        get() = java.io.File(McApplication.get().filesDir, "player_history.json")

    private val historyJson: Json by lazy { Json { ignoreUnknownKeys = true } }

    /** 历史记录文件读写互斥，避免多人进出服时并发写导致 JSON 损坏 */
    private val historyMutex: Mutex by lazy { Mutex() }

    /** 启动时异步加载历史记录文件（文件缺失/损坏时从空历史开始） */
    private fun loadPlayerHistory() {
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    val f = playerHistoryFile
                    if (f.exists()) {
                        val fileList = historyJson.decodeFromString<List<PlayerHistoryEntry>>(f.readText())
                        // 与启动瞬间已记录的内存事件合并去重（按时间倒序，保留最新 500 条），
                        // 避免文件加载晚于首条进服事件时覆盖内存新条目
                        val merged = (_playerHistory.value + fileList)
                            .distinct()
                            .sortedByDescending { it.time }
                            .take(500)
                        _playerHistory.value = merged
                    }
                } catch (e: Exception) {
                    // 忽略损坏文件
                }
            }
        }
    }

    /** 追加一条进服/离服事件并异步持久化（保留最近 500 条） */
    private fun recordPlayerEvent(player: String, event: String) {
        val entry = PlayerHistoryEntry(player, event, timeNow())
        _playerHistory.value = (listOf(entry) + _playerHistory.value).take(500)
        val snapshot = _playerHistory.value
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    playerHistoryFile.writeText(historyJson.encodeToString(snapshot))
                } catch (e: Exception) {
                    // 写入失败不阻断运行
                }
            }
        }
    }

    private fun timeNow(): String =
        java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
            .format(java.util.Date())

    /** 在线玩家集合维护（进服：增量添加，去重） */
    private fun addOnlinePlayer(name: String) {
        val cur = _onlinePlayerNames.value
        if (name !in cur) _onlinePlayerNames.value = cur + name
    }

    /** 在线玩家集合维护（离服：移除） */
    private fun removeOnlinePlayer(name: String) {
        _onlinePlayerNames.value = _onlinePlayerNames.value.filter { it != name }
    }

    /** 请求在线玩家列表（发送 list 命令，结果通过日志解析全量校正名单） */
    fun refreshOnlinePlayers() {
        if (!repo.termuxRuntime.isMcRunning()) return
        playerManager.requestOnlineList()
    }

    /** 刷新玩家数据（OP/白名单/封禁列表） */
    fun refreshPlayers() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    _ops.value = playerManager.readOps(dirName)
                    _whitelist.value = playerManager.readWhitelist(dirName)
                    _bannedPlayers.value = playerManager.readBanned(dirName)
                    // 同步白名单开关状态（从 server.properties 读取）
                    val props = propertiesManager.readProperties(dirName)
                    _whitelistEnabled.value = props["white-list"]?.equals("true", ignoreCase = true) == true
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("刷新玩家数据失败: ${e.message}")
            }
        }
    }

    /**
     * 命令发送后延迟刷新，等待 MC 完成回写 JSON 文件
     * @param msg 操作成功消息
     * @param refresh 是否需要刷新列表（kick 不改列表无需刷新）
     */
    private fun afterCmd(sent: Boolean, msg: String, refresh: Boolean = true) {
        if (!sent) {
            _errorFlow.tryEmit("服务器未运行，无法发送命令")
            return
        }
        _messageFlow.tryEmit(msg)
        if (refresh) {
            viewModelScope.launch {
                delay(600)  // 等待 MC 处理命令并回写 JSON
                refreshPlayers()
            }
        }
    }

    fun opPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.opPlayer(name)
        afterCmd(sent, "已为 $name 添加 OP")
    }

    /** 设置 OP 并指定等级（1-4） */
    fun opPlayerWithLevel(name: String, level: Int) {
        if (name.isBlank()) return
        val sent = playerManager.opPlayerWithLevel(name, level)
        afterCmd(sent, "已为 $name 设置 OP（等级 $level）")
    }

    fun deopPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.deopPlayer(name)
        afterCmd(sent, "已取消 $name 的 OP")
    }

    fun whitelistAdd(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistAdd(name)
        afterCmd(sent, "已将 $name 加入白名单")
    }

    fun whitelistRemove(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistRemove(name)
        afterCmd(sent, "已将 $name 移出白名单")
    }

    /** 切换白名单开关 */
    fun toggleWhitelist(enabled: Boolean) {
        val sent = if (enabled) playerManager.whitelistOn() else playerManager.whitelistOff()
        if (sent) {
            _whitelistEnabled.value = enabled
            _messageFlow.tryEmit(if (enabled) "白名单已开启" else "白名单已关闭")
        } else {
            _errorFlow.tryEmit("服务器未运行，无法切换白名单")
        }
    }

    fun kickPlayer(name: String, reason: String = "") {
        if (name.isBlank()) return
        val sent = playerManager.kickPlayer(name, reason)
        afterCmd(sent, "已踢出 $name", refresh = false)
    }

    fun banPlayer(name: String, reason: String = "Banned by admin") {
        if (name.isBlank()) return
        val sent = playerManager.banPlayer(name, reason)
        afterCmd(sent, "已永久封禁 $name")
    }

    /** 限时封禁 */
    fun tempBanPlayer(name: String, duration: String, reason: String = "") {
        if (name.isBlank() || duration.isBlank()) return
        val sent = playerManager.tempBanPlayer(name, duration, reason)
        afterCmd(sent, "已限时封禁 $name（$duration）")
    }

    fun pardonPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.pardonPlayer(name)
        afterCmd(sent, "已解除 $name 的封禁")
    }

    /** 请求在线玩家列表（发送 list 命令） */
    fun requestOnlinePlayers() {
        val sent = playerManager.requestOnlineList()
        if (sent) {
            _messageFlow.tryEmit("已请求在线玩家列表，结果将显示在日志中")
        } else {
            _errorFlow.tryEmit("服务器未运行")
        }
    }

    /** 设置玩家游戏模式 */
    fun setGameMode(name: String, mode: Int) {
        if (name.isBlank()) return
        val sent = playerManager.setGameMode(name, mode)
        val modeName = when (mode) { 0 -> "生存"; 1 -> "创造"; 2 -> "冒险"; 3 -> "旁观"; else -> "未知" }
        afterCmd(sent, "已将 $name 设为$modeName 模式", refresh = false)
    }

    /** 给玩家经验 */
    fun giveXp(name: String, amount: Int) {
        if (name.isBlank() || amount <= 0) return
        val sent = playerManager.giveXp(name, amount)
        afterCmd(sent, "已给予 $name $amount 经验", refresh = false)
    }

    // ── 崩溃报告 ────────────────────────────────────────────────────

    private val crashReportManager = CrashReportManager(repo.termuxRuntime)

    private val _crashReports = MutableStateFlow<List<CrashReportManager.CrashReport>>(emptyList())
    val crashReports: StateFlow<List<CrashReportManager.CrashReport>> = _crashReports.asStateFlow()

    /** 加载崩溃报告列表 */
    fun loadCrashReports() {
        if (!isBootstrapped.value) return
        viewModelScope.launch {
            try {
                _crashReports.value = withContext(Dispatchers.IO) { crashReportManager.listCrashReports() }
            } catch (e: Exception) {
                _errorFlow.tryEmit("加载崩溃报告失败: ${e.message}")
            }
        }
    }

    /** 当前查看的崩溃报告全文（供 UI 展示） */
    private val _currentCrashContent = MutableStateFlow<String?>(null)
    val currentCrashContent: StateFlow<String?> = _currentCrashContent.asStateFlow()

    /** 读取崩溃报告全文 */
    fun readCrashReport(fileName: String) {
        viewModelScope.launch {
            try {
                _currentCrashContent.value = withContext(Dispatchers.IO) { crashReportManager.readCrashReport(fileName) }
                if (_currentCrashContent.value == null) {
                    _errorFlow.tryEmit("读取崩溃报告失败: 文件不存在")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("读取崩溃报告失败: ${e.message}")
            }
        }
    }

    /** 关闭崩溃报告详情视图 */
    fun clearCrashContent() {
        _currentCrashContent.value = null
    }

    /** 删除崩溃报告 */
    fun deleteCrashReport(fileName: String) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { crashReportManager.deleteCrashReport(fileName) }
                _messageFlow.tryEmit("已删除崩溃报告: $fileName")
                loadCrashReports()
            } catch (e: Exception) {
                _errorFlow.tryEmit("删除崩溃报告失败: ${e.message}")
            }
        }
    }

    /** 清空所有崩溃报告 */
    fun clearCrashReports() {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { crashReportManager.clearAllCrashReports() }
                _messageFlow.tryEmit("已清空 $count 个崩溃报告")
                loadCrashReports()
            } catch (e: Exception) {
                _errorFlow.tryEmit("清空崩溃报告失败: ${e.message}")
            }
        }
    }

    // ── 定时备份配置 ────────────────────────────────────────────────

    fun setAutoBackupInterval(min: Int) = updateConfig { it.copy(autoBackupIntervalMin = min) }
    fun setMaxSnapshots(max: Int) = updateConfig { it.copy(maxSnapshots = max) }

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

    /** 核心下载进度信息（已下载字节、总字节、速度 bytes/s），仅在下载中有效 */
    data class DownloadProgress(
        val downloadedBytes: Long = 0L,
        val totalBytes: Long = -1L,
        val speedBytesPerSec: Long = 0L
    ) {
        /** 速度的格式化文本，如 "2.35 MB/s" 或 "128 KB/s" */
        val speedText: String
            get() = formatSpeed(speedBytesPerSec)
        /** 进度百分比（0-100），总字节未知时为 0 */
        val percent: Int
            get() = if (totalBytes > 0) ((downloadedBytes * 100) / totalBytes).toInt().coerceIn(0, 100) else 0
        /** 已下载大小文本，如 "12.5 MB" */
        val downloadedText: String
            get() = formatSize(downloadedBytes)
        /** 总大小文本，如 "58.0 MB"，未知时为 "?" */
        val totalText: String
            get() = if (totalBytes > 0) formatSize(totalBytes) else "?"

        private fun formatSpeed(bytesPerSec: Long): String {
            val mb = bytesPerSec / 1024.0 / 1024.0
            val kb = bytesPerSec / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.2f MB/s", mb)
                kb >= 1.0 -> String.format("%.0f KB/s", kb)
                else -> "$bytesPerSec B/s"
            }
        }
        private fun formatSize(bytes: Long): String {
            val mb = bytes / 1024.0 / 1024.0
            val kb = bytes / 1024.0
            return when {
                mb >= 1.0 -> String.format("%.1f MB", mb)
                kb >= 1.0 -> String.format("%.0f KB", kb)
                else -> "$bytes B"
            }
        }
    }

    private val _downloadProgress = MutableStateFlow(DownloadProgress())
    val downloadProgress: StateFlow<DownloadProgress> = _downloadProgress.asStateFlow()

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

    /** 下载服务端核心（使用自定义名称，保存到独立目录），成功返回 true */
    fun downloadCore(customName: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        if (_isDownloadingCore.value) return
        if (customName.isBlank()) {
            _errorFlow.tryEmit("请输入核心名称")
            return
        }
        _isDownloadingCore.value = true
        _downloadProgress.value = DownloadProgress()
        viewModelScope.launch {
            try {
                controller.downloadCore(config.value, customName.trim()) { downloaded, total, speed ->
                    _downloadProgress.value = DownloadProgress(downloaded, total, speed)
                }
                _messageFlow.tryEmit("服务端核心「${customName.trim()}」下载完成")
            } catch (e: Exception) {
                _errorFlow.tryEmit("服务端核心下载失败: ${e.message}")
            } finally {
                _isDownloadingCore.value = false
                _downloadProgress.value = DownloadProgress()
            }
        }
    }

    /** 选择要启动的核心（按名称） */
    fun setActiveCore(name: String) {
        updateConfig { it.copy(activeCoreName = name) }
    }

    /** 删除一个已安装的核心（按名称） */
    fun deleteCore(name: String) {
        viewModelScope.launch {
            try {
                val core = config.value.installedCores.find { it.name == name }
                    ?: throw RuntimeException("核心 $name 不存在")
                // 删除整个文件夹
                val dir = repo.termuxRuntime.serverDirFor(core.dirName)
                val deleted = withContext(Dispatchers.IO) { dir.deleteRecursively() }
                if (!deleted) {
                    _errorFlow.tryEmit("删除核心文件夹失败: ${dir.absolutePath}")
                    return@launch
                }
                val updated = config.value.installedCores.filter { it.name != name }
                val newActive = if (config.value.activeCoreName == name) updated.firstOrNull()?.name else config.value.activeCoreName
                repo.saveConfig(config.value.copy(
                    installedCores = updated,
                    activeCoreName = newActive
                ))
                _messageFlow.tryEmit("已删除核心「$name」")
            } catch (e: Exception) {
                _errorFlow.tryEmit("删除核心失败: ${e.message}")
            }
        }
    }

    // ── 文件管理 ────────────────────────────────────────────────────

    data class FileEntry(
        val name: String,
        val path: String,
        val isDirectory: Boolean,
        val sizeBytes: Long,
        val sizeText: String,
        val lastModified: Long,
        val modifiedText: String
    )

    private val _fileList = MutableStateFlow<List<FileEntry>>(emptyList())
    val fileList: StateFlow<List<FileEntry>> = _fileList.asStateFlow()

    private val _currentPath = MutableStateFlow("")
    val currentPath: StateFlow<String> = _currentPath.asStateFlow()

    /** 文件管理根目录（home/servers/） */
    val fileManagerRoot: java.io.File
        get() = java.io.File(repo.termuxRuntime.installer.rootDir, "home/servers")

    /** 加载指定目录的文件列表 */
    fun loadFiles(path: java.io.File) {
        viewModelScope.launch {
            try {
                _currentPath.value = path.absolutePath
                _fileList.value = withContext(Dispatchers.IO) {
                    if (!path.exists() || !path.isDirectory) {
                        emptyList()
                    } else {
                        path.listFiles()?.sortedWith(
                            compareByDescending<java.io.File> { it.isDirectory }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        )?.map { f ->
                            FileEntry(
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = f.isDirectory,
                                sizeBytes = if (f.isFile) f.length() else 0L,
                                sizeText = if (f.isFile) formatBytes(f.length()) else "",
                                lastModified = f.lastModified(),
                                modifiedText = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(f.lastModified()))
                            )
                        } ?: emptyList()
                    }
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("读取目录失败: ${e.message}")
            }
        }
    }

    /** 加载文件管理根目录 */
    fun loadFilesRoot() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit("Termux 环境仍在初始化，请稍候...")
            return
        }
        loadFiles(fileManagerRoot)
    }

    /** 向上返回上级目录 */
    fun navigateUp() {
        val current = java.io.File(_currentPath.value)
        val parent = current.parentFile
        if (parent != null && parent.absolutePath.startsWith(fileManagerRoot.absolutePath)) {
            loadFiles(parent)
        }
    }

    /** 刷新当前目录文件列表 */
    fun refreshFiles() {
        loadFiles(java.io.File(_currentPath.value))
    }

    /** 打开 MC 终端前初始化日志 */
    fun launchMcConsole() {
        // 确保终端日志色标已处理
        _messageFlow.tryEmit("MC 终端已就绪，输入 help 查看可用命令")
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(java.util.Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    /** 删除文件或目录 */
    fun deleteFile(file: java.io.File) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
                if (ok) {
                    _messageFlow.tryEmit("已删除: ${file.name}")
                    loadFiles(java.io.File(_currentPath.value))
                } else {
                    _errorFlow.tryEmit("删除失败: ${file.name}")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("删除失败: ${e.message}")
            }
        }
    }

    /** 上传文件：从 Uri 复制到目标目录 */
    fun uploadFile(uri: android.net.Uri, targetDir: java.io.File) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val app = McApplication.get()
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException("无法打开文件")
                    val fileName = queryFileName(uri) ?: "uploaded_${System.currentTimeMillis()}"
                    val targetFile = java.io.File(targetDir, fileName)
                    input.use { ins ->
                        java.io.FileOutputStream(targetFile).use { fos ->
                            ins.copyTo(fos)
                        }
                    }
                }
                _messageFlow.tryEmit("文件上传成功")
                loadFiles(java.io.File(_currentPath.value))
            } catch (e: Exception) {
                _errorFlow.tryEmit("上传失败: ${e.message}")
            }
        }
    }

    /** 从 Uri 查询文件名 */
    private fun queryFileName(uri: android.net.Uri): String? {
        return try {
            val app = McApplication.get()
            val cursor = app.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                val nameIndex = it.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                if (nameIndex >= 0 && it.moveToFirst()) it.getString(nameIndex) else null
            }
        } catch (e: Exception) {
            null
        }
    }

    /** 创建新目录 */
    fun createDirectory(parent: java.io.File, name: String) {
        if (name.isBlank()) return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    java.io.File(parent, name).mkdirs()
                }
                if (ok) {
                    _messageFlow.tryEmit("目录已创建: $name")
                    loadFiles(java.io.File(_currentPath.value))
                } else {
                    _errorFlow.tryEmit("创建目录失败（可能已存在）")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("创建目录失败: ${e.message}")
            }
        }
    }

    /**
     * init 块必须放在类体末尾：这里启动的协程（Dispatchers.IO/Default）会在
     * 构造函数完成前抢先执行，若 init 在类体前部，会读到尚未赋值的字段
     * （historyMutex/playerManager 等），导致启动时 NullPointerException 崩溃。
     */
    init {
        loadPlayerHistory()
        startDeviceStatsCollection()
        // 订阅 consoleFlow，使用环形缓冲 + 批量刷新（100ms），避免每行 O(n) 拷贝
        viewModelScope.launch(Dispatchers.Default) {
            repo.termuxRuntime.consoleFlow.collect { line ->
                synchronized(consoleBuffer) {
                    if (consoleBuffer.size >= 1000) consoleBuffer.removeFirst()
                    consoleBuffer.addLast(line)
                    consoleDirty = true
                }
                parseConsoleLine(line)
            }
        }
        // 定时将脏标记的缓冲区快照推送到 StateFlow（批量刷新，减少 UI 重组）
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(100)
                if (consoleDirty) {
                    val snapshot: List<String> = synchronized(consoleBuffer) {
                        consoleDirty = false
                        consoleBuffer.toList()
                    }
                    _consoleLines.value = snapshot
                }
            }
        }
    }
}
