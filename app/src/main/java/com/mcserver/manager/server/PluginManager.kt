package com.mcserver.manager.server

import android.content.Context
import android.net.Uri
import android.util.Log
import com.mcserver.manager.runtime.TermuxRuntime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.jar.JarFile

/**
 * 插件管理器（重构版）
 *
 * 设计原则：
 *  1. 真实文件系统为唯一真相源 —— 扫描 plugins/ 目录下实际存在的 .jar 文件
 *  2. 三种安装方式：精选推荐库 / 本地 SAF 上传 / 自定义 URL 直装
 *  3. 启用/禁用采用 Bukkit 标准 —— 文件名以 `-` 前缀为禁用状态
 *  4. 多核心隔离 —— 每个核心独立 plugins/ 目录
 *  5. plugin.yml 元信息解析带 lastModified 缓存，文件未变直接复用
 */
class PluginManager(
    private val termux: TermuxRuntime,
    @Suppress("unused") private val context: Context
) {

    companion object {
        private const val TAG = "PluginManager"
        private const val JAR_EXT = ".jar"
        private const val DISABLED_PREFIX = "-"
        /** 模组禁用后缀（Fabric/Forge 官方约定：xxx.jar → xxx.jar.disabled） */
        private const val MOD_DISABLED_SUFFIX = ".jar.disabled"
        private const val UPDATE_CHECK_COOLDOWN_MS = 5 * 60 * 1000L  // 更新检测冷却 5 分钟
    }

    /**
     * 精选插件库
     *
     * URL 选择策略：优先使用 GitHub Releases latest 重定向（自动跟随最新版本）
     */
    data class CuratedPlugin(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val avatarText: String,
        val homepage: String,
        val downloadUrl: String,
        val targetFileName: String,
        /** GitHub 仓库全名（owner/repo），用于更新检测 API */
        val repo: String,
        /** asset 文件名包含模式；非空时下载前用 GitHub API 动态解析最新 asset 直链（如 ViaVersion） */
        val githubAssetPattern: String? = null
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
            targetFileName = "LuckPerms-Bukkit.jar",
            repo = "LuckPerms/LuckPerms",
            githubAssetPattern = "LuckPerms-Bukkit"
        ),
        CuratedPlugin(
            id = "essentialsx",
            name = "EssentialsX",
            author = "EssentialsX Team",
            description = "基础指令套件：/home、/tpa、/spawn、经济系统、防卡盾等",
            avatarText = "EX",
            homepage = "https://essentialsx.net",
            downloadUrl = "https://github.com/EssentialsX/Essentials/releases/latest/download/EssentialsX.jar",
            targetFileName = "EssentialsX.jar",
            repo = "EssentialsX/Essentials",
            githubAssetPattern = "EssentialsX-"
        ),
        CuratedPlugin(
            id = "vault",
            name = "Vault",
            author = "MilkBowl",
            description = "经济/权限/聊天 API 抽象层，大部分插件的依赖前置",
            avatarText = "VT",
            homepage = "https://github.com/MilkBowl/Vault",
            downloadUrl = "https://github.com/MilkBowl/Vault/releases/latest/download/Vault.jar",
            targetFileName = "Vault.jar",
            repo = "MilkBowl/Vault"
        ),
        CuratedPlugin(
            id = "worldedit",
            name = "WorldEdit",
            author = "EngineHub",
            description = "世界编辑神器，//set、//copy、//paste 等大批量操作",
            avatarText = "WE",
            homepage = "https://worldedit.enginehub.org",
            downloadUrl = "https://github.com/EngineHub/WorldEdit/releases/latest/download/worldedit-bukkit.jar",
            targetFileName = "worldedit-bukkit.jar",
            repo = "EngineHub/WorldEdit",
            githubAssetPattern = "worldedit-bukkit"
        ),
        CuratedPlugin(
            id = "coreprotect",
            name = "CoreProtect",
            author = "PlayPro",
            description = "方块日志记录与回滚，/co i 查询、/co rollback 恢复",
            avatarText = "CP",
            homepage = "https://coreprotect.net",
            downloadUrl = "https://github.com/PlayPro/CoreProtect/releases/latest/download/CoreProtect.jar",
            targetFileName = "CoreProtect.jar",
            repo = "PlayPro/CoreProtect",
            githubAssetPattern = "CoreProtect-"
        ),
        CuratedPlugin(
            id = "protocollib",
            name = "ProtocolLib",
            author = "dmulloy2",
            description = "协议层抽象库，大量插件依赖以拦截/伪造数据包",
            avatarText = "PL",
            homepage = "https://github.com/dmulloy2/ProtocolLib",
            downloadUrl = "https://github.com/dmulloy2/ProtocolLib/releases/latest/download/ProtocolLib.jar",
            targetFileName = "ProtocolLib.jar",
            repo = "dmulloy2/ProtocolLib",
            githubAssetPattern = "ProtocolLib"
        ),
        CuratedPlugin(
            id = "viaversion",
            name = "ViaVersion",
            author = "ViaVersion Team",
            description = "跨版本协议支持，让低版本客户端连接高版本服务器",
            avatarText = "VV",
            homepage = "https://github.com/ViaVersion/ViaVersion",
            downloadUrl = "https://github.com/ViaVersion/ViaVersion/releases/latest",
            targetFileName = "ViaVersion.jar",
            repo = "ViaVersion/ViaVersion",
            githubAssetPattern = "ViaVersion"
        ),
        CuratedPlugin(
            id = "viabackwards",
            name = "ViaBackwards",
            author = "ViaVersion Team",
            description = "反向协议支持，让高版本客户端连接低版本服务器（需配合 ViaVersion）",
            avatarText = "VB",
            homepage = "https://github.com/ViaVersion/ViaBackwards",
            downloadUrl = "https://github.com/ViaVersion/ViaBackwards/releases/latest",
            targetFileName = "ViaBackwards.jar",
            repo = "ViaVersion/ViaBackwards",
            githubAssetPattern = "ViaBackwards"
        )
    )

    /**
     * 从 plugin.yml 解析出的插件元信息
     * 兼容 Bukkit/Spigot/Paper 的 plugin.yml 和 paper-plugin.yml
     */
    data class PluginMeta(
        val name: String,
        val version: String,
        val mainClass: String,
        val author: String,
        val description: String,
        val apiVersion: String,
        val depends: List<String>,
        val softDepends: List<String>
    )

    /**
     * 真实已安装插件（来自文件系统扫描 + plugin.yml 解析）
     */
    data class InstalledPlugin(
        val fileName: String,
        val baseName: String,
        val sizeBytes: Long,
        val sizeText: String,
        val lastModified: Long,
        val lastModifiedText: String,
        val isEnabled: Boolean,
        val sourceTag: String,          // "精选" / "本地"
        val meta: PluginMeta?           // plugin.yml 解析结果，可能为空（非插件 jar）
    )

    /**
     * 精选插件的更新检测结果
     */
    data class CuratedUpdateInfo(
        val curated: CuratedPlugin,
        val latestVersion: String,      // GitHub Releases 最新 tag
        val latestReleaseUrl: String,   // GitHub Releases 页面
        val installedVersion: String?,  // 已安装版本（从 plugin.yml 解析），null 表示未安装
        val hasUpdate: Boolean          // 是否有更新
    )

    // ── plugin.yml 解析缓存：key = jar.absolutePath，value = (lastModified, meta) ──
    private val metaCache = ConcurrentHashMap<String, Pair<Long, PluginMeta?>>()

    // ── 更新检测缓存：key = curated.id，value = (检测时间戳, info) ──
    private val updateCache = ConcurrentHashMap<String, Pair<Long, CuratedUpdateInfo>>()

    /**
     * 扫描指定核心目录下 plugins/ 文件夹的所有 .jar 文件
     * 并行解析 plugin.yml 以加速
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

        // 并行解析每个 jar 的 plugin.yml
        val installed = jarFiles.map { f ->
            async { toInstalledPlugin(f) }
        }.map { it.await() }

        installed.sortedWith(
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
        // 清缓存（旧文件解析结果失效）
        metaCache.remove(target.absolutePath)

        try {
            downloadTo(url, target, onProgress)
            if (!target.exists() || target.length() < 1024) {
                throw RuntimeException("下载文件过小或为空（${target.length()} bytes），可能 URL 失效")
            }
            backup?.takeIf { it.exists() }?.delete()
            target
        } catch (e: Exception) {
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
     * @param alsoRemoveDataDir 是否同时删除插件数据目录（plugins/插件名/）
     */
    suspend fun delete(fileName: String, dirName: String, alsoRemoveDataDir: Boolean = false): Boolean =
        withContext(Dispatchers.IO) {
            val pluginsDir = pluginsDirOf(dirName)
            val file = File(pluginsDir, fileName)
            if (!file.exists()) return@withContext false

            // 先尝试解析元信息获取插件名（用于清理数据目录）
            val pluginName = if (alsoRemoveDataDir) {
                val installed = toInstalledPlugin(file)
                installed.meta?.name ?: installed.baseName
            } else null

            // 清缓存
            metaCache.remove(file.absolutePath)

            val ok = file.delete()
            if (!ok) {
                termux.execOnce("rm", "-f", file.absolutePath)
            }

            // 清理插件数据目录
            if (alsoRemoveDataDir && pluginName != null) {
                val dataDir = File(pluginsDir, pluginName)
                if (dataDir.exists() && dataDir.isDirectory) {
                    dataDir.deleteRecursively()
                }
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

        // 同步迁移缓存 key
        val oldCache = metaCache.remove(file.absolutePath)
        if (file.renameTo(target)) {
            if (oldCache != null) metaCache[target.absolutePath] = oldCache
            newName
        } else null
    }

    /**
     * 检测某个精选插件是否已安装（同 baseName 视为已安装，不区分大小写和禁用前缀）
     */
    fun isCuratedInstalled(curated: CuratedPlugin, installedList: List<InstalledPlugin>): Boolean {
        val targetBase = curated.targetFileName.removeSuffix(JAR_EXT)
        return installedList.any { it.baseName.equals(targetBase, ignoreCase = true) }
    }

    /**
     * 检测精选插件的更新状态
     * 5 分钟内复用缓存，避免频繁请求 GitHub API
     */
    suspend fun checkCuratedUpdates(installedList: List<InstalledPlugin>): List<CuratedUpdateInfo> =
        withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            // 找出需要重新检测的项（未缓存或已过期）
            val needFetch = curatedPlugins.filter { c ->
                val cached = updateCache[c.id]
                cached == null || now - cached.first > UPDATE_CHECK_COOLDOWN_MS
            }

            // 并行请求 GitHub API
            val freshInfos = needFetch.map { c ->
                async {
                    val installed = installedList.find {
                        it.baseName.equals(c.targetFileName.removeSuffix(JAR_EXT), ignoreCase = true)
                    }
                    val installedVersion = installed?.meta?.version
                    try {
                        val (latestVer, releaseUrl) = fetchLatestRelease(c.repo)
                        val hasUpdate = installedVersion != null && !installedVersion.equals(latestVer, ignoreCase = true)
                        CuratedUpdateInfo(c, latestVer, releaseUrl, installedVersion, hasUpdate)
                    } catch (e: Exception) {
                        Log.w(TAG, "checkUpdates failed for ${c.id}: ${e.message}")
                        CuratedUpdateInfo(c, "未知", c.homepage, installedVersion, false)
                    }
                }
            }.map { it.await() }

            // 写入缓存
            freshInfos.forEach { info ->
                updateCache[info.curated.id] = now to info
            }

            // 合并缓存与新检测
            curatedPlugins.map { c ->
                updateCache[c.id]?.second ?: run {
                    val installed = installedList.find {
                        it.baseName.equals(c.targetFileName.removeSuffix(JAR_EXT), ignoreCase = true)
                    }
                    CuratedUpdateInfo(c, "未知", c.homepage, installed?.meta?.version, false)
                }
            }
        }

    /**
     * 清空更新检测缓存（强制下次重新检测）
     */
    fun invalidateUpdateCache() {
        updateCache.clear()
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

        // 解析 plugin.yml（带缓存）
        val meta = readPluginMetaCached(f)

        return InstalledPlugin(
            fileName = rawName,
            baseName = baseNameNoExt,
            sizeBytes = f.length(),
            sizeText = formatFileSize(f.length()),
            lastModified = f.lastModified(),
            lastModifiedText = formatTime(f.lastModified()),
            isEnabled = isEnabled,
            sourceTag = sourceTag,
            meta = meta
        )
    }

    /**
     * 读取 jar 内的 plugin.yml / paper-plugin.yml，带 lastModified 缓存
     */
    private fun readPluginMetaCached(f: File): PluginMeta? {
        val lastMod = f.lastModified()
        val cached = metaCache[f.absolutePath]
        if (cached != null && cached.first == lastMod) {
            return cached.second
        }
        val meta = try {
            readPluginMeta(f)
        } catch (e: Exception) {
            Log.w(TAG, "readPluginMeta failed for ${f.name}: ${e.message}")
            null
        }
        metaCache[f.absolutePath] = lastMod to meta
        return meta
    }

    /**
     * 从 jar 内读取 plugin.yml 或 paper-plugin.yml
     * 支持 Bukkit/Spigot/Paper 三种格式
     */
    private fun readPluginMeta(f: File): PluginMeta? {
        JarFile(f).use { jar ->
            // 优先 paper-plugin.yml，其次 plugin.yml
            val entry = jar.getEntry("paper-plugin.yml") ?: jar.getEntry("plugin.yml") ?: return null
            val yamlText = jar.getInputStream(entry).bufferedReader().use { it.readText() }
            return parsePluginYaml(yamlText)
        }
    }

    /**
     * 解析 plugin.yml 文本
     * 简单行解析，兼容 Bukkit/Spigot/Paper 格式，避免引入完整 YAML 库
     * 仅提取顶层字段，不支持嵌套结构
     */
    private fun parsePluginYaml(text: String): PluginMeta? {
        // 收集顶层 key: value 对（缩进为 0 的行）
        val topFields = mutableMapOf<String, String>()
        var currentKey: String? = null
        var currentValue = StringBuilder()
        var inList = false

        fun flush() {
            if (currentKey != null) {
                val value = currentValue.toString().trim()
                if (value.isNotEmpty()) {
                    topFields[currentKey!!] = value
                }
                currentKey = null
                currentValue = StringBuilder()
            }
        }

        for (line in text.lines()) {
            if (line.isBlank() || line.trim().startsWith("#")) continue
            val indent = line.takeWhile { it == ' ' }.length
            val trimmed = line.trim()

            if (indent == 0 && trimmed.contains(":")) {
                // 顶层字段
                flush()
                val idx = trimmed.indexOf(":")
                currentKey = trimmed.substring(0, idx).trim()
                val rest = trimmed.substring(idx + 1).trim()
                if (rest.isNotEmpty()) {
                    currentValue.append(rest)
                    inList = false
                } else {
                    // 可能是多行列表（下一行检查）
                    inList = false
                }
            } else if (currentKey != null) {
                // 多行列表项
                if (trimmed.startsWith("- ") || trimmed.startsWith("-")) {
                    val item = trimmed.removePrefix("-").trim().trim('\'').trim('"')
                    if (currentValue.isNotEmpty()) currentValue.append(", ")
                    currentValue.append(item)
                    inList = true
                } else if (!inList) {
                    // 多行字符串值（换行续行）
                    currentValue.append(" ").append(trimmed)
                }
            }
        }
        flush()

        val name = topFields["name"] ?: return null
        val version = topFields["version"] ?: "未知"
        val mainClass = topFields["main"] ?: topFields["bootstrap"] ?: ""
        val author = topFields["author"] ?: topFields["authors"] ?: "未知"
        val description = topFields["description"] ?: ""
        val apiVersion = topFields["api-version"] ?: ""
        val depends = topFields["depend"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()
        val softDepends = topFields["softdepend"]?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        return PluginMeta(
            name = name,
            version = version,
            mainClass = mainClass,
            author = author,
            description = description,
            apiVersion = apiVersion,
            depends = depends,
            softDepends = softDepends
        )
    }

    /**
     * 调用 GitHub API 获取最新 Release 信息
     * @return (tag_name, html_url)
     */
    private fun fetchLatestRelease(repo: String): Pair<String, String> {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("User-Agent", "McServerManager/1.0 (Android)")
            setRequestProperty("Accept", "application/vnd.github+json")
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("GitHub API HTTP $code")
            }
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            // 简单 JSON 解析（避免引入完整 JSON 库）
            val tagRegex = """"tag_name"\s*:\s*"([^"]+)"""".toRegex()
            val urlRegex = """"html_url"\s*:\s*"([^"]+)"""".toRegex()
            val tag = tagRegex.find(body)?.groupValues?.get(1)
                ?: throw RuntimeException("未找到 tag_name 字段")
            val releaseUrl = urlRegex.find(body)?.groupValues?.get(1) ?: "https://github.com/$repo/releases"
            return tag to releaseUrl
        } finally {
            conn.disconnect()
        }
    }

    /**
     * 解析 GitHub 最新 release 中匹配指定名称模式的 .jar asset 下载直链。
     * 用于 asset 文件名带版本号的仓库（如 ViaVersion-4.x.x.jar，无固定 latest/download 文件名）。
     * 逐 asset 对象解析（name 在 browser_download_url 之前，按段配对），排除 -sources/-dev/-javadoc 包。
     * @param repo GitHub 仓库全名（owner/repo）
     * @param pattern asset 文件名包含的模式（如 "ViaVersion"）
     * @return 匹配的 browser_download_url；解析失败返回 null
     */
    fun resolveLatestAsset(repo: String, pattern: String): String? {
        return try {
            val url = URL("https://api.github.com/repos/$repo/releases/latest")
            val conn = (url.openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = true
                connectTimeout = 15_000
                readTimeout = 15_000
                setRequestProperty("User-Agent", "McServerManager/1.0 (Android)")
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) return null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                // 提取 assets 数组内容（简单正则解析，避免引入完整 JSON 库）
                val assetsBody = Regex("\"assets\"\\s*:\\s*\\[(.*?)\\]", RegexOption.DOT_MATCHES_ALL)
                    .find(body)?.groupValues?.get(1) ?: return null
                val nameRegex = Regex("\"name\"\\s*:\\s*\"([^\"]+)\"")
                val urlRegex = Regex("\"browser_download_url\"\\s*:\\s*\"([^\"]+)\"")
                val names = nameRegex.findAll(assetsBody)
                    .map { it.range.first to it.groupValues[1] }.toList()
                // GitHub API 每个 asset 对象内 name 位于 browser_download_url 之前：
                // 以 name 位置为界切段，段内取 browser_download_url 保证配对正确
                names.forEachIndexed { i, (pos, name) ->
                    val segEnd = if (i + 1 < names.size) names[i + 1].first else assetsBody.length
                    val segment = assetsBody.substring(pos, segEnd)
                    val assetUrl = urlRegex.find(segment)?.groupValues?.get(1) ?: return@forEachIndexed
                    val lower = name.lowercase()
                    if (lower.contains(pattern.lowercase()) &&
                        lower.endsWith(".jar") &&
                        !lower.contains("-sources") &&
                        !lower.contains("-dev") &&
                        !lower.contains("-javadoc")
                    ) {
                        return assetUrl
                    }
                }
                null
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            null
        }
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

    // ── 模组管理（Fabric/Forge 的 mods/ 目录） ─────────────────────

    /** 模组目录：home/servers/{dirName}/mods */
    fun modsDirOf(dirName: String): File =
        File(termux.installer.rootDir, "home/servers/$dirName/mods")

    /** 已安装模组条目（简化，不解析 jar 内元信息） */
    data class ModEntry(
        val fileName: String,
        val baseName: String,
        val sizeText: String,
        val isEnabled: Boolean
    )

    /** 读取 mods/ 目录下的模组列表（禁用文件为 .jar.disabled 后缀，Fabric/Forge 官方约定） */
    fun readMods(dirName: String): List<ModEntry> {
        val dir = modsDirOf(dirName)
        if (!dir.exists() || !dir.isDirectory) return emptyList()
        return dir.listFiles { f ->
            f.isFile && (f.name.endsWith(JAR_EXT, ignoreCase = true) || f.name.endsWith(MOD_DISABLED_SUFFIX, ignoreCase = true))
        }
            ?.sortedBy { it.name.lowercase() }
            ?.map { f ->
                val isEnabled = f.name.endsWith(JAR_EXT, ignoreCase = true)
                val baseName = if (isEnabled) f.name.removeSuffix(JAR_EXT)
                               else f.name.removeSuffix(MOD_DISABLED_SUFFIX)
                ModEntry(f.name, baseName, formatSize(f.length()), isEnabled)
            } ?: emptyList()
    }

    /** 切换模组启用状态（.jar ↔ .jar.disabled） */
    suspend fun toggleModEnabled(fileName: String, dirName: String): String? = withContext(Dispatchers.IO) {
        val dir = modsDirOf(dirName)
        val file = File(dir, fileName)
        if (!file.exists()) return@withContext null
        val newName = if (fileName.endsWith(MOD_DISABLED_SUFFIX, ignoreCase = true)) {
            fileName.removeSuffix(MOD_DISABLED_SUFFIX)
        } else {
            "$fileName.disabled"
        }
        return@withContext if (file.renameTo(File(dir, newName))) newName else null
    }

    /** 删除模组文件 */
    suspend fun deleteMod(fileName: String, dirName: String): Boolean = withContext(Dispatchers.IO) {
        val dir = modsDirOf(dirName)
        val file = File(dir, fileName)
        if (!file.exists()) return@withContext false
        file.delete() || run { termux.execOnce("rm", "-f", file.absolutePath); !file.exists() }
    }

    /** 从 URL 下载安装模组到 mods/ 目录 */
    suspend fun installModFromUrl(
        url: String,
        targetFileName: String,
        dirName: String,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> }
    ): File = withContext(Dispatchers.IO) {
        val dir = modsDirOf(dirName).apply { mkdirs() }
        val target = File(dir, ensureJarExtension(targetFileName))
        val backup: File? = if (target.exists()) File(dir, "${target.name}.bak") else null
        if (backup != null) target.renameTo(backup)
        try {
            downloadTo(url, target, onProgress)
            if (!target.exists() || target.length() < 1024) {
                throw RuntimeException("下载文件过小或为空（${target.length()} bytes），可能 URL 失效")
            }
            backup?.takeIf { it.exists() }?.delete()
            target
        } catch (e: Exception) {
            if (backup?.exists() == true) backup.renameTo(target)
            throw e
        }
    }

    /** 从本地 Uri 上传模组到 mods/ 目录 */
    suspend fun installModFromUri(uri: Uri, dirName: String, fallbackName: String = "mod.jar"): String =
        withContext(Dispatchers.IO) {
            val dir = modsDirOf(dirName).apply { mkdirs() }
            val safeName = ensureJarExtension(queryFileName(uri) ?: fallbackName)
            val target = File(dir, safeName)
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

    // ── 精选模组（GitHub 动态解析最新版） ─────────────────────────

    data class CuratedMod(
        val id: String,
        val name: String,
        val author: String,
        val description: String,
        val avatarText: String,
        val homepage: String,
        val targetFileName: String,
        val repo: String,
        val githubAssetPattern: String
    )

    val curatedMods: List<CuratedMod> = listOf(
        CuratedMod("fabric-api", "Fabric API", "FabricMC",
            "Fabric 模组基础 API，绝大多数 Fabric 模组的前置依赖",
            "FA", "https://github.com/FabricMC/fabric", "fabric-api.jar",
            "FabricMC/fabric", "fabric-api"),
        CuratedMod("sodium", "Sodium", "CaffeineMC",
            "高性能渲染优化模组，大幅提升帧率（Fabric）",
            "SD", "https://github.com/CaffeineMC/sodium", "sodium.jar",
            "CaffeineMC/sodium", "sodium-fabric"),
        CuratedMod("lithium", "Lithium", "CaffeineMC",
            "通用游戏逻辑优化模组，降低服务端/客户端卡顿（Fabric）",
            "LI", "https://github.com/CaffeineMC/lithium", "lithium.jar",
            "CaffeineMC/lithium", "lithium-fabric"),
        CuratedMod("modmenu", "Mod Menu", "TerraformersMC",
            "模组列表界面，可视化查看/配置已安装模组（Fabric）",
            "MM", "https://github.com/TerraformersMC/ModMenu", "modmenu.jar",
            "TerraformersMC/ModMenu", "ModMenu")
    )

    /** 当前核心的 mods 目录路径 */
    fun currentModsPath(dirName: String): String = modsDirOf(dirName).absolutePath

    private fun formatSize(bytes: Long): String = when {
        bytes >= 1024 * 1024 -> String.format("%.1f MB", bytes / (1024.0 * 1024.0))
        bytes >= 1024 -> String.format("%.1f KB", bytes / 1024.0)
        else -> "$bytes B"
    }
}
