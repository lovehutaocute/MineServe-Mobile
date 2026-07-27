package com.mcserver.manager.server

import com.mcserver.manager.data.InstallStep
import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.data.StepStatus
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * MC 服务控制器：
 *  - 一键安装依赖：JDK → proot → tmux → frp
 *  - 下载并启动服务端核心
 *  - 解析 consoleFlow 推导 TPS/在线玩家/内存（占位实现）
 */
class McServerController(
    private val termux: TermuxRuntime,
    private val repo: ServerRepository
) {

    /**
     * 一键安装：依次执行 4 个步骤，逐步更新 installSteps 状态
     */
    suspend fun installDependencies() = withContext(Dispatchers.IO) {
        val steps = InstallStep.values()
        steps.forEachIndexed { idx, step ->
            repo.markStep(step, StepStatus.Active, idx * 25)
            val code = when (step) {
                InstallStep.Jdk -> termux.execOnce("pkg", "install", "-y", "openjdk-17")
                InstallStep.Proot -> termux.execOnce("pkg", "install", "-y", "proot")
                InstallStep.Tmux -> termux.execOnce("pkg", "install", "-y", "tmux")
                InstallStep.Frp -> termux.execOnce("pkg", "install", "-y", "frp")
            }
            if (code == 0) {
                repo.markStep(step, StepStatus.Done, (idx + 1) * 25)
            } else {
                repo.markStep(step, StepStatus.Wait, idx * 25)
                return@withContext false
            }
        }
        true
    }

    /**
     * 下载服务端核心
     */
    suspend fun downloadCore(config: McConfig) = withContext(Dispatchers.IO) {
        val url = when (config.selectedCore) {
            // 实际生产中应聚合 papermc.io / fabricmc.net / files.minecraftforge.net / mojang 官方
            else -> "https://api.papermc.io/v2/projects/paper/versions/${config.mcVersion}/builds/latest/downloads/paper-${config.mcVersion}.jar"
        }
        termux.execOnce(
            "wget", "-q", "-O", "/home/server/server.jar", url
        )
    }

    /**
     * 启动 MC 服务（在 tmux 内，进程脱离 APP）
     */
    suspend fun start(config: McConfig) = withContext(Dispatchers.IO) {
        if (!repo.serverState.value.isInstallComplete) {
            installDependencies()
            downloadCore(config)
        }
        termux.startMc(
            jarPath = "/home/server/server.jar",
            maxHeapMb = config.maxHeapMb,
            onExit = { code ->
                repo.updateServerState { it.copy(isRunning = false) }
            }
        )
        repo.updateServerState { it.copy(isRunning = true) }
    }

    suspend fun stop() = withContext(Dispatchers.IO) {
        termux.stopMc()
        repo.updateServerState { it.copy(isRunning = false) }
    }

    /**
     * 发送指令到 MC 控制台（如 /op /say /list）
     */
    fun sendCommand(line: String) {
        if (!line.startsWith("/")) {
            termux.sendCommand("/$line")
        } else {
            termux.sendCommand(line)
        }
    }
}
