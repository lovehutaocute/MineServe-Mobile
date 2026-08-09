package com.mineserve.mobile.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mineserve.mobile.R
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.BootReceiver
import com.mineserve.mobile.KeepAlivePixelActivity
import com.mineserve.mobile.KeepAliveWorker
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.service.McForegroundService
import android.net.Uri
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.data.ApkDownloader
import com.mineserve.mobile.data.UpdateChecker
import com.mineserve.mobile.data.UpdateCheckResult
import com.mineserve.mobile.data.UpdateInfo
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.CrashReportManager
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.PlayerManager
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.server.ServerPropertiesManager
import com.mineserve.mobile.server.TunnelManager
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
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import java.io.File

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

    /** bootstrap 当前镜像源索引 */
    val currentMirrorIndex: StateFlow<Int> = McApplication.get().currentMirrorIndex

    /** 镜像源名称列表 */
    val mirrorSources: List<String> get() = McApplication.get().mirrorSources

    /** 请求切换到下一个镜像源 */
    fun switchBootstrapMirror() {
        android.util.Log.i("McViewModel", "[切换] switchBootstrapMirror 调用, 线程=${Thread.currentThread().name}")
        McApplication.get().switchBootstrapMirror()
    }

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

    /** 强制删除 Termux 依赖：彻底卸载，删除后不自动重新初始化（需手动安装） */
    fun forceDeleteBootstrap() {
        McApplication.get().forceDeleteBootstrap()
    }

    private val _consoleLines = MutableStateFlow<List<String>>(emptyList())
    val consoleLines: StateFlow<List<String>> = _consoleLines.asStateFlow()

    // ── Termux 终端（会话面板） ─────────────────────────────

    private val _termuxLines = MutableStateFlow<List<String>>(emptyList())
    val termuxLines: StateFlow<List<String>> = _termuxLines.asStateFlow()

    private val _termuxBusy = MutableStateFlow(false)
    val termuxBusy: StateFlow<Boolean> = _termuxBusy.asStateFlow()

    /** 执行 Termux shell 命令（IO 线程，输出实时追加到 termuxLines，命令回显 $ cmd） */
    fun execTermuxCommand(command: String) {
        if (command.isBlank() || _termuxBusy.value) return
        // 环境未初始化完成时禁止输入（bootstrap 下载/解压/装依赖中，命令必然失败）
        if (!isBootstrapped.value) {
            appendTermux("⏳ Termux 环境尚未初始化完成，请等待安装完成后重试")
            return
        }
        viewModelScope.launch {
            _termuxBusy.value = true
            try {
                // 命令执行前快速自愈：apt/pkg 新装包命令立即可用，无需重启
                withContext(Dispatchers.IO) {
                    repo.termuxRuntime.refreshTermux()
                }
                appendTermux("$ " + command)
                val exit = withContext(Dispatchers.IO) {
                    repo.termuxRuntime.execTermux(command) { line ->
                        appendTermux(line)
                    }
                }
                if (exit != 0) {
                    val hint = when (exit) {
                        126 -> "（命令找到但无法执行：权限不足或依赖缺失）"
                        127 -> "（命令未找到：请检查命令是否存在）"
                        else -> ""
                    }
                    appendTermux("(退出码 $exit)$hint")
                    // 失败时输出环境诊断，便于定位根因
                    appendTermux(repo.termuxRuntime.diagnoseCommand(command))
                }
            } catch (e: Exception) {
                appendTermux("执行错误: ${e.message}")
            } finally {
                _termuxBusy.value = false
            }
        }
    }

    /** 追加一行到 termuxLines（500 行环形缓冲） */
    private fun appendTermux(line: String) {
        val cur = _termuxLines.value
        _termuxLines.value = if (cur.size >= 500) cur.drop(cur.size - 499) + line else cur + line
    }

    // ── 服务器图标（server-icon.png） ────────────────────────

    /** 图标变更信号（UI 用于重载预览） */
    private val _serverIconVersion = MutableStateFlow(0)
    val serverIconVersion: StateFlow<Int> = _serverIconVersion.asStateFlow()

    /** 当前核心的 server-icon.png 文件（不存在返回 null） */
    fun serverIconFile(): File? {
        val dirName = activeDirName() ?: return null
        return File(repo.termuxRuntime.serverDirFor(dirName), "server-icon.png")
            .takeIf { it.exists() }
    }

    /** 更换服务器图标：读取 Uri → 居中裁剪缩放 64×64 → PNG 写入 server-icon.png */
    fun setServerIcon(uri: Uri) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val app = McApplication.get()
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException("无法读取所选图片")
                    val src = input.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: throw RuntimeException("无法解析图片")
                    // 居中裁剪为正方形后缩放到 64×64
                    val size = minOf(src.width, src.height)
                    val x = (src.width - size) / 2
                    val y = (src.height - size) / 2
                    val square = android.graphics.Bitmap.createBitmap(src, x, y, size, size)
                    val icon = android.graphics.Bitmap.createScaledBitmap(square, 64, 64, true)
                    val target = File(repo.termuxRuntime.serverDirFor(dirName), "server-icon.png")
                    target.parentFile?.mkdirs()
                    java.io.FileOutputStream(target).use { fos ->
                        icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                    }
                    if (square !== icon) square.recycle()
                    icon.recycle()
                }
                _messageFlow.tryEmit(str(R.string.ui_server_icon_updated))
                _serverIconVersion.value++
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.ui_server_icon_fail, e.message))
            }
        }
    }

    /** 恢复默认：删除 server-icon.png */
    fun removeServerIcon() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    File(repo.termuxRuntime.serverDirFor(dirName), "server-icon.png").delete()
                }
                _messageFlow.tryEmit(str(R.string.ui_server_icon_reset_done))
                _serverIconVersion.value++
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.ui_server_icon_fail, e.message))
            }
        }
    }

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

    // ── 软件更新（GitHub Releases） ──────────────────────────────────

    /** 更新界面状态 */
    sealed interface UpdateUiState {
        data object Idle : UpdateUiState
        data object Checking : UpdateUiState
        data class Available(val info: UpdateInfo) : UpdateUiState
        data class Downloading(val progress: Float, val info: UpdateInfo) : UpdateUiState
        data class Downloaded(val info: UpdateInfo) : UpdateUiState
        data class Failed(val message: String) : UpdateUiState
    }

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /** 更新对话框是否显示 */
    private val _updateDialogVisible = MutableStateFlow(false)
    val updateDialogVisible: StateFlow<Boolean> = _updateDialogVisible.asStateFlow()

    /** 最近一次更新检查结果描述（设置页显示），如「已是最新版本 · 08:30」 */
    private val _lastUpdateCheckResult = MutableStateFlow<String?>(null)
    val lastUpdateCheckResult: StateFlow<String?> = _lastUpdateCheckResult.asStateFlow()

    fun dismissUpdateDialog() { _updateDialogVisible.value = false }

    /** 检查更新：manual=true 来自设置页（显示检查进度 + 失败提示）；auto=true 启动检查（失败静默 + 有新版发通知） */
    fun checkForUpdate(manual: Boolean = false) {
        if (_updateState.value is UpdateUiState.Checking) return
        val app = McApplication.get()
        _updateState.value = UpdateUiState.Checking
        if (manual) _updateDialogVisible.value = true // 手动检查：立即显示检查中对话框
        viewModelScope.launch {
            when (val result = UpdateChecker.checkLatest(BuildConfig.VERSION_NAME)) {
                is UpdateCheckResult.Latest -> {
                    _updateState.value = UpdateUiState.Idle
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_already_latest)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = false
                        _messageFlow.tryEmit(app.getString(R.string.update_already_latest))
                    }
                }
                is UpdateCheckResult.Update -> {
                    _updateState.value = UpdateUiState.Available(result.info)
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_available, result.info.versionName)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = true
                    } else {
                        showUpdateNotification(app, result.info)
                    }
                }
                is UpdateCheckResult.Error -> {
                    _updateState.value = UpdateUiState.Idle
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_check_failed)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = false
                        _messageFlow.tryEmit(app.getString(R.string.update_check_failed))
                    }
                }
            }
        }
    }

    private fun nowTime(): String =
        java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
            .format(java.util.Date())

    fun showUpdateDialog() {
        if (_updateState.value is UpdateUiState.Available) _updateDialogVisible.value = true
    }

    /** 打开浏览器跳到 GitHub Release 页面（供用户手动下载更新） */
    fun openGithubUpdate() {
        val state = _updateState.value
        if (state !is UpdateUiState.Available) return
        val url = state.info.htmlUrl.ifBlank { "https://github.com/lovehutaocute/MineServe-Mobile/releases/latest" }
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            McApplication.get().startActivity(intent)
            dismissUpdateDialog()
        } catch (e: Exception) {
            _messageFlow.tryEmit("无法打开浏览器: ${e.message}")
        }
    }

    /** 开始下载新版 APK（完成后自动调系统安装器） */
    fun downloadUpdate() {        val state = _updateState.value
        if (state !is UpdateUiState.Available) return
        val app = McApplication.get()
        _updateState.value = UpdateUiState.Downloading(0f, state.info)
        viewModelScope.launch {
            try {
                val target = File(app.cacheDir, "update/MineServeMobile-latest.apk")
                ApkDownloader.download(state.info.downloadUrl, target) { p ->
                    _updateState.value = UpdateUiState.Downloading(p, state.info)
                }
                _updateState.value = UpdateUiState.Downloaded(state.info)
                installApk(app, target)
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Failed(e.message ?: app.getString(R.string.update_download_failed))
            }
        }
    }

    /** 调系统安装器安装 APK */
    fun installApk(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _updateState.value = UpdateUiState.Failed(e.message ?: "install error")
        }
    }

    /** 自动检查发现新版时发系统通知，点击进入更新对话框 */
    private fun showUpdateNotification(app: Context, info: UpdateInfo) {
        val nm = app.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val contentIntent = PendingIntent.getActivity(
            app, 1001,
            Intent(app, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("open_update", true)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(app, app.getString(R.string.notif_channel_id))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(app.getString(R.string.update_notif_title, info.versionName))
            .setContentText(app.getString(R.string.update_notif_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try { nm.notify(1001, notification) } catch (_: Exception) {}
    }

    // ── 设备状态（常规权限可采集，无需 root） ─────────────────────────

    /** 设备级指标：内存/存储/电池 */
    data class DeviceStats(
        val totalMemoryMb: Long = 0L,
        val availMemoryMb: Long = 0L,
        val totalStorageMb: Long = 0L,
        val availStorageMb: Long = 0L,
        val batteryPercent: Int = -1,   // -1 表示未知
        val isCharging: Boolean = false,
        val totalRxBytes: Long = 0L,    // 累计下载量（TrafficStats）
        val totalTxBytes: Long = 0L,    // 累计上传量
        val rxSpeedBps: Long = 0L,      // 实时下载速度（字节/秒）
        val txSpeedBps: Long = 0L       // 实时上传速度（字节/秒）
    )

    private val _deviceStats = MutableStateFlow(DeviceStats())
    val deviceStats: StateFlow<DeviceStats> = _deviceStats.asStateFlow()

    /** 网络流量速度采样（上次采样值） */
    private var lastRxBytes = 0L
    private var lastTxBytes = 0L
    private var lastNetSampleMs = 0L

    /** 定时采集设备指标（每 3 秒），同时将 MC 进程真实内存写入 usedMemoryMb */
    private fun startDeviceStatsCollection() {
        viewModelScope.launch {
            while (true) {
                withContext(Dispatchers.IO) { collectDeviceStatsOnce() }
                delay(10000)
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

            // 网络流量（TrafficStats 设备总量，无权限）与实时速度（相邻采样差值）
            val rxBytes = android.net.TrafficStats.getTotalRxBytes().takeIf { it >= 0 } ?: 0L
            val txBytes = android.net.TrafficStats.getTotalTxBytes().takeIf { it >= 0 } ?: 0L
            val now = android.os.SystemClock.elapsedRealtime()
            var rxSpeed = 0L
            var txSpeed = 0L
            if (lastNetSampleMs > 0 && now > lastNetSampleMs) {
                val dtMs = (now - lastNetSampleMs).coerceAtLeast(1)
                if (rxBytes >= lastRxBytes) rxSpeed = (rxBytes - lastRxBytes) * 1000 / dtMs
                if (txBytes >= lastTxBytes) txSpeed = (txBytes - lastTxBytes) * 1000 / dtMs
            }
            lastRxBytes = rxBytes
            lastTxBytes = txBytes
            lastNetSampleMs = now

            val newStats = DeviceStats(
                totalMemoryMb = totalMemMb,
                availMemoryMb = availMemMb,
                totalStorageMb = totalStorageMb,
                availStorageMb = availStorageMb,
                batteryPercent = batteryPercent,
                isCharging = isCharging,
                totalRxBytes = rxBytes,
                totalTxBytes = txBytes,
                rxSpeedBps = rxSpeed,
                txSpeedBps = txSpeed
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
            _errorFlow.tryEmit(str(R.string.s187))
            return
        } else {
            "$ip:$port"
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("MC Server", address))
        _messageFlow.tryEmit(str(R.string.s188, address))
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
                _errorFlow.tryEmit(str(R.string.s191, e.message))
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
    fun setAptMirror(mirror: com.mineserve.mobile.data.AptMirror) =
        updateConfig { it.copy(aptMirror = mirror) }
    fun setDownloadMirror(mirror: com.mineserve.mobile.data.DownloadMirror) =
        updateConfig { it.copy(downloadMirror = mirror) }

    /** 多线程下载是否启用（内置下载模块开关，默认启用） */
    fun isMultiThreadDownloadEnabled(): Boolean = com.mineserve.mobile.data.DownloadPrefs.isEnabled()

    /** 下载线程数 */
    fun downloadThreadCount(): Int = com.mineserve.mobile.data.DownloadPrefs.threadCount()

    /** 切换多线程下载开关 */
    fun setMultiThreadDownloadEnabled(enabled: Boolean) {
        com.mineserve.mobile.data.DownloadPrefs.setEnabled(enabled)
        _messageFlow.tryEmit(if (enabled) "已启用多线程下载" else "已关闭多线程下载（改用单流下载）")
    }

    /** 设置下载线程数 */
    fun setDownloadThreadCount(count: Int) {
        com.mineserve.mobile.data.DownloadPrefs.setThreadCount(count)
    }

    fun installDependencies() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
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
                    _messageFlow.tryEmit(str(R.string.s193))
                } else {
                    _errorFlow.tryEmit(str(R.string.s194))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s195, e.message))
            } finally {
                _isInstalling.value = false
            }
        }
    }

    fun startServer() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        viewModelScope.launch {
            try {
                controller.start(config.value)
                _messageFlow.tryEmit(str(R.string.s196))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s197, e.message))
            }
        }
    }

    fun stopServer() {
        viewModelScope.launch {
            try {
                controller.stop()
                _messageFlow.tryEmit(str(R.string.s198))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s199, e.message))
            }
        }
    }

    // ── 内网穿透 ──────────────────────────────────────────────

    fun startTunnel() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        if (tunnelState.value.status == TunnelStatus.Starting) {
            _errorFlow.tryEmit(str(R.string.s200))
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
                    TunnelStatus.Starting -> _messageFlow.tryEmit(str(R.string.s203))
                    TunnelStatus.Failed -> _errorFlow.tryEmit(str(R.string.s204, st.errorMessage))
                    else -> _messageFlow.tryEmit(str(R.string.s205))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s206, e.message))
            }
        }
    }

    fun stopTunnel() {
        viewModelScope.launch {
            try {
                tunnelManager.stop()
                _messageFlow.tryEmit(str(R.string.s207))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s208, e.message))
            }
        }
    }

    fun copyTunnelUrl(context: android.content.Context) {
        val url = tunnelState.value.publicUrl
        if (url.isBlank()) {
            _errorFlow.tryEmit(str(R.string.s209))
            return
        }
        val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE)
            as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Tunnel URL", url))
        _messageFlow.tryEmit(str(R.string.s210, url))
    }

    fun sendCommand(line: String) {
        try {
            controller.sendCommand(line)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s211, e.message))
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

    /** 真实已安装模组列表 */
    private val _mods = MutableStateFlow<List<PluginManager.ModEntry>>(emptyList())
    val mods: StateFlow<List<PluginManager.ModEntry>> = _mods.asStateFlow()

    val curatedMods: List<PluginManager.CuratedMod>
        get() = pluginManager.curatedMods

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
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                _installedPlugins.value = pluginManager.scan(dirName)
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s213, e.message))
            }
        }
    }

    // ── 模组管理 ──────────────────────────────────────────────────

    /** 扫描当前核心的 mods 目录 */
    fun refreshMods() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                _mods.value = withContext(Dispatchers.IO) { pluginManager.readMods(dirName) }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s214, e.message))
            }
        }
    }

    /** 切换模组启用状态 */
    fun toggleModEnabled(fileName: String) {
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) { pluginManager.toggleModEnabled(fileName, dirName) }
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s215, e.message))
            }
        }
    }

    /** 删除模组 */
    fun deleteMod(fileName: String) {
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { pluginManager.deleteMod(fileName, dirName) }
                if (ok) _messageFlow.tryEmit(str(R.string.s216, fileName))
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s217, e.message))
            }
        }
    }

    /** 从本地 Uri 上传模组 */
    fun installModFromUri(uri: Uri) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                val name = withContext(Dispatchers.IO) { pluginManager.installModFromUri(uri, dirName) }
                _messageFlow.tryEmit(str(R.string.s218, name))
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s219, e.message))
            }
        }
    }

    /** 从精选库下载安装模组（GitHub 动态解析最新版本） */
    fun installCuratedMod(mod: PluginManager.CuratedMod) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveLatestAsset(mod.repo, mod.githubAssetPattern)
                } ?: throw RuntimeException("无法解析最新版本下载地址")
                withContext(Dispatchers.IO) { pluginManager.installModFromUrl(url, mod.targetFileName, dirName) }
                _messageFlow.tryEmit(str(R.string.s221, mod.name))
                refreshMods()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s222, mod.name, e.message))
            }
        }
    }

    /** 当前核心的 mods 目录路径 */
    fun currentModsPath(): String? {
        val dirName = activeDirName() ?: return null
        return pluginManager.currentModsPath(dirName)
    }

    // ── Modrinth 模组获取 ────────────────────────────────────────

    private val _modrinthResults = MutableStateFlow<List<PluginManager.ModrinthHit>>(emptyList())
    val modrinthResults: StateFlow<List<PluginManager.ModrinthHit>> = _modrinthResults.asStateFlow()

    /** Modrinth 全部可用加载器 */
    private val _modrinthLoaders = MutableStateFlow<List<String>>(emptyList())
    val modrinthLoaders: StateFlow<List<String>> = _modrinthLoaders.asStateFlow()

    /** Modrinth 支持的 MC 游戏版本（供版本筛选下拉） */
    private val _modrinthGameVersions = MutableStateFlow<List<String>>(emptyList())
    val modrinthGameVersions: StateFlow<List<String>> = _modrinthGameVersions.asStateFlow()

    /** 模组筛选选中的游戏版本（默认当前服务器版本） */
    private val _selectedModVersion = MutableStateFlow("")
    val selectedModVersion: StateFlow<String> = _selectedModVersion.asStateFlow()

    /** 当前核心对应的 Modrinth 加载器名 */
    private fun modrinthLoader(core: com.mineserve.mobile.data.ServerCore): String =
        if (core == com.mineserve.mobile.data.ServerCore.Fabric || core == com.mineserve.mobile.data.ServerCore.Quilt) "fabric"
        else "forge"

    /** 当前激活核心的 MC 版本（用于版本匹配判断） */
    private fun currentServerVersion(): String {
        val cfg = config.value
        return cfg.installedCores.find { it.name == cfg.activeCoreName }?.version
            ?: cfg.mcVersion
    }

    /** 加载 Modrinth 可用加载器列表（仅保留模组专用加载器，与插件池完全隔离） */
    fun loadModrinthLoaders() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { pluginManager.fetchModrinthLoaders() }
            _modrinthLoaders.value = pluginManager.filterModLoaders(all)
        }
    }

    /** 加载 Modrinth 游戏版本列表；默认选中当前服务器版本 */
    fun loadModrinthGameVersions() {
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) { pluginManager.fetchModrinthGameVersions() }
            _modrinthGameVersions.value = versions
            val serverVer = currentServerVersion()
            _selectedModVersion.value = serverVer.takeIf { it.isNotBlank() && it in versions } ?: versions.firstOrNull().orEmpty()
        }
    }

    /** 切换模组筛选的 MC 版本 */
    fun setSelectedModVersion(v: String) {
        _selectedModVersion.value = v
    }

    /** 搜索 Modrinth 模组（多加载器 + 版本筛选 + 排序） */
    fun searchModrinthMods(query: String, loaders: List<String>, sort: String, mcVersion: String) {
        viewModelScope.launch {
            _modrinthResults.value = withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, loaders, sort, projectType = "mod", mcVersion = mcVersion)
            }
            if (_modrinthResults.value.isEmpty()) {
                _messageFlow.tryEmit(str(R.string.s223))
            }
        }
    }

    /** 一键安装 Modrinth 模组（解析指定版本 release 直链并下载到 mods/） */
    fun installModrinthMod(hit: PluginManager.ModrinthHit, mcVersion: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        val loader = modrinthLoader(config.value.selectedCore)
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveModrinthDownload(hit.slug, mcVersion, loader)
                } ?: throw RuntimeException("该模组不支持当前 MC 版本/加载器")
                val fileName = "${hit.slug}.jar"
                withContext(Dispatchers.IO) {
                    // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                    _pluginDownloadProgress.value = _pluginDownloadProgress.value + (hit.slug to PluginDownloadProgress(
                        pluginId = hit.slug, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                    ))
                    pluginManager.installModFromUrl(url, fileName, dirName) { d, t, s ->
                        _pluginDownloadProgress.value = _pluginDownloadProgress.value + (hit.slug to PluginDownloadProgress(
                            pluginId = hit.slug, downloadedBytes = d, totalBytes = t, speedBytesPerSec = s
                        ))
                    }
                }
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - hit.slug
                _messageFlow.tryEmit(str(R.string.s225, hit.title))
                refreshMods()
            } catch (e: Exception) {
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - hit.slug
                _errorFlow.tryEmit(str(R.string.s226, hit.title, e.message))
            }
        }
    }

    // ── Modrinth 插件获取（插件页复用同一下载模块） ───────────────

    private val _pluginModrinthResults = MutableStateFlow<List<PluginManager.ModrinthHit>>(emptyList())
    val pluginModrinthResults: StateFlow<List<PluginManager.ModrinthHit>> = _pluginModrinthResults.asStateFlow()

    private val _pluginModrinthLoaders = MutableStateFlow<List<String>>(emptyList())
    val pluginModrinthLoaders: StateFlow<List<String>> = _pluginModrinthLoaders.asStateFlow()

    private val _selectedPluginVersion = MutableStateFlow("")
    val selectedPluginVersion: StateFlow<String> = _selectedPluginVersion.asStateFlow()

    fun loadPluginModrinthLoaders() {
        viewModelScope.launch {
            val all = withContext(Dispatchers.IO) { pluginManager.fetchModrinthLoaders() }
            _pluginModrinthLoaders.value = pluginManager.filterPluginLoaders(all)
        }
    }

    fun loadPluginModrinthVersions() {
        viewModelScope.launch {
            val versions = withContext(Dispatchers.IO) { pluginManager.fetchModrinthGameVersions() }
            if (_pluginModrinthResults.value.isEmpty()) {
                val serverVer = currentServerVersion()
                _selectedPluginVersion.value = serverVer.takeIf { it.isNotBlank() && it in versions } ?: versions.firstOrNull().orEmpty()
            }
        }
    }

    fun setSelectedPluginVersion(v: String) {
        _selectedPluginVersion.value = v
    }

    /** 插件页搜索 Modrinth 插件（project_type=plugin，布局与模组页统一） */
    fun searchModrinthPlugin(query: String, loaders: List<String>, sort: String, mcVersion: String) {
        viewModelScope.launch {
            _pluginModrinthResults.value = withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, loaders, sort, projectType = "plugin", mcVersion = mcVersion)
            }
            if (_pluginModrinthResults.value.isEmpty()) {
                _messageFlow.tryEmit(str(R.string.s223))
            }
        }
    }

    /** 安装 Modrinth 插件到 plugins/ 目录 */
    fun installModrinthPlugin(hit: PluginManager.ModrinthHit, mcVersion: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        val loader = "bukkit"
        viewModelScope.launch {
            try {
                val url = withContext(Dispatchers.IO) {
                    pluginManager.resolveModrinthDownload(hit.slug, mcVersion, loader)
                } ?: throw RuntimeException("该插件不支持所选 MC 版本/加载器")
                val fileName = "${hit.slug}.jar"
                withContext(Dispatchers.IO) {
                    // 下载开始前先放占位进度，保证进度弹窗一定显示
                    _pluginDownloadProgress.value = _pluginDownloadProgress.value + (hit.slug to PluginDownloadProgress(
                        pluginId = hit.slug, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                    ))
                    pluginManager.installFromUrl(url, fileName, dirName) { d, t, s ->
                        _pluginDownloadProgress.value = _pluginDownloadProgress.value + (hit.slug to PluginDownloadProgress(
                            pluginId = hit.slug, downloadedBytes = d, totalBytes = t, speedBytesPerSec = s
                        ))
                    }
                }
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - hit.slug
                _messageFlow.tryEmit(str(R.string.s229, hit.title))
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - hit.slug
                _errorFlow.tryEmit(str(R.string.s230, hit.title, e.message))
            }
        }
    }

    /** 从精选库下载安装插件 */
    fun installCuratedPlugin(curated: PluginManager.CuratedPlugin) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        if (_pluginDownloadProgress.value.containsKey(curated.id)) {
            _errorFlow.tryEmit(str(R.string.s227, curated.name))
            return
        }
        viewModelScope.launch {
            try {
                // asset 文件名带版本号的精选资源（如 ViaVersion），先经 GitHub API 解析最新直链；
                // 解析失败直接报错，不静默回退到 HTML 页面
                val resolvedUrl = if (curated.githubAssetPattern != null) {
                    withContext(Dispatchers.IO) {
                        pluginManager.resolveLatestAsset(curated.repo, curated.githubAssetPattern)
                    } ?: throw RuntimeException("无法解析 ${curated.name} 最新版本下载地址")
                } else {
                    curated.downloadUrl
                }
                // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                _pluginDownloadProgress.value = _pluginDownloadProgress.value + (curated.id to PluginDownloadProgress(
                    pluginId = curated.id, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                ))
                pluginManager.installFromUrl(
                    resolvedUrl,
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
                _messageFlow.tryEmit(str(R.string.s229, curated.name))
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - curated.id
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - curated.id
                _errorFlow.tryEmit(str(R.string.s230, curated.name, e.message))
            }
        }
    }

    /** 从本地 Uri 上传插件 */
    fun installPluginFromUri(uri: Uri) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        viewModelScope.launch {
            try {
                val fileName = pluginManager.installFromUri(uri, dirName)
                _messageFlow.tryEmit(str(R.string.s231, fileName))
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s232, e.message))
            }
        }
    }

    /** 删除插件 */
    fun deletePlugin(fileName: String, alsoRemoveDataDir: Boolean = false) {
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
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
                    _errorFlow.tryEmit(str(R.string.s235))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s236, e.message))
            }
        }
    }

    /** 切换插件启用/禁用 */
    fun togglePluginEnabled(fileName: String) {
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
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
                    _errorFlow.tryEmit(str(R.string.s239))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s240, e.message))
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
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
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
                _errorFlow.tryEmit(str(R.string.s243, e.message))
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
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s212))
            return
        }
        if (url.isBlank() || customFileName.isBlank()) {
            _errorFlow.tryEmit(str(R.string.s244))
            return
        }
        if (_pluginDownloadProgress.value.containsKey(URL_DOWNLOAD_ID)) {
            _errorFlow.tryEmit(str(R.string.s245))
            return
        }
        viewModelScope.launch {
            try {
                // 下载开始前先放占位进度，保证进度弹窗一定显示（下载瞬间完成也不会漏）
                _pluginDownloadProgress.value = _pluginDownloadProgress.value + (URL_DOWNLOAD_ID to PluginDownloadProgress(
                    pluginId = URL_DOWNLOAD_ID, downloadedBytes = 0, totalBytes = 0, speedBytesPerSec = 0
                ))
                pluginManager.installFromUrl(url, customFileName, dirName) { downloaded, total, speed ->
                    _pluginDownloadProgress.value = _pluginDownloadProgress.value + (URL_DOWNLOAD_ID to PluginDownloadProgress(
                        pluginId = URL_DOWNLOAD_ID,
                        downloadedBytes = downloaded,
                        totalBytes = total,
                        speedBytesPerSec = speed
                    ))
                }
                _messageFlow.tryEmit(str(R.string.s246, customFileName))
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - URL_DOWNLOAD_ID
                refreshInstalledPlugins()
            } catch (e: Exception) {
                _pluginDownloadProgress.value = _pluginDownloadProgress.value - URL_DOWNLOAD_ID
                _errorFlow.tryEmit(str(R.string.s247, e.message))
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

    /** 更新前备份存档（更新对话框「先备份存档」入口） */
    fun backupBeforeUpdate() {
        viewModelScope.launch {
            try {
                val path = createSnapshot()
                if (path != null) {
                    _messageFlow.tryEmit(str(R.string.update_backup_done, java.io.File(path).name))
                } else {
                    _messageFlow.tryEmit(str(R.string.update_backup_empty))
                }
            } catch (e: Exception) {
                _messageFlow.tryEmit("备份失败: ${e.message}")
            }
        }
    }

    /** 外部备份整个世界（world+nether+end → 外部目录） */
    fun backupWorldToExternal() {        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupWorldToExternal(dirName)
                }
                if (path != null) {
                    _messageFlow.tryEmit("世界备份完成: ${java.io.File(path).name}")
                } else {
                    _errorFlow.tryEmit("世界备份失败（无世界存档或外部目录不可写）")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("世界备份失败: ${e.message}")
            }
        }
    }

    /** 外部备份整个服务器（world+核心+配置+插件 → 外部目录） */
    fun backupServerToExternal() {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupServerToExternal(dirName)
                }
                if (path != null) {
                    _messageFlow.tryEmit("服务器备份完成: ${java.io.File(path).name}")
                } else {
                    _errorFlow.tryEmit("服务器备份失败（外部目录不可写）")
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit("服务器备份失败: ${e.message}")
            }
        }
    }

    // ── 外部备份导入/还原（含重名检测） ─────────────────────────────

    /** 服务器还原重名冲突（UI 弹框询问） */
    data class RestoreConflict(val zipName: String, val dirName: String)

    private val _restoreConflict = kotlinx.coroutines.flow.MutableStateFlow<RestoreConflict?>(null)
    val restoreConflict: kotlinx.coroutines.flow.StateFlow<RestoreConflict?> = _restoreConflict.asStateFlow()

    /** 列出外部备份 zip（供备份页展示） */
    fun externalBackups(): List<java.io.File> = com.mineserve.mobile.server.ExternalBackupStore.listBackups()

    /** 从外部 zip 还原世界（zip 内 world/ 前缀） */
    fun restoreWorldFromExternal(zipName: String) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.restoreWorldFromExternal(file, dirName) }
                if (ok) _messageFlow.tryEmit("世界已还原: $zipName")
                else _errorFlow.tryEmit("世界还原失败")
            } catch (e: Exception) {
                _errorFlow.tryEmit("世界还原失败: ${e.message}")
            }
        }
    }

    /** 请求还原服务器：解析目标目录名，存在同名则发冲突事件（UI 弹框） */
    fun requestRestoreServer(zipName: String) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = backupManager.parseServerDirFromZip(zipName)
        if (dirName == null) {
            _errorFlow.tryEmit("无法识别备份的服务器目录名")
            return
        }
        val target = java.io.File(repo.termuxRuntime.serversDir, dirName)
        if (target.exists()) {
            _restoreConflict.value = RestoreConflict(zipName, dirName)
        } else {
            performRestoreServer(zipName, dirName, overwrite = false)
        }
    }

    /** 确认还原服务器（overwrite=true 覆盖同名） */
    fun confirmRestoreServer(overwrite: Boolean) {
        val conflict = _restoreConflict.value ?: return
        _restoreConflict.value = null
        performRestoreServer(conflict.zipName, conflict.dirName, overwrite)
    }

    fun dismissRestoreConflict() { _restoreConflict.value = null }

    private fun performRestoreServer(zipName: String, dirName: String, overwrite: Boolean) {
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    backupManager.restoreServerFromExternal(file, dirName, overwrite)
                }
                if (ok) _messageFlow.tryEmit("服务器已还原: $zipName")
                else _errorFlow.tryEmit("服务器还原失败")
            } catch (e: Exception) {
                _errorFlow.tryEmit("服务器还原失败: ${e.message}")
            }
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
                _errorFlow.tryEmit(str(R.string.s248, e.message))
            }
        }
    }

    /** 删除当前激活核心的世界文件夹（world / world_nether / world_the_end），不可恢复 */
    fun deleteWorldDirs() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s249))
            return
        }
        viewModelScope.launch {
            try {
                val deleted = withContext(Dispatchers.IO) {
                    val base = File(repo.termuxRuntime.installer.rootDir, "home/servers/$dirName")
                    var count = 0
                    for (name in listOf("world", "world_nether", "world_the_end")) {
                        val dir = File(base, name)
                        if (dir.exists() && dir.deleteRecursively()) count++
                    }
                    count
                }
                _messageFlow.tryEmit(str(R.string.ui_world_deleted, deleted))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.ui_world_delete_failed))
            }
        }
    }

    /** 恢复快照（会先停止服务器） */
    fun restoreSnapshot(name: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s249))
            return
        }
        viewModelScope.launch {
            try {
                _messageFlow.tryEmit(str(R.string.s250))
                val ok = withContext(Dispatchers.IO) { backupManager.restoreSnapshot(name, dirName) }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s251))
                    loadSnapshots()
                } else {
                    _errorFlow.tryEmit(str(R.string.s252))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s253, e.message))
            }
        }
    }

    /** 导出快照 zip 到用户选择的 Uri（自定义导出路径） */
    fun exportSnapshotToUri(snapshotName: String, uri: android.net.Uri) {
        val src = java.io.File(repo.termuxRuntime.installer.rootDir, "home/snapshots/$snapshotName")
        if (!src.exists()) {
            _errorFlow.tryEmit(str(R.string.s254))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val output = McApplication.get().contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException("无法打开导出目标")
                    output.use { os -> src.inputStream().use { it.copyTo(os) } }
                }
                _messageFlow.tryEmit(str(R.string.s256, snapshotName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s257, e.message))
            }
        }
    }

    /** 删除快照 */
    fun deleteSnapshot(name: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.deleteSnapshot(name) }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s258, name))
                    loadSnapshots()
                } else {
                    _errorFlow.tryEmit(str(R.string.s259))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s260, e.message))
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
                _errorFlow.tryEmit(str(R.string.s261, e.message))
            }
        }
    }

    /** 保存 server.properties */
    fun saveServerProperties(props: Map<String, String>) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s249))
            return
        }
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { propertiesManager.writeProperties(props, dirName) }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s262))
                    _serverProperties.value = props
                } else {
                    _errorFlow.tryEmit(str(R.string.s263))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s264, e.message))
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

    /** 当前 server.properties 配置的默认 OP 等级（null 表示未配置，使用 MC 默认 4） */
    private val _defaultOpLevel = MutableStateFlow<Int?>(null)
    val defaultOpLevel: StateFlow<Int?> = _defaultOpLevel.asStateFlow()

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
                    // 同步默认 OP 等级（op-permission-level）
                    _defaultOpLevel.value = props["op-permission-level"]?.toIntOrNull()
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s265, e.message))
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
            _errorFlow.tryEmit(str(R.string.s266))
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
        afterCmd(sent, str(R.string.s1055, name))
    }

    /** 设置 OP 并指定等级（1-4）。手动指定等级需重启服务器才生效到权限系统。 */
    fun opPlayerWithLevel(name: String, level: Int) {
        if (name.isBlank()) return
        val dirName = activeDirName() ?: run {
            _errorFlow.tryEmit(str(R.string.s266))
            return
        }
        viewModelScope.launch {
            val sent = withContext(Dispatchers.IO) {
                playerManager.opPlayerWithLevel(name, level, dirName)
            }
            afterCmd(sent, str(R.string.s1056, name, level))
        }
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
            _errorFlow.tryEmit(str(R.string.s274))
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
            _messageFlow.tryEmit(str(R.string.s279))
        } else {
            _errorFlow.tryEmit(str(R.string.s280))
        }
    }

    /** 设置玩家游戏模式 */
    fun setGameMode(name: String, mode: Int) {
        if (name.isBlank()) return
        val sent = playerManager.setGameMode(name, mode)
        val modeNameRes = when (mode.coerceIn(0, 3)) {
            0 -> R.string.s281; 1 -> R.string.s282; 2 -> R.string.s283; 3 -> R.string.s284; else -> R.string.s281
        }
        afterCmd(sent, str(R.string.s285, name, str(modeNameRes)), refresh = false)
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
                _errorFlow.tryEmit(str(R.string.s287, e.message))
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
                    _errorFlow.tryEmit(str(R.string.s288))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s289, e.message))
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
                _messageFlow.tryEmit(str(R.string.s290, fileName))
                loadCrashReports()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s291, e.message))
            }
        }
    }

    /** 清空所有崩溃报告 */
    fun clearCrashReports() {
        viewModelScope.launch {
            try {
                val count = withContext(Dispatchers.IO) { crashReportManager.clearAllCrashReports() }
                _messageFlow.tryEmit(str(R.string.s292, count))
                loadCrashReports()
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s293, e.message))
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
                _errorFlow.tryEmit(str(R.string.s294, e.message))
                _availableVersions.value = emptyList()
            } finally {
                _isLoadingVersions.value = false
            }
        }
    }

    /** 下载服务端核心（使用自定义名称，保存到独立目录），成功返回 true */
    fun downloadCore(customName: String) {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
            return
        }
        if (_isDownloadingCore.value) return
        if (customName.isBlank()) {
            _errorFlow.tryEmit(str(R.string.s295))
            return
        }
        _isDownloadingCore.value = true
        _downloadProgress.value = DownloadProgress()
        viewModelScope.launch {
            try {
                controller.downloadCore(config.value, customName.trim()) { downloaded, total, speed ->
                    _downloadProgress.value = DownloadProgress(downloaded, total, speed)
                }
                _messageFlow.tryEmit(str(R.string.s296, customName.trim()))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s297, e.message))
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
                    _errorFlow.tryEmit(str(R.string.s299, dir.absolutePath))
                    return@launch
                }
                val updated = config.value.installedCores.filter { it.name != name }
                val newActive = if (config.value.activeCoreName == name) updated.firstOrNull()?.name else config.value.activeCoreName
                repo.saveConfig(config.value.copy(
                    installedCores = updated,
                    activeCoreName = newActive
                ))
                _messageFlow.tryEmit(str(R.string.s300, name))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s301, e.message))
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
                _errorFlow.tryEmit(str(R.string.s302, e.message))
            }
        }
    }

    /** 加载文件管理根目录 */
    fun loadFilesRoot() {
        if (!isBootstrapped.value) {
            _errorFlow.tryEmit(str(R.string.s192))
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
        _messageFlow.tryEmit(str(R.string.s303))
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
                    _messageFlow.tryEmit(str(R.string.s304, file.name))
                    loadFiles(java.io.File(_currentPath.value))
                } else {
                    _errorFlow.tryEmit(str(R.string.s305, file.name))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s236, e.message))
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
                _messageFlow.tryEmit(str(R.string.s307))
                loadFiles(java.io.File(_currentPath.value))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s308, e.message))
            }
        }
    }

    /** 导出文件/文件夹到用户选择的 Uri（文件夹打包为 zip） */
    fun exportPathToUri(source: java.io.File, uri: android.net.Uri) {
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val app = McApplication.get()
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException("无法打开导出目标")
                    output.use { os ->
                        if (source.isDirectory) {
                            // 文件夹打包为 zip
                            java.util.zip.ZipOutputStream(os).use { zos ->
                                source.walkTopDown().filter { it.isFile }.forEach { f ->
                                    val entryName = source.toURI().relativize(f.toURI()).path
                                    zos.putNextEntry(java.util.zip.ZipEntry(entryName))
                                    f.inputStream().use { it.copyTo(zos) }
                                    zos.closeEntry()
                                }
                            }
                        } else {
                            source.inputStream().use { it.copyTo(os) }
                        }
                    }
                }
                _messageFlow.tryEmit(str(R.string.s309))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s257, e.message))
            }
        }
    }

    /** 导出整个服务器核心目录（打包 zip） */
    fun exportServerToUri(uri: android.net.Uri) {
        val dirName = activeDirName() ?: return
        val dir = java.io.File(fileManagerRoot, dirName)
        if (!dir.exists()) {
            _errorFlow.tryEmit(str(R.string.s310))
            return
        }
        exportPathToUri(dir, uri)
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
                    _messageFlow.tryEmit(str(R.string.s311, name))
                    loadFiles(java.io.File(_currentPath.value))
                } else {
                    _errorFlow.tryEmit(str(R.string.s312))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s313, e.message))
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
                // 无 UI 订阅时（后台/无页面）长睡，仅控制台可见时保持 100ms 高频刷新
                if (_consoleLines.subscriptionCount.value <= 0) {
                    delay(2000)
                    continue
                }
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

    // ── 后台保活（开机自启 / 周期保活） ─────────────────────────────

    /** 本地化字符串（ViewModel 中获取资源） */
    private fun str(id: Int, vararg args: Any?): String =
        McApplication.get().getString(id, *args)

    private fun metaPrefs() =
        McApplication.get().getSharedPreferences(BootReceiver.META_PREFS, android.content.Context.MODE_PRIVATE)

    /** 开机自启动开关状态 */
    fun isBootAutoStart(): Boolean = metaPrefs().getBoolean(BootReceiver.KEY_BOOT_AUTO_START, false)

    /** 设置开机自启动：开启时立即拉起前台服务，关闭时停止 */
    fun setBootAutoStart(v: Boolean) {
        metaPrefs().edit().putBoolean(BootReceiver.KEY_BOOT_AUTO_START, v).apply()
        if (v) startKeepAliveService() else stopKeepAliveService()
    }

    /** 后台周期保活开关状态 */
    fun isKeepAliveEnabled(): Boolean = metaPrefs().getBoolean(BootReceiver.KEY_KEEP_ALIVE, false)

    /** 设置后台周期保活：开启时调度 WorkManager 周期任务，关闭时取消 */
    fun setKeepAliveEnabled(v: Boolean) {
        metaPrefs().edit().putBoolean(BootReceiver.KEY_KEEP_ALIVE, v).apply()
        if (v) scheduleKeepAlive() else cancelKeepAlive()
    }

    /** 一像素保活开关状态 */
    fun isPixelKeepAlive(): Boolean = metaPrefs().getBoolean(BootReceiver.KEY_PIXEL, false)

    /** 设置一像素保活：开启时启动 1px 透明 Activity 常驻，关闭时发送销毁广播 */
    fun setPixelKeepAlive(v: Boolean) {
        metaPrefs().edit().putBoolean(BootReceiver.KEY_PIXEL, v).apply()
        val app = McApplication.get()
        if (v) {
            try {
                val intent = android.content.Intent(app, KeepAlivePixelActivity::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                app.startActivity(intent)
            } catch (_: Exception) {}
        } else {
            KeepAlivePixelActivity.stop(app)
        }
    }

    /** 拉起前台保活服务 */
    fun startKeepAliveService() {
        try {
            val intent = android.content.Intent(McApplication.get(), McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_START }
            McApplication.get().startForegroundService(intent)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s314, e.message))
        }
    }

    /** 停止前台保活服务 */
    fun stopKeepAliveService() {
        try {
            val intent = android.content.Intent(McApplication.get(), McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_STOP }
            McApplication.get().startService(intent)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun scheduleKeepAlive() {
        val request = androidx.work.PeriodicWorkRequestBuilder<KeepAliveWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
        androidx.work.WorkManager.getInstance(McApplication.get()).enqueueUniquePeriodicWork(
            "keep_alive", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    private fun cancelKeepAlive() {
        androidx.work.WorkManager.getInstance(McApplication.get()).cancelUniqueWork("keep_alive")
    }
}
