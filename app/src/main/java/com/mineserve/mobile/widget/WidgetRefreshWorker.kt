package com.mineserve.mobile.widget

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.data.WidgetUpdater
import java.io.File

/**
 * 桌面组件兜底刷新（15 分钟周期，由 WorkManager 在 App 进程死后仍会调度）：
 * 杀掉 App 后没有任何运行中的代码去纠正组件，这里按 /proc 的真实进程状态
 * 推送最终状态，消除组件停留在“运行中”的陈旧显示。
 */
class WidgetRefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = McApplication.get(applicationContext)
        // App 进程被杀后 mcProcess 引用已丢失，isMcRunning 必为 false；
        // 因此用 /proc 扫描判断 Termux 环境内是否仍有 java/proot 进程存活（孤儿进程场景）。
        val alive = runCatching { app.termuxRuntime.isMcRunning() }.getOrDefault(false) || anyMcProcessAlive()
        app.repository.updateServerState { st ->
            if (alive) {
                st.copy(
                    isRunning = true,
                    runningSinceMs = st.runningSinceMs.takeIf { it > 0L }
                        ?: android.os.SystemClock.elapsedRealtime()
                )
            } else {
                st.copy(isRunning = false, runningSinceMs = 0L)
            }
        }
        WidgetUpdater.refresh(applicationContext)
        return Result.success()
    }

    /** MC 以 App 私有 Termux 环境运行：cmdline 含应用私有路径且为 java/proot 启动。 */
    private fun anyMcProcessAlive(): Boolean = runCatching {
        File("/proc").listFiles { f -> f.isDirectory && f.name.toIntOrNull() != null }
            ?.any { p ->
                runCatching {
                    val cmd = File(p, "cmdline").readBytes().decodeToString()
                    cmd.contains("com.mineserve.mobile/files/home") && cmd.contains("java")
                }.getOrDefault(false)
            } ?: false
    }.getOrDefault(false)
}
