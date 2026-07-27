package com.mcserver.manager.server

import com.mcserver.manager.data.PluginInfo
import com.mcserver.manager.data.ServerRepository
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * 插件管理：
 *  - 安装：从挂载的 plugins/ 目录复制 jar
 *  - 卸载：删除 jar 并 reload
 *  - 实际生产可对接 SpigotMC / Modrinth 市场 API
 */
class PluginManager(
    private val termux: TermuxRuntime,
    private val repo: ServerRepository
) {

    suspend fun install(plugin: PluginInfo) = withContext(Dispatchers.IO) {
        // 1. 下载插件 jar 到 /home/server/plugins/
        // 2. 触发 reload：termux.sendCommand("reload")
        val list = repo.pluginsFlow.first().map {
            if (it.id == plugin.id) it.copy(installed = true) else it
        }
        repo.setPlugins(list)
        termux.sendCommand("reload")
    }

    suspend fun uninstall(plugin: PluginInfo) = withContext(Dispatchers.IO) {
        // 1. 删除 /home/server/plugins/<id>.jar
        // 2. reload
        val list = repo.pluginsFlow.first().map {
            if (it.id == plugin.id) it.copy(installed = false) else it
        }
        repo.setPlugins(list)
        termux.sendCommand("reload")
    }
}
