package com.mineserve.mobile.data

/**
 * 版本字符串规范化：仅去除首尾空白。
 *
 * 历史版本曾将 NeoForge 下的 `1.21.11` 强制纠正为 `1.21.1`，
 * 但 `1.21.11` 是真实存在的 Minecraft 版本，该纠正反而误伤了合法输入。
 * 真正的版本匹配修复已移至 [com.mineserve.mobile.server.McServerController.resolveNeoForgeUrl]，
 * 通过保留点号段边界避免 `1.21.1` 被误匹配为 `1.21.11`。
 */
object MinecraftVersionNormalizer {
    fun forCore(core: ServerCore, version: String): String = version.trim()
}
