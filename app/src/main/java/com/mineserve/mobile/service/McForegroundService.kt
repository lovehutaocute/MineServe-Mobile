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
import com.mineserve.mobile.data.EventLogStore
import com.mineserve.mobile.data.InstallStep
import com.mineserve.mobile.data.StartupPhase
import com.mineserve.mobile.data.StepStatus
import com.mineserve.mobile.data.WidgetEventType
import com.mineserve.mobile.data.startupPhaseForLog
import com.mineserve.mobile.runtime.TermuxRuntime
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.ExternalBackupStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

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
    private lateinit var backupManager: BackupManager

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    // ponytail: one active server process, so one service-wide backup lock is enough.
    @Volatile private var autoBackupRunning = false
    @Volatile private var cachedDirName: String? = null
    private val chatRegex = Regex("<([A-Za-z0-9_]{1,16})>\\s*(.+)$")
    private val joinRegex = Regex(":\\s*([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?joined the game")
    private val leaveRegex = Regex(":\\s*([A-Za-z0-9_]{1,16})(?:\\[[^\\]]*\\])?\\s+(?:has\\s+)?left the game")
    private val pnxJoinRegex = Regex("(?:^|[: ]\\s*)([A-Za-z0-9_]{1,16})(?:\\[[^]]*])?\\s+logged in with entity id", RegexOption.IGNORE_CASE)
    private val pnxLeaveRegex = Regex("(?:^|[: ]\\s*)([A-Za-z0-9_]{1,16})(?:\\[[^]]*])?\\s+disconnected(?:[: ]|$)", RegexOption.IGNORE_CASE)

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        termux = McApplication.get(this).termuxRuntime
        backupManager = BackupManager(termux)
        scope.launch {
            McApplication.get(this@McForegroundService).repository.configFlow
                .map { it.keepStatusOverlay }
                .distinctUntilChanged()
                .collect { enabled -> syncOverlay(enabled) }
        }
        scope.launch {
            val app = McApplication.get(this@McForegroundService)
            combine(app.repository.configFlow, app.repository.serverState) { config, state -> config.keepStatusOverlay to state }
                .collect { (enabled, state) -> if (enabled) StatusOverlay.update(state.cpuPercent, state.usedMemoryMb) }
        }
        // 移到 IO 线程，避免主线程阻塞导致 ANR
        scope.launch { detectSurvivingProcess() }
        // 解析 list/tps 命令回复并写入 serverState：
        // 该解析原先只在 UI 层（McViewModel）进行，App 退后台后桌面组件数据会停更。
        startConsoleParsing()
    }

    private fun startConsoleParsing() {
        scope.launch {
            McApplication.get(this@McForegroundService).repository.configFlow.collect { config ->
                cachedDirName = config.installedCores.find { it.name == config.activeCoreName }?.dirName
            }
        }
        scope.launch {
            termux.consoleFlow.collect { line ->
                try {
                    when {
                        line.contains("players online") -> PLAYERS_REGEX.find(line)?.let { m ->
                            val online = m.groupValues[1].toIntOrNull() ?: return@let
                            val max = m.groupValues[2].toIntOrNull() ?: return@let
                            McApplication.get(this@McForegroundService).repository.updateServerState {
                                it.copy(onlinePlayers = online, maxPlayers = max)
                            }
                        }
                        line.contains("TPS from last 1m") -> TPS_REGEX.find(line)?.let { m ->
                            val tps = m.groupValues[1].toDoubleOrNull() ?: return@let
                            McApplication.get(this@McForegroundService).repository.updateServerState {
                                it.copy(tps = tps, healthPercent = ((tps / 20.0) * 100).toInt().coerceIn(0, 100))
                            }
                        }
                        // 启动完成检测下沉：App 退后台后无人设置 runningSinceMs，
                        // 桌面组件会一直停留在“启动中”。
                        startupPhaseForLog(line) == StartupPhase.Ready ->
                            McApplication.get(this@McForegroundService).repository.updateServerState { st ->
                                if (!st.isRunning || st.startupPhase == StartupPhase.Ready) st
                                else st.copy(
                                    startupPhase = StartupPhase.Ready,
                                    runningSinceMs = st.runningSinceMs.takeIf { it > 0L }
                                        ?: android.os.SystemClock.elapsedRealtime()
                                )
                            }
                    }
                    recordWidgetEvents(line)
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 玩家进离服与聊天公告写入事件日志组件的数据源。 */
    private fun recordWidgetEvents(line: String) {
        val dir = cachedDirName ?: return
        val context = this@McForegroundService
        chatRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.CHAT, it.groupValues[1], it.groupValues[2])
        }
        joinRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.JOIN, it.groupValues[1])
        }
        leaveRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.LEAVE, it.groupValues[1])
        }
        pnxJoinRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.JOIN, it.groupValues[1])
        }
        pnxLeaveRegex.find(line)?.let {
            EventLogStore.add(context, dir, WidgetEventType.LEAVE, it.groupValues[1])
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startForegroundInternal()
            ACTION_REFRESH_KEEP_ALIVE -> scope.launch { syncOverlayFromConfig() }
            ACTION_REFRESH_STATUS -> scope.launch { refreshServerStatusNow() }
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
        // 配置读取（DataStore）移到 IO 线程，避免主线程磁盘 I/O
        scope.launch { acquireLocks() }
        // 启动 socket 监听 + 健康 watchdog
        startWatchdog()
        // 授权页返回、厂商系统延迟刷新 AppOps 时，前一次挂载可能发生得太早。
        scope.launch {
            kotlinx.coroutines.delay(500L)
            syncOverlayFromConfig()
        }
    }

    private fun stopForegroundInternal() {
        StatusOverlay.hide(this)
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
                // A user-triggered start already owns the startup state. Do not
                // turn it into "running" before the console reports readiness.
                if (it.isRunning && it.runningSinceMs == 0L) return@updateServerState it
                // The original launch log is not replayed after an app restart.
                // Restore the timestamp so the dashboard cannot remain in the
                // perpetual "starting" state after reattaching.
                it.copy(
                    isRunning = true,
                    runningSinceMs = it.runningSinceMs.takeIf { since -> since > 0L }
                        ?: android.os.SystemClock.elapsedRealtime()
                )
            }
        } else {
            // 服务被系统重启后新进程的 ServerState 是初始“已停止”，状态无变化
            // 不会触发 diff 钩子；而组件上仍是陈旧的“运行中”，这里必须主动补刷。
            com.mineserve.mobile.data.WidgetUpdater.refresh(this)
        }
    }

    /** 立即同步一次服务端状态：更新存活状态，并向运行中的服务端发起 list/tps 查询。 */
    private suspend fun refreshServerStatusNow() {
        val alive = try { termux.isMcRunning() } catch (e: Exception) { false }
        val app = McApplication.get(this@McForegroundService)
        app.repository.updateServerState {
            if (!alive) {
                it.copy(isRunning = false, runningSinceMs = 0L, tps = 0.0, cpuPercent = null, usedMemoryMb = 0L)
            } else {
                it.copy(isRunning = true)
            }
        }
        if (alive) {
            try {
                val config = runCatching { app.repository.configFlow.first() }.getOrNull()
                if (config != null) {
                    termux.sendCommand("list")
                    if (config.installedCores.any {
                            it.name == config.activeCoreName && it.core == com.mineserve.mobile.data.ServerCore.Paper
                        }
                    ) {
                        termux.sendCommand("tps")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "refreshServerStatusNow query failed: ${e.message}")
            }
        }
        // 状态可能没变化（diff 钩子不触发），这里主动补刷组件。
        com.mineserve.mobile.data.WidgetUpdater.refresh(this@McForegroundService)
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
                app.repository.updateServerState {
                    if (!alive) {
                        it.copy(isRunning = false, runningSinceMs = 0L)
                    } else {
                        // A live Java process is not proof that the server has finished loading.
                        // The console's ready signal sets runningSinceMs once the core is usable.
                        it.copy(isRunning = true)
                    }
                }
                // 服务器运行时，每 60 秒（2 个 tick）发送一次查询命令获取真实数据
                if (alive && tick % 2 == 0) {
                    try {
                        val config = runCatching {
                            app.repository.configFlow.first()
                        }.getOrNull()
                        if (config != null) {
                            maybeAutoBackup(config)
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
            // 服务器运行中每 30s 同步一次桌面组件（停止/崩溃等事件由状态 diff 钩子驱动）
            com.mineserve.mobile.data.WidgetUpdater.refresh(this@McForegroundService)
            kotlinx.coroutines.delay(30_000L)
            }
        }
    }

    /**
     * 唤醒锁 + Wi-Fi 锁，确保 MC 进程在屏幕熄灭时仍可接收玩家连接
     */
    private fun maybeAutoBackup(config: com.mineserve.mobile.data.McConfig) {
        val active = config.installedCores.find { it.name == config.activeCoreName } ?: return
        val baseIntervalMs = config.autoBackupIntervalMin * 60_000L
        if (baseIntervalMs <= 0 || autoBackupRunning) return
        // 多服务器自动备份错峰：每个服务器在基础间隔上叠加 0–10 分钟的唯一偏移，
        // 避免多个服务器在同一时刻同时打包造成 IO 高峰
        val staggerOffset = (active.dirName.hashCode() and 0x7fffffff) % 600_000L
        val intervalMs = baseIntervalMs + staggerOffset
        if (!ExternalBackupStore.hasPermission(this)) {
            termux.emitLog("[backup] 自动备份跳过：未授予外部存储访问权限")
            return
        }
        val prefs = getSharedPreferences("auto_backup", MODE_PRIVATE)
        val key = "last_${active.dirName}_${config.autoBackupType.name}"
        if (System.currentTimeMillis() - prefs.getLong(key, 0L) < intervalMs) return
        autoBackupRunning = true
        scope.launch {
            try {
                val typeName = config.autoBackupType.displayName
                termux.emitLog("[backup] 正在自动$typeName: ${active.name}")
                termux.sendCommand(active.core.consoleSaveCommand)
                kotlinx.coroutines.delay(1_000L)
                val path = when (config.autoBackupType) {
                    com.mineserve.mobile.data.AutoBackupType.World -> backupManager.backupWorldToExternal(
                        active.dirName,
                        BackupManager.BackupOrigin.Automatic,
                        config.maxSnapshots
                    )
                    com.mineserve.mobile.data.AutoBackupType.Server -> backupManager.backupServerToExternal(
                        active.dirName,
                        active.core.displayName,
                        BackupManager.BackupOrigin.Automatic,
                        config.maxSnapshots
                    )
                }
                if (path != null) {
                    prefs.edit().putLong(key, System.currentTimeMillis()).apply()
                    termux.emitLog("[backup] 自动备份完成: ${java.io.File(path).name}")
                } else termux.emitLog("[backup] 自动备份失败：外部目录不可写或没有可备份的数据")
            } catch (e: Exception) {
                termux.emitLog("[backup] 自动备份失败: ${e.message}")
            } finally {
                autoBackupRunning = false
            }
        }
    }

    private suspend fun acquireLocks() {
        val app = McApplication.get(this)
        val config = runCatching {
            app.repository.configFlow.first()
        }.getOrNull()
        val keepCpu = config?.keepCpuWakelock ?: true
        val keepWifi = config?.keepWifiLock ?: true
        syncOverlay(config?.keepStatusOverlay == true)

        if (keepCpu && wakeLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "MineServeMobile::McWake"
            ).apply { acquire() }
        }
        if (keepWifi && wifiLock?.isHeld != true) {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY else WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wifiLock = wm.createWifiLock(
                mode,
                "MineServeMobile::McWifi"
            ).apply { acquire() }
        }
    }

    private fun releaseLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wifiLock?.let { if (it.isHeld) it.release() }
    }

    private suspend fun syncOverlayFromConfig() {
        val enabled = McApplication.get(this).repository.configFlow.first().keepStatusOverlay
        syncOverlay(enabled)
    }

    private fun syncOverlay(enabled: Boolean) {
        if (enabled) StatusOverlay.show(this) else StatusOverlay.hide(this)
    }

    /**
     * 用户从最近任务划掉 APP 时触发
     * Android 12+ 不允许在后台启动 FGS，使用非精确闹钟延迟重启
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // 划掉任务时进程即将终止：按 MC 实际存活状态落一次组件终态，
        // 避免组件在进程死后长期显示“运行中”。
        val alive = runCatching { termux.isMcRunning() }.getOrDefault(false)
        McApplication.get(this).repository.updateServerState { st ->
            if (alive) st else st.copy(isRunning = false, runningSinceMs = 0L)
        }
        com.mineserve.mobile.data.WidgetUpdater.refresh(this)
        Log.i(TAG, "onTaskRemoved: mcAlive=$alive, scheduling restart via inexact alarm")
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
        StatusOverlay.hide(this)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val NOTIF_ID = 1001
        const val ACTION_START = "com.mineserve.mobile.action.START"
        const val ACTION_STOP = "com.mineserve.mobile.action.STOP"
        const val ACTION_REFRESH_KEEP_ALIVE = "com.mineserve.mobile.action.REFRESH_KEEP_ALIVE"
        const val ACTION_REFRESH_STATUS = "com.mineserve.mobile.action.REFRESH_STATUS"
        private const val TAG = "McForegroundService"
        private val PLAYERS_REGEX = Regex("There are (\\d+) of a max of (\\d+) players online")
        private val TPS_REGEX = Regex("TPS from last 1m.*?:\\s*([\\d.]+)")

        /** 服务是否在运行（供保活检查） */
        @Volatile
        var isRunning: Boolean = false
    }
}
