package com.mineserve.mobile.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.data.WidgetUpdater
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 桌面组件按钮的统一广播入口：控制台启停、核心/Java 切换、存档保存与备份。
 * 所有动作在 IO 协程执行，完成后刷新全部组件。
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
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
    }
}
