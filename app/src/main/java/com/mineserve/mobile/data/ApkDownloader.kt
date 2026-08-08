package com.mineserve.mobile.data

import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * APK 下载器（软件更新用）：多源自动重试 + 多线程分片下载。
 * - 每个候选源使用 MultiThreadDownloader（32 并发分片，受设置页「多线程下载」开关/线程数控制）
 * - 顺序尝试：GitHub 直连 → 多个第三方镜像，任一源成功即完成
 * - 单源快速失败（连接 8s/读 20s），挂梯子时直连判失败后立即切镜像，避免长时间卡死
 * - 全部失败抛异常（附带各源错误）
 */
object ApkDownloader {

    /** 镜像前缀（按顺序尝试，前面的优先） */
    private val MIRROR_PREFIXES = listOf(
        "https://gh-proxy.com/",
        "https://ghfast.top/",
        "https://mirror.ghproxy.com/",
        "https://gh-proxy.net/",
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

    /** 下载到缓存目录，回调进度（0f..1f）。多源自动重试 + 多线程，全部失败抛异常。 */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val errors = mutableListOf<String>()
            for (candidate in candidateUrls(url)) {
                try {
                    MultiThreadDownloader.download(
                        url = candidate,
                        target = target,
                        threads = DownloadPrefs.threadCount(),
                        enabled = DownloadPrefs.isEnabled(),
                        onProgress = { downloaded, total, _ ->
                            if (total > 0) onProgress(downloaded.toFloat() / total.toFloat())
                        },
                        onLog = { msg -> android.util.Log.i("ApkDownloader", msg) }
                    )
                    onProgress(1f)
                    return@withContext
                } catch (e: Exception) {
                    errors.add("${candidate.take(60)}: ${e.message}")
                    target.delete()
                }
            }
            throw RuntimeException("所有下载源均失败：\n" + errors.joinToString("\n"))
        }
}
