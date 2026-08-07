package com.mineserve.mobile.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK 下载器：多源自动重试。
 * 顺序尝试：GitHub 直连 → 第三方镜像（gh-proxy / ghfast / ghproxy.net / moeyy），
 * 任一源成功即完成；全部失败抛异常（附带各源错误）。不依赖设备代理。
 */
object ApkDownloader {

    /** 镜像前缀（按顺序尝试，前面的优先） */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://ghproxy.net/",
        "https://github.moeyy.xyz/",
    )

    /** 生成候选下载地址：直连 + 各镜像 */
    private fun candidateUrls(url: String): List<String> {
        val list = mutableListOf(url)
        for (prefix in MIRROR_PREFIXES) {
            list.add(prefix + url)
        }
        return list
    }

    /** 下载到缓存目录，回调进度（0f..1f）。多源自动重试，全部失败抛异常。 */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            for (candidate in candidateUrls(url)) {
                try {
                    downloadSingle(candidate, target, onProgress)
                    onProgress(1f)
                    return@withContext
                } catch (e: Exception) {
                    errors.add("${candidate.take(60)}: ${e.message}")
                    target.delete()
                }
            }
            throw RuntimeException("所有下载源均失败：\n" + errors.joinToString("\n"))
        }

    private fun downloadSingle(url: String, target: File, onProgress: (Float) -> Unit) {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 12000
            readTimeout = 20000
            setRequestProperty("User-Agent", "MineServeMobile")
            setInstanceFollowRedirects(true)
        }
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code")
            }
            val total = conn.contentLengthLong
            conn.inputStream.use { input ->
                target.parentFile?.mkdirs()
                target.outputStream().use { output ->
                    val buf = ByteArray(64 * 1024)
                    var downloaded = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        output.write(buf, 0, n)
                        downloaded += n
                        if (total > 0) {
                            onProgress(downloaded.toFloat() / total.toFloat())
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }
    }
}
