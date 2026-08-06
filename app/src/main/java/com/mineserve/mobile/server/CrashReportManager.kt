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
    fun captureCrash(exitCode: Int, wasRunningBefore: Boolean, dirName: String): String? {
        // 只在之前在运行且非正常退出时生成报告（正常 stop 命令退出码通常是 0）
        if (!wasRunningBefore || exitCode == 0) {
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
            sb.appendLine("====================================")
            sb.appendLine()

            // ── 最近日志（最后 200 行）──
            sb.appendLine("--- 最近日志 (最后 200 行) ---")
            val logFile = latestLogFile(dirName)
            val recentLines: List<String> = if (logFile.exists()) {
                try {
                    logFile.readLines().takeLast(200)
                } catch (e: Exception) {
                    listOf("(读取 latest.log 失败: ${e.message})")
                }
            } else {
                listOf("(latest.log 不存在)")
            }
            recentLines.forEach { sb.appendLine(it) }
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
