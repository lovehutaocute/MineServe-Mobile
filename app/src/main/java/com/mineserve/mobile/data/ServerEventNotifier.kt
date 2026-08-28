package com.mineserve.mobile.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.core.app.NotificationCompat
import com.mineserve.mobile.R

/**
 * 服务器事件系统通知：App 在后台/锁屏时也能感知启动完成、玩家上下线与异常退出。
 * 等级：0=关闭 1=仅重要（默认） 2=全部（含玩家上下线）。
 */
object ServerEventNotifier {
    const val PREFS = "notify_prefs"
    const val KEY_LEVEL = "level"
    const val CHANNEL_ID = "mc_server_events"

    const val ID_STARTED = 2101
    const val ID_PLAYER = 2102
    const val ID_CRASH = 2103
    const val ID_STOPPED = 2104

    fun level(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_LEVEL, 1)

    fun setLevel(context: Context, value: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY_LEVEL, value.coerceIn(0, 2)).apply()
    }

    /** 创建事件通知渠道（幂等）。 */
    fun createChannel(context: Context) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.O) return
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.notif_events_channel_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = context.getString(R.string.notif_events_channel_desc)
                setShowBadge(false)
            }
        )
    }

    /**
     * 按当前等级发送通知；requiredLevel 表示事件所需的档位（1=重要，2=全部）。
     * 玩家事件使用固定 id 覆盖更新，避免快速加入/离开时刷出多张通知。
     */
    fun notify(context: Context, title: String, text: String, id: Int, requiredLevel: Int) {
        if (level(context) < requiredLevel) return
        try {
            createChannel(context)
            val n = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_launcher_foreground)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setAutoCancel(true)
                .build()
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
                .notify(id, n)
        } catch (_: Exception) {
            // 通知失败不影响服务器运行
        }
    }
}
