package com.mineserve.mobile.mcp

import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipInputStream

/**
 * MCP 压缩包解压（纯 JVM，可单测）：
 * 支持 zip / tar / tar.gz(tgz) / tar.bz2(tbz2) / tar.xz(txz)。
 * 解压目标始终限制在指定目录内（canonical 路径校验），跳过符号链接等特殊 tar 条目。
 */
object McpArchive {

    data class Stats(val files: Int, val dirs: Int, val bytes: Long, val skipped: Int) {
        fun describe(): String {
            val parts = mutableListOf("Extracted $files files (${bytes / 1024} KB)")
            if (dirs > 0) parts.add("$dirs dirs")
            if (skipped > 0) parts.add("$skipped entries skipped (unsafe links or path traversal)")
            return parts.joinToString(", ")
        }
    }

    /** 解压 [archive] 到 [dest]（dest 必须已存在且为目录） */
    fun extract(archive: File, dest: File): Stats {
        require(dest.isDirectory) { "Destination must be an existing directory" }
        val name = archive.name.lowercase()
        return when {
            name.endsWith(".zip") -> extractZip(archive, dest)
            name.endsWith(".tar.gz") || name.endsWith(".tgz") ->
                extractTar(archive, dest) { BufferedInputStream(java.util.zip.GZIPInputStream(it)) }
            name.endsWith(".tar.bz2") || name.endsWith(".tbz2") ->
                extractTar(archive, dest) { BufferedInputStream(BZip2CompressorInputStream(it)) }
            name.endsWith(".tar.xz") || name.endsWith(".txz") ->
                extractTar(archive, dest) { BufferedInputStream(XZCompressorInputStream(it)) }
            name.endsWith(".tar") -> extractTar(archive, dest) { BufferedInputStream(it) }
            else -> throw IllegalArgumentException("Unsupported archive type (use .zip / .tar / .tar.gz / .tar.bz2 / .tar.xz)")
        }
    }

    /** 条目目标路径；越界返回 null。条目名统一归一化为 '/'（Windows 工具打包的 zip 用 '\' 分隔） */
    private fun safeTarget(dest: File, entryName: String): File? {
        val normalized = entryName.replace('\\', '/')
        val canonicalDest = dest.canonicalFile
        val target = File(canonicalDest, normalized).canonicalFile
        if (target == canonicalDest) return null
        return target.takeIf { it.path.startsWith(canonicalDest.path + File.separator) }
    }

    private fun extractZip(archive: File, dest: File): Stats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var skipped = 0
        ZipInputStream(BufferedInputStream(FileInputStream(archive))).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val target = safeTarget(dest, entry.name)
                if (target == null) {
                    skipped++
                    continue
                }
                if (entry.isDirectory) {
                    target.mkdirs()
                    dirs++
                } else {
                    target.parentFile?.mkdirs()
                    FileOutputStream(target).use { out -> zip.copyTo(out, 64 * 1024) }
                    bytes += target.length()
                    files++
                }
                zip.closeEntry()
            }
        }
        return Stats(files, dirs, bytes, skipped)
    }

    private fun extractTar(archive: File, dest: File, wrap: (FileInputStream) -> java.io.InputStream): Stats {
        var files = 0
        var dirs = 0
        var bytes = 0L
        var skipped = 0
        TarArchiveInputStream(wrap(FileInputStream(archive))).use { tar ->
            while (true) {
                val entry = tar.nextTarEntry ?: break
                val target = safeTarget(dest, entry.name)
                if (target == null) {
                    skipped++
                    continue
                }
                when {
                    entry.isDirectory -> {
                        target.mkdirs()
                        dirs++
                    }
                    entry.isSymbolicLink || entry.isLink || !entry.isFile -> skipped++ // 不落地链接等特殊条目
                    else -> {
                        target.parentFile?.mkdirs()
                        FileOutputStream(target).use { out -> tar.copyTo(out, 64 * 1024) }
                        bytes += target.length()
                        files++
                    }
                }
            }
        }
        return Stats(files, dirs, bytes, skipped)
    }
}
