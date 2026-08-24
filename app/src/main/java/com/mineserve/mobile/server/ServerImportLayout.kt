package com.mineserve.mobile.server

import java.util.Locale

/**
 * 压缩包导入布局判定（纯逻辑，无 Android 依赖，便于 JVM 单元测试）：
 *  - [computeStripPrefix]：计算需要剥离的顶层目录（规避「压缩包整体打包服务器文件夹」导致的多层嵌套）
 *  - [isJunkEntry]：macOS/系统垃圾条目
 *  - [strippedPath]：剥离顶层目录后的相对路径
 *  - [entryLooksLikeServerDir]：顶层目录下是否含有服务器特征文件/目录
 */
object ServerImportLayout {

    /**
     * 计算需要剥离的顶层目录：
     * 排除 macOS/系统垃圾条目后，若所有条目共享唯一顶层目录且该目录下有实际内容，
     * 则返回该目录名（解压时剥离），否则返回 null。
     * 多顶层项时：若恰有一个顶层目录像服务器根目录、其余顶层项只是零散文件（readme 等），仍剥离该目录。
     */
    fun computeStripPrefix(entryNames: List<String>): String? {
        val meaningful = entryNames.map { it.trim('/') }.filter { !isJunkEntry(it) }
        if (meaningful.isEmpty()) return null
        val topLevel = meaningful.map { it.substringBefore('/') }.distinct()

        // 单顶层目录：剥离它（压缩包直接把整个服务器文件夹打进去的常见情况）
        if (topLevel.size == 1) {
            val prefix = topLevel.first()
            if (prefix.isEmpty()) return null
            val hasContent = meaningful.any { it != prefix && it.removePrefix("$prefix/").isNotEmpty() }
            return if (hasContent) prefix else null
        }

        // 多顶层项：若恰有一个顶层目录像服务器根目录、其余顶层项只是零散文件，仍剥离该目录
        val serverRootCandidates = topLevel.filter { p ->
            meaningful.any { it.startsWith("$p/") } && entryLooksLikeServerDir(meaningful, p)
        }
        if (serverRootCandidates.size == 1) {
            val candidate = serverRootCandidates.first()
            val othersFlat = topLevel.filter { it != candidate }
                .all { p -> meaningful.none { it.startsWith("$p/") } }
            if (othersFlat) return candidate
        }
        return null
    }

    /** 顶层目录下是否含有服务器特征文件/目录（仅按条目名判断） */
    fun entryLooksLikeServerDir(entryNames: List<String>, prefix: String): Boolean {
        val under = entryNames.filter { it.startsWith("$prefix/") }
            .map { it.removePrefix("$prefix/").substringBefore('/') }
        return under.any { n ->
            val lower = n.lowercase(Locale.US)
            n == "world" || n == "world_nether" || n == "world_the_end" ||
                n == "plugins" || n == "mods" || n == "libraries" || n == "cache" ||
                n == ".fabric" || n == ".paper" ||
                lower == "server.jar" || lower == "eula.txt" || lower == "server.properties" ||
                lower == "version.json" || lower.endsWith(".jar")
        }
    }

    /** 系统垃圾条目：__MACOSX、.DS_Store、Thumbs.db、AppleDouble ._* 等 */
    fun isJunkEntry(entryName: String): Boolean {
        val n = entryName.trim('/')
        if (n.isEmpty()) return true
        if (n == "__MACOSX" || n.startsWith("__MACOSX/")) return true
        val base = n.substringAfterLast('/')
        return base == ".DS_Store" || base == "Thumbs.db" || base.startsWith("._")
    }

    /** 剥离顶层目录后的相对路径；纯垃圾/目录自身返回 null（跳过） */
    fun strippedPath(entryName: String, stripPrefix: String?): String? {
        val n = entryName.trim('/')
        if (n.isEmpty()) return null
        if (stripPrefix == null) return n
        if (n == stripPrefix) return null
        if (n.startsWith("$stripPrefix/")) return n.removePrefix("$stripPrefix/").ifEmpty { null }
        return n
    }

    /** SAF providers should return a display name, but never let it create nested targets. */
    fun importedJarFileName(displayName: String): String =
        displayName.substringAfterLast('/').substringAfterLast('\\').ifBlank { "server.jar" }
}

