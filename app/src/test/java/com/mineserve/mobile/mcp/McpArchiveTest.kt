package com.mineserve.mobile.mcp

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.FileOutputStream
import java.util.zip.GZIPOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class McpArchiveTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun makeZip(target: File, entries: Map<String, String>) {
        ZipOutputStream(FileOutputStream(target)).use { zip ->
            entries.forEach { (name, content) ->
                if (name.endsWith("/")) {
                    zip.putNextEntry(ZipEntry(name)); zip.closeEntry()
                } else {
                    zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
                }
            }
        }
    }

    @Test
    fun extractsZipAndSkipsTraversalEntries() {
        val archive = tmp.newFile("save.zip")
        makeZip(
            archive, mapOf(
                "world/level.dat" to "A",
                "world/region/" to "",
                "world/region/r.0.0.mca" to "chunkdata",
                "../evil.txt" to "nope"
            )
        )
        val dest = tmp.newFolder("out")
        val stats = McpArchive.extract(archive, dest)
        assertEquals(2, stats.files)
        assertEquals(1, stats.dirs)
        assertEquals(1, stats.skipped)
        assertEquals("A", File(dest, "world/level.dat").readText())
        assertEquals("chunkdata", File(dest, "world/region/r.0.0.mca").readText())
        assertTrue(!File(dest.parentFile, "evil.txt").exists())
    }

    @Test
    fun extractsTarGz() {
        val archive = tmp.newFile("pack.tar.gz")
        TarArchiveOutputStream(GZIPOutputStream(FileOutputStream(archive))).use { tar ->
            val dir = TarArchiveEntry("mod/config/")
            tar.putArchiveEntry(dir)
            tar.closeArchiveEntry()
            val entry = TarArchiveEntry("mod/config/settings.yml")
            entry.size = 4
            tar.putArchiveEntry(entry)
            tar.write("test".toByteArray())
            tar.closeArchiveEntry()
        }
        val dest = tmp.newFolder("out2")
        val stats = McpArchive.extract(archive, dest)
        assertEquals(1, stats.files)
        assertEquals(1, stats.dirs)
        assertEquals("test", File(dest, "mod/config/settings.yml").readText())
    }

    @Test
    fun rejectsUnknownArchiveType() {
        val archive = tmp.newFile("data.rar").apply { writeText("junk") }
        val dest = tmp.newFolder("out3")
        var thrown: Exception? = null
        try { McpArchive.extract(archive, dest) } catch (e: Exception) { thrown = e }
        assertTrue(thrown is IllegalArgumentException)
    }
}
