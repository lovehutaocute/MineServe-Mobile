package com.mineserve.mobile.data

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.widget.RemoteViews
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.widget.StatusWidgetLarge
import com.mineserve.mobile.widget.StatusWidgetMedium
import com.mineserve.mobile.widget.StatusWidgetSmall
import com.mineserve.mobile.widget.WidgetActionReceiver

/**
 * 桌面组件状态刷新：从 serverState 构建小/中/大三种 RemoteViews 并推送。
 * 刷新入口统一走 [refresh]（300ms 防抖合并），状态变化的 diff 判定在
 * ServerRepository.updateServerState 中完成。
 */
object WidgetUpdater {
    private const val ACTION_WIDGET_START = "com.mineserve.mobile.widget.START_SERVER"
    private const val ACTION_WIDGET_STOP = "com.mineserve.mobile.widget.STOP_SERVER"
    private const val DEBOUNCE_MS = 300L

    private val handler = Handler(Looper.getMainLooper())
    private var pending = false
    private var lastPushAtMs = 0L

    /** 状态变化触发的刷新（防抖合并高频变化）。 */
    fun refresh(context: Context) {
        val appContext = context.applicationContext
        if (pending) return
        val since = System.currentTimeMillis() - lastPushAtMs
        if (since >= DEBOUNCE_MS) {
            pending = true
            handler.post { pushNow(appContext) }
            return
        }
        pending = true
        handler.postDelayed({ pushNow(appContext) }, DEBOUNCE_MS - since)
    }

    private fun pushNow(context: Context) {
        pending = false
        lastPushAtMs = System.currentTimeMillis()
        try {
            val state = McApplication.get(context).repository.serverState.value
            val manager = AppWidgetManager.getInstance(context)
            updateProvider(context, manager, StatusWidgetSmall::class.java) { buildSmall(context, state) }
            updateProvider(context, manager, StatusWidgetMedium::class.java) { buildMedium(context, state) }
            updateProvider(context, manager, StatusWidgetLarge::class.java) { buildLarge(context, state) }
        } catch (_: Exception) {
            // Widget 刷新失败不影响服务器运行
        }
    }

    private fun updateProvider(
        context: Context,
        manager: AppWidgetManager,
        provider: Class<*>,
        views: () -> RemoteViews
    ) {
        val ids = manager.getAppWidgetIds(ComponentName(context, provider))
        if (ids == null || ids.isEmpty()) return
        manager.updateAppWidget(ids, views())
    }

    private fun buildSmall(context: Context, state: ServerState): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_small).apply {
            bindStatus(context, this, state)
        }

    private fun buildMedium(context: Context, state: ServerState): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_medium).apply {
            bindStatus(context, this, state)
            setTextViewText(R.id.widget_tps_text, tpsText(context, state))
            setTextViewText(R.id.widget_memory_text, memoryText(state))
            bindActionButton(context, this, state)
        }

    private fun buildLarge(context: Context, state: ServerState): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_large).apply {
            bindStatus(context, this, state)
            setTextViewText(R.id.widget_tps_text, tpsText(context, state))
            setTextViewText(R.id.widget_memory_text, memoryText(state))
            setTextViewText(R.id.widget_cpu_text, cpuText(state))
            setTextViewText(R.id.widget_uptime_text, uptimeText(state))
            bindActionButton(context, this, state)
        }

    private fun bindStatus(context: Context, views: RemoteViews, state: ServerState) {
        val starting = state.isRunning && state.runningSinceMs == 0L
        val statusText = when {
            starting -> context.getString(R.string.widget_status_starting)
            state.isRunning -> context.getString(R.string.widget_status_running)
            state.startupPhase == StartupPhase.Failed -> context.getString(R.string.widget_status_failed)
            else -> context.getString(R.string.widget_status_stopped)
        }
        views.setTextViewText(R.id.widget_status_text, statusText)
        views.setTextViewText(R.id.widget_players_text, playersText(state))
        // 状态点：运行=薄荷绿，启动中=琥珀，停止/失败=珊瑚红
        val dotColor = when {
            starting -> 0xFFFFA726.toInt()
            state.isRunning -> 0xFF2FBF87.toInt()
            else -> 0xFFF97066.toInt()
        }
        views.setInt(R.id.widget_status_dot, "setColorFilter", dotColor)
        views.setOnClickPendingIntent(R.id.widget_root, openAppIntent(context))
    }

    private fun bindActionButton(context: Context, views: RemoteViews, state: ServerState) {
        val running = state.isRunning
        views.setTextViewText(
            R.id.widget_action_btn,
            context.getString(if (running) R.string.widget_btn_stop else R.string.widget_btn_start)
        )
        views.setOnClickPendingIntent(
            R.id.widget_action_btn,
            widgetActionIntent(context, if (running) ACTION_WIDGET_STOP else ACTION_WIDGET_START)
        )
    }

    private fun playersText(state: ServerState): String =
        if (state.isRunning) "${state.onlinePlayers}/${state.maxPlayers}" else "--"

    private fun tpsText(context: Context, state: ServerState): String =
        context.getString(R.string.widget_tps_label) + " " +
            (if (state.isRunning && state.tps > 0.0) String.format("%.1f", state.tps) else "--")

    private fun memoryText(state: ServerState): String =
        if (state.isRunning && state.usedMemoryMb > 0) {
            val mb = state.usedMemoryMb
            (if (mb >= 1024) String.format("%.1f GB", mb / 1024.0) else "$mb MB")
        } else "--"

    private fun cpuText(state: ServerState): String =
        (state.cpuPercent?.let { "$it%" }) ?: "--"

    private fun uptimeText(state: ServerState): String {
        if (!state.isRunning || state.runningSinceMs <= 0L) return "--"
        val totalSec = (android.os.SystemClock.elapsedRealtime() - state.runningSinceMs) / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    private fun openAppIntent(context: Context): PendingIntent =
        PendingIntent.getActivity(
            context, 0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

    private fun widgetActionIntent(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context,
            if (action == ACTION_WIDGET_START) 4001 else 4002,
            Intent(context, WidgetActionReceiver::class.java).setAction(action),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
}
