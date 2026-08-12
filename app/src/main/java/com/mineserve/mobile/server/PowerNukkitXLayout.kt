package com.mineserve.mobile.server

import java.io.File
import java.util.jar.JarFile

/** Paths used by PowerNukkitX. Java server layouts remain unchanged. */
object PowerNukkitXLayout {
    const val worldsDirectory = "worlds"
    const val propertiesFile = "server.properties"
    val knownConfigFiles = listOf("server.properties", "nukkit.yml", "config.yml")

    fun isPowerNukkitX(dir: File): Boolean {
        val candidates = dir.listFiles()?.filter { it.isFile && it.extension.equals("jar", true) } ?: return false
        return candidates.any { jar ->
            runCatching {
                JarFile(jar).use { it.getEntry("org/powernukkitx/Server.class") != null }
            }.getOrDefault(false)
        }
    }

    fun worldDirectories(dir: File): List<File> = listOf(File(dir, worldsDirectory))

    fun configFiles(dir: File): List<File> = knownConfigFiles.map { File(dir, it) }
}
