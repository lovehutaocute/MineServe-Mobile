package com.mineserve.mobile.server

import com.mineserve.mobile.data.ServerCore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

class ServerCoreDetectorTest {

    @Test
    fun serverDirectoryNamesAreAsciiAndUniqueForChineseNames() {
        val chinese = McServerController.sanitizeDirName("服务器导出")
        val otherChinese = McServerController.sanitizeDirName("服务器备份")

        assertTrue(chinese.matches(Regex("[A-Za-z0-9_-]+")))
        assertNotEquals(chinese, otherChinese)
        assertEquals("paper-1_21_8", McServerController.sanitizeDirName("paper-1_21_8"))
        assertTrue(McServerController.sanitizeDirName("forge 1.21.8/%").matches(Regex("[A-Za-z0-9_-]+")))
    }

    private fun tempDir(name: String): File {
        val dir = java.nio.file.Files.createTempDirectory("mcs-detector-" + name).toFile()
        dir.deleteOnExit()
        return dir
    }

    private fun createJar(path: File, entries: Map<String, ByteArray>) {
        JarOutputStream(path.outputStream()).use { jos ->
            entries.forEach { (name, data) ->
                jos.putNextEntry(JarEntry(name))
                jos.write(data)
                jos.closeEntry()
            }
        }
    }

    private fun emptyJar(path: File) = createJar(path, emptyMap())

    private val manifest = "Manifest-Version: 1.0\r\nMain-Class: io.papermc.paperclip.Main\r\n\r\n".toByteArray()
    private fun versionJson(id: String) = "{\"id\": \"$id\", \"name\": \"$id\"}".toByteArray()

    @Test
    fun detectsPaperFromManifestMainAndEmbeddedVersion() {
        val dir = tempDir("paper")
        createJar(File(dir, "server.jar"), mapOf("META-INF/MANIFEST.MF" to manifest, "version.json" to versionJson("1.21.8")))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.Paper, result.core)
        assertEquals("1.21.8", result.version)
        assertEquals("server.jar", result.serverFile)
    }

    @Test
    fun detectsLeavesAndKeepsImportedJarName() {
        val dir = tempDir("leaves")
        createJar(File(dir, "leaves-1.21.8.jar"), mapOf("org/leavesmc/leaves/LeavesConfig.class" to ByteArray(0)))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.Leaves, result.core)
        assertEquals("leaves-1.21.8.jar", result.serverFile)
    }

    @Test
    fun detectsVanillaFromRootVersionJson() {
        val dir = tempDir("vanilla")
        emptyJar(File(dir, "server.jar"))
        File(dir, "version.json").writeText(versionJson("1.20.4").toString(Charsets.UTF_8))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.Vanilla, result.core)
        assertEquals("1.20.4", result.version)
    }

    @Test
    fun detectsPowerNukkitXWithGameVersionFromCache() {
        val dir = tempDir("pnx")
        createJar(File(dir, "powernukkitx.jar"), mapOf("cn/nukkit/Server.class" to ByteArray(0)))
        val cache = File(dir, "cache").apply { mkdirs() }
        emptyJar(File(cache, "mojang_26.2.jar"))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.PowerNukkitX, result.core)
        assertEquals("26.2", result.version)
    }

    @Test
    fun detectsModernPowerNukkitXEntryPoint() {
        val dir = tempDir("pnx-modern")
        val jar = File(dir, "server.jar")
        createJar(jar, mapOf("org/powernukkitx/Server.class" to ByteArray(0)))
        assertEquals("org.powernukkitx.Server", powerNukkitXMainClass(jar))
        assertEquals(ServerCore.PowerNukkitX, ServerCoreDetector.detect(dir).core)
    }

    @Test
    fun detectsVelocityFromFileName() {
        val dir = tempDir("velocity")
        emptyJar(File(dir, "velocity-3.4.0-SNAPSHOT.jar"))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.Velocity, result.core)
        assertEquals("3.4.0-SNAPSHOT", result.version)
    }

    @Test
    fun detectsBungeeCordFromManifest() {
        val dir = tempDir("bungee")
        val bungeeManifest = "Manifest-Version: 1.0\r\nMain-Class: net.md_5.bungee.BungeeCord\r\nImplementation-Version: 1.21-R0.1\r\n\r\n"
        createJar(File(dir, "server.jar"), mapOf("META-INF/MANIFEST.MF" to bungeeManifest.toByteArray()))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.BungeeCord, result.core)
        assertEquals("1.21-R0.1", result.version)
    }

    @Test
    fun detectsForgeFromLibrariesPath() {
        val dir = tempDir("forge")
        emptyJar(File(dir, "server.jar"))
        val lib = File(dir, "libraries/net/minecraftforge/forge/1.20.1-47.2.0").apply { mkdirs() }
        emptyJar(File(lib, "forge-1.20.1-47.2.0-server.jar"))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.Forge, result.core)
        assertEquals("1.20.1", result.version)
    }

    @Test
    fun detectsNeoForgeWithVersionMapping() {
        val dir = tempDir("neoforge")
        emptyJar(File(dir, "server.jar"))
        val lib = File(dir, "libraries/net/neoforged/neoforge/21.1.0").apply { mkdirs() }
        emptyJar(File(lib, "neoforge-21.1.0-universal.jar"))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.NeoForge, result.core)
        assertEquals("1.21.1", result.version)
    }

    @Test
    fun unknownCoreStillReadsVersion() {
        val dir = tempDir("unknown")
        emptyJar(File(dir, "something.jar"))
        File(dir, "version.json").writeText(versionJson("1.19.2").toString(Charsets.UTF_8))
        val result = ServerCoreDetector.detect(dir)
        assertNull(result.core)
        assertEquals("1.19.2", result.version)
        assertEquals("something.jar", result.serverFile)
    }

    @Test
    fun emptyDirReturnsNullCore() {
        val dir = tempDir("empty")
        val result = ServerCoreDetector.detect(dir)
        assertNull(result.core)
        assertNull(result.version)
        assertNull(result.serverFile)
    }

    @Test
    fun doesNotUseInstallerOrGuessBetweenUnknownJars() {
        val installerOnly = tempDir("installer-only")
        emptyJar(File(installerOnly, "forge-installer.jar"))
        assertNull(ServerCoreDetector.detect(installerOnly).serverFile)

        val multipleUnknown = tempDir("multiple-unknown")
        emptyJar(File(multipleUnknown, "custom-a.jar"))
        emptyJar(File(multipleUnknown, "custom-b.jar"))
        assertNull(ServerCoreDetector.detect(multipleUnknown).serverFile)
    }

    @Test
    fun detectsQuiltLauncher() {
        val dir = tempDir("quilt")
        emptyJar(File(dir, "quilt-server-launch.jar"))
        val result = ServerCoreDetector.detect(dir)
        assertEquals(ServerCore.Quilt, result.core)
    }

    @Test
    fun bundledBukkitCoreUsesItsNormalJarLaunch() {
        val dir = tempDir("bukkit-bundled")
        val core = File(dir, "craftbukkit.jar")
        createJar(core, mapOf(
            "org/bukkit/craftbukkit/Main.class" to ByteArray(0),
            "joptsimple/OptionException.class" to ByteArray(0)
        ))

        assertNull(bukkitLaunchArguments(dir, core))
    }

    @Test
    fun importedBukkitDirectoryUsesBundledLibrariesClasspath() {
        val dir = tempDir("bukkit-libraries")
        val core = File(dir, "craftbukkit.jar")
        createJar(core, mapOf("org/bukkit/craftbukkit/Main.class" to ByteArray(0)))
        val dependency = File(dir, "libraries/jopt-simple.jar").apply { parentFile!!.mkdirs() }
        createJar(dependency, mapOf("joptsimple/OptionException.class" to ByteArray(0)))

        val arguments = bukkitLaunchArguments(dir, core)
        assertTrue(arguments!!.contains("-cp"))
        assertTrue(arguments.contains(core.absolutePath))
        assertTrue(arguments.contains(dependency.absolutePath))
        assertTrue(arguments.endsWith("org.bukkit.craftbukkit.Main"))
    }

    @Test
    fun incompleteBukkitImportFailsBeforeStartingJava() {
        val dir = tempDir("bukkit-incomplete")
        val core = File(dir, "craftbukkit.jar")
        createJar(core, mapOf("org/bukkit/craftbukkit/Main.class" to ByteArray(0)))

        val error = runCatching { bukkitLaunchArguments(dir, core) }.exceptionOrNull()
        assertNotNull(error)
        assertTrue(error!!.message!!.contains("jopt-simple"))
    }
}
