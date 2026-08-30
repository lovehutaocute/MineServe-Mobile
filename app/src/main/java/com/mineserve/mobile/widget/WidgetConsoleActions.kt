package com.mineserve.mobile.widget

import android.content.Context
import com.mineserve.mobile.McApplication
import com.mineserve.mobile.R
import com.mineserve.mobile.data.JavaVersion
import com.mineserve.mobile.data.McConfig
import com.mineserve.mobile.data.ServerEventNotifier
import com.mineserve.mobile.server.BackupManager
import com.mineserve.mobile.server.McServerController
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

/**
 * 控制台组件的后台动作实现（由 WidgetActionReceiver 在 IO 协程中调用）。
 * 所有动作完成后由调用方触发 WidgetUpdater.refresh。
 */
object WidgetConsoleActions {

    @Volatile private var backupRunning = false

    /** 循环切换已安装核心；服务器运行中禁止切换，返回当前核心名。 */
    suspend fun cycleCore(context: Context, delta: Int): String? {
        val repo = McApplication.get(context).repository
        if (repo.termuxRuntime.isMcRunning()) {
            repo.termuxRuntime.emitLog("[widget] 服务器运行中，无法切换核心")
            return repo.configFlow.first().activeCoreName
        }
        val config = repo.configFlow.first()
        val cores = config.installedCores
        if (cores.isEmpty()) return config.activeCoreName
        val idx = cores.indexOfFirst { it.name == config.activeCoreName }.takeIf { it >= 0 } ?: 0
        val next = cores[(idx + delta + cores.size) % cores.size]
        val updated = config.copy(activeCoreName = next.name)
        repo.saveConfig(updated)
        awaitConfigSaved(repo) { it.activeCoreName == next.name }
        // 该字段就是启动（controller.start）读取的全局选择，切换即生效
        repo.termuxRuntime.emitLog("[widget] 已切换服务器核心: ${next.name}")
        return next.name
    }

    /** 循环切换 Java 版本。 */
    suspend fun cycleJava(context: Context, delta: Int): String? {
        val repo = McApplication.get(context).repository
        val versions = JavaVersion.values()
        val config = repo.configFlow.first()
        val idx = versions.indexOf(config.selectedJavaVersion).takeIf { it >= 0 } ?: 0
        val next = versions[(idx + delta + versions.size) % versions.size]
        val updated = config.copy(selectedJavaVersion = next)
        repo.saveConfig(updated)
        awaitConfigSaved(repo) { it.selectedJavaVersion == next }
        // 启动校验与 Java 解析读取的就是 selectedJavaVersion，切换即生效
        repo.termuxRuntime.emitLog("[widget] 已切换 Java 版本: ${next.displayName}")
        return next.displayName
    }

    /**
     * saveConfig 是 300ms 防抖异步落盘：不等落盘就刷新组件/触发启动会读到旧值，
     * 造成“切换无响应”。这里轮询确认目标字段已持久化（最多 2 秒）。
     */
    private suspend fun awaitConfigSaved(repo: com.mineserve.mobile.data.ServerRepository, matches: (McConfig) -> Boolean) {
        val deadline = System.currentTimeMillis() + 2_000L
        while (System.currentTimeMillis() < deadline) {
            val current = runCatching { repo.configFlow.first() }.getOrNull()
            if (current != null && matches(current)) return
            delay(100L)
        }
    }

    /** 启动服务器（后台执行完整启动流程，失败发事件通知）。 */
    suspend fun startServer(context: Context): Boolean {
        val app = McApplication.get(context)
        val repo = app.repository
        val config = repo.configFlow.first()
        return runCatching {
            McServerController(app.termuxRuntime, repo).let { kotlinx.coroutines.runBlocking { it.start(config) } }
            true
        }.getOrElse { e ->
            ServerEventNotifier.notify(
                app,
                app.getString(R.string.notif_schedule_start_fail_title),
                app.getString(R.string.notif_schedule_start_fail_text, e.message ?: ""),
                ServerEventNotifier.ID_SCHEDULE_FAIL, 1
            )
            false
        }
    }

    /** 停止服务器。 */
    suspend fun stopServer(context: Context): Boolean {
        val app = McApplication.get(context)
        return runCatching {
            McServerController(app.termuxRuntime, app.repository).let { kotlinx.coroutines.runBlocking { it.stop() } }
            true
        }.getOrDefault(false)
    }

    /** 保存世界：按核心类型发 save-all / save hold。 */
    suspend fun saveWorld(context: Context): Boolean {
        val repo = McApplication.get(context).repository
        val config = repo.configFlow.first()
        val core = config.installedCores.find { it.name == config.activeCoreName } ?: return false
        if (!repo.termuxRuntime.isMcRunning()) return false
        repo.termuxRuntime.sendCommand(core.core.consoleSaveCommand)
        return true
    }

    /** 存档备份：先按核心类型保存，再打包世界快照，完成后发事件通知。 */
    suspend fun backupWorld(context: Context): Boolean {
        if (backupRunning) return false
        backupRunning = true
        try {
            val app = McApplication.get(context)
            val repo = app.repository
            val config = repo.configFlow.first()
            val core = config.installedCores.find { it.name == config.activeCoreName } ?: return false
            if (repo.termuxRuntime.isMcRunning()) {
                repo.termuxRuntime.sendCommand(core.core.consoleSaveCommand)
                delay(1_000L)
            }
            val path = BackupManager(app.termuxRuntime).backupWorldToExternal(
                core.dirName, BackupManager.BackupOrigin.Manual, config.maxSnapshots
            )
            if (path == null) {
                ServerEventNotifier.notify(
                    app,
                    app.getString(R.string.widget_backup_failed_title),
                    app.getString(R.string.widget_backup_failed_text),
                    ServerEventNotifier.ID_SCHEDULE_FAIL, 1
                )
                return false
            }
            ServerEventNotifier.notify(
                app,
                app.getString(R.string.widget_backup_done_title),
                app.getString(R.string.widget_backup_done_text, java.io.File(path).name),
                ServerEventNotifier.ID_BACKUP_DONE, 1
            )
            return true
        } finally {
            backupRunning = false
        }
    }
}
