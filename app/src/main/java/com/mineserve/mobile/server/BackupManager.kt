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

    // ── 外部备份（/storage/emulated/0/世界与服务器的备份/） ─────────────

    private fun serverDir(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName")

    private fun ts(): String = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    /** 打包目录列表到 zip（保留顶层目录名） */
    private fun zipDirs(dirs: List<File>, out: File): Boolean = try {
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { zos ->
            dirs.forEach { dir ->
                if (dir.isDirectory) {
                    dir.walkTopDown().forEach { f ->
                        val rel = f.relativeTo(dir)
                        if (f.isDirectory) {
                            zos.putNextEntry(java.util.zip.ZipEntry(dir.name + "/" + rel.path + "/"))
                            zos.closeEntry()
                        } else {
                            zos.putNextEntry(java.util.zip.ZipEntry(dir.name + "/" + rel.path))
                            FileInputStream(f).use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }
            }
        }
        out.exists() && out.length() > 0
    } catch (e: Exception) { out.delete(); false }

    /** 打包整个目录到 zip（排除 logs/ 与 session.lock） */
    private fun zipDirFiltered(dir: File, out: File): Boolean = try {
        java.util.zip.ZipOutputStream(FileOutputStream(out)).use { zos ->
            dir.walkTopDown().forEach { f ->
                val rel = f.relativeTo(dir)
                val relStr = rel.path.replace('\\', '/')
                if (relStr == "logs" || relStr.startsWith("logs/") || relStr == "session.lock") return@forEach
                if (f.isDirectory) {
                    zos.putNextEntry(java.util.zip.ZipEntry(relStr + "/"))
                    zos.closeEntry()
                } else {
                    zos.putNextEntry(java.util.zip.ZipEntry(relStr))
                    FileInputStream(f).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
        out.exists() && out.length() > 0
    } catch (e: Exception) { out.delete(); false }

    /** 外部备份整个世界（world + world_nether + world_the_end）→ world_{dir}_{ts}.zip */
    fun backupWorldToExternal(dirName: String): String? {
        if (!ExternalBackupStore.ensure()) return null
        val dir = serverDir(dirName)
        val worlds = listOf(
            File(dir, "world"), File(dir, "world_nether"), File(dir, "world_the_end")
        )
        if (!worlds.any { it.isDirectory }) return null
        val out = File(ExternalBackupStore.rootDir, "world_${dirName}_${ts()}.zip")
        return if (zipDirs(worlds, out)) out.absolutePath else null
    }

    /** 外部备份整个服务器目录（world + 核心 jar + 配置 + 插件，排除 logs/session.lock） */
    fun backupServerToExternal(dirName: String): String? {
        if (!ExternalBackupStore.ensure()) return null
        val dir = serverDir(dirName)
        if (!dir.isDirectory) return null
        val out = File(ExternalBackupStore.rootDir, "server_${dirName}_${ts()}.zip")
        return if (zipDirFiltered(dir, out)) out.absolutePath else null
    }

    /** 停止当前运行中的服务器（若在运行） */
    private suspend fun stopServerIfRunning() {
        if (termux.isMcRunning()) {
            termux.sendCommand("stop")
            var waited = 0
            while (termux.isMcRunning() && waited < 10_000) { delay(500); waited += 500 }
            if (termux.isMcRunning()) termux.stopMc()
        }
    }

    /** 从外部 zip 还原世界（zip 内 world/world_nether/world_the_end 前缀） */
    suspend fun restoreWorldFromExternal(file: File, dirName: String): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext false
            stopServerIfRunning()
            val serverDir = serverDir(dirName)
            // 备份旧 world
            listOf("world", "world_nether", "world_the_end").forEach { dim ->
                val d = File(serverDir, dim)
                if (d.exists()) {
                    val bak = File(serverDir, "$dim.bak.${ts()}")
                    if (!d.renameTo(bak)) d.deleteRecursively()
                }
            }
            extractZipToDir(file, serverDir)
            true
        } catch (e: Exception) { false }
    }

    /**
     * 从外部 zip 还原整个服务器（zip 内为服务器目录相对路径）。
     * @param overwrite true 时覆盖同名目录，false 时目标存在则失败（用于重名检测）
     */
    suspend fun restoreServerFromExternal(file: File, targetDirName: String, overwrite: Boolean): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!file.exists()) return@withContext false
            stopServerIfRunning()
            val target = serverDir(targetDirName)
            if (target.exists()) {
                if (!overwrite) return@withContext false
                target.deleteRecursively()
            }
            target.mkdirs()
            extractZipToDir(file, target)
            true
        } catch (e: Exception) { false }
    }

    /** 从文件名 server_{dir}_{ts}.zip 解析原始服务器目录名（解析失败返回 null） */
    fun parseServerDirFromZip(name: String): String? {
        val base = name.removeSuffix(".zip")
        // server_{dir}_{yyyyMMdd_HHmmss}
        val parts = base.split("_")
        if (parts.size < 3) return null
        return parts.drop(1).dropLast(1).joinToString("_").ifBlank { null }
    }

    /** 删除外部备份文件 */
    fun deleteExternalBackup(name: String): Boolean {
        val f = File(ExternalBackupStore.rootDir, name)
        return f.exists() && f.isFile && f.delete()
    }

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
