package com.mineserve.mobile.server

import com.mineserve.mobile.runtime.TermuxRuntime
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃报告管理器：
 *  - captureCrash：MC 进程异常退出时收集最近日志，生成崩溃报告
 *  - listCrashReports / listNativeCrashReports：列出应用自建 / MC 原生崩溃报告
 *  - readCrashReport / deleteCrashReport / clearAllCrashReports：读取、删除、清空
 *
 * 报告目录说明：
 *  - 自建报告：home/crash-logs/crash_yyyyMMdd_HHmmss.txt（应用生成，含最近日志 + 原生报告）
 *  - MC 原生：home/servers/{dirName}/crash-reports/（Minecraft 服务端自动生成）
 */
class CrashReportManager(private val termux: TermuxRuntime) {

    /** 崩溃报告信息 */
    data class CrashReport(
        val fileName: String,
        val path: String,
        val sizeBytes: Long,
        val sizeText: String,
        val createdTime: Long,
        val createdText: String,
        val preview: String    // 前 500 字符预览
    )

    /** 自建崩溃报告目录（home/crash-logs） */
    private val crashLogsDir: File
        get() = File(termux.installer.rootDir, "home/crash-logs").apply { mkdirs() }

    /** MC 原生崩溃报告目录（home/servers/{dirName}/crash-reports） */
    private fun nativeCrashReportsDir(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName/crash-reports")

    /** MC latest.log 路径 */
    private fun latestLogFile(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName/logs/latest.log")

    /**
     * 收集崩溃日志。
     * 当 MC 进程异常退出时调用，收集最近的日志写入报告文件。
     * @param exitCode 进程退出码
     * @param wasRunningBefore 是否之前在运行（区分正常停止和崩溃）
     * @return 生成的报告路径，失败返回 null
     */
    fun captureCrash(exitCode: Int, wasRunningBefore: Boolean, dirName: String, allowCleanExit: Boolean = false): String? {
        // 只在之前在运行且非正常退出时生成报告（正常 stop 命令退出码通常是 0；
        // 启动后快速退出即使 exit=0 也按异常处理）
        if (!wasRunningBefore || (exitCode == 0 && !allowCleanExit)) {
            return null
        }

        val now = Date()
        val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(now)
        val reportFile = File(crashLogsDir, "crash_$ts.txt")

        return try {
            val sb = StringBuilder()
            // ── 头部 ──
            sb.appendLine("====================================")
            sb.appendLine("MineServeMobile 崩溃报告")
            sb.appendLine("时间: ${SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(now)}")
            sb.appendLine("退出码: $exitCode")
            sb.appendLine("进程状态: 异常退出")
            if (exitCode == 137) {
                sb.appendLine("说明: exit=137 通常表示进程被 SIGKILL 终止，可能是 Android 内存压力、系统后台限制或外部强制停止；请结合下方首个 Java 异常判断根因。")
            }
            sb.appendLine("====================================")
            sb.appendLine()

            // ── 最近日志 ──
            // Paper 的 Watchdog 转储一次就有几百行线程栈，单纯取"最后 N 行"会把
            // 最关键的「Server thread 卡在哪」整段截掉。这里改为：
            //   1) 先按关键词提取重点段落（Watchdog / Server thread / 异常栈等）完整保留
            //   2) 再补上最后 N 行作为上下文
            //   3) 两者去重后按原顺序输出
            val logFile = latestLogFile(dirName)
            val allLines: List<String> = if (logFile.exists()) {
                try {
                    logFile.readLines()
                } catch (e: Exception) {
                    listOf("(读取 latest.log 失败: ${e.message})")
                }
            } else {
                listOf("(latest.log 不存在)")
            }

            val highlights = extractHighlights(allLines)
            if (highlights.isNotEmpty()) {
                sb.appendLine("--- 关键片段 (Watchdog / 异常栈 / 致命错误) ---")
                highlights.forEach { sb.appendLine(it) }
                sb.appendLine()
            }

            val tailCount = 800
            sb.appendLine("--- 最近日志 (最后 ${minOf(tailCount, allLines.size)} 行，共 ${allLines.size} 行) ---")
            allLines.takeLast(tailCount).forEach { sb.appendLine(it) }
            sb.appendLine()

            // ── MC 原生崩溃报告 ──
            sb.appendLine("--- MC 原生崩溃报告 ---")
            val nativeLatest = findLatestNativeCrashReport(dirName)
            if (nativeLatest != null) {
                sb.appendLine("(来源: ${nativeLatest.name})")
                try {
                    sb.appendLine(nativeLatest.readText())
                } catch (e: Exception) {
                    sb.appendLine("(读取原生崩溃报告失败: ${e.message})")
                }
            } else {
                sb.appendLine("(无 MC 原生崩溃报告)")
            }

            reportFile.writeText(sb.toString())
            reportFile.absolutePath
        } catch (e: Exception) {
            null
        }
    }

    /** 列出所有崩溃报告，按时间倒序 */
    fun listCrashReports(): List<CrashReport> {
        return listReportsFrom(crashLogsDir, isNative = false)
    }

    /** 读取完整崩溃报告内容 */
    fun readCrashReport(fileName: String): String? {
        val file = File(crashLogsDir, fileName)
        return if (file.exists() && file.isFile) {
            try { file.readText() } catch (e: Exception) { null }
        } else {
            null
        }
    }

    /** 删除崩溃报告 */
    fun deleteCrashReport(fileName: String): Boolean {
        val file = File(crashLogsDir, fileName)
        return if (file.exists() && file.isFile) {
            file.delete()
        } else {
            false
        }
    }

    /** 清空所有崩溃报告 */
    fun clearAllCrashReports(): Int {
        val dir = crashLogsDir
        if (!dir.exists() || !dir.isDirectory) return 0
        var count = 0
        dir.listFiles()?.forEach { f ->
            if (f.isFile && f.delete()) count++
        }
        return count
    }

    /** 读取 MC 原生 crash-reports 目录下的报告（MC 自动生成的） */
    fun listNativeCrashReports(dirName: String): List<CrashReport> {
        return listReportsFrom(nativeCrashReportsDir(dirName), isNative = true)
    }

    // ── 内部工具 ──

    /**
     * 从完整日志中提取「关键片段」，供崩溃报告优先展示。
     *
     * 设计目标：Paper Watchdog 转储的线程栈长达数百行，而报告体积有限。
     * 若只截取尾部，最关键的 `Current Thread: Server thread` 段落会被丢掉，
     * 导致无法定位主线程卡在何处。这里按块提取：
     *
     *  - 命中 [KEY_MARKERS] 的行作为起点，向后吞掉随后的缩进栈行（以 tab/空格开头）
     *  - 命中 [SEVERE_MARKERS] 的行单独保留（一行一事，无后继栈）
     *  - `Caused by:` 行向前回溯，尽量带上所属异常栈
     *
     * 输出保持原始顺序并去除重叠，最多 [MAX_HIGHLIGHT_LINES] 行，避免报告过大。
     */
    private fun extractHighlights(lines: List<String>): List<String> {
        if (lines.isEmpty()) return emptyList()

        val picked = sortedSetOf<Int>()

        lines.forEachIndexed { idx, line ->
            val isBlockStart = KEY_MARKERS.any { line.contains(it, ignoreCase = true) }
            if (isBlockStart) {
                // 块起点：本行 + 后续连续缩进的栈行
                picked.add(idx)
                var j = idx + 1
                while (j < lines.size && j - idx <= 60 && isStackLine(lines[j])) {
                    picked.add(j)
                    j++
                }
                return@forEachIndexed
            }
            if (SEVERE_MARKERS.any { line.contains(it, ignoreCase = true) }) {
                picked.add(idx)
            }
        }

        if (picked.isEmpty()) return emptyList()
        return picked.take(MAX_HIGHLIGHT_LINES).map { lines[it] }
    }

    /** 栈行/续行判定：以空白字符开头（Java 栈、at ... 等） */
    private fun isStackLine(line: String): Boolean =
        line.isNotEmpty() && (line[0] == '\t' || line[0] == ' ')

    /** 需要连同后续栈一起完整保留的标志行 */
    private val KEY_MARKERS = listOf(
        "Paper Watchdog Thread/ERROR",
        "The server has not responded",
        "Current Thread: Server thread",
        "Current Thread: \"Server thread\"",
        "Server thread dump",
        "Exception in thread",
        "Caused by:"
    )

    /** 单独成行的严重错误标志 */
    private val SEVERE_MARKERS = listOf(
        "OutOfMemoryError",
        "StackOverflowError",
        "NoSuchMethodError",
        "NoClassDefFoundError",
        "FATAL",
        "/WARN]: Failed to",
        "Could not",
        "Unable to"
    )

    private val MAX_HIGHLIGHT_LINES = 600

    /** 扫描指定目录下的报告文件，解析文件名时间戳并按时间倒序排列 */
    private fun listReportsFrom(dir: File, isNative: Boolean): List<CrashReport> {
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?: return emptyList()
        return files.map { f ->
            val (createdTime, createdText) = parseReportTime(f, isNative)
            CrashReport(
                fileName = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                sizeText = formatSize(f.length()),
                createdTime = createdTime,
                createdText = createdText,
                preview = readPreview(f)
            )
        }.sortedByDescending { it.createdTime }
    }

    /** 读取文件前 500 字符作为预览 */
    private fun readPreview(file: File): String {
        return try {
            val text = file.readText()
            if (text.length <= 500) text else text.substring(0, 500)
        } catch (e: Exception) {
            ""
        }
    }

    /** 解析报告文件名中的时间戳；解析失败回退到文件最后修改时间 */
    private fun parseReportTime(file: File, isNative: Boolean): Pair<Long, String> {
        val fallback = file.lastModified()
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val fallbackText = fmt.format(Date(fallback))
        return try {
            val timestamp = if (isNative) {
                parseNativeFilename(file.name) ?: fallback
            } else {
                parseOwnFilename(file.name) ?: fallback
            }
            if (timestamp == fallback) {
                Pair(fallback, fallbackText)
            } else {
                Pair(timestamp, fmt.format(Date(timestamp)))
            }
        } catch (e: Exception) {
            Pair(fallback, fallbackText)
        }
    }

    /** 解析自建报告文件名 crash_yyyyMMdd_HHmmss.txt */
    private fun parseOwnFilename(name: String): Long? {
        if (!name.startsWith("crash_") || !name.endsWith(".txt")) return null
        val core = name.removePrefix("crash_").removeSuffix(".txt") // yyyyMMdd_HHmmss
        return SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(core)?.time
    }

    /** 解析 MC 原生报告文件名 crash-yyyy-MM-dd_HH.mm.ss-server.txt */
    private fun parseNativeFilename(name: String): Long? {
        if (!name.startsWith("crash-") || !name.endsWith(".txt")) return null
        // 提取 yyyy-MM-dd_HH.mm.ss 部分（crash- 后到第一个 - 之前）
        val core = name.removePrefix("crash-").substringBefore("-")
        if (core.isEmpty()) return null
        return SimpleDateFormat("yyyy-MM-dd_HH.mm.ss", Locale.US).parse(core)?.time
    }

    /** 查找 MC 原生 crash-reports 目录下最新的报告文件 */
    private fun findLatestNativeCrashReport(dirName: String): File? {
        val dir = nativeCrashReportsDir(dirName)
        if (!dir.exists() || !dir.isDirectory) return null
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?: return null
        if (files.isEmpty()) return null
        // 按解析出的时间戳排序，解析失败回退到 lastModified
        return files.maxByOrNull { f -> parseNativeFilename(f.name) ?: f.lastModified() }
    }

    /** 格式化文件大小为 KB/MB */
    private fun formatSize(bytes: Long): String {
        return when {
            bytes >= 1024 * 1024 -> String.format(Locale.US, "%.2f MB", bytes / (1024.0 * 1024.0))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }
}
