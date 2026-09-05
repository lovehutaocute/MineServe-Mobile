package com.mineserve.mobile.mcp

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile

/**
 * MCP 文件工具的纯 JVM 辅助函数（可单测）：
 *  - 沙盒路径解析：任何读写都限制在服务器目录内
 *  - 高效日志尾部读取：从文件末尾按块回扫定位行边界，避免整文件加载
 */
object McpFileUtils {

    /**
     * 把相对路径解析到 [root] 内的绝对路径。
     * 越界（`..`、绝对路径、符号链接逃逸）抛 [IllegalArgumentException]。
     */
    fun sandboxedFile(root: File, relPath: String): File {
        val cleanRoot = root.canonicalFile
        val trimmed = relPath.trim().ifEmpty { "." }
        val target = File(cleanRoot, trimmed).canonicalFile
        if (target != cleanRoot && !target.path.startsWith(cleanRoot.path + File.separator)) {
            throw IllegalArgumentException("Path escapes the server directory: $relPath")
        }
        return target
    }

    /**
     * 读取文件末尾最多 [maxLines] 行（UTF-8）。
     * 从文件末尾按 8KB 块回扫定位行边界，只读需要的部分；
     * 超过 [maxBytes] 时进一步裁剪，此时会丢弃被截断的首行。
     */
    fun tailLines(file: File, maxLines: Int, maxBytes: Long = 2L * 1024 * 1024): List<String> {
        if (maxLines <= 0 || !file.isFile || file.length() == 0L) return emptyList()
        RandomAccessFile(file, "r").use { raf ->
            val size = raf.length()
            var pos = size
            var newlines = 0
            var start = 0L
            var found = false
            val buf = ByteArray(8192)
            while (pos > 0 && !found) {
                val read = minOf(buf.size.toLong(), pos).toInt()
                pos -= read
                raf.seek(pos)
                raf.readFully(buf, 0, read)
                for (i in read - 1 downTo 0) {
                    if (buf[i] == '\n'.code.toByte()) {
                        newlines++
                        if (newlines > maxLines) {
                            start = pos + i + 1
                            found = true
                            break
                        }
                    }
                }
            }
            if (found) {
                // start 定位在某个 '\n' 之后：行边界天然完整；仅当字节上限进一步裁剪时才丢首行
                var dropFirst = false
                if (size - start > maxBytes) {
                    start = size - maxBytes
                    dropFirst = true
                }
                return readFrom(raf, start, size, dropFirst).takeLast(maxLines)
            }
            // 行数不足：整读（必要时按字节裁剪并丢弃被截断的首行）
            val clipped = (size - maxBytes).coerceAtLeast(0L)
            return readFrom(raf, clipped, size, dropFirstLine = clipped > 0L).takeLast(maxLines)
        }
    }

    private fun readFrom(raf: RandomAccessFile, from: Long, to: Long, dropFirstLine: Boolean): List<String> {
        if (to <= from) return emptyList()
        val out = ByteArray((to - from).toInt())
        raf.seek(from)
        raf.readFully(out)
        var lines = String(out, Charsets.UTF_8).split('\n')
        // 尾部换行会 split 出一个空串；截断起点可能落在多字节字符中间，首行一并丢弃
        if (lines.lastOrNull()?.isEmpty() == true) lines = lines.dropLast(1)
        if (dropFirstLine && lines.isNotEmpty()) lines = lines.drop(1)
        return lines.map { it.trimEnd('\r') }
    }

    /** 递归删除目录/文件，返回删除的条目数（不含失败的） */
    fun deleteRecursivelyCounted(file: File): Int {
        if (file.isDirectory) {
            var count = 0
            file.listFiles()?.forEach { count += deleteRecursivelyCounted(it) }
            return count + if (file.delete()) 1 else 0
        }
        return if (file.delete()) 1 else 0
    }

    /** 是否疑似二进制内容（前 8KB 出现 NUL 字节） */
    fun looksBinary(file: File): Boolean =
        file.inputStream().use { input ->
            val head = ByteArray(8192)
            val n = input.read(head)
            (0 until (n.coerceAtLeast(0))).any { head[it] == 0.toByte() }
        }
}
