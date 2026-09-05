package com.mineserve.mobile.mcp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class McpFileUtilsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ── sandboxedFile ──────────────────────────────────────────

    @Test
    fun sandboxResolvesRelativePathInsideRoot() {
        val root = tmp.newFolder("server")
        val file = McpFileUtils.sandboxedFile(root, "config/server.properties")
        assertTrue(file.path.startsWith(root.canonicalPath))
        assertEquals("server.properties", file.name)
    }

    @Test
    fun sandboxEmptyPathResolvesToRoot() {
        val root = tmp.newFolder("server2")
        assertEquals(root.canonicalFile, McpFileUtils.sandboxedFile(root, ""))
    }

    @Test
    fun sandboxRejectsTraversal() {
        val root = tmp.newFolder("server3")
        assertThrows(IllegalArgumentException::class.java) {
            McpFileUtils.sandboxedFile(root, "../../etc/passwd")
        }
    }

    @Test
    fun sandboxRejectsDeepTraversal() {
        val root = tmp.newFolder("server4")
        assertThrows(IllegalArgumentException::class.java) {
            McpFileUtils.sandboxedFile(root, "worlds/../..")
        }
    }

    // ── tailLines ──────────────────────────────────────────────

    @Test
    fun tailReturnsLastNLines() {
        val file = tmp.newFile("latest.log").apply {
            writeText((1..1000).joinToString("\n") { "line $it" } + "\n")
        }
        val tail = McpFileUtils.tailLines(file, 10)
        assertEquals(10, tail.size)
        assertEquals("line 991", tail.first())
        assertEquals("line 1000", tail.last())
    }

    @Test
    fun tailKeepsBlankMiddleLinesButDropsTrailingNewline() {
        val file = tmp.newFile("blank.log").apply { writeText("a\n\nb\n") }
        val tail = McpFileUtils.tailLines(file, 100)
        assertEquals(listOf("a", "", "b"), tail)
    }

    @Test
    fun tailHandlesFileWithoutTrailingNewline() {
        val file = tmp.newFile("notrail.log").apply { writeText("x\ny\nz") }
        assertEquals(listOf("x", "y", "z"), McpFileUtils.tailLines(file, 10))
    }

    @Test
    fun tailRespectsMaxBytesAndDropsPartialFirstLine() {
        val file = tmp.newFile("big.log")
        val filler = "x".repeat(100)
        file.writeText((1..500).joinToString("\n") { "$filler $it" } + "\n")
        val tail = McpFileUtils.tailLines(file, 500, maxBytes = 1024)
        // 首行被字节裁剪截断，必须丢弃；其余行保持完整
        assertTrue(tail.all { it.startsWith("$filler ") || it == "$filler 500" })
        assertTrue(tail.size < 500)
    }

    @Test
    fun tailOfMissingOrEmptyFileIsEmpty() {
        assertEquals(emptyList<String>(), McpFileUtils.tailLines(tmp.newFile("none.log"), 10))
    }

    // ── deleteRecursivelyCounted ───────────────────────────────

    @Test
    fun deleteCountsNestedEntries() {
        val dir = tmp.newFolder("world").also { d ->
            tmp.newFolder("world", "region")
            java.io.File(d, "level.dat").writeText("data")
            java.io.File(d, "region/r.0.0.mca").writeText("chunk")
        }
        val removed = McpFileUtils.deleteRecursivelyCounted(dir)
        assertEquals(4, removed) // world + region + 2 files
        assertTrue(!dir.exists())
    }

    // ── looksBinary ────────────────────────────────────────────

    @Test
    fun binaryDetection() {
        val text = tmp.newFile("a.txt").apply { writeText("hello") }
        val bin = tmp.newFile("b.jar").apply { writeBytes(byteArrayOf(0x50, 0x4B, 0x00, 0x01)) }
        assertTrue(!McpFileUtils.looksBinary(text))
        assertTrue(McpFileUtils.looksBinary(bin))
    }
}
