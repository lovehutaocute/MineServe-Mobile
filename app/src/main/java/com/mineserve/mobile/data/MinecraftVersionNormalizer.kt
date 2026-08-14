package com.mineserve.mobile.data

/** Keeps known malformed Minecraft version input from becoming a different protocol version. */
object MinecraftVersionNormalizer {
    fun forCore(core: ServerCore, version: String): String {
        val value = version.trim()
        return if (core == ServerCore.NeoForge && value == "1.21.11") "1.21.1" else value
    }
}
