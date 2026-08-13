package com.mineserve.mobile.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class AppRelease(val tag: String, val notes: String, val apkUrls: List<String>)

@Serializable private data class ReleaseDto(
    val tag_name: String = "",
    val body: String = "",
    val assets: List<AssetDto> = emptyList()
)
@Serializable private data class AssetDto(val name: String = "", val browser_download_url: String = "")

object AppUpdateService {
    private const val API = "https://api.github.com/repos/lovehutaocute/MineServe-Mobile/releases/latest"
    private const val APK_NAME = "MineServeMobile-arm64-v8a-release.apk"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun latest(currentVersion: String): AppRelease? = withContext(Dispatchers.IO) {
        val connection = URL(API).openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 12_000
            connection.readTimeout = 12_000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            connection.setRequestProperty("User-Agent", "MineServeMobile")
            if (connection.responseCode !in 200..299) {
                throw IllegalStateException("更新检查失败：HTTP ${connection.responseCode}")
            }
            val release = json.decodeFromString<ReleaseDto>(connection.inputStream.bufferedReader().use { it.readText() })
            val tag = release.tag_name.trim().removePrefix("v")
            val apk = release.assets.firstOrNull { it.name == APK_NAME }
                ?: release.assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) }
                ?: throw IllegalStateException("发行版未包含 ARM64 APK")
            if (compare(tag, currentVersion) <= 0) null
            else AppRelease(tag, release.body.trim(), downloadUrls(apk.browser_download_url))
        } finally {
            connection.disconnect()
        }
    }

    suspend fun download(urls: List<String>, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, "${target.name}.part")
        var lastError: Exception? = null
        for (url in urls.distinct()) {
            temp.delete()
            val connection = URL(url).openConnection() as HttpURLConnection
            try {
                connection.instanceFollowRedirects = true
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.setRequestProperty("User-Agent", "MineServeMobile")
                if (connection.responseCode !in 200..299) {
                    throw IllegalStateException("HTTP ${connection.responseCode}")
                }
                val total = connection.contentLengthLong
                connection.inputStream.use { input ->
                    temp.outputStream().use { output ->
                        val buffer = ByteArray(32 * 1024)
                        var downloaded = 0L
                        while (true) {
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (total > 0) onProgress(downloaded.toFloat() / total)
                        }
                    }
                }
                if (!isApk(temp)) throw IllegalStateException("下载内容不是有效 APK")
                if (!temp.renameTo(target)) throw IllegalStateException("无法保存更新安装包")
                onProgress(1f)
                return@withContext
            } catch (e: Exception) {
                lastError = e
            } finally {
                connection.disconnect()
            }
        }
        temp.delete()
        throw IllegalStateException("更新下载失败：${lastError?.message ?: "所有下载源均不可用"}")
    }

    private fun downloadUrls(url: String): List<String> = listOf(
        url,
        "https://ghfast.top/$url",
        "https://gh-proxy.com/$url",
        "https://mirror.ghproxy.com/$url"
    )

    private fun isApk(file: File): Boolean = file.length() > 256 * 1024 && file.inputStream().use {
        it.read() == 0x50 && it.read() == 0x4B
    }

    private fun compare(a: String, b: String): Int {
        val left = a.split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.removePrefix("v").split('.').map { it.toIntOrNull() ?: 0 }
        repeat(maxOf(left.size, right.size)) { index ->
            val delta = left.getOrElse(index) { 0 } - right.getOrElse(index) { 0 }
            if (delta != 0) return delta
        }
        return 0
    }
}
