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

    override fun onCreate() {
        super.onCreate()
        instance = this
        termuxRuntime = TermuxRuntime(this)
        repository = ServerRepository(this, termuxRuntime)
        createNotificationChannel()
        WorkManager.initialize(this, workManagerConfiguration)

        // 异步初始化 Termux 环境，失败不崩溃只记录日志
        GlobalScope.launch(Dispatchers.IO) {
            val ok = try {
                termuxRuntime.bootstrap { phase, progress ->
                    android.util.Log.i("McApplication", "bootstrap: ${phase.label} $progress%")
                    repository.updateServerState { it.copy(currentProgress = progress) }
                }
            } catch (t: Throwable) {
                android.util.Log.e("McApplication", "bootstrap crashed", t)
                false
            }
            if (ok) {
                _isBootstrapped.value = true
                android.util.Log.i("McApplication", "bootstrap completed")
            } else {
                android.util.Log.e("McApplication", "bootstrap failed")
            }
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
