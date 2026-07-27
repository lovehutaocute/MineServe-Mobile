package com.mcserver.manager.server

import com.mcserver.manager.data.PluginInfo
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 插件管理（生产化）：
 *  - 安装：通过 Termux 内的 wget 下载插件 jar 到 plugins/ 目录
 *  - 卸载：删除 jar 文件
 *  - 热重载：通过 tmux send-keys 发送 reload 指令
 *
 * 说明：文件操作走 Termux 内的命令（wget/rm），确保权限与路径
 * 与 proot rootfs 一致，不依赖 APP 层 Java File 访问。
 */
class PluginManager(
    private val termux: TermuxRuntime,
    private val repo: ServerRepository
) {
    // 插件下载源（CI 最新构建）
    private val pluginUrls = mapOf(
        "luckperms" to "https://ci.lucko.me/job/LuckPerms/lastSuccessfulBuild/artifact/bukkit/build/libs/LuckPerms-Bukkit.jar",
        "essentialsx" to "https://ci.ender.zone/job/EssentialsX/lastSuccessfulBuild/artifact/jars/EssentialsX.jar",
        "vault" to "https://github.com/MilkBowl/Vault/releases/latest/download/Vault.jar",
        "worldedit" to "https://ci.enginehub.org/job/worldedit/lastSuccessfulBuild/artifact/worldedit-bukkit/build/libs/worldedit-bukkit.jar",
        "coreprotect" to "https://ci.codemc.io/job/PlayPro/job/CoreProtect/lastSuccessfulBuild/artifact/target/CoreProtect.jar"
    )

    suspend fun install(plugin: PluginInfo) = withContext(Dispatchers.IO) {
        val url = pluginUrls[plugin.id] ?: return@withContext
        // 1. 用 Termux 内的 wget 下载插件 jar 到 plugins/ 目录
        termux.execOnce("wget", "-q", "-O", "/home/server/plugins/${plugin.id}.jar", url)

        // 2. 更新插件状态
        val list = repo.pluginsFlow.first().map {
            if (it.id == plugin.id) it.copy(installed = true) else it
        }
        repo.setPlugins(list)

        // 3. 触发 reload（通过 tmux send-keys）
        termux.sendCommand("reload")
    }

    suspend fun uninstall(plugin: PluginInfo) = withContext(Dispatchers.IO) {
        // 1. 删除 jar 文件
        termux.execOnce("rm", "-f", "/home/server/plugins/${plugin.id}.jar")

        // 2. 更新插件状态
        val list = repo.pluginsFlow.first().map {
            if (it.id == plugin.id) it.copy(installed = false) else it
        }
        repo.setPlugins(list)

        // 3. reload
        termux.sendCommand("reload")
    }
}
