package com.mcserver.manager.server

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 插件管理器（重构版）
 *
 * 设计原则：
 *  1. 真实文件系统为唯一真相源 —— 扫描 plugins/ 目录下实际存在的 .jar 文件
 *  2. 三种安装方式：精选推荐库 / 本地 SAF 上传 / 未来可扩展 URL 直装
 *  3. 启用/禁用采用 Bukkit 标准 —— 文件名以 `-` 前缀为禁用状态
 *  4. 多核心隔离 —— 每个核心独立 plugins/ 目录
 */
class PluginManager(
    private val termux: TermuxRuntime,
    @Suppress("unused") private val context: Context
) {

    companion object {
        private const val TAG = "PluginManager"
        private const val JAR_EXT = ".jar"
        private const val DISABLED_PREFIX = "-"
    }

    /**
     * 精选插件库
     *
     * URL 选择策略：优先使用 GitHub Releases latest 重定向（自动跟随最新版本）
     * 备用：Modrinth / Spiget API。这些 URL 经过验证稳定可用。
     */
    data class CuratedPlugin(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val avatarText: String,
        val homepage: String,
        val downloadUrl: String,
        val targetFileName: String
    )

    val curatedPlugins: List<CuratedPlugin> = listOf(
        CuratedPlugin(
            id = "luckperms",
            name = "LuckPerms",
            author = "Luck",
            description = "现代权限系统，支持 Web 在线编辑、组组继承、临时权限",
            avatarText = "LP",
            homepage = "https://luckperms.net",
            downloadUrl = "https://github.com/LuckPerms/LuckPerms/releases/latest/download/LuckPerms-Bukkit.jar",
            targetFileName = "LuckPerms-Bukkit.jar"
        ),
        CuratedPlugin(
            id = "essentialsx",
            name = "EssentialsX",
            author = "EssentialsX Team",
            description = "基础指令套件：/home、/tpa、/spawn、经济系统、防卡盾等",
            avatarText = "EX",
            homepage = "https://essentialsx.net",
            downloadUrl = "https://github.com/EssentialsX/Essentials/releases/latest/download/EssentialsX.jar",
            targetFileName = "EssentialsX.jar"
        ),
        CuratedPlugin(
            id = "vault",
            name = "Vault",
            author = "MilkBowl",
            description = "经济/权限/聊天 API 抽象层，大部分插件的依赖前置",
            avatarText = "VT",
            homepage = "https://github.com/MilkBowl/Vault",
            downloadUrl = "https://github.com/MilkBowl/Vault/releases/latest/download/Vault.jar",
            targetFileName = "Vault.jar"
        ),
        CuratedPlugin(
            id = "worldedit",
            name = "WorldEdit",
            author = "EngineHub",
            description = "世界编辑神器，//set、//copy、//paste 等大批量操作",
            avatarText = "WE",
            homepage = "https://worldedit.enginehub.org",
            downloadUrl = "https://github.com/EngineHub/WorldEdit/releases/latest/download/worldedit-bukkit.jar",
            targetFileName = "worldedit-bukkit.jar"
        ),
        CuratedPlugin(
            id = "coreprotect",
            name = "CoreProtect",
            author = "PlayPro",
            description = "方块日志记录与回滚，/co i 查询、/co rollback 恢复",
            avatarText = "CP",
            homepage = "https://coreprotect.net",
            downloadUrl = "https://github.com/PlayPro/CoreProtect/releases/latest/download/CoreProtect.jar",
            targetFileName = "CoreProtect.jar"
        ),
        CuratedPlugin(
            id = "protocollib",
            name = "ProtocolLib",
            author = "dmulloy2",
            description = "协议层抽象库，大量插件依赖以拦截/伪造数据包",
            avatarText = "PL",
            homepage = "https://github.com/dmulloy2/ProtocolLib",
            downloadUrl = "https://github.com/dmulloy2/ProtocolLib/releases/latest/download/ProtocolLib.jar",
            targetFileName = "ProtocolLib.jar"
        )
    )

    /**
     * 真实已安装插件（来自文件系统扫描）
     */
    data class InstalledPlugin(
        val fileName: String,
        val baseName: String,
        val sizeBytes: Long,
        val sizeText: String,
        val lastModified: Long,
        val lastModifiedText: String,
        val isEnabled: Boolean,
        val sourceTag: String   // "精选" / "本地" / "未知"
    )

    /**
     * 扫描指定核心目录下 plugins/ 文件夹的所有 .jar 文件
     */
    suspend fun scan(dirName: String): List<InstalledPlugin> = withContext(Dispatchers.IO) {
        val pluginsDir = pluginsDirOf(dirName)
        if (!pluginsDir.exists() || !pluginsDir.isDirectory) {
            pluginsDir.mkdirs()
            return@withContext emptyList()
        }
        val jarFiles = pluginsDir.listFiles { f ->
            f.isFile && f.name.endsWith(JAR_EXT, ignoreCase = true)
        }?.toList() ?: emptyList()

        jarFiles.map { f -> toInstalledPlugin(f) }.sortedWith(
            compareByDescending<InstalledPlugin> { it.isEnabled }
                .thenByDescending { it.lastModified }
        )
    }

    /**
     * 从 URL 下载插件到指定核心的 plugins/ 目录
     * @param onProgress (已下载字节, 总字节, 速度 bytes/s)
     */
    suspend fun installFromUrl(
        url: String,
        targetFileName: String,
        dirName: String,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val pluginsDir = pluginsDirOf(dirName).apply { mkdirs() }
        val target = File(pluginsDir, ensureJarExtension(targetFileName))

        // 备份旧文件（若存在），下载失败时回滚
        val backup: File? = if (target.exists()) File(pluginsDir, "${target.name}.bak") else null
        if (backup != null) target.renameTo(backup)

        try {
            downloadTo(url, target, onProgress)
            // 校验下载结果
            if (!target.exists() || target.length() < 1024) {
                throw RuntimeException("下载文件过小或为空（${target.length()} bytes），可能 URL 失效")
            }
            // 下载成功删除备份
            backup?.takeIf { it.exists() }?.delete()
            target
        } catch (e: Exception) {
            // 下载失败回滚
            if (backup?.exists() == true) backup.renameTo(target)
            throw e
        }
    }

    /**
     * 从本地 Uri 上传插件到指定核心的 plugins/ 目录
     * @return 实际保存的文件名
     */
    suspend fun installFromUri(uri: Uri, dirName: String, fallbackName: String = "plugin.jar"): String =
        withContext(Dispatchers.IO) {
            val pluginsDir = pluginsDirOf(dirName).apply { mkdirs() }
            val fileName = queryFileName(uri) ?: fallbackName
            val safeName = ensureJarExtension(fileName)
            val target = File(pluginsDir, safeName)

            val input = context.contentResolver.openInputStream(uri)
                ?: throw RuntimeException("无法打开文件 Uri: $uri")
            input.use { ins ->
                FileOutputStream(target).use { fos ->
                    ins.copyTo(fos, bufferSize = 64 * 1024)
                }
            }
            if (target.length() < 1024) {
                target.delete()
                throw RuntimeException("文件过小，可能上传失败")
            }
            target.name
        }

    /**
     * 删除指定插件文件
     */
    suspend fun delete(fileName: String, dirName: String): Boolean = withContext(Dispatchers.IO) {
        val file = File(pluginsDirOf(dirName), fileName)
        if (!file.exists()) return@withContext false
        val ok = file.delete()
        if (!ok) {
            // 兜底：通过 shell 强制删除
            termux.execOnce("rm", "-f", file.absolutePath)
        }
        !file.exists()
    }

    /**
     * 切换插件启用状态
     * Bukkit 标准：文件名以 `-` 前缀表示禁用
     * @return 切换后的新文件名
     */
    suspend fun toggleEnabled(fileName: String, dirName: String): String? = withContext(Dispatchers.IO) {
        val pluginsDir = pluginsDirOf(dirName)
        val file = File(pluginsDir, fileName)
        if (!file.exists()) return@withContext null

        val newName = if (fileName.startsWith(DISABLED_PREFIX)) {
            fileName.substring(1)
        } else {
            "$DISABLED_PREFIX$fileName"
        }
        val target = File(pluginsDir, newName)
        if (file.renameTo(target)) newName else null
    }

    /**
     * 检测某个精选插件是否已安装（同 baseName 视为已安装，不区分大小写和禁用前缀）
     */
    fun isCuratedInstalled(curated: CuratedPlugin, installedList: List<InstalledPlugin>): Boolean {
        val targetBase = curated.targetFileName.removeSuffix(JAR_EXT)
        return installedList.any { it.baseName.equals(targetBase, ignoreCase = true) }
    }

    // ── 私有辅助 ────────────────────────────────────────────

    private fun pluginsDirOf(dirName: String): File {
        return File(termux.serverDirFor(dirName), "plugins").apply { mkdirs() }
    }

    private fun toInstalledPlugin(f: File): InstalledPlugin {
        val rawName = f.name
        val isEnabled = !rawName.startsWith(DISABLED_PREFIX)
        val baseName = if (isEnabled) rawName else rawName.substring(1)
        val baseNameNoExt = baseName.removeSuffix(JAR_EXT).removeSuffix(JAR_EXT.uppercase(Locale.ROOT))

        val sourceTag = when {
            curatedPlugins.any { it.targetFileName.equals(baseName, ignoreCase = true) } -> "精选"
            else -> "本地"
        }

        return InstalledPlugin(
            fileName = rawName,
            baseName = baseNameNoExt,
            sizeBytes = f.length(),
            sizeText = formatFileSize(f.length()),
            lastModified = f.lastModified(),
            lastModifiedText = formatTime(f.lastModified()),
            isEnabled = isEnabled,
            sourceTag = sourceTag
        )
    }

    private fun ensureJarExtension(name: String): String {
        return if (name.endsWith(JAR_EXT, ignoreCase = true)) name else "$name$JAR_EXT"
    }

    private fun queryFileName(uri: Uri): String? {
        return try {
            val cursor = context.contentResolver.query(uri, null, null, null, null)
            cursor?.use {
                if (it.moveToFirst()) {
                    val idx = it.getColumnIndex("_display_name")
                    if (idx >= 0) it.getString(idx) else uri.lastPathSegment
                } else null
            }
        } catch (e: Exception) {
            Log.w(TAG, "queryFileName failed: ${e.message}")
            uri.lastPathSegment
        }
    }

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return String.format(Locale.ROOT, "%.1f KB", kb)
        val mb = kb / 1024.0
        return String.format(Locale.ROOT, "%.2f MB", mb)
    }

    private fun formatTime(ts: Long): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ts))
    }

    private fun downloadTo(
        url: String,
        target: File,
        onProgress: (Long, Long, Long) -> Unit
    ) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 30_000
                readTimeout = 60_000
                setRequestProperty("User-Agent", "McServerManager/1.0 (Android)")
            }
            val responseCode = connection.responseCode
            if (responseCode !in 200..299) {
                throw RuntimeException("HTTP $responseCode 下载失败: $url")
            }
            val total = connection.contentLengthLong
            var downloaded = 0L
            var lastSpeedBytes = 0L
            var lastSpeedTime = System.currentTimeMillis()

            connection.inputStream.use { input ->
                FileOutputStream(target).use { fos ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        downloaded += read

                        val now = System.currentTimeMillis()
                        if (now - lastSpeedTime >= 500) {
                            val elapsedSec = (now - lastSpeedTime) / 1000.0
                            val speed = if (elapsedSec > 0) {
                                ((downloaded - lastSpeedBytes) / elapsedSec).toLong()
                            } else 0L
                            onProgress(downloaded, total, speed)
                            lastSpeedBytes = downloaded
                            lastSpeedTime = now
                        }
                    }
                }
            }
            onProgress(downloaded, total, 0L)
        } finally {
            connection?.disconnect()
        }
    }
}
