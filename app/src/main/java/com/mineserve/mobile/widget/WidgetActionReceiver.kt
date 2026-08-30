package com.mineserve.mobile.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.data.WidgetUpdater
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 桌面组件「启动/停止」按钮的执行器：
 * 后台构造 McServerController 直接启停服务器，失败时发事件通知。
 */
class WidgetActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = McApplication.get(context)
                val repository = app.repository
                val controller = McServerController(app.termuxRuntime, repository)
                when (action) {
                    ACTION_WIDGET_START -> runCatching {
                        val config = repository.configFlow.first()
                        kotlinx.coroutines.runBlocking { controller.start(config) }
                    }.onFailure { e ->
                        ServerEventNotifier.notify(
                            app,
                            app.getString(R.string.notif_schedule_start_fail_title),
                            app.getString(R.string.notif_schedule_start_fail_text, e.message ?: ""),
                            ServerEventNotifier.ID_SCHEDULE_FAIL, 1
                        )
                    }
                    ACTION_WIDGET_STOP -> runCatching {
                        kotlinx.coroutines.runBlocking { controller.stop() }
                    }
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
    }
}
