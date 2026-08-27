package com.mineserve.mobile.ui

// 性能修改理由：日志只在有新增内容时发布不可变快照，并降低资源采样频率以减少界面重组。
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.mineserve.mobile.R
import com.mineserve.mobile.BuildConfig
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.BootReceiver
import com.mineserve.mobile.KeepAliveWorker
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.service.McForegroundService
import android.net.Uri
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.ServerCore
import com.mineserve.mobile.data.MinecraftVersionNormalizer
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.ServerState
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.startupPhaseForLog
import com.mineserve.mobile.data.TunnelState
import com.mineserve.mobile.data.TunnelStatus
import com.mineserve.mobile.data.TunnelType
import com.mineserve.mobile.data.DiagnosticCheck
import com.mineserve.mobile.data.DiagnosticReport
import com.mineserve.mobile.data.DiagnosticStatus
import com.mineserve.mobile.data.ServerResourceStats
import com.mineserve.mobile.data.AppRelease
import com.mineserve.mobile.data.AppUpdateService
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.CrashReportManager
import com.mineserve.mobile.server.CrashReportAnalyzer
import com.mineserve.mobile.server.SafeTextFile
import com.mineserve.mobile.server.McServerController
import com.mineserve.mobile.server.PlayerManager
import com.mineserve.mobile.server.PluginManager
import com.mineserve.mobile.server.ServerPropertiesManager
import com.mineserve.mobile.server.PowerNukkitXConfigManager
import com.mineserve.mobile.server.PowerNukkitXLayout
import com.mineserve.mobile.server.ServerImporter
import com.mineserve.mobile.server.TunnelManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.Inet4Address
import java.net.NetworkInterface
import java.net.InetSocketAddress
import java.net.Socket
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.ClipData
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import android.os.SystemClock
import java.io.File

/**
 * 顶层共享 ViewModel：
 *  - 暴露 McConfig / ServerState / Plugins / ConsoleLog
 *  - 转发用户操作到 Controller / Manager
 *  - 所有操作捕获异常，通过 errorFlow 传递给 UI，不崩溃
 */
class McViewModel(
    private val app: McApplication,
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
    val isBootstrapped: StateFlow<Boolean> = app.isBootstrapped

    /** Termux 环境初始化错误信息 */
    val bootstrapError: StateFlow<String?> = app.bootstrapError

    /** bootstrap 下载速度（bytes/s） */
    val bootstrapSpeed: StateFlow<Long> = app.bootstrapSpeed

    /** bootstrap 当前镜像源索引 */
    val currentMirrorIndex: StateFlow<Int> = app.currentMirrorIndex

    /** 镜像源名称列表 */
    val mirrorSources: List<String> get() = app.mirrorSources

    /** 请求切换到下一个镜像源 */
    fun switchBootstrapMirror() {
        android.util.Log.i("McViewModel", "[切换] switchBootstrapMirror 调用, 线程=${Thread.currentThread().name}")
        app.switchBootstrapMirror()
    }

    /** apt 安装下载速度（bytes/s） */
    private val _installSpeed = MutableStateFlow(0L)
    val installSpeed: StateFlow<Long> = _installSpeed.asStateFlow()

    /** 重试 Termux 环境初始化 */
    fun retryBootstrap() {
        app.startBootstrap()
    }

    /** 删除 Termux 运行环境（会自动重新初始化） */
    fun deleteBootstrap() {
        app.deleteBootstrap()
    }

    /** 强制删除 Termux 依赖：彻底卸载，删除后不自动重新初始化（需手动安装） */
    fun forceDeleteBootstrap() {
        app.forceDeleteBootstrap()
    }

    private val _consoleLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val consoleLines: StateFlow<ImmutableList<String>> = _consoleLines.asStateFlow()

    /** 终端专用的后台预处理日志，避免 Compose 主线程翻译和关键词扫描。 */
    private val _terminalConsoleLines = MutableStateFlow<ImmutableList<TerminalDisplayLine>>(persistentListOf())
    val terminalConsoleLines: StateFlow<ImmutableList<TerminalDisplayLine>> = _terminalConsoleLines.asStateFlow()
    private val _logTranslationEnabled = MutableStateFlow(true)
    val logTranslationEnabled: StateFlow<Boolean> = _logTranslationEnabled.asStateFlow()

    /** Small, stable preview for cards that only show the latest few server messages. */
    private val _consolePreviewLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val consolePreviewLines: StateFlow<ImmutableList<String>> = _consolePreviewLines.asStateFlow()

    // ── Termux 终端（会话面板） ─────────────────────────────

    private val _termuxLines = MutableStateFlow<ImmutableList<String>>(persistentListOf())
    val termuxLines: StateFlow<ImmutableList<String>> = _termuxLines.asStateFlow()

    private val _termuxBusy = MutableStateFlow(false)
    val termuxBusy: StateFlow<Boolean> = _termuxBusy.asStateFlow()

    private val terminalMutex = Mutex()
    @Volatile private var interactiveTerminalSessionId: String? = null
    private val _terminalSessions = MutableStateFlow(
        listOf(
            TerminalSession("minecraft", str(R.string.mc_console_label), TerminalSessionType.Minecraft),
            TerminalSession("termux-1", "Termux 1", TerminalSessionType.Termux)
        )
    )
    val terminalSessions: StateFlow<List<TerminalSession>> = _terminalSessions.asStateFlow()
    private val _activeTerminalSessionId = MutableStateFlow("minecraft")
    val activeTerminalSessionId: StateFlow<String> = _activeTerminalSessionId.asStateFlow()

    fun createTerminalSession() {
        val next = (_terminalSessions.value.count { it.type == TerminalSessionType.Termux } + 1)
        val id = "termux-${System.nanoTime()}"
        _terminalSessions.value = _terminalSessions.value + TerminalSession(id, "Termux $next", TerminalSessionType.Termux)
        _activeTerminalSessionId.value = id
    }

    fun selectTerminalSession(id: String) { _activeTerminalSessionId.value = id }

    fun setLogTranslationEnabled(enabled: Boolean) {
        _logTranslationEnabled.value = enabled
        terminalDisplayDirty = true
    }

    fun closeTerminalSession(id: String) {
        val session = _terminalSessions.value.firstOrNull { it.id == id } ?: return
        if (session.type == TerminalSessionType.Minecraft) return
        val remaining = _terminalSessions.value.filterNot { it.id == id }
        if (remaining.none { it.type == TerminalSessionType.Termux }) {
            val fallback = TerminalSession("termux-${System.nanoTime()}", "Termux 1", TerminalSessionType.Termux)
            _terminalSessions.value = remaining + fallback
            _activeTerminalSessionId.value = fallback.id
        } else {
            _terminalSessions.value = remaining
            if (_activeTerminalSessionId.value == id) _activeTerminalSessionId.value = remaining.first().id
        }
    }

    fun executeTerminalCommand(id: String, command: String) {
        if (command.isBlank()) return
        val session = _terminalSessions.value.firstOrNull { it.id == id } ?: return
        if (session.type != TerminalSessionType.Termux || !isBootstrapped.value) return
        viewModelScope.launch {
            terminalMutex.withLock {
                updateTerminalSession(id) { it.copy(busy = true) }
                enqueueTerminalLine(id, "$ $command")
                interactiveTerminalSessionId = id
                try {
                    withContext(Dispatchers.IO) { repo.termuxRuntime.refreshTermux() }
                    val exit = withContext(Dispatchers.IO) {
                        repo.termuxRuntime.execTermux(command) { line -> enqueueTerminalLine(id, line) }
                    }
                    if (exit != 0) enqueueTerminalLine(id, str(R.string.term_exit_code, exit))
                } catch (e: Exception) {
                    enqueueTerminalLine(id, str(R.string.term_exec_error, e.message))
                } finally {
                    interactiveTerminalSessionId = null
                    updateTerminalSession(id) { it.copy(busy = false) }
                }
            }
        }
    }

    /** 向当前 Termux 会话中的命令发送一行 stdin。 */
    fun sendTerminalInput(id: String, input: String) {
        if (input.isBlank() || interactiveTerminalSessionId != id) return
        if (repo.termuxRuntime.sendTermuxInput(input)) {
            enqueueTerminalLine(id, "> $input")
        }
    }

    /** 清空指定终端会话的显示行（保留会话本身） */
    fun clearTerminalSession(id: String) {
        synchronized(terminalOutputBuffers) { terminalOutputBuffers.remove(id) }
        updateTerminalSession(id) { it.copy(lines = kotlinx.collections.immutable.persistentListOf()) }
    }

    /** 清空 MC 控制台缓冲 */
    fun clearConsole() {
        consoleBuffer.clear()
        pendingConsoleBuffer.clear()
        terminalConsoleBuffer.clear()
        consoleGeneration++
        terminalGeneration++
        consoleUiGeneration = consoleGeneration
        terminalUiGeneration = terminalGeneration
        previewUiGeneration = consoleGeneration
        _consoleLines.value = persistentListOf()
        _consolePreviewLines.value = persistentListOf()
        _terminalConsoleLines.value = persistentListOf()
    }

    private fun updateTerminalSession(id: String, transform: (TerminalSession) -> TerminalSession) {
        _terminalSessions.value = _terminalSessions.value.map { if (it.id == id) transform(it) else it }
    }

    private val terminalOutputBuffers = mutableMapOf<String, LogBuffer<String>>()

    private fun enqueueTerminalLine(id: String, line: String) {
        synchronized(terminalOutputBuffers) {
            terminalOutputBuffers.getOrPut(id) { LogBuffer(MAX_LOG_LINES) }.add(line)
        }
    }

    /** 执行 Termux shell 命令（IO 线程，输出实时追加到 termuxLines，命令回显 $ cmd） */
    fun execTermuxCommand(command: String) {
        if (command.isBlank() || _termuxBusy.value) return
        // 环境未初始化完成时禁止输入（bootstrap 下载/解压/装依赖中，命令必然失败）
        if (!isBootstrapped.value) {
            appendTermux(str(R.string.termux_not_ready))
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
                        126 -> str(R.string.termux_hint_126)
                        127 -> str(R.string.termux_hint_127)
                        else -> ""
                    }
                    appendTermux(str(R.string.term_exit_code, exit) + hint)
                    // 失败时输出环境诊断，便于定位根因
                    appendTermux(repo.termuxRuntime.diagnoseCommand(command))
                }
            } catch (e: Exception) {
                appendTermux(str(R.string.term_exec_error, e.message))
            } finally {
                _termuxBusy.value = false
            }
        }
    }

    private val legacyTermuxBuffer = LogBuffer<String>(MAX_LOG_LINES)

    /** 追加到旧版日志缓冲，由后台批处理协程发布，避免逐行触发重组。 */
    private fun appendTermux(line: String) {
        legacyTermuxBuffer.add(line)
    }

    // ── 服务器图标（server-icon.png） ────────────────────────

    /** 图标变更信号（UI 用于重载预览） */
    private val _serverIconVersion = MutableStateFlow(0)
    val serverIconVersion: StateFlow<Int> = _serverIconVersion.asStateFlow()

    /** 当前核心的 server-icon.png 文件（不存在返回 null） */
    fun serverIconFile(): File? {
        val dirName = activeDirName() ?: return null
        val dir = repo.termuxRuntime.serverDirFor(dirName)
        return if (PowerNukkitXLayout.isPowerNukkitX(dir)) null
        else File(dir, "server-icon.png")
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
        if (PowerNukkitXLayout.isPowerNukkitX(repo.termuxRuntime.serverDirFor(dirName))) {
            _errorFlow.tryEmit(str(R.string.err_pnx_icon_full))
            return
        }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_read_image))
                    val src = input.use { android.graphics.BitmapFactory.decodeStream(it) }
                        ?: throw RuntimeException(str(R.string.err_parse_image))
                    try {
                        // 居中裁剪为正方形后缩放到 64×64
                        val size = minOf(src.width, src.height)
                        val x = (src.width - size) / 2
                        val y = (src.height - size) / 2
                        val square = android.graphics.Bitmap.createBitmap(src, x, y, size, size)
                        try {
                            val icon = android.graphics.Bitmap.createScaledBitmap(square, 64, 64, true)
                            try {
                                val target = File(repo.termuxRuntime.serverDirFor(dirName), "server-icon.png")
                                target.parentFile?.mkdirs()
                                java.io.FileOutputStream(target).use { fos ->
                                    icon.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, fos)
                                }
                            } finally {
                                if (!icon.isRecycled) icon.recycle()
                            }
                        } finally {
                            if (square !== src && !square.isRecycled) square.recycle()
                        }
                    } finally {
                        if (!src.isRecycled) src.recycle()
                    }
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
        if (PowerNukkitXLayout.isPowerNukkitX(repo.termuxRuntime.serverDirFor(dirName))) {
            _errorFlow.tryEmit(str(R.string.err_pnx_icon))
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
    private val consoleBuffer = LogBuffer<String>(MAX_LOG_LINES)
    private val pendingConsoleBuffer = LogBuffer<String>(MAX_LOG_LINES)
    private val terminalConsoleBuffer = LogBuffer<TerminalDisplayLine>(MAX_LOG_LINES)
    @Volatile private var terminalDisplayDirty = false
    private var consoleGeneration = 0L
    private var terminalGeneration = 0L
    private var consoleUiGeneration = -1L
    private var terminalUiGeneration = -1L
    private var previewUiGeneration = -1L
    private var lastPreviewPublishedAtMs = 0L

    /** 错误消息流，UI 层收集后用 Snackbar 显示 */
    private val _errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val errorFlow = _errorFlow.asSharedFlow()

    /** 操作结果消息流，UI 层收集后用 Snackbar 显示 */
    private val _messageFlow = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val messageFlow = _messageFlow.asSharedFlow()

    /** 依赖安装中状态，UI 层据此控制按钮和加载动画 */
    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _javaOperation = MutableStateFlow<String?>(null)
    val javaOperation: StateFlow<String?> = _javaOperation.asStateFlow()

    private val _installedJava = MutableStateFlow<Set<JavaVersion>>(emptySet())
    val installedJava: StateFlow<Set<JavaVersion>> = _installedJava.asStateFlow()

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
        data class Available(val release: AppRelease) : UpdateUiState
        data class Downloading(val progress: Float, val release: AppRelease) : UpdateUiState
        data class Downloaded(val release: AppRelease, val apkPath: String) : UpdateUiState
        data class Failed(val message: String, val release: AppRelease? = null) : UpdateUiState
    }

    private val _updateState = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val updateState: StateFlow<UpdateUiState> = _updateState.asStateFlow()

    /** 更新对话框是否显示 */
    private val _updateDialogVisible = MutableStateFlow(false)
    val updateDialogVisible: StateFlow<Boolean> = _updateDialogVisible.asStateFlow()

    /** 最近一次更新检查结果描述（设置页显示），如「已是最新版本 · 08:30」 */
    private val _lastUpdateCheckResult = MutableStateFlow<String?>(null)
    val lastUpdateCheckResult: StateFlow<String?> = _lastUpdateCheckResult.asStateFlow()
    private var skippedReleaseTag: String? = null

    fun dismissUpdateDialog() { _updateDialogVisible.value = false }

    /** 检查更新：manual=true 来自设置页（显示检查进度 + 失败提示）；auto=true 启动检查（失败静默 + 有新版发通知） */
    fun checkForUpdate(manual: Boolean = false) {
        if (_updateState.value is UpdateUiState.Checking) return
        _updateState.value = UpdateUiState.Checking
        if (manual) _updateDialogVisible.value = true // 手动检查：立即显示检查中对话框
        viewModelScope.launch {
            try {
                val release = AppUpdateService.latest(BuildConfig.VERSION_NAME)
                if (release == null) {
                    _updateState.value = UpdateUiState.Idle
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_already_latest)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = false
                        _messageFlow.tryEmit(app.getString(R.string.update_already_latest))
                    }
                } else {
                    _updateState.value = UpdateUiState.Available(release)
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_available, release.tag)} · ${nowTime()}"
                    if (manual || skippedReleaseTag != release.tag) {
                        _updateDialogVisible.value = true
                        if (!manual) showUpdateNotification(app, release)
                    }
                }
            } catch (e: Exception) {
                    _updateState.value = UpdateUiState.Failed(e.message ?: str(R.string.msg_update_check_failed))
                    _lastUpdateCheckResult.value =
                        "${app.getString(R.string.update_check_failed)} · ${nowTime()}"
                    if (manual) {
                        _updateDialogVisible.value = true
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

    fun skipCurrentUpdate() {
        val state = _updateState.value as? UpdateUiState.Available ?: return
        skippedReleaseTag = state.release.tag
        dismissUpdateDialog()
    }

    /** 打开浏览器跳到 GitHub Release 页面（供用户手动下载更新） */
    fun openGithubUpdate() {
        val url = (_updateState.value as? UpdateUiState.Available)?.release?.releaseUrl
            ?: com.mineserve.mobile.data.AppUpdateService.PROJECT_URL
        try {
            val intent = android.content.Intent(
                android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(url)
            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            app.startActivity(intent)
        } catch (e: Exception) {
            _messageFlow.tryEmit(str(R.string.msg_open_browser_fail, e.message))
        }
    }

    /** 开始下载新版 APK（完成后自动调系统安装器） */
    fun downloadUpdate() {
        val state = _updateState.value
        val release = when (state) {
            is UpdateUiState.Available -> state.release
            is UpdateUiState.Failed -> state.release
            else -> null
        } ?: return
        _updateState.value = UpdateUiState.Downloading(0f, release)
        viewModelScope.launch {
            try {
                val target = File(app.cacheDir, "update/MineServeMobile-latest.apk")
                AppUpdateService.download(release.apkUrls, target) { p ->
                    _updateState.value = UpdateUiState.Downloading(p, release)
                }
                _updateState.value = UpdateUiState.Downloaded(release, target.absolutePath)
            } catch (e: Exception) {
                _updateState.value = UpdateUiState.Failed(e.message ?: app.getString(R.string.update_download_failed), release)
            }
        }
    }

    /** 调系统安装器安装 APK */
    fun installApk(context: Context, file: File) {
        try {
            if (!AppUpdateService.isApk(file)) {
                throw IllegalStateException(str(R.string.err_apk_corrupt))
            }
            val archive = context.packageManager.getPackageArchiveInfo(file.absolutePath, 0)
                ?: throw IllegalStateException(str(R.string.err_apk_read_pkg))
            if (archive.packageName != context.packageName) {
                throw IllegalStateException(str(R.string.err_apk_pkg_mismatch))
            }
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O &&
                !context.packageManager.canRequestPackageInstalls()) {
                context.startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:${context.packageName}")
                    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
                _messageFlow.tryEmit(str(R.string.msg_allow_unknown_install))
                return
            }
            val uri = FileProvider.getUriForFile(
                context, context.packageName + ".fileprovider", file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                clipData = ClipData.newRawUri("update-apk", uri)
            }
            val installers = context.packageManager.queryIntentActivities(
                intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY
            )
            if (installers.isEmpty()) {
                throw IllegalStateException(str(R.string.err_no_installer))
            }
            installers.forEach { handler ->
                context.grantUriPermission(
                    handler.activityInfo.packageName,
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            _updateState.value = UpdateUiState.Failed(e.message ?: "install error")
        }
    }

    fun installDownloadedUpdate(path: String) = installApk(app, File(path))

    /** 自动检查发现新版时发系统通知，点击进入更新对话框 */
    private fun showUpdateNotification(app: Context, info: AppRelease) {
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
            .setContentTitle(app.getString(R.string.update_notif_title, info.tag))
            .setContentText(app.getString(R.string.update_notif_text))
            .setContentIntent(contentIntent)
            .setAutoCancel(true)
            .build()
        try { nm.notify(1001, notification) } catch (_: Exception) {}
    }

    // ── 服务端资源状态 ───────────────────────────────────────────────

    private val _serverResources = MutableStateFlow(ServerResourceStats())
    val serverResources: StateFlow<ServerResourceStats> = _serverResources.asStateFlow()
    private var cachedResourceDir: String? = null
    private var cachedDirectoryBytes: Long? = null
    private var cachedDirectoryBytesAtMs = 0L
    private var previousCpuTotalTicks = 0L
    private var previousCpuIdleTicks = 0L

    /** Samples global system CPU plus values that belong to the selected server. */
    private fun startServerResourceCollection() {
        viewModelScope.launch {
            // Defer the first recursive server-directory scan until the first screen settles.
            delay(15_000)
            while (true) {
                withContext(Dispatchers.IO) { collectServerResourcesOnce() }
                delay(15_000)
            }
        }
    }

    private fun collectServerResourcesOnce() {
        try {
            val cfg = config.value
            val active = cfg.installedCores.find { it.name == cfg.activeCoreName }
            val dir = active?.let { File(repo.termuxRuntime.installer.rootDir, "home/servers/${it.dirName}") }
            val available = dir?.takeIf { it.exists() }?.let { android.os.StatFs(it.path).availableBytes }
            val now = System.currentTimeMillis()
            val running = repo.termuxRuntime.isMcRunning()
            val directoryBytes = dir?.takeIf { running && it.exists() }?.let { serverDir ->
                if (cachedResourceDir != serverDir.path || now - cachedDirectoryBytesAtMs >= 60_000L) {
                    cachedResourceDir = serverDir.path
                    cachedDirectoryBytes = serverDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                    cachedDirectoryBytesAtMs = now
                }
                cachedDirectoryBytes
            }
            val memory = repo.termuxRuntime.mcProcessMemoryMb().takeIf { running && it > 0L }
            // CPU is the whole device's usage and remains available even when the server is stopped.
            val cpu = readSystemCpuPercent()
            _serverResources.value = ServerResourceStats(
                processMemoryMb = memory,
                cpuPercent = cpu,
                availableBytes = available,
                directoryBytes = directoryBytes,
                javaAvailable = repo.termuxRuntime.isJavaInstalled(cfg.selectedJavaVersion),
                sampledAtMs = now
            )
            repo.updateServerState { it.copy(usedMemoryMb = memory ?: 0L, cpuPercent = cpu) }
        } catch (e: Exception) {
            // Keep the last known snapshot when Android or PRoot denies a probe.
        }
    }

    /** Linux procfs 轻量采样：计算整机系统总 CPU 占用，不依赖 MC 进程。 */
    private fun readSystemCpuPercent(): Int? = runCatching {
        val sample = File("/proc/stat").useLines { lines ->
            val fields = lines.firstOrNull { it.startsWith("cpu ") }
                ?.trim()?.split(Regex("\\s+")) ?: return@useLines null
            val values = fields.drop(1).map { token ->
                val value = token.toLongOrNull()
                if (value == null || value < 0L) return@useLines null
                value
            }
            if (values.size < 5) return@useLines null

            // /proc/stat: user, nice, system, idle, iowait, irq, softirq, steal...
            val totalTicks = values.sum()
            val idleTicks = values[3] + values[4]
            if (totalTicks <= 0L || idleTicks > totalTicks) return@useLines null
            CpuTicks(totalTicks, idleTicks)
        } ?: return@runCatching null

        val totalDelta = sample.totalTicks - previousCpuTotalTicks
        val idleDelta = sample.idleTicks - previousCpuIdleTicks
        val percent = if (previousCpuTotalTicks > 0L &&
            totalDelta > 0L && idleDelta in 0L..totalDelta
        ) {
            ((totalDelta - idleDelta).toDouble() / totalDelta * 100.0)
                .toInt().coerceIn(0, 100)
        } else null

        // Always advance the baseline so a counter reset or malformed interval can resync next time.
        previousCpuTotalTicks = sample.totalTicks
        previousCpuIdleTicks = sample.idleTicks
        percent
    }.getOrNull()

    private data class CpuTicks(val totalTicks: Long, val idleTicks: Long)

    private val _diagnosticReport = MutableStateFlow(DiagnosticReport())
    val diagnosticReport: StateFlow<DiagnosticReport> = _diagnosticReport.asStateFlow()
    private val _isDiagnosing = MutableStateFlow(false)
    val isDiagnosing: StateFlow<Boolean> = _isDiagnosing.asStateFlow()
    private val _isRepairingRuntime = MutableStateFlow(false)
    val isRepairingRuntime: StateFlow<Boolean> = _isRepairingRuntime.asStateFlow()

    fun runDiagnostics() {
        if (_isDiagnosing.value || _isRepairingRuntime.value) return
        _isDiagnosing.value = true
        viewModelScope.launch {
            try {
                _diagnosticReport.value = withContext(Dispatchers.IO) { buildDiagnosticReport() }
            } catch (e: Exception) {
                _diagnosticReport.value = DiagnosticReport(
                    checks = listOf(DiagnosticCheck(
                        "scan", str(R.string.diag_check_scan), e.message ?: str(R.string.diag_check_scan_fail), DiagnosticStatus.Failed
                    )),
                    generatedAtMs = System.currentTimeMillis()
                )
            } finally {
                _isDiagnosing.value = false
            }
        }
    }

    fun safeRepairRuntime() {
        if (_isRepairingRuntime.value || _isDiagnosing.value) return
        _isRepairingRuntime.value = true
        _diagnosticReport.value = _diagnosticReport.value.copy(isRunning = true)
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val cfg = config.value
                    val core = cfg.installedCores.find { it.name == cfg.activeCoreName }
                    repo.termuxRuntime.fixRootfsPermissions()
                    repo.termuxRuntime.repairInstalledCommands()
                    if (repo.termuxRuntime.isReady()) {
                        repo.termuxRuntime.autoRepairRuntime(
                            cfg.selectedJavaVersion,
                            core?.let { repo.termuxRuntime.needsFontRuntime(it.core) } == true
                        )
                    }
                }
                refreshJava()
                refreshDependencies()
                _messageFlow.tryEmit(str(R.string.msg_repair_done))
            } catch (e: Exception) {
                _errorFlow.tryEmit(e.message ?: str(R.string.msg_repair_fail))
            } finally {
                _isRepairingRuntime.value = false
                runDiagnostics()
            }
        }
    }

    private fun buildDiagnosticReport(): DiagnosticReport {
        val cfg = config.value
        val runtime = repo.termuxRuntime
        val active = cfg.installedCores.find { it.name == cfg.activeCoreName }
        val checks = mutableListOf<DiagnosticCheck>()
        val runtimeReady = runtime.isReady()
        checks += DiagnosticCheck(
            "runtime", str(R.string.diag_check_runtime),
            if (runtimeReady) str(R.string.diag_check_runtime_ok) else str(R.string.diag_check_runtime_bad),
            if (runtimeReady) DiagnosticStatus.Pass else DiagnosticStatus.Failed, !runtimeReady
        )
        val javaCheck = runtime.javaRuntimeDiagnostic(cfg.selectedJavaVersion)
        checks += DiagnosticCheck(
            "java", str(R.string.diag_check_java, cfg.selectedJavaVersion.displayName), javaCheck.second,
            if (javaCheck.first) DiagnosticStatus.Pass else DiagnosticStatus.Failed, !javaCheck.first
        )
        if (active?.core == ServerCore.NeoForge && cfg.selectedJavaVersion != JavaVersion.Java8) {
            checks += DiagnosticCheck(
                "native-jna", str(R.string.diag_check_native),
                str(R.string.diag_check_native_detail),
                DiagnosticStatus.Warning
            )
        }
        val prootRequired = cfg.selectedJavaVersion == JavaVersion.Java8
        val downloaderRequired = active?.core?.needsInstaller == true
        val requiredCommandsReady = (!prootRequired || runtime.isCommandInstalled("proot")) &&
            (!downloaderRequired || runtime.isCommandInstalled("wget"))
        checks += DiagnosticCheck(
            "commands", str(R.string.diag_check_commands),
            when {
                !prootRequired && !downloaderRequired -> str(R.string.diag_check_commands_none)
                requiredCommandsReady -> str(R.string.diag_check_commands_ready, if (prootRequired) "proot" else "wget")
                else -> str(R.string.diag_check_commands_missing2, listOfNotNull(if (prootRequired) "proot" else null, if (downloaderRequired) "wget" else null).joinToString(" / "))
            },
            if (!prootRequired && !downloaderRequired) DiagnosticStatus.NotApplicable else if (requiredCommandsReady) DiagnosticStatus.Pass else DiagnosticStatus.Warning,
            !requiredCommandsReady
        )
        if (active == null) {
            checks += DiagnosticCheck(
                "core", str(R.string.diag_check_core), str(R.string.diag_check_core_none), DiagnosticStatus.Warning
            )
            checks += DiagnosticCheck(
                "storage", str(R.string.diag_check_storage), str(R.string.diag_check_storage_none), DiagnosticStatus.NotApplicable
            )
            checks += DiagnosticCheck(
                "port", str(R.string.diag_check_port), str(R.string.diag_check_port_none), DiagnosticStatus.NotApplicable
            )
        } else {
            val dir = File(runtime.installer.rootDir, "home/servers/${active.dirName}")
            val entryName = active.serverFile?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
            val jar = entryName?.let { File(dir, it) }
            val dirReady = dir.isDirectory && dir.canRead() && dir.canWrite()
            checks += DiagnosticCheck(
                "core", str(R.string.diag_check_core),
                if (!dirReady) str(R.string.diag_check_core_dir_bad) else if (jar?.isFile != true) str(R.string.diag_check_core_entry_missing, entryName ?: "未指定") else str(R.string.diag_check_core_ok, active.name),
                if (dirReady && jar?.isFile == true) DiagnosticStatus.Pass else DiagnosticStatus.Failed
            )
            val fontNeeded = runtime.needsFontRuntime(active.core)
            val fontsReady = fontNeeded && runtime.fontRuntimeReady(cfg.selectedJavaVersion)
            checks += DiagnosticCheck(
                "fonts", str(R.string.diag_check_fonts),
                when {
                    !fontNeeded -> str(R.string.diag_check_fonts_not_needed)
                    fontsReady -> str(R.string.diag_check_fonts_ready)
                    else -> str(R.string.diag_check_fonts_need)
                },
                when {
                    !fontNeeded -> DiagnosticStatus.NotApplicable
                    fontsReady -> DiagnosticStatus.Pass
                    else -> DiagnosticStatus.Warning
                }, fontNeeded
            )
            val available = try { if (dir.exists()) android.os.StatFs(dir.path).availableBytes else null } catch (_: Exception) { null }
            checks += DiagnosticCheck(
                "storage", str(R.string.diag_check_storage),
                available?.let { str(R.string.diag_check_storage_avail, formatDiagnosticBytes(it)) } ?: str(R.string.diag_check_storage_unreadable),
                if (available != null) DiagnosticStatus.Pass else DiagnosticStatus.Warning
            )
            val running = runtime.isMcRunning()
            val bedrock = active.core == ServerCore.PowerNukkitX
            val portOk = if (running) {
                if (bedrock) probeBedrockUdpPort(cfg.localPort) else probeLoopbackPort(cfg.localPort)
            } else false
            val runningSince = repo.serverState.value.runningSinceMs
            val starting = running && runningSince > 0L &&
                android.os.SystemClock.elapsedRealtime() - runningSince < 15_000L
            checks += DiagnosticCheck(
                "port", str(R.string.diag_check_port_title, if (bedrock) "UDP" else "TCP", cfg.localPort),
                when { !running -> str(R.string.diag_check_port_not_running); portOk -> str(R.string.diag_check_port_ok, cfg.localPort); starting -> str(R.string.diag_check_port_starting); else -> str(R.string.diag_check_port_bad2) },
                when { !running -> DiagnosticStatus.NotApplicable; portOk -> DiagnosticStatus.Pass; else -> DiagnosticStatus.Warning }
            )
            val crash = (crashReportManager.listCrashReports() + crashReportManager.listNativeCrashReports(active.dirName))
                .maxByOrNull { it.createdTime }
            checks += DiagnosticCheck(
                "crash", str(R.string.diag_check_crash),
                crash?.let { str(R.string.diag_check_crash_found, it.fileName) } ?: str(R.string.diag_check_crash_none),
                if (crash == null) DiagnosticStatus.Pass else DiagnosticStatus.Warning
            )
        }
        return DiagnosticReport(checks, System.currentTimeMillis(), repo.termuxRuntime.isMcRunning())
    }

    private fun probeLoopbackPort(port: Int): Boolean = try {
        Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 1_500); true }
    } catch (_: Exception) { false }

    private fun probeBedrockUdpPort(port: Int): Boolean = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = 1_500
            val packet = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN)
                .put(0x01.toByte())
                .putLong(System.currentTimeMillis())
                .put(byteArrayOf(0x00, 0xff.toByte(), 0xff.toByte(), 0x00, 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfe.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0xfd.toByte(), 0x12, 0x34, 0x56, 0x78))
                .putLong(System.nanoTime()).array()
            socket.send(DatagramPacket(packet, packet.size, java.net.InetAddress.getByName("127.0.0.1"), port))
            val response = DatagramPacket(ByteArray(2048), 2048)
            socket.receive(response)
            response.length > 0
        }
    } catch (_: Exception) { false }

    private fun formatDiagnosticBytes(bytes: Long): String = when {
        bytes >= 1_073_741_824L -> String.format(java.util.Locale.US, "%.1f GB", bytes / 1_073_741_824.0)
        bytes >= 1_048_576L -> String.format(java.util.Locale.US, "%.1f MB", bytes / 1_048_576.0)
        bytes >= 1_024L -> String.format(java.util.Locale.US, "%.1f KB", bytes / 1_024.0)
        else -> "$bytes B"
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
        private const val MAX_LOG_LINES = 1500
        private const val LOG_FLUSH_MS = 200L
        private const val CONSOLE_PREVIEW_FLUSH_MS = 750L
        /** 下载阶段锁定超时：60 秒无 post-download 消息则强制解锁 */
        private const val DOWNLOAD_LOCK_TIMEOUT_MS = 60_000L
        // 预编译正则，避免每行重新编译
        private val PLAYERS_REGEX = Regex("There are (\\d+) of a max of (\\d+) players online")
        private val TPS_REGEX = Regex("TPS from last 1m.*?:\\s*([\\d.]+)")

        val Factory: ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return McApplication.get().container.viewModelFactory.create(modelClass)
            }
        }
    }

    /**
     * 解析 MC 控制台输出，提取 TPS / 玩家数 / 启动状态等运行时信息。
     * 使用快速前缀检查避免不必要的正则匹配。
     */
    private var lastJavaCompatibilityWarning: String? = null

    private fun parseConsoleLine(line: String) {
        try {
            val startupPhase = startupPhaseForLog(line)
            updateStartupPhaseFromLog(startupPhase)
            val requiredJava = CrashReportAnalyzer.requiredJavaVersion(line)
            if (requiredJava != null) {
                val selected = when (config.value.selectedJavaVersion) {
                    JavaVersion.Java8 -> 8; JavaVersion.Java17 -> 17; JavaVersion.Java21 -> 21; JavaVersion.Java25 -> 25
                }
                if (selected < requiredJava) {
                    val core = config.value.installedCores.firstOrNull { it.name == config.value.activeCoreName }?.core?.displayName ?: str(R.string.diag_core_unknown_name)
                    val warning = str(R.string.msg_java_warning, core, selected, requiredJava)
                    if (warning != lastJavaCompatibilityWarning) {
                        lastJavaCompatibilityWarning = warning
                        _errorFlow.tryEmit(str(R.string.err_java_unsupported, warning))
                    }
                }
            }
            // 快速前缀检查：只有包含关键子串的行才进一步处理
            when {
                playerManager.extractPowerNukkitPlayerEvent(line) != null -> {
                    val (name, joined) = playerManager.extractPowerNukkitPlayerEvent(line)!!
                    repo.updateServerState { it.copy(onlinePlayers = (it.onlinePlayers + if (joined) 1 else -1).coerceAtLeast(0)) }
                    if (joined) addOnlinePlayer(name) else removeOnlinePlayer(name)
                    recordPlayerEvent(name, if (joined) "进服" else "离服")
                }
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
                        // A valid list response proves the server is already accepting commands.
                        markServerReady()
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
                startupPhase == StartupPhase.Ready -> markServerReady()
            }
        } catch (e: Exception) {
            // 解析失败不影响正常运行
        }
    }

    /** 将控制台明确的就绪信号统一转换为运行中。 */
    private fun markServerReady() {
        var becameReady = false
        repo.updateServerState { state ->
            if (!state.isRunning) return@updateServerState state
            becameReady = state.runningSinceMs == 0L
            state.copy(
                tps = 20.0,
                healthPercent = 100,
                maxMemoryMb = config.value.maxHeapMb.toLong(),
                startupPhase = StartupPhase.Ready,
                runningSinceMs = state.runningSinceMs.takeIf { it > 0L }
                    ?: SystemClock.elapsedRealtime()
            )
        }
        // 启动完成时主动请求一次 list，全量校正在线玩家名单。
        if (becameReady && repo.termuxRuntime.isMcRunning()) playerManager.requestOnlineList()
    }

    /** 在后台日志解析线程推进启动阶段，UI 不扫描原始日志。
     * 下载阶段锁定：检测到下载日志后锁定在 DownloadingDependencies，
     * 直到出现明确的 post-download 消息（LoadingCore 及以上）才解锁；
     * 若 60 秒内无 post-download 消息则超时强制解锁。 */
    private fun updateStartupPhaseFromLog(phase: StartupPhase?) {
        phase ?: return
        repo.updateServerState { state ->
            if (!state.isRunning || state.startupPhase == StartupPhase.Ready) return@updateServerState state

            // 下载活动检测：无条件更新阶段并记录时间戳
            if (phase == StartupPhase.DownloadingDependencies) {
                return@updateServerState state.copy(
                    startupPhase = phase,
                    lastDownloadActivityMs = SystemClock.elapsedRealtime()
                )
            }

            // 下载阶段锁定：当前处于 DownloadingDependencies 时
            if (state.startupPhase == StartupPhase.DownloadingDependencies) {
                // post-download 消息（LoadingCore / CreatingWorld / StartingNetwork）→ 解锁
                if (phase == StartupPhase.LoadingCore ||
                    phase == StartupPhase.CreatingWorld ||
                    phase == StartupPhase.StartingNetwork ||
                    phase == StartupPhase.Ready
                ) {
                    return@updateServerState state.copy(startupPhase = phase)
                }
                // 超时保护：60 秒无 post-download 消息则强制解锁
                val elapsed = SystemClock.elapsedRealtime() - state.lastDownloadActivityMs
                if (elapsed < DOWNLOAD_LOCK_TIMEOUT_MS) return@updateServerState state
                // 超时后允许 Ready 跃升
            }

            if (phase.progress >= state.startupPhase.progress) {
                state.copy(startupPhase = phase)
            } else state
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

    fun refreshJava() {
        if (!isBootstrapped.value) return
        viewModelScope.launch(Dispatchers.IO) {
            _installedJava.value = repo.termuxRuntime.installedJavaVersions()
        }
    }

    fun refreshDependencies() {
        if (!isBootstrapped.value) return
        viewModelScope.launch(Dispatchers.IO) {
            val steps = repo.termuxRuntime.installedDependencySteps()
            repo.updateServerState { state ->
                state.copy(installSteps = steps)
            }
        }
    }

    fun setJavaVersion(version: JavaVersion) = updateConfig {
        it.copy(selectedJavaVersion = version)
    }

    fun setJavaCardAtBottom(atBottom: Boolean) =
        updateConfig { it.copy(javaCardAtBottom = atBottom) }

    fun installJava(version: JavaVersion) {
        if (!isBootstrapped.value || _isInstalling.value) return
        _isInstalling.value = true
        _javaOperation.value = str(R.string.msg_java_op_installing, version.displayName)
        viewModelScope.launch {
            try {
                if (repo.termuxRuntime.installJava(version)) {
                    if (version == JavaVersion.Java8) {
                        repo.saveConfig(config.value.copy(selectedJavaVersion = JavaVersion.Java8))
                    }
                    refreshJava()
                    _messageFlow.tryEmit(str(R.string.msg_java_installed, version.displayName))
                } else _errorFlow.tryEmit(str(R.string.err_java_install_fail, version.displayName))
            } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.msg_java_install_fail2)) }
            finally {
                _javaOperation.value = null
                _isInstalling.value = false
            }
        }
    }

    fun clearAndReinstallJava() {
        if (!isBootstrapped.value || _isInstalling.value) return
        if (repo.termuxRuntime.isMcRunning()) {
            _errorFlow.tryEmit(str(R.string.err_java_running))
            return
        }
        _isInstalling.value = true
        _javaOperation.value = str(R.string.msg_java_op_reinstall)
        viewModelScope.launch {
            try {
                if (repo.termuxRuntime.clearAndReinstallJava()) {
                    refreshJava()
                    _messageFlow.tryEmit(str(R.string.msg_java_reinstalled))
                } else _errorFlow.tryEmit(str(R.string.err_java_reinstall_fail))
            } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.msg_java_reinstall_fail2)) }
            finally {
                _javaOperation.value = null
                _isInstalling.value = false
            }
        }
    }

    fun selectCore(core: ServerCore) = updateConfig {
        if (core == ServerCore.PowerNukkitX) it.copy(
            selectedCore = core,
            mcVersion = "latest",
            localPort = if (it.localPort == 25565) 19132 else it.localPort
        ) else it.copy(selectedCore = core)
    }

    fun setMcVersion(version: String) = updateConfig {
        val normalized = MinecraftVersionNormalizer.forCore(it.selectedCore, version)
        if (normalized != version.trim()) {
            _messageFlow.tryEmit(str(R.string.msg_version_corrected, it.selectedCore.displayName, version.trim(), normalized))
        }
        it.copy(mcVersion = normalized)
    }

    fun setLocalPort(port: Int) = updateConfig { it.copy(localPort = port) }
    fun setDomain(d: String) = updateConfig { it.copy(customDomain = d) }
    fun setTunnelType(type: TunnelType) = updateConfig { it.copy(tunnelType = type) }
    fun setMaxHeap(mb: Int) = updateConfig { it.copy(maxHeapMb = mb) }
    fun setAutoRestart(v: Boolean) = updateConfig { it.copy(autoRestartOnCrash = v) }
    fun setKeepWifiLock(v: Boolean) = updateConfig { it.copy(keepWifiLock = v) }
    fun setKeepCpuWakelock(v: Boolean) = updateConfig { it.copy(keepCpuWakelock = v) }
    fun setKeepScreenOnWhileRunning(v: Boolean) = updateConfig { it.copy(keepScreenOnWhileRunning = v) }
    fun setKeepStatusOverlay(v: Boolean) = updateConfig { it.copy(keepStatusOverlay = v) }
    fun setAptMirror(mirror: com.mineserve.mobile.data.AptMirror) =
        viewModelScope.launch {
            val effective = if (mirror == com.mineserve.mobile.data.AptMirror.Official) {
                com.mineserve.mobile.data.AptMirror.Tuna
            } else mirror
            repo.saveConfig(config.value.copy(aptMirror = effective))
            withContext(Dispatchers.IO) { repo.termuxRuntime.setAptMirror(effective) }
        }
    fun setDownloadMirror(mirror: com.mineserve.mobile.data.DownloadMirror) =
        updateConfig { it.copy(downloadMirror = mirror) }

    /** 多线程下载是否启用（内置下载模块开关，默认启用） */
    fun isMultiThreadDownloadEnabled(): Boolean = com.mineserve.mobile.data.DownloadPrefs.isEnabled()

    /** 下载线程数 */
    fun downloadThreadCount(): Int = com.mineserve.mobile.data.DownloadPrefs.threadCount()

    /** 切换多线程下载开关 */
    fun setMultiThreadDownloadEnabled(enabled: Boolean) {
        com.mineserve.mobile.data.DownloadPrefs.setEnabled(enabled)
        _messageFlow.tryEmit(if (enabled) str(R.string.msg_mt_enabled) else str(R.string.msg_mt_disabled))
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
                    refreshDependencies()
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
                lastJavaCompatibilityWarning = null
                val current = config.value
                val startConfig = current
                repo.updateServerState {
                    it.copy(
                        isRunning = true,
                        runningSinceMs = 0L,
                        startupPhase = StartupPhase.PreparingEnvironment,
                        lastDownloadActivityMs = 0L
                    )
                }
                _messageFlow.tryEmit(str(R.string.s196))
                controller.start(startConfig)
                startKeepAliveService()
            } catch (e: Exception) {
                repo.updateServerState {
                    it.copy(isRunning = false, runningSinceMs = 0L, startupPhase = StartupPhase.Failed)
                }
                repo.termuxRuntime.emitLog("[startMc] 启动失败: ${e.message}")
                showStartupFailureReport(e)
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
                            if (url.isNotBlank()) str(R.string.msg_tunnel_started_url, url)
                            else str(R.string.msg_tunnel_started)
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
                } ?: throw RuntimeException(str(R.string.err_parse_latest_url))
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

    /** Modrinth 搜索参数（用于分页加载更多） */
    private data class ModrinthQueryArgs(
        val query: String,
        val loaders: List<String>,
        val sort: String,
        val mcVersion: String
    )

    private var lastModrinthModQuery: ModrinthQueryArgs? = null
    private val _isLoadingMoreMods = MutableStateFlow(false)
    val isLoadingMoreMods: StateFlow<Boolean> = _isLoadingMoreMods.asStateFlow()

    /** 搜索 Modrinth 模组（多加载器 + 版本筛选 + 排序） */
    fun searchModrinthMods(query: String, loaders: List<String>, sort: String, mcVersion: String) {
        lastModrinthModQuery = ModrinthQueryArgs(query, loaders, sort, mcVersion)
        viewModelScope.launch {
            _modrinthResults.value = withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, loaders, sort, projectType = "mod", mcVersion = mcVersion)
            }
            if (_modrinthResults.value.isEmpty()) {
                _messageFlow.tryEmit(str(R.string.s223))
            }
        }
    }

    /** 分页加载更多 Modrinth 模组（追加到现有结果） */
    fun loadMoreModrinthMods() {
        val args = lastModrinthModQuery ?: run {
            _messageFlow.tryEmit("请先执行搜索")
            return
        }
        if (_isLoadingMoreMods.value) return
        viewModelScope.launch {
            _isLoadingMoreMods.value = true
            try {
                val offset = _modrinthResults.value.size
                val more = withContext(Dispatchers.IO) {
                    pluginManager.searchModrinth(args.query, args.loaders, args.sort, projectType = "mod", mcVersion = args.mcVersion, offset = offset)
                }
                if (more.isEmpty()) {
                    _messageFlow.tryEmit("没有更多结果")
                } else {
                    _modrinthResults.value = (_modrinthResults.value + more).distinctBy { it.slug }
                }
            } finally {
                _isLoadingMoreMods.value = false
            }
        }
    }

    /** 检测已安装插件冲突（同名 / 同主类） */
    fun detectPluginConflicts(plugins: List<PluginManager.InstalledPlugin>): List<String> =
        pluginManager.detectPluginConflicts(plugins)

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
                } ?: throw RuntimeException(str(R.string.err_mod_incompatible))
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

    private var lastModrinthPluginQuery: ModrinthQueryArgs? = null
    private val _isLoadingMorePlugins = MutableStateFlow(false)
    val isLoadingMorePlugins: StateFlow<Boolean> = _isLoadingMorePlugins.asStateFlow()

    /** 插件页搜索 Modrinth 插件（project_type=plugin，布局与模组页统一） */
    fun searchModrinthPlugin(query: String, loaders: List<String>, sort: String, mcVersion: String) {
        lastModrinthPluginQuery = ModrinthQueryArgs(query, loaders, sort, mcVersion)
        viewModelScope.launch {
            _pluginModrinthResults.value = withContext(Dispatchers.IO) {
                pluginManager.searchModrinth(query, loaders, sort, projectType = "plugin", mcVersion = mcVersion)
            }
            if (_pluginModrinthResults.value.isEmpty()) {
                _messageFlow.tryEmit(str(R.string.s223))
            }
        }
    }

    /** 分页加载更多 Modrinth 插件（追加到现有结果） */
    fun loadMoreModrinthPlugin() {
        val args = lastModrinthPluginQuery ?: run {
            _messageFlow.tryEmit("请先执行搜索")
            return
        }
        if (_isLoadingMorePlugins.value) return
        viewModelScope.launch {
            _isLoadingMorePlugins.value = true
            try {
                val offset = _pluginModrinthResults.value.size
                val more = withContext(Dispatchers.IO) {
                    pluginManager.searchModrinth(args.query, args.loaders, args.sort, projectType = "plugin", mcVersion = args.mcVersion, offset = offset)
                }
                if (more.isEmpty()) {
                    _messageFlow.tryEmit("没有更多结果")
                } else {
                    _pluginModrinthResults.value = (_pluginModrinthResults.value + more).distinctBy { it.slug }
                }
            } finally {
                _isLoadingMorePlugins.value = false
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
                } ?: throw RuntimeException(str(R.string.err_plugin_incompatible))
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
                    } ?: throw RuntimeException(str(R.string.err_parse_curated_url, curated.name))
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
                    val msg = if (alsoRemoveDataDir) str(R.string.msg_deleted_with_data, fileName) else str(R.string.msg_deleted, fileName)
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
                    val action = if (newName.startsWith("-")) str(R.string.msg_disabled) else str(R.string.msg_enabled)
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
                    if (updateCount > 0) str(R.string.msg_updates_found, updateCount)
                    else str(R.string.msg_updates_all_latest)
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
        return withContext(Dispatchers.IO) {
            backupManager.backupWorldToExternal(dirName, BackupManager.BackupOrigin.Manual)
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
                _messageFlow.tryEmit(str(R.string.err_backup_fail, e.message))
            }
        }
    }

    /** 外部备份整个世界（world+nether+end → 外部目录） */
    fun backupWorldToExternal() {        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupWorldToExternal(dirName, BackupManager.BackupOrigin.Manual)
                }
                if (path != null) {
                    _messageFlow.tryEmit(str(R.string.msg_world_backup_done, java.io.File(path).name))
                } else {
                    _errorFlow.tryEmit(str(R.string.err_world_backup_empty))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_world_backup_fail, e.message))
            }
        }
    }

    /** 外部备份整个服务器（world+核心+配置+插件 → 外部目录，文件名带核心类型） */
    fun backupServerToExternal() {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        // 当前服务器核心类型写入文件名（还原时据此识别核心）
        val coreTag = config.value.installedCores
            .firstOrNull { it.dirName == dirName }?.core?.displayName
        viewModelScope.launch {
            try {
                val path = withContext(Dispatchers.IO) {
                    backupManager.backupServerToExternal(dirName, coreTag, BackupManager.BackupOrigin.Manual)
                }
                if (path != null) {
                    _messageFlow.tryEmit(str(R.string.msg_server_backup_done, java.io.File(path).name))
                } else {
                    _errorFlow.tryEmit(str(R.string.err_server_backup_fail))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_server_backup_fail_msg, e.message))
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

    fun externalBackupInfo(file: java.io.File): BackupManager.ExternalBackupInfo =
        backupManager.parseExternalBackup(file.name, file.lastModified())

    /** 删除外部备份文件 */
    fun deleteExternalBackup(name: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    backupManager.deleteExternalBackup(name)
                }
                if (ok) _messageFlow.tryEmit(str(R.string.msg_backup_deleted, name))
                else _errorFlow.tryEmit(str(R.string.err_backup_delete_fail, name))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_backup_delete_fail, e.message))
            }
        }
    }

    /** 从手机其他位置导入备份 zip 到外部备份目录（SAF 选择） */
    fun importBackupToExternal(uri: android.net.Uri) {        viewModelScope.launch {
            try {
                if (!com.mineserve.mobile.server.ExternalBackupStore.ensure()) {
                    _errorFlow.tryEmit(str(R.string.err_ext_dir_unavailable))
                    return@launch
                }
                val fileName = withContext(Dispatchers.IO) {
                    app.contentResolver.query(
                        uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null
                    )?.use { c ->
                        if (c.moveToFirst()) c.getString(0) else null
                    } ?: "backup_${System.currentTimeMillis()}.zip"
                }
                val safeName = fileName?.takeIf { it.endsWith(".zip") } ?: "backup_${System.currentTimeMillis()}.zip"
                val target = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, safeName)
                withContext(Dispatchers.IO) {
                    app.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
                _messageFlow.tryEmit(str(R.string.msg_backup_imported, safeName))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_backup_import_fail, e.message))
            }
        }
    }

    /** 重命名外部备份文件 */
    fun renameExternalBackup(oldName: String, newName: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.renameExternalBackup(oldName, newName) }
                if (ok) _messageFlow.tryEmit(str(R.string.bk_rename_done, newName))
                else _errorFlow.tryEmit(str(R.string.bk_rename_fail))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.bk_rename_fail))
            }
        }
    }

    /** 校验外部备份 zip 完整性 */
    fun validateExternalBackup(name: String) {
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.validateZip(name) }
                if (ok) _messageFlow.tryEmit(str(R.string.bk_validate_done, name))
                else _errorFlow.tryEmit(str(R.string.bk_validate_fail, name))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.bk_validate_fail, name))
            }
        }
    }

    /** 从外部 zip 还原世界（zip 内 world/ 前缀） */
    fun restoreWorldFromExternal(zipName: String) {        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val dirName = activeDirName() ?: run { _errorFlow.tryEmit(str(R.string.s212)); return }
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) { backupManager.restoreWorldFromExternal(file, dirName) }
                if (ok) _messageFlow.tryEmit(str(R.string.msg_world_restored, zipName))
                else _errorFlow.tryEmit(str(R.string.err_world_restore_fail))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_world_restore_fail_msg, e.message))
            }
        }
    }

    /** 请求还原服务器：解析目标目录名与核心类型，存在同名则发冲突事件（UI 弹框） */
    fun requestRestoreServer(zipName: String) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        val (dirName, coreTag) = backupManager.parseServerZipInfo(zipName)
        val target = java.io.File(repo.termuxRuntime.serversDir, dirName)
        if (target.exists()) {
            _restoreConflict.value = RestoreConflict(zipName, dirName)
        } else {
            performRestoreServer(zipName, dirName, overwrite = false, coreTag = coreTag)
        }
    }

    /** 确认还原服务器（overwrite=true 覆盖同名） */
    fun confirmRestoreServer(overwrite: Boolean) {
        val conflict = _restoreConflict.value ?: return
        _restoreConflict.value = null
        val coreTag = backupManager.parseServerZipInfo(conflict.zipName).second
        performRestoreServer(conflict.zipName, conflict.dirName, overwrite, coreTag)
    }

    fun dismissRestoreConflict() { _restoreConflict.value = null }

    private fun performRestoreServer(zipName: String, dirName: String, overwrite: Boolean, coreTag: String? = null) {
        val file = java.io.File(com.mineserve.mobile.server.ExternalBackupStore.rootDir, zipName)
        viewModelScope.launch {
            try {
                val ok = withContext(Dispatchers.IO) {
                    backupManager.restoreServerFromExternal(file, dirName, overwrite)
                }
                if (ok) {
                    // 注册到已安装核心列表并设为当前选中（否则概览/核心列表不显示）
                    registerRestoredCore(dirName, coreTag)
                    _messageFlow.tryEmit(str(R.string.msg_server_restored, zipName))
                } else {
                    _errorFlow.tryEmit(str(R.string.err_server_restore_fail))
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.err_server_restore_fail_msg, e.message))
            }
        }
    }

    /**
     * 把还原的服务器注册进已安装核心列表（config.installedCores）并设为当前选中。
     * 核心类型从备份文件名识别（coreTag），无法识别时标记 Unknown。
     */
    private fun registerRestoredCore(dirName: String, coreTag: String? = null) {
        val cfg = config.value
        if (cfg.installedCores.any { it.dirName == dirName }) {
            // 已存在：只确保设为当前选中
            if (cfg.activeCoreName != dirName) {
                updateConfig { it.copy(activeCoreName = dirName) }
            }
            return
        }
        // 从 coreTag（核心 displayName）匹配枚举，未知则 Unknown
        val core = com.mineserve.mobile.data.ServerCore.entries
            .firstOrNull { it.displayName == coreTag }
            ?: com.mineserve.mobile.data.ServerCore.Unknown
        updateConfig {
            it.copy(
                installedCores = it.installedCores + com.mineserve.mobile.data.InstalledCore(
                    name = dirName,
                    core = core,
                    version = str(R.string.ver_restored),
                    dirName = dirName
                ),
                activeCoreName = dirName
            )
        }
    }

    // ── 导入服务器（文件夹 / 压缩包 / JAR） ────────────────────────

    private val serverImporter = ServerImporter(app, repo.termuxRuntime)

    private val _isImportingServer = MutableStateFlow(false)
    val isImportingServer: StateFlow<Boolean> = _isImportingServer.asStateFlow()

    private val _importProgress = MutableStateFlow<Float?>(null)
    val importProgress: StateFlow<Float?> = _importProgress.asStateFlow()

    /** 从外部存储文件夹（SAF tree）导入为新的服务器；displayName 为空时自动解析文件夹名 */
    fun importServerFromFolder(uri: android.net.Uri, displayName: String? = null) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromFolder(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                _messageFlow.tryEmit(importServerMessage(imported))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 从外部存储压缩包（zip/tar/tar.gz/tar.xz/tar.bz2/7z）导入为新的服务器；displayName 为空时自动解析 */
    fun importServerFromArchive(uri: android.net.Uri, displayName: String? = null) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromArchive(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                _messageFlow.tryEmit(importServerMessage(imported))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 从单个服务端 JAR 创建标准服务器目录后导入。 */
    fun importServerFromJar(uri: android.net.Uri, displayName: String? = null) {
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            _isImportingServer.value = true
            _importProgress.value = null
            try {
                val imported = withContext(Dispatchers.IO) {
                    serverImporter.importFromJar(uri, displayName) { done, total ->
                        _importProgress.value = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                    }
                }
                registerImportedServer(imported)
                _messageFlow.tryEmit(importServerMessage(imported))
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.import_server_failed, e.message))
            } finally {
                _isImportingServer.value = false
                _importProgress.value = null
            }
        }
    }

    /** 为导入确认对话框预填名称：kind 为 folder / archive / jar */
    suspend fun proposeImportName(kind: String, uri: android.net.Uri): String? = withContext(Dispatchers.IO) {
        runCatching {
            when (kind) {
            "folder" -> serverImporter.proposeFolderName(uri)
            "jar" -> serverImporter.proposeJarName(uri)
            "modpack" -> serverImporter.proposeArchiveName(uri)
            else -> serverImporter.proposeArchiveName(uri)
            }
        }.getOrNull()
    }

    /** 把导入的服务器注册进已安装核心列表并设为当前选中（核心/版本为自动识别结果） */
    private fun registerImportedServer(imported: ServerImporter.ImportedServer) {
        updateConfig {
            val core = imported.core ?: com.mineserve.mobile.data.ServerCore.Unknown
            it.copy(
                installedCores = it.installedCores.filter { c -> c.dirName != imported.dirName } +
                    com.mineserve.mobile.data.InstalledCore(
                        name = imported.displayName,
                        core = core,
                        version = imported.version ?: str(R.string.ver_imported),
                        dirName = imported.dirName,
                        serverFile = imported.serverFile
                    ),
                activeCoreName = imported.displayName
            )
        }
    }

    /** 导入完成提示（含自动识别的核心与版本） */
    private fun importServerMessage(imported: ServerImporter.ImportedServer): String {
        val coreLabel = if (imported.core != null && imported.core != com.mineserve.mobile.data.ServerCore.Unknown) {
            listOf(imported.core.displayName, imported.version).filter { !it.isNullOrBlank() }.joinToString(" ")
        } else null
        val suffix = if (coreLabel != null) {
            str(R.string.import_server_core, coreLabel)
        } else {
            str(R.string.import_server_core_unknown)
        }
        return str(R.string.import_server_done, imported.displayName) + "（" + suffix + "）"
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
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_export))
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
    private val powerNukkitXConfigManager = PowerNukkitXConfigManager(repo.termuxRuntime)

    fun isPowerNukkitXActive(): Boolean = config.value.installedCores
        .firstOrNull { it.name == config.value.activeCoreName }?.core == ServerCore.PowerNukkitX

    fun supportedServerPropertyKeys(): Set<String> =
        if (isPowerNukkitXActive()) powerNukkitXConfigManager.supportedKeys()
        else emptySet()

    private val _serverProperties = MutableStateFlow<Map<String, String>>(emptyMap())
    val serverProperties: StateFlow<Map<String, String>> = _serverProperties.asStateFlow()

    /** 加载 server.properties */
    fun loadServerProperties() {
        if (!isBootstrapped.value) return
        val dirName = activeDirName() ?: return
        viewModelScope.launch {
            try {
                _serverProperties.value = withContext(Dispatchers.IO) {
                    if (isPowerNukkitXActive()) powerNukkitXConfigManager.read(dirName)
                    else propertiesManager.readProperties(dirName)
                }
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
                val ok = withContext(Dispatchers.IO) {
                    if (isPowerNukkitXActive()) {
                        val supported = props.filterKeys { it in powerNukkitXConfigManager.supportedKeys() }
                        val yamlOk = powerNukkitXConfigManager.write(dirName, supported)
                        val serverProps = propertiesManager.readProperties(dirName).toMutableMap()
                        serverProps["server-port"] = props["server-port"] ?: serverProps["server-port"] ?: "19132"
                        yamlOk && propertiesManager.writeProperties(serverProps, dirName)
                    } else propertiesManager.writeProperties(props, dirName)
                }
                if (ok) {
                    _messageFlow.tryEmit(str(R.string.s262))
                    _serverProperties.value = props
                    refreshLanIp()
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
        val time: String,
        val sessionStart: Long? = null,
        val sessionEnd: Long? = null,
        val interrupted: Boolean = false
    )
    data class PlayerActivitySummary(
        val player: String,
        val sessions: Int,
        val totalSeconds: Long,
        val active: Boolean,
        val lastJoinTime: String? = null,
        val lastLeaveTime: String? = null,
        val lastExitReason: String? = null
    )

    private val _playerHistory: MutableStateFlow<List<PlayerHistoryEntry>> by lazy { MutableStateFlow(emptyList()) }
    val playerHistory: StateFlow<List<PlayerHistoryEntry>> by lazy { _playerHistory.asStateFlow() }

    private val legacyPlayerHistoryFile: java.io.File get() = java.io.File(app.filesDir, "player_history.json")
    private fun playerHistoryFile() = java.io.File(app.filesDir, "player-history/${activeDirName() ?: "unselected"}.json")

    private val historyJson: Json by lazy { Json { ignoreUnknownKeys = true } }

    /** 历史记录文件读写互斥，避免多人进出服时并发写导致 JSON 损坏 */
    private val historyMutex: Mutex by lazy { Mutex() }

    /** 启动时异步加载历史记录文件（文件缺失/损坏时从空历史开始） */
    private fun loadPlayerHistory() {
        // Activity data is isolated by active server. Do not merge a previous server's snapshot.
        _playerHistory.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    val f = playerHistoryFile()
                    if (!f.exists() && legacyPlayerHistoryFile.exists()) {
                        f.parentFile?.mkdirs()
                        legacyPlayerHistoryFile.copyTo(f, overwrite = false)
                    }
                    if (f.exists()) {
                        val fileList = historyJson.decodeFromString<List<PlayerHistoryEntry>>(f.readText())
                        val merged = fileList.distinct().sortedByDescending { it.time }
                        _playerHistory.value = merged
                        // A process restart has no trustworthy leave timestamp. Preserve it as interrupted.
                        val interrupted = merged.map { if (it.event == "进服" && it.sessionEnd == null) it.copy(interrupted = true) else it }
                        _playerHistory.value = interrupted
                        f.writeText(historyJson.encodeToString(interrupted))
                    }
                } catch (e: Exception) {
                    // 忽略损坏文件
                }
            }
        }
    }

    /** 追加一条进服/离服事件并异步持久化（保留最近 500 条） */
    private fun recordPlayerEvent(player: String, event: String) {
        val now = System.currentTimeMillis()
        val previous = _playerHistory.value
        val open = previous.firstOrNull { it.player.equals(player, true) && it.event == "进服" && it.sessionEnd == null && !it.interrupted }
        val entry = if (event == "离服" && open != null) {
            PlayerHistoryEntry(player, event, timeNow(), open.sessionStart ?: now, now)
        } else {
            PlayerHistoryEntry(player, event, timeNow(), if (event == "进服") now else null, null)
        }
        _playerHistory.value = listOf(entry) + previous.map {
            if (event == "离服" && it == open) it.copy(sessionEnd = now) else it
        }
        val snapshot = _playerHistory.value
        viewModelScope.launch(Dispatchers.IO) {
            historyMutex.withLock {
                try {
                    val file = playerHistoryFile(); file.parentFile?.mkdirs()
                    file.writeText(historyJson.encodeToString(snapshot))
                } catch (e: Exception) {
                    // 写入失败不阻断运行
                }
            }
        }
    }

    fun playerActivitySummaries(): List<PlayerActivitySummary> {
        val entries = _playerHistory.value
        return entries.groupBy { it.player }.map { (player, rows) ->
            val seconds = rows.filter { it.event == "离服" && !it.interrupted }.sumOf { ((it.sessionEnd ?: 0L) - (it.sessionStart ?: 0L)).coerceAtLeast(0L) / 1000 }
            val lastJoin = rows.filter { it.event == "进服" }.maxByOrNull { it.time }
            val lastLeave = rows.filter { it.event == "离服" }.maxByOrNull { it.time }
            PlayerActivitySummary(
                player = player,
                sessions = rows.count { it.event == "离服" },
                totalSeconds = seconds,
                active = rows.any { it.event == "进服" && it.sessionEnd == null },
                lastJoinTime = lastJoin?.time,
                lastLeaveTime = lastLeave?.time,
                lastExitReason = lastLeave?.let {
                    if (it.interrupted) "服务停止或应用重启导致中断" else "日志未提供退出原因"
                }
            )
        }.sortedByDescending { it.totalSeconds }
    }

    fun interruptPlayerSessions() {
        val open = _playerHistory.value.filter { it.event == "进服" && it.sessionEnd == null }
        if (open.isEmpty()) return
        val replacements = open.associateWith { it.copy(interrupted = true) }
        _playerHistory.value = _playerHistory.value.map { replacements[it] ?: it }
        val snapshot = _playerHistory.value
        viewModelScope.launch(Dispatchers.IO) { historyMutex.withLock {
            val file = playerHistoryFile(); file.parentFile?.mkdirs(); file.writeText(historyJson.encodeToString(snapshot))
        } }
    }

    fun clearPlayerHistory() {
        _playerHistory.value = emptyList()
        viewModelScope.launch(Dispatchers.IO) { historyMutex.withLock { playerHistoryFile().delete() } }
    }

    fun exportPlayerHistory(uri: android.net.Uri) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) {
                app.contentResolver.openOutputStream(uri)?.bufferedWriter()?.use { out ->
                    out.appendLine("player,event,time,session_start,session_end,duration_seconds,interrupted")
                    _playerHistory.value.forEach { row ->
                        fun csv(v: String) = "\"${v.replace("\"", "\"\"")}\""
                        val duration = if (row.sessionStart != null && row.sessionEnd != null && !row.interrupted) ((row.sessionEnd - row.sessionStart) / 1000).toString() else ""
                        out.appendLine(listOf(csv(row.player), csv(row.event), csv(row.time), row.sessionStart ?: "", row.sessionEnd ?: "", duration, row.interrupted).joinToString(","))
                    }
                } ?: error(str(R.string.err_open_export))
            }
            _messageFlow.tryEmit(str(R.string.msg_history_exported))
        } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.err_export_fail)) }
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

    data class TextEditorFile(val path: String, val name: String, val content: String)
    private val _textEditorFile = MutableStateFlow<TextEditorFile?>(null)
    val textEditorFile: StateFlow<TextEditorFile?> = _textEditorFile.asStateFlow()

    fun openTextFile(file: java.io.File) = viewModelScope.launch {
        try {
            val content = withContext(Dispatchers.IO) { SafeTextFile.read(fileManagerRoot, file) }
            _textEditorFile.value = TextEditorFile(content.file.absolutePath, content.file.name, content.text)
        } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.err_open_text)) }
    }
    fun closeTextFile() { _textEditorFile.value = null }
    fun saveTextFile(path: String, text: String) = viewModelScope.launch {
        try {
            withContext(Dispatchers.IO) { SafeTextFile.write(fileManagerRoot, java.io.File(path), text) }
            _textEditorFile.value = TextEditorFile(path, java.io.File(path).name, text)
            _messageFlow.tryEmit(str(R.string.msg_file_saved))
            refreshFiles()
        } catch (e: Exception) { _errorFlow.tryEmit(e.message ?: str(R.string.err_save_text)) }
    }
    fun canEditTextFile(file: java.io.File) = file.isFile && SafeTextFile.isSupported(file)

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
                    val props = if (isPowerNukkitXActive()) powerNukkitXConfigManager.read(dirName)
                    else propertiesManager.readProperties(dirName)
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
        afterCmd(sent, str(R.string.msg_op_cancelled, name))
    }

    fun whitelistAdd(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistAdd(name)
        afterCmd(sent, str(R.string.msg_whitelist_added, name))
    }

    fun whitelistRemove(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.whitelistRemove(name)
        afterCmd(sent, str(R.string.msg_whitelist_removed, name))
    }

    /** 切换白名单开关 */
    fun toggleWhitelist(enabled: Boolean) {
        val sent = if (enabled) playerManager.whitelistOn() else playerManager.whitelistOff()
        if (sent) {
            _whitelistEnabled.value = enabled
            _messageFlow.tryEmit(if (enabled) str(R.string.msg_whitelist_toggled_on) else str(R.string.msg_whitelist_toggled_off))
        } else {
            _errorFlow.tryEmit(str(R.string.s274))
        }
    }

    fun kickPlayer(name: String, reason: String = "") {
        if (name.isBlank()) return
        val sent = playerManager.kickPlayer(name, reason)
        afterCmd(sent, str(R.string.msg_kicked, name), refresh = false)
    }

    fun banPlayer(name: String, reason: String = "Banned by admin") {
        if (name.isBlank()) return
        val sent = playerManager.banPlayer(name, reason)
        afterCmd(sent, str(R.string.msg_banned, name))
    }

    /** 限时封禁 */
    fun tempBanPlayer(name: String, duration: String, reason: String = "") {
        if (name.isBlank() || duration.isBlank()) return
        val sent = playerManager.tempBanPlayer(name, duration, reason)
        afterCmd(sent, str(R.string.msg_banned_temp, name, duration))
    }

    fun pardonPlayer(name: String) {
        if (name.isBlank()) return
        val sent = playerManager.pardonPlayer(name)
        afterCmd(sent, str(R.string.msg_pardoned, name))
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
        afterCmd(sent, str(R.string.msg_xp_given, name, amount), refresh = false)
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
                _crashReports.value = withContext(Dispatchers.IO) {
                    val native = config.value.installedCores.flatMap { core ->
                        crashReportManager.listNativeCrashReports(core.dirName)
                    }
                    (crashReportManager.listCrashReports() + native).sortedByDescending { it.createdTime }
                }
            } catch (e: Exception) {
                _errorFlow.tryEmit(str(R.string.s287, e.message))
            }
        }
    }

    /** 当前查看的崩溃报告全文（供 UI 展示） */
    private val _currentCrashContent = MutableStateFlow<String?>(null)
    val currentCrashContent: StateFlow<String?> = _currentCrashContent.asStateFlow()
    private val _currentCrashAnalysis = MutableStateFlow<CrashReportAnalyzer.Analysis?>(null)
    val currentCrashAnalysis: StateFlow<CrashReportAnalyzer.Analysis?> = _currentCrashAnalysis.asStateFlow()

    /** Shows a structured report when validation fails before a process can create its own crash file. */
    private fun showStartupFailureReport(error: Throwable) {
        val text = buildString {
            appendLine(str(R.string.startup_report_title))
            appendLine(str(R.string.startup_report_time, timeNow()))
            appendLine(str(R.string.startup_report_exception, error::class.java.name, error.message ?: str(R.string.startup_report_no_detail)))
            appendLine()
            append(error.stackTraceToString())
        }
        _currentCrashContent.value = text
        _currentCrashAnalysis.value = CrashReportAnalyzer.analyze(text)
    }

    private fun showStartupFailureReport(failure: McServerController.StartupFailure) {
        viewModelScope.launch(Dispatchers.IO) {
            val report = failure.reportPath?.let { runCatching { File(it).readText() }.getOrNull() }
            val text = report ?: buildString {
                appendLine(str(R.string.startup_report_title))
                appendLine(str(R.string.startup_report_time, timeNow()))
                appendLine(str(R.string.startup_report_exitcode, failure.code))
                appendLine(str(R.string.startup_report_reason, failure.detail))
                appendLine(str(R.string.startup_report_hint))
            }
            withContext(kotlinx.coroutines.Dispatchers.Main) {
                _currentCrashContent.value = text
                _currentCrashAnalysis.value = CrashReportAnalyzer.analyze(text)
            }
        }
    }

    /** 读取崩溃报告全文 */
    fun readCrashReport(fileName: String) {
        viewModelScope.launch {
            try {
                _currentCrashContent.value = withContext(Dispatchers.IO) { java.io.File(_crashReports.value.firstOrNull { it.fileName == fileName }?.path ?: "").takeIf { it.isFile }?.readText() }
                _currentCrashAnalysis.value = _currentCrashContent.value?.let(CrashReportAnalyzer::analyze)
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
        _currentCrashAnalysis.value = null
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
    /** 读取应用自身崩溃日志（全局 uncaught handler 写入），无则返回 null */
    fun appCrashLog(): String? {
        val dir = java.io.File(app.filesDir, "home")
        val candidates = listOf(
            java.io.File(dir, "crash_log.txt"),
            java.io.File(dir, "crash_log_read.txt")
        )
        val file = candidates.firstOrNull { it.exists() && it.length() > 0 } ?: return null
        return try {
            val text = file.readText()
            if (text.length > 4000) text.takeLast(4000) else text
        } catch (_: Exception) {
            null
        }
    }

    /** 清空应用崩溃日志文件 */
    fun clearAppCrashLog() {
        try {
            val dir = java.io.File(app.filesDir, "home")
            java.io.File(dir, "crash_log.txt").delete()
            java.io.File(dir, "crash_log_read.txt").delete()
        } catch (_: Exception) {
        }
    }

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

    fun setAutoBackupInterval(min: Int) = updateConfig {
        it.copy(autoBackupIntervalMin = if (min <= 0) 0 else min.coerceIn(5, 10080))
    }
    fun setAutoBackupType(type: com.mineserve.mobile.data.AutoBackupType) =
        updateConfig { it.copy(autoBackupType = type) }
    fun setMaxSnapshots(max: Int) = updateConfig { it.copy(maxSnapshots = max.coerceIn(1, 100)) }

    // ── 服务端核心下载相关 ──────────────────────────────────────────

    /** 可用版本列表（从 API 获取，供 DownloadScreen 选择） */
    private val _availableVersions = MutableStateFlow<List<String>>(emptyList())
    val availableVersions: StateFlow<List<String>> = _availableVersions.asStateFlow()
    private var versionsLoadToken = 0L
    private val _versionHints = MutableStateFlow<Map<String, String?>>(emptyMap())
    val versionHints: StateFlow<Map<String, String?>> = _versionHints.asStateFlow()

    fun supportedGameVersion(coreVersion: String): String? = _versionHints.value[coreVersion]

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
        val token = ++versionsLoadToken
        _availableVersions.value = emptyList()
        _versionHints.value = emptyMap()
        _isLoadingVersions.value = true
        viewModelScope.launch {
            try {
                val options = controller.fetchVersionOptions(core)
                if (token != versionsLoadToken) return@launch
                val versions = options.map { it.version }
                _availableVersions.value = versions
                _versionHints.value = options.associate { it.version to it.supportedGameVersion }
                if (core == ServerCore.PowerNukkitX && config.value.selectedCore == core &&
                    config.value.mcVersion == "latest" && versions.isNotEmpty()) {
                    updateConfig { it.copy(mcVersion = versions.first()) }
                }
            } catch (e: Exception) {
                if (token != versionsLoadToken) return@launch
                _errorFlow.tryEmit(str(R.string.s294, e.message))
                _availableVersions.value = emptyList()
                _versionHints.value = emptyMap()
            } finally {
                if (token == versionsLoadToken) _isLoadingVersions.value = false
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
        updateConfig {
            val core = it.installedCores.firstOrNull { installed -> installed.name == name }
            it.copy(
                activeCoreName = name,
                selectedJavaVersion = it.selectedJavaVersion
            )
        }
    }

    /** 删除一个已安装的核心（按名称） */
    /** 校验已安装核心；未知核心只检查原文件，绝不自动下载或覆盖。 */
    fun verifyOrRepairCore(dirName: String) {
        val cfg = config.value
        val core = cfg.installedCores.firstOrNull { it.dirName == dirName } ?: return
        if (!isBootstrapped.value) { _errorFlow.tryEmit(str(R.string.s192)); return }
        viewModelScope.launch {
            try {
                val serverDir = repo.termuxRuntime.serverDirFor(dirName)
                val entryName = core.serverFile?.trim()?.substringAfterLast('/')?.substringAfterLast('\\')?.takeIf { it.isNotBlank() }
                if (entryName == null) {
                    _errorFlow.tryEmit(str(R.string.err_core_unknown_no_repair, "未指定启动文件"))
                    return@launch
                }
                val jar = java.io.File(serverDir, entryName)
                val valid = withContext(Dispatchers.IO) {
                    if (!jar.isFile) false
                    else if (core.core == com.mineserve.mobile.data.ServerCore.Unknown) true
                    else runCatching {
                        java.util.jar.JarFile(jar).use { jf -> jf.manifest != null }
                    }.getOrDefault(false)
                }
                if (valid) {
                    _messageFlow.tryEmit(str(R.string.msg_core_verify_ok, core.name, formatCoreSize(jar.length())))
                    return@launch
                }
                if (core.core == com.mineserve.mobile.data.ServerCore.Unknown) {
                    _errorFlow.tryEmit(str(R.string.err_core_unknown_no_repair, entryName))
                    return@launch
                }
                _messageFlow.tryEmit(str(R.string.msg_core_repairing, core.name))
                _isDownloadingCore.value = true
                try {
                    val downloadConfig = cfg.copy(selectedCore = core.core, mcVersion = core.version)
                    withContext(Dispatchers.IO) {
                        controller.downloadCoreTo(jar.absolutePath, downloadConfig, dirName) { _, _, _ -> }
                    }
                    _messageFlow.tryEmit(str(R.string.msg_core_repaired, core.name))
                } finally {
                    _isDownloadingCore.value = false
                }
            } catch (e: Exception) {
                _isDownloadingCore.value = false
                _errorFlow.tryEmit(str(R.string.err_core_repair_fail, e.message))
            }
        }
    }

    private fun formatCoreSize(bytes: Long): String =
        if (bytes >= 1024 * 1024) "%.1f MB".format(bytes / 1024.0 / 1024.0)
        else if (bytes >= 1024) "%.1f KB".format(bytes / 1024.0)
        else "$bytes B"

    fun deleteCore(name: String) {
        viewModelScope.launch {
            try {
                val core = config.value.installedCores.find { it.name == name }
                    ?: throw RuntimeException(str(R.string.msg_core_not_found, name))
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
        val isEditable: Boolean,
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
                        // SimpleDateFormat 创建较贵，且非线程安全；在单线程的 map 内复用一个实例，
                        // 避免大目录（成百上千文件）下每个文件都 new 一次带来的 GC 与构造开销。
                        val formatter = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US)
                        path.listFiles()?.sortedWith(
                            compareByDescending<java.io.File> { it.isDirectory }
                                .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name }
                        )?.map { f ->
                            FileEntry(
                                name = f.name,
                                path = f.absolutePath,
                                isDirectory = f.isDirectory,
                                isEditable = f.isFile && SafeTextFile.isSupported(f),
                                sizeBytes = if (f.isFile) f.length() else 0L,
                                sizeText = if (f.isFile) formatBytes(f.length()) else "",
                                lastModified = f.lastModified(),
                                modifiedText = formatter.format(java.util.Date(f.lastModified()))
                            )
                        } ?: emptyList()
                    }
                }a.util.Date(f.lastModified()))
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
                    val input = app.contentResolver.openInputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_file))
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
                    val output = app.contentResolver.openOutputStream(uri)
                        ?: throw RuntimeException(str(R.string.err_open_export))
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
        viewModelScope.launch {
            config.map { it.activeCoreName }.distinctUntilChanged().collect { loadPlayerHistory() }
        }
        viewModelScope.launch {
            var wasRunning = false
            serverState.collect { state ->
                if (wasRunning && !state.isRunning) interruptPlayerSessions()
                wasRunning = state.isRunning
            }
        }
        startServerResourceCollection()
        // 订阅 consoleFlow，使用环形缓冲 + 批量刷新，避免每行 O(n) 拷贝。
        viewModelScope.launch(Dispatchers.Default) {
            controller.startupFailures.collect { showStartupFailureReport(it) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            repo.termuxRuntime.consoleFlow.collect { line ->
                consoleBuffer.add(line)
                pendingConsoleBuffer.add(line)
                parseConsoleLine(line)
            }
        }
        // Process only newly received lines. Older terminal rows keep their identity across UI flushes.
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                // 无 UI 订阅时（后台/无页面）长睡，控制台可见时以帧友好的频率刷新。
                if (_consoleLines.subscriptionCount.value <= 0 &&
                    _consolePreviewLines.subscriptionCount.value <= 0 &&
                    _terminalConsoleLines.subscriptionCount.value <= 0
                ) {
                    delay(2000)
                    continue
                }
                delay(200)
                val batch = pendingConsoleBuffer.snapshotAndClear()
                if (batch.isNotEmpty()) {
                    consoleGeneration++
                }
                if (terminalDisplayDirty) {
                    terminalConsoleBuffer.replace(
                        consoleBuffer.snapshot().map { TerminalLogProcessor.process(it, _logTranslationEnabled.value) }
                    )
                    terminalDisplayDirty = false
                    terminalGeneration++
                } else if (batch.isNotEmpty()) {
                    batch.forEach { terminalConsoleBuffer.add(TerminalLogProcessor.process(it, _logTranslationEnabled.value)) }
                    terminalGeneration++
                }
                if (_consoleLines.subscriptionCount.value > 0 && consoleUiGeneration != consoleGeneration) {
                    _consoleLines.value = consoleBuffer.snapshot()
                    consoleUiGeneration = consoleGeneration
                }
                if (_terminalConsoleLines.subscriptionCount.value > 0 && terminalUiGeneration != terminalGeneration) {
                    _terminalConsoleLines.value = terminalConsoleBuffer.snapshot()
                    terminalUiGeneration = terminalGeneration
                }
                val now = System.currentTimeMillis()
                if (_consolePreviewLines.subscriptionCount.value > 0 &&
                    previewUiGeneration != consoleGeneration &&
                    now - lastPreviewPublishedAtMs >= CONSOLE_PREVIEW_FLUSH_MS
                ) {
                    _consolePreviewLines.value = consoleBuffer.last(8)
                    previewUiGeneration = consoleGeneration
                    lastPreviewPublishedAtMs = now
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(LOG_FLUSH_MS)
                val batch = legacyTermuxBuffer.snapshotAndClear()
                if (batch.isNotEmpty()) {
                    val current = _termuxLines.value
                    _termuxLines.value = (current + batch).takeLast(MAX_LOG_LINES).toImmutableList()
                }
            }
        }
        viewModelScope.launch(Dispatchers.Default) {
            while (true) {
                delay(LOG_FLUSH_MS)
                val batch = synchronized(terminalOutputBuffers) {
                    terminalOutputBuffers.mapValues { (_, buffer) -> buffer.snapshotAndClear() }
                        .filterValues { it.isNotEmpty() }
                }
                batch.forEach { (id, lines) ->
                    updateTerminalSession(id) { session ->
                        val additions = lines.map { TerminalLogProcessor.process(it, false) }
                        session.copy(lines = (session.lines + additions).takeLast(MAX_LOG_LINES).toImmutableList())
                    }
                }
            }
        }
    }

    // ── 后台保活（开机自启 / 周期保活） ─────────────────────────────

    /** 本地化字符串（ViewModel 中获取资源） */
    private fun str(id: Int, vararg args: Any?): String =
        app.getString(id, *args)

    private fun metaPrefs() =
        app.getSharedPreferences(BootReceiver.META_PREFS, android.content.Context.MODE_PRIVATE)

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

    /** 拉起前台保活服务 */
    fun startKeepAliveService() {
        try {
            val intent = android.content.Intent(app, McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_START }
            app.startForegroundService(intent)
        } catch (e: Exception) {
            _errorFlow.tryEmit(str(R.string.s314, e.message))
        }
    }

    /** 停止前台保活服务 */
    fun stopKeepAliveService() {
        try {
            val intent = android.content.Intent(app, McForegroundService::class.java)
                .apply { action = McForegroundService.ACTION_STOP }
            app.startService(intent)
        } catch (e: Exception) {
            // 忽略
        }
    }

    private fun scheduleKeepAlive() {
        val request = androidx.work.PeriodicWorkRequestBuilder<KeepAliveWorker>(15, java.util.concurrent.TimeUnit.MINUTES).build()
        androidx.work.WorkManager.getInstance(app).enqueueUniquePeriodicWork(
            "keep_alive", androidx.work.ExistingPeriodicWorkPolicy.UPDATE, request
        )
    }

    private fun cancelKeepAlive() {
        androidx.work.WorkManager.getInstance(app).cancelUniqueWork("keep_alive")
    }
}
