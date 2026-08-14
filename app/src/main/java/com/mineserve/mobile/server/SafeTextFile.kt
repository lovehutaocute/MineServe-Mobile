package com.mineserve.mobile.server

import java.io.File
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.serialization.json.Json

/** Small server-root-bound text reader/writer used by the file editor. */
object SafeTextFile {
    const val MAX_BYTES = 1024 * 1024L
    private val extensions = setOf("properties", "yml", "yaml", "json", "txt", "log")

    data class Content(val file: File, val text: String)

    fun isSupported(file: File): Boolean = file.extension.lowercase() in extensions

    fun read(root: File, candidate: File): Content {
        val file = checkedFile(root, candidate)
        require(file.length() <= MAX_BYTES) { "文件超过 1 MiB，请使用导出后编辑" }
        val bytes = file.readBytes()
        require(!bytes.take(4096).contains(0)) { "二进制文件不能在文本编辑器中打开" }
        return Content(file, bytes.toString(Charset.forName("UTF-8")))
    }

    fun write(root: File, candidate: File, text: String) {
        val file = checkedFile(root, candidate)
        if (file.extension.equals("json", true)) Json.parseToJsonElement(text)
        val bytes = text.toByteArray(Charsets.UTF_8)
        require(bytes.size <= MAX_BYTES) { "保存内容超过 1 MiB 限制" }
        val temp = File(file.parentFile, ".${file.name}.mineserve.tmp")
        try {
            temp.writeBytes(bytes)
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: Exception) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { if (temp.exists()) temp.delete() }
    }

    private fun checkedFile(root: File, candidate: File): File {
        val safeRoot = root.canonicalFile
        val file = candidate.canonicalFile
        require(file.isFile && file.exists()) { "文件不存在或不是普通文件" }
        require(file.path.startsWith(safeRoot.path + File.separator)) { "文件不在服务器目录内" }
        require(!Files.isSymbolicLink(candidate.toPath())) { "不支持编辑符号链接" }
        return file
    }
}
