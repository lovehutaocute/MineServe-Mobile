package com.mcserver.manager

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.work.Configuration
import androidx.work.WorkManager
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.runtime.TermuxRuntime
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

    override fun onCreate() {
        super.onCreate()
        instance = this

        // 全局崩溃捕获：写入文件 + 推送到日志流
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            val crashLog = buildString {
                appendLine("========================================")
                appendLine("崩溃时间: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())}")
                appendLine("线程: ${thread.name}")
                appendLine("异常: ${throwable.javaClass.name}: ${throwable.message}")
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
                java.io.File(filesDir, "crash_log.txt").appendText("$crashLog\n")
                // 推送到 UI 日志
                termuxRuntime.emitLog("[crash] $crashLog")
            } catch (_: Exception) {}
            // 交给系统默认处理（弹窗/退出）
            defaultHandler?.uncaughtException(thread, throwable)
        }

        termuxRuntime = TermuxRuntime(this)
        repository = ServerRepository(this, termuxRuntime)
        createNotificationChannel()
        WorkManager.initialize(this, workManagerConfiguration)

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
                    android.util.Log.i("McApplication", "bootstrap: ${phase.label} $progress%")
                    repository.updateServerState { it.copy(currentProgress = progress) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("McApplication", "bootstrap crashed", t)
                _bootstrapError.value = t.message ?: "未知错误"
                false
            }
            if (ok) {
                _isBootstrapped.value = true
                android.util.Log.i("McApplication", "bootstrap completed")
                repository.updateServerState { it.copy(currentProgress = 100) }
            } else {
                android.util.Log.e("McApplication", "bootstrap failed")
                if (_bootstrapError.value == null) {
                    _bootstrapError.value = "初始化失败，请检查网络后重试"
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
                    installSteps = com.mcserver.manager.data.InstallStep.values().map { step ->
                        com.mcserver.manager.data.StepState(step, com.mcserver.manager.data.StepStatus.Wait)
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
