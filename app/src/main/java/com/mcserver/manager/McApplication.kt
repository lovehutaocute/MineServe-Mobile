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

/**
 * 应用入口：负责初始化
 * - 通知通道（Android 8+ 必须）
 * - WorkManager（手动初始化，便于注入 Repository）
 * - 全局单例 TermuxRuntime 与 ServerRepository
 */
class McApplication : Application(), Configuration.Provider {

    lateinit var termuxRuntime: TermuxRuntime
        private set
    lateinit var repository: ServerRepository
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        termuxRuntime = TermuxRuntime(this)
        repository = ServerRepository(this, termuxRuntime)
        createNotificationChannel()
        WorkManager.initialize(this, workManagerConfiguration)
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
