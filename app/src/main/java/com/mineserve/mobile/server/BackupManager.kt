package com.mineserve.mobile.server

import com.mineserve.mobile.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream

/**
 * 备份管理器：列出 / 创建 / 恢复 / 删除世界快照。
 *
 * 快照存放于 /home/snapshots/world_yyyyMMdd_HHmmss.zip，
 * 世界目录位于 /home/servers/{dirName}/world/。
 *
 * 恢复流程：停止服务器 → 备份当前 world（重命名为 world.bak.<ts>）
 *           → 删除 world → 解压快照到 world 目录。
 */
class BackupManager(private val termux: TermuxRuntime) {

    /** 快照信息数据类 */
    data class SnapshotInfo(
        val name: String,           // 文件名（不含路径）
        val path: String,           // 完整路径
        val sizeBytes: Long,        // 文件大小
        val sizeText: String,       // 友好显示（如 "12.3 MB"）
        val createdTime: Long,      // 时间戳
        val createdText: String     // 友好显示（如 "2026-07-28 15:30"）
    )

    private val snapshotDir: File get() = File(termux.installer.rootDir, "home/snapshots")

    private fun worldDir(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName/world")

    /** 列出所有快照，按时间倒序（最新在前） */
    fun listSnapshots(): List<SnapshotInfo> {
        val dir = snapshotDir.apply { mkdirs() }
        val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".zip") }
            ?: return emptyList()
        val parser = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        return files.map { f ->
            val (ts, tsText) = parseSnapshotTime(f.name, parser)
            SnapshotInfo(
                name = f.name,
                path = f.absolutePath,
                sizeBytes = f.length(),
                sizeText = formatSize(f.length()),
                createdTime = ts,
                createdText = tsText
            )
        }.sortedByDescending { it.createdTime }
    }

    /** 从文件名 world_yyyyMMdd_HHmmss.zip 解析时间戳与友好文本 */
    private fun parseSnapshotTime(
        fileName: String,
        parser: SimpleDateFormat
    ): Pair<Long, String> {
        val base = fileName.removeSuffix(".zip")
        val prefix = "world_"
        if (!base.startsWith(prefix)) return 0L to "--"
        val tsStr = base.substring(prefix.length)
        return try {
            val date = parser.parse(tsStr) ?: return 0L to "--"
            val outFmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
            date.time to outFmt.format(date)
        } catch (e: Exception) {
            0L to "--"
        }
    }

    /** 创建快照（封装 TermuxRuntime.createSnapshot，返回路径或 null） */
    fun createSnapshot(dirName: String): String? = termux.createSnapshot(dirName = dirName)

    /**
     * 恢复快照：
     * 1. 若 MC 在运行，发送 stop 命令并轮询等待退出（最多 10s），超时则强制停止
     * 2. 备份当前 world 目录（重命名为 world.bak.<timestamp>）
     * 3. 删除 world 目录
     * 4. 解压快照到 world 目录
     */
    suspend fun restoreSnapshot(snapshotName: String, dirName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val snapshotFile = File(snapshotDir, snapshotName)
            if (!snapshotFile.exists() || !snapshotFile.isFile) return@withContext false

            // 1. 停止服务器（如果在运行）
            if (termux.isMcRunning()) {
                termux.sendCommand("stop")
                var waited = 0
                while (termux.isMcRunning() && waited < 10_000) {
                    delay(500)
                    waited += 500
                }
                // 超时仍未退出，强制停止
                if (termux.isMcRunning()) {
                    termux.stopMc()
                }
            }

            val serverDir = worldDir(dirName).parentFile
            val dims = listOf("world", "world_nether", "world_the_end")

            // 2. 备份当前三维度目录（重命名为 *.bak.<timestamp>）
            dims.forEach { dim ->
                val dimDir = File(serverDir, dim)
                if (dimDir.exists()) {
                    val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                    val backup = File(serverDir, "$dim.bak.$ts")
                    if (!dimDir.renameTo(backup)) {
                        // 重命名失败，直接递归删除
                        dimDir.deleteRecursively()
                    }
                }
            }

            // 3. 确保已删除（重命名成功后原目录不存在）
            dims.forEach { dim ->
                val dimDir = File(serverDir, dim)
                if (dimDir.exists()) dimDir.deleteRecursively()
            }

            // 4. 解压快照到 serverDir（zip 条目带 world/ 前缀，恢复三维度目录）
            extractZipToDir(snapshotFile, serverDir)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 删除快照文件 */
    fun deleteSnapshot(snapshotName: String): Boolean {
        val file = File(snapshotDir, snapshotName)
        return file.exists() && file.isFile && file.delete()
    }

    /**
     * 解压 zip 到目标目录。
     * 含 zip-slip 防护（参考 BootstrapInstaller.extractZipToDir）：
     * 校验每个条目的 canonical 路径必须以 destDir 的 canonical 路径为前缀，
     * 防止恶意 zip 通过 ../ 逃逸出目标目录。
     */
    private fun extractZipToDir(zipFile: File, destDir: File) {
        destDir.mkdirs()
        FileInputStream(zipFile).buffered().use { fis ->
            ZipInputStream(fis).use { zis ->
                var entry: ZipEntry? = zis.nextEntry
                while (entry != null) {
                    val entryName = entry.name.removePrefix("./")
                    val outFile = File(destDir, entryName)

                    // zip-slip / path traversal 防护
                    val canonicalDest = destDir.canonicalPath + File.separator
                    val canonicalEntry = outFile.canonicalPath
                    if (!canonicalEntry.startsWith(canonicalDest)) {
                        entry = zis.nextEntry
                        continue
                    }

                    if (entry.isDirectory) {
                        // 若路径上已有同名文件，先删除
                        if (outFile.exists() && !outFile.isDirectory) outFile.delete()
                        outFile.mkdirs()
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out -> zis.copyTo(out) }
                    }
                    entry = zis.nextEntry
                }
            }
        }
    }

    /** 格式化文件大小为 KB/MB/GB 友好文本 */
    private fun formatSize(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> String.format(Locale.US, "%.2f GB", bytes / gb)
            bytes >= mb -> String.format(Locale.US, "%.1f MB", bytes / mb)
            bytes >= kb -> String.format(Locale.US, "%.1f KB", bytes / kb)
            else -> "$bytes B"
        }
    }
}
