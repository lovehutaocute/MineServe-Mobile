package com.mineserve.mobile.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.ScheduleManager
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 每日定时开/停服触发点：到点后按最新配置执行，随后重注册次日闹钟。
 * 开服失败（环境未就绪、核心缺失等）时发事件通知提醒，不影响应用。
 */
class ScheduleAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(ScheduleManager.EXTRA_ACTION) ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val app = McApplication.get(context)
                val repository = app.repository
                val config = repository.configFlow.first()
                val controller = McServerController(app.termuxRuntime, repository)
                if (action == ScheduleManager.ACTION_START_SERVER) {
                    runCatching {
                        kotlinx.coroutines.runBlocking { controller.start(config) }
                    }.onFailure { e ->
                        ServerEventNotifier.notify(
                            app,
                            app.getString(R.string.notif_schedule_start_fail_title),
                            app.getString(R.string.notif_schedule_start_fail_text, e.message ?: ""),
                            ServerEventNotifier.ID_SCHEDULE_FAIL, 1
                        )
                    }
                } else if (action == ScheduleManager.ACTION_STOP_SERVER) {
                    runCatching {
                        kotlinx.coroutines.runBlocking { controller.stop() }
                    }
                }
                // 触发后按最新配置重注册下一次（配置可能已被修改）
                ScheduleManager.register(context, repository.configFlow.first())
            } finally {
                pending.finish()
            }
        }
    }
}
