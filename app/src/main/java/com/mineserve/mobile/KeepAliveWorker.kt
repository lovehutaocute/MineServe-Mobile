package com.mineserve.mobile

import android.content.Context
import android.content.Intent
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mineserve.mobile.service.McForegroundService

/**
 * 后台周期保活：每 15 分钟检查前台服务是否存活，未存活且开启「后台保活」时重新拉起。
 */
class KeepAliveWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val keepAlive = applicationContext.getSharedPreferences(
            BootReceiver.META_PREFS, Context.MODE_PRIVATE
        ).getBoolean(BootReceiver.KEY_KEEP_ALIVE, false)
        if (keepAlive && !McForegroundService.isRunning) {
            try {
                applicationContext.startForegroundService(
                    Intent(applicationContext, McForegroundService::class.java).apply {
                        action = McForegroundService.ACTION_START
                    }
                )
            } catch (e: Exception) {
                // 后台启动限制等异常，等待下次周期
            }
        }
        return Result.success()
    }
}
