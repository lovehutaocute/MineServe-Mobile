package com.mineserve.mobile.service

import android.app.AlarmManager
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.net.wifi.WifiManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.mineserve.mobile.MainActivity
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * 前台服务：MC 进程托管 + 日志 socket 服务端 + 保活
 *
 * 关键点：
 * 1. onStartCommand 返回 START_STICKY → 系统在资源充足时会自动重启 Service
 * 2. foregroundServiceType=specialUse (Android 13+) 已在 manifest 声明
 * 3. WakeLock + WifiLock 防止 CPU/网络休眠
 * 4. onTaskRemoved：用户划掉任务时尝试通过非精确闹钟重启（Android 12+ 不允许精确闹钟）
 * 5. onCreate 中检测 tmux session 是否存在 → 判断是否有"上次存活"的 MC 进程
 */
class McForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var termux: TermuxRuntime

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        termux = McApplication.get(this).termuxRuntime
        // 移到 IO 线程，避免主线程阻塞导致 ANR
        scope.launch { detectSurvivingProcess() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundInternal()
            ACTION_STOP -> {
                scope.launch {
                    termux.stopMc()
                    stopForegroundInternal()
                    stopSelf()
                }
                return START_NOT_STICKY
            }
            else -> {
                // null intent（系统重启）或其他 action：仍需启动前台通知
                startForegroundInternal()
            }
        }
        // START_STICKY：进程被杀后系统会在资源充足时尝试重启 Service
        return START_STICKY
    }

    private fun startForegroundInternal() {
        val notif = buildNotification(getString(R.string.notif_title_running), getString(R.string.notif_text_running))
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+ 必须显式传入 FGS 类型
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIF_ID, notif)
        }
        acquireLocks()
        // 启动 socket 监听 + 健康 watchdog
        startWatchdog()
    }

    private fun stopForegroundInternal() {
        releaseLocks()
        stopForeground(STOP_FOREGROUND_REMOVE)
    }

    /**
     * 启动后检测：APP 重启后是否有"上次存活"的 MC 进程
     */
    private fun detectSurvivingProcess() {
        val running = try { termux.isMcRunning() } catch (e: Exception) { false }
        if (running) {
            Log.i(TAG, "Detected surviving MC process, will reattach to log stream")
            McApplication.get(this).repository.updateServerState {
                it.copy(isRunning = true)
            }
        }
    }

    /**
     * 健康 watchdog：每 30 秒检查 MC 进程是否存活，更新 StateFlow。
     * 每 60 秒发送 list 命令获取玩家数，Paper 核心发送 tps 命令获取 TPS。
     */
    private fun startWatchdog() {
        scope.launch {
            var tick = 0
            while (true) {
                val alive = try { termux.isMcRunning() } catch (e: Exception) { false }
                val app = McApplication.get(this@McForegroundService)
                app.repository.updateServerState { it.copy(isRunning = alive) }
                // 服务器运行时，每 60 秒（2 个 tick）发送一次查询命令获取真实数据
                if (alive && tick % 2 == 0) {
                    try {
                        val config = runCatching {
                            runBlocking { app.repository.configFlow.first() }
                        }.getOrNull()
                        if (config != null) {
                            // list 命令获取在线玩家数（所有核心支持）
                            termux.sendCommand("list")
                            // Paper 核心额外发送 tps 命令
                            if (config.selectedCore == com.mineserve.mobile.data.ServerCore.Paper) {
                                termux.sendCommand("tps")
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "watchdog query failed: ${e.message}")
                    }
                }
                tick++
                kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    /**
     * 唤醒锁 + Wi-Fi 锁，确保 MC 进程在屏幕熄灭时仍可接收玩家连接
     */
    private fun acquireLocks() {
        val app = McApplication.get(this)
        val config = runCatching {
            runBlocking { app.repository.configFlow.first() }
        }.getOrNull()
        val keepCpu = config?.keepCpuWakelock ?: true
        val keepWifi = config?.keepWifiLock ?: true

        if (keepCpu) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MCServerManager::McWake"
            ).apply { acquire(60 * 60 * 1000L) }
        }
        if (keepWifi) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wm.createWifiLock(
                WifiManager.WIFI_MODE_FULL_HIGH_PERF,
                "MCServerManager::McWifi"
            ).apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    /**
     * 用户从最近任务划掉 APP 时触发
     * Android 12+ 不允许在后台启动 FGS，使用非精确闹钟延迟重启
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.i(TAG, "onTaskRemoved: scheduling restart via inexact alarm")
        scheduleRestart()
    }

    private fun scheduleRestart() {
        val am = getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(this, McForegroundService::class.java).apply {
            action = ACTION_START
        }
        val pi = PendingIntent.getService(
            this, 0, intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )
        // Android 12+：使用 setAndAllowWhileIdle（非精确，规避 SCHEDULE_EXACT_ALARM 限制）
        am.setAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            System.currentTimeMillis() + 2000L,
            pi
        )
    }

    private fun buildNotification(title: String, content: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, getString(R.string.notif_channel_id))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    override fun onDestroy() {
        super.onDestroy()
        isRunning = false
        scope.cancel()
        releaseLocks()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.mineserve.mobile.action.START"
        const val ACTION_STOP = "com.mineserve.mobile.action.STOP"
        private const val TAG = "McForegroundService"

        /** 服务是否在运行（供保活检查） */
        @Volatile
        var isRunning: Boolean = false
    }
}
