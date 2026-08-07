package com.mineserve.mobile.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 最新版本信息 */
data class UpdateInfo(
    val versionName: String,
    val downloadUrl: String,
    val notes: String,
    val publishedAt: String,
)

/** 更新检查结果 */
sealed interface UpdateCheckResult {
    /** 已是最新版本 */
    data object Latest : UpdateCheckResult
    /** 发现新版本 */
    data class Update(val info: UpdateInfo) : UpdateCheckResult
    /** 检查失败（网络等） */
    data class Error(val message: String) : UpdateCheckResult
}

@Serializable
private data class GitHubRelease(
    val tag_name: String = "",
    val body: String = "",
    val published_at: String = "",
    val assets: List<Asset> = emptyList(),
)

@Serializable
private data class Asset(
    val name: String = "",
    val browser_download_url: String = "",
)

/**
 * 从 GitHub Releases 检查最新版本。
 * 对比当前 BuildConfig.VERSION_NAME，返回可安装的新版本信息；无更新返回 null。
 */
object UpdateChecker {

    private const val REPO = "lovehutaocute/MineServe-Mobile"
    private const val RELEASE_API = "https://api.github.com/repos/$REPO/releases/latest"
    /** 当前 App 实际产出的安装包名（与 Release 资产对应） */
    private const val APK_NAME = "MineServeMobile-arm64-v8a-release.apk"

    private val json = Json { ignoreUnknownKeys = true }

    /** 检查是否有新版本。返回检查结果（已最新 / 有更新 / 失败）。 */
    suspend fun checkLatest(currentVersionName: String): UpdateCheckResult = withContext(Dispatchers.IO) {
        try {
            val conn = (URL(RELEASE_API).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("Accept", "application/vnd.github+json")
                setRequestProperty("User-Agent", "MineServeMobile")
            }
            val code = conn.responseCode
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            if (code !in 200..299) return@withContext UpdateCheckResult.Error("HTTP $code")

            val release = json.decodeFromString<GitHubRelease>(body)
            val asset = release.assets.firstOrNull { it.name == APK_NAME }
                ?: release.assets.firstOrNull { it.name.endsWith(".apk") }
                ?: return@withContext UpdateCheckResult.Error("未找到安装包资产")

            val newVersion = normalizeVersion(release.tag_name)
                ?: return@withContext UpdateCheckResult.Error("版本号解析失败")
            val current = normalizeVersion(currentVersionName)
                ?: return@withContext UpdateCheckResult.Latest
            if (compareVersions(newVersion, current) <= 0) return@withContext UpdateCheckResult.Latest

            UpdateCheckResult.Update(
                UpdateInfo(
                    versionName = newVersion.joinToString("."),
                    downloadUrl = asset.browser_download_url,
                    notes = release.body.trim(),
                    publishedAt = release.published_at,
                )
            )
        } catch (e: Exception) {
            UpdateCheckResult.Error(e.message ?: "网络错误")
        }
    }

    /** 把 "v1.2.3" / "1.2.3" 解析为可比较的版本号数组；解析失败返回 null */
    fun parseVersion(v: String): List<Int>? {
        val cleaned = v.trim().removePrefix("v").removePrefix("V")
        val parts = cleaned.split(".")
        val nums = parts.map { it.toIntOrNull() ?: return null }
        return nums
    }

    private fun normalizeVersion(raw: String): List<Int>? = parseVersion(raw)

    /** 版本数组比较：a > b 返回正数，相等返回 0 */
    fun compareVersions(a: List<Int>, b: List<Int>): Int {
        val len = maxOf(a.size, b.size)
        for (i in 0 until len) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x - y
        }
        return 0
    }
}
