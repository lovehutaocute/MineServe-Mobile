package com.mineserve.mobile.server

import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File

/** Minimal pnx.yml scalar editor that preserves comments and unknown YAML. */
class PowerNukkitXConfigManager(private val termux: TermuxRuntime) {
    private val paths = linkedMapOf(
        "server-port" to "settings.port", "max-players" to "settings.maxPlayers",
        "motd" to "settings.motd", "level-name" to "settings.defaultLevelName",
        "white-list" to "settings.allowList", "online-mode" to "settings.xboxAuth",
        "difficulty" to "gameplay-settings.difficulty", "gamemode" to "gameplay-settings.gamemode",
        "force-gamemode" to "gameplay-settings.forceGamemode", "pvp" to "gameplay-settings.pvp",
        "view-distance" to "gameplay-settings.viewDistance", "spawn-protection" to "gameplay-settings.spawnProtection",
        "allow-nether" to "gameplay-settings.allowNether", "enable-command-block" to "gameplay-settings.enableCommandBlocks",
        "enable-status" to "network-settings.enableQuery", "base-tps" to "performance-settings.baseTps"
    )

    private fun file(dirName: String): File =
        File(termux.serverDirFor(dirName), PowerNukkitXLayout.pnxConfigFile)

    fun supportedKeys(): Set<String> = paths.keys

    fun read(dirName: String): Map<String, String> {
        val source = file(dirName)
        if (!source.isFile) return emptyMap()
        val result = linkedMapOf<String, String>()
        var section: String? = null
        source.forEachLine { raw ->
            val line = raw.trimEnd()
            if (line.isBlank() || line.trimStart().startsWith("#")) return@forEachLine
            if (!line.startsWith(" ") && !line.startsWith("\t") && line.endsWith(":")) {
                section = line.removeSuffix(":").trim()
                return@forEachLine
            }
            val match = Regex("^\\s{2}([A-Za-z0-9_-]+):\\s*(.*?)\\s*(?:#.*)?$").matchEntire(line)
                ?: return@forEachLine
            val currentSection = section ?: return@forEachLine
            val path = "$currentSection.${match.groupValues[1]}"
            val key = paths.entries.firstOrNull { it.value == path }?.key ?: return@forEachLine
            var value = match.groupValues[2].trim().trim('"', '\'')
            if (key == "difficulty") value = listOf("peaceful", "easy", "normal", "hard").getOrElse(value.toIntOrNull() ?: 1) { value }
            if (key == "gamemode") value = listOf("survival", "creative", "adventure", "spectator").getOrElse(value.toIntOrNull() ?: 0) { value }
            result[key] = value
        }
        return result
    }

    fun write(dirName: String, values: Map<String, String>): Boolean {
        val source = file(dirName)
        if (!source.isFile) return false
        return try {
            val lines = source.readLines().toMutableList()
            var section: String? = null
            for (i in lines.indices) {
                val raw = lines[i]
                val trimmed = raw.trimEnd()
                if (trimmed.isNotBlank() && !trimmed.startsWith(" ") &&
                    !trimmed.startsWith("\t") && trimmed.endsWith(":")) {
                    section = trimmed.removeSuffix(":").trim()
                    continue
                }
                val match = Regex("^(\\s{2})([A-Za-z0-9_-]+):(.*)$").matchEntire(raw) ?: continue
                val currentSection = section ?: continue
                val path = "$currentSection.${match.groupValues[2]}"
                val key = paths.entries.firstOrNull { it.value == path }?.key ?: continue
                var value = values[key] ?: continue
                if (key == "difficulty") value = listOf("peaceful", "easy", "normal", "hard").indexOf(value).coerceAtLeast(0).toString()
                if (key == "gamemode") value = listOf("survival", "creative", "adventure", "spectator").indexOf(value).coerceAtLeast(0).toString()
                val tail = match.groupValues[3]
                val comment = tail.substringAfter("#", "").let { if (it.isBlank()) "" else " #$it" }
                lines[i] = "${match.groupValues[1]}${match.groupValues[2]}: $value$comment"
            }
            source.writeText(lines.joinToString("\n") + "\n")
            true
        } catch (_: Exception) {
            false
        }
    }

    fun updatePort(dirName: String, port: Int): Boolean {
        val source = file(dirName)
        if (!source.isFile) return false
        return write(dirName, mapOf("server-port" to port.toString()))
    }
}
