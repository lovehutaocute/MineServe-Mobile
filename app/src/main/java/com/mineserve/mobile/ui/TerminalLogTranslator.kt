package com.mineserve.mobile.ui

/** Display-only translations for high-signal Minecraft logs. Raw console data stays unchanged. */
object TerminalLogTranslator {
    fun translate(line: String): String {
        val translated = when {
            line.contains("Done (") && line.contains("For help") -> "服务端已启动完成"
            line.contains("Starting minecraft server", true) -> "正在启动 Minecraft 服务端"
            line.contains("Preparing level", true) -> "正在加载世界"
            line.contains("joined the game", true) -> line.replace("joined the game", "进入了服务器", ignoreCase = true)
            line.contains("left the game", true) -> line.replace("left the game", "离开了服务器", ignoreCase = true)
            line.contains("Stopping server", true) -> "正在停止服务端"
            line.contains("Saving the game", true) -> "正在保存世界"
            line.contains("Saved the game", true) -> "世界保存完成"
            line.contains("You need to agree to the EULA", true) -> "未接受 Minecraft EULA，服务端无法启动"
            line.contains("Address already in use", true) || line.contains("BindException", true) -> "端口已被占用，服务端无法监听"
            line.contains("OutOfMemoryError", true) || line.contains("Java heap space", true) -> "Java 内存不足（OutOfMemoryError）"
            line.contains("UnsupportedClassVersionError", true) || line.contains("class file version", true) -> "Java 版本不兼容"
            line.contains("mod loading has failed", true) || line.contains("Mixin apply failed", true) -> "模组加载失败或版本不匹配"
            line.contains("Could not load plugin", true) || line.contains("Unsupported API version", true) -> "插件加载失败或 API 版本不匹配"
            line.contains("[ERROR]", true) || line.contains("[FATAL]", true) -> "错误：$line"
            line.contains("[WARN]", true) -> "警告：$line"
            else -> return line
        }
        return "$translated\n  原文: $line"
    }
}
