package com.mineserve.mobile

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.mineserve.mobile.data.ServerRepository
import com.mineserve.mobile.data.UsageTracker
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 应用入口：负责初始化
 * - 通知通道（Android 8+ 必须）
 * - WorkManager（手动初始化，便于注入 Repository）
 * - 全局单例 TermuxRuntime 与 ServerRepository
 * - 异步初始化 Termux 环境（bootstrap），UI 可通过 isBootstrapped 观察进度
 */
class McApplication : Application(), Configuration.Provider {

    lateinit var termuxRuntime: TermuxRuntime
        private set
    lateinit var repository: ServerRepository
        private set

    /** Termux 环境初始化完成标志，UI 层可观察 */
    private val _isBootstrapped = MutableStateFlow(false)
    val isBootstrapped: StateFlow<Boolean> = _isBootstrapped.asStateFlow()

    /** Termux 环境初始化错误信息，UI 层可观察 */
    private val _bootstrapError = MutableStateFlow<String?>(null)
    val bootstrapError: StateFlow<String?> = _bootstrapError.asStateFlow()

    /** Bootstrap 下载速度（bytes/s） */
    private val _bootstrapSpeed = MutableStateFlow(0L)
    val bootstrapSpeed: StateFlow<Long> = _bootstrapSpeed.asStateFlow()

    /** 当前 bootstrap 使用的镜像源索引（-1 表示未在下载中） */
    val currentMirrorIndex: StateFlow<Int> get() = termuxRuntime.installer.currentMirrorIndex

    /** 镜像源名称列表 */
    val mirrorSources: List<String> get() = termuxRuntime.installer.mirrorSources

    /** 请求停止当前镜像源下载并切换到下一个 */
    fun switchBootstrapMirror() {
        termuxRuntime.installer.requestStopAndSwitch()
    }

    /** 上次崩溃日志路径（APP 内文件管理器可访问） */
    val crashLogFile: java.io.File
        get() = java.io.File(filesDir, "home/crash_log.txt")

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 全局崩溃捕获
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashLog = buildString {
                appendLine("========================================")
                appendLine(getString(R.string.s3, java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())))
                appendLine(getString(R.string.s4, thread.name))
                appendLine(getString(R.string.s5, throwable.javaClass.name, throwable.message))
                for (el in throwable.stackTrace.take(30)) {
                    appendLine("  at $el")
                }
                var cause = throwable.cause
                while (cause != null) {
                    appendLine("Caused by: ${cause.javaClass.name}: ${cause.message}")
                    for (el in cause.stackTrace.take(10)) {
                        appendLine("  at $el")
                    }
                    cause = cause.cause
                }
                appendLine("========================================")
            }
            try {
                java.io.File(filesDir, "home").mkdirs()
                java.io.File(filesDir, "home/crash_log.txt").appendText("$crashLog\n")
            } catch (_: Exception) {}
            defaultHandler?.uncaughtException(thread, throwable)
        }

        termuxRuntime = TermuxRuntime(this)

        // 启动时推送上次崩溃日志到控制台（IO 线程，不阻塞主线程启动）
        if (crashLogFile.exists() && crashLogFile.length() > 0) {
            GlobalScope.launch(Dispatchers.IO) {
                try {
                    val lastCrash = crashLogFile.readText().takeLast(3000)
                    termuxRuntime.emitLog("[crash] 上次崩溃日志:\n$lastCrash")
                    crashLogFile.renameTo(java.io.File(filesDir, "home/crash_log_read.txt"))
                } catch (_: Exception) {}
            }
        }
        repository = ServerRepository(this, termuxRuntime)
        createNotificationChannel()
        WorkManager.initialize(this, workManagerConfiguration)

        // 累计使用人数统计：设备标识上报（每天一次，失败静默）
        UsageTracker.maybePulse(this)

        // 设置 bootstrap 日志回调，推送到 consoleFlow
        termuxRuntime.setBootstrapLogCallback { msg ->
            termuxRuntime.emitLog("[bootstrap] $msg")
        }

        // 设置 bootstrap 速度回调
        termuxRuntime.installer.onSpeed = { _, speedBps ->
            _bootstrapSpeed.value = speedBps
        }

        // 异步初始化 Termux 环境
        startBootstrap()
    }

    /** 启动/重试 bootstrap 初始化 */
    fun startBootstrap() {
        if (_isBootstrapped.value) return
        GlobalScope.launch(Dispatchers.IO) {
            _bootstrapError.value = null
            val ok = try {
                termuxRuntime.bootstrap { phase, progress ->
                    android.util.Log.i("McApplication", "bootstrap: ${getString(phase.labelRes)} $progress%")
                    repository.updateServerState { it.copy(currentProgress = progress) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("McApplication", "bootstrap crashed", t)
                _bootstrapError.value = t.message ?: getString(R.string.s7)
                false
            }
            if (ok) {
                _isBootstrapped.value = true
                android.util.Log.i("McApplication", "bootstrap completed")
                repository.updateServerState { it.copy(currentProgress = 100) }
            } else {
                android.util.Log.e("McApplication", "bootstrap failed")
                if (_bootstrapError.value == null) {
                    _bootstrapError.value = getString(R.string.s8)
                }
            }
        }
    }

    /**
     * 删除 Termux 运行环境并重置状态。
     * 删除后自动重新开始初始化流程。
     */
    fun deleteBootstrap() {
        GlobalScope.launch(Dispatchers.IO) {
            _isBootstrapped.value = false
            _bootstrapError.value = null
            repository.updateServerState {
                it.copy(
                    currentProgress = 0,
                    installSteps = com.mineserve.mobile.data.InstallStep.values().map { step ->
                        com.mineserve.mobile.data.StepState(step, com.mineserve.mobile.data.StepStatus.Wait)
                    }
                )
            }
            termuxRuntime.deleteBootstrap()
            // 重新开始初始化
            startBootstrap()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            getString(R.string.notif_channel_id),
            getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_LOW // 低优先级，无声不弹
        ).apply {
            description = getString(R.string.notif_channel_desc)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    companion object {
        @Volatile private var instance: McApplication? = null
        fun get(): McApplication = instance!!
        fun get(context: Context): McApplication =
            context.applicationContext as McApplication
    }
}
