package com.mineserve.mobile.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.data.WidgetPreferences
import com.mineserve.mobile.data.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面组件按钮的统一广播入口：控制台启停、核心/Java 切换、存档保存/备份，
 * 以及状态总览组件的“立即刷新”（只读，不受操作按钮开关限制）。
 * 所有动作在 IO 协程执行，完成后刷新全部组件。
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 状态刷新只读且是组件核心功能，不纳入“允许操作按钮”开关。
                if (action == ACTION_REFRESH_STATUS) {
                    WidgetConsoleActions.refreshServerStatus(context)
                    WidgetUpdater.refresh(context)
                    return@launch
                }
                if (!WidgetPreferences.areActionsEnabled(context)) return@launch
                when (action) {
                    ACTION_WIDGET_START -> WidgetConsoleActions.startServer(context)
                    ACTION_WIDGET_STOP -> WidgetConsoleActions.stopServer(context)
                    ACTION_CORE_PREV -> WidgetConsoleActions.cycleCore(context, -1)
                    ACTION_CORE_NEXT -> WidgetConsoleActions.cycleCore(context, 1)
                    ACTION_JAVA_PREV -> WidgetConsoleActions.cycleJava(context, -1)
                    ACTION_JAVA_NEXT -> WidgetConsoleActions.cycleJava(context, 1)
                    ACTION_SAVE -> WidgetConsoleActions.saveWorld(context)
                    ACTION_BACKUP -> WidgetConsoleActions.backupWorld(context)
                }
                WidgetUpdater.refresh(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        const val ACTION_WIDGET_START = "com.mineserve.mobile.widget.START_SERVER"
        const val ACTION_WIDGET_STOP = "com.mineserve.mobile.widget.STOP_SERVER"
        const val ACTION_CORE_PREV = "com.mineserve.mobile.widget.CORE_PREV"
        const val ACTION_CORE_NEXT = "com.mineserve.mobile.widget.CORE_NEXT"
        const val ACTION_JAVA_PREV = "com.mineserve.mobile.widget.JAVA_PREV"
        const val ACTION_JAVA_NEXT = "com.mineserve.mobile.widget.JAVA_NEXT"
        const val ACTION_SAVE = "com.mineserve.mobile.widget.SAVE_WORLD"
        const val ACTION_BACKUP = "com.mineserve.mobile.widget.BACKUP_WORLD"
        const val ACTION_REFRESH_STATUS = "com.mineserve.mobile.widget.REFRESH_STATUS"
    }
}
