package com.mineserve.mobile.data

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** APK 下载器：下载到缓存目录，回调下载进度（0f..1f），失败抛异常 */
object ApkDownloader {

    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15000
                readTimeout = 30000
                setRequestProperty("User-Agent", "MineServeMobile")
            }
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    throw RuntimeException("下载失败（HTTP $code）")
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
                onProgress(1f)
            } finally {
                conn.disconnect()
            }
        }
}
