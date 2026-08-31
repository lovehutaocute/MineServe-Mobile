package com.mineserve.mobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.mineserve.mobile.data.ScheduleManager
import com.mineserve.mobile.service.McForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * 开机自启动：设备开机完成后，若开启「开机自启」开关，拉起前台保活服务；
 * 同时按配置重注册每日定时开/停服闹钟（进程与闹钟在重启后不会保留）。
 * 开关状态存于 SharedPreferences（mc_config_meta），由保活页面设置。
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // 重新注册每日定时开/停服闹钟
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { McApplication.get(context).repository.configFlow.first() }
                    .getOrNull()
                    ?.let { ScheduleManager.register(context, it) }
                // 开机后纠正桌面组件（重启前可能停留在“运行中”）
                com.mineserve.mobile.data.WidgetUpdater.refresh(context)
            } finally {
                pending.finish()
            }
        }

        val bootAuto = context.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_BOOT_AUTO_START, false)
        if (bootAuto) {
            Log.i(TAG, "开机自启：拉起前台保活服务")
            try {
                context.startForegroundService(
                    Intent(context, McForegroundService::class.java).apply {
                        action = McForegroundService.ACTION_START
                    }
                )
            } catch (e: Exception) {
                Log.w(TAG, "开机自启失败: ${e.message}")
            }
        }
    }

    companion object {
        const val META_PREFS = "mc_config_meta"
        const val KEY_BOOT_AUTO_START = "boot_auto_start"
        const val KEY_KEEP_ALIVE = "keep_alive_enabled"
        private const val TAG = "BootReceiver"
    }
}
