package com.mcserver.manager.server.tunnel

import com.mcserver.manager.data.McConfig
import com.mcserver.manager.data.TunnelType
import com.mcserver.manager.runtime.TermuxRuntime

/**
 * playit.gg 隧道后端 — 专为 Minecraft 设计，免费零配置。
 *
 * playit 是一个自动分配公网地址的隧道服务，无需注册域名或购买 VPS。
 * 首次运行时会生成一个 claim URL，用户在浏览器中绑定账号后即可使用。
 */
class PlayitBackend(
    termux: TermuxRuntime,
    binaryManager: BinaryManager
) : TermuxBackend(termux, binaryManager, TunnelType.Playit) {

    override suspend fun ensureBinary(): String? {
        return binaryManager.ensure("playit")
    }

    override fun buildArgs(config: McConfig, binary: String): List<String> {
        // 新版 playit CLI 只需 --secret，端口在 playit.gg 网页后台配置
        return listOf("--secret", "mc-server")
    }

    override fun parsePublicUrl(line: String): String? {
        // playit 输出格式: "tunnel ready: tcp://auto.playit.gg:12345"
        val regex = Regex("""(?:tunnel ready|allocated)[:\s]+(tcp://[\w.:-]+)""", RegexOption.IGNORE_CASE)
        return regex.find(line)?.groupValues?.get(1)
    }

    override fun killProcess() {
        termux.execOnce("pkill", "-f", "playit")
    }

    override fun diagnoseFailure(exitCode: Int, output: String): String {
        return when {
            output.contains("claim", ignoreCase = true) ->
                "playit.gg 需要绑定账号：请在浏览器中打开 claim URL 完成绑定，然后重试"
            output.contains("network", ignoreCase = true) || output.contains("connect", ignoreCase = true) ->
                "playit.gg 网络连接失败，请检查网络后重试"
            else -> super.diagnoseFailure(exitCode, output)
        }
    }
}
