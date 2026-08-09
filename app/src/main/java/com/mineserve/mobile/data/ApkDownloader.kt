package com.mineserve.mobile.data

import java.io.File
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext

/**
 * APK 下载器（软件更新用）：多源并发竞争 + 多线程分片下载。
 * - 每个候选源使用 MultiThreadDownloader（32 并发分片，受设置页「多线程下载」开关/线程数控制）
 * - **并发**尝试所有候选源（GitHub 直连 + 各镜像），先完成者胜，其余取消——
 *   速度取决于最快源，避免顺序尝试时被慢源/超时阻塞（进度条长时间为 0）
 * - 各源独立临时文件，成功后 rename 到目标，失败源自动清理
 * - 全部失败抛异常（附带各源错误）
 */
object ApkDownloader {

    /** 镜像前缀（并发尝试，任选其一成功即完成） */
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

    /**
     * 下载到 target，回调进度（0f..1f）。
     * 并发竞争所有候选源，先完成者胜；全部失败抛异常。
     */
    suspend fun download(url: String, target: File, onProgress: (Float) -> Unit = {}) =
        withContext(Dispatchers.IO) {
            val candidates = candidateUrls(url)
            val errors = java.util.Collections.synchronizedList(mutableListOf<String>())
            var winner: Pair<String, File>? = null

            supervisorScope {
                val jobs: List<Deferred<Pair<String, File>?>> = candidates.mapIndexed { idx, candidate ->
                    async {
                        val tmp = File(target.parentFile, "${target.name}.part$idx")
                        try {
                            tmp.delete()
                            MultiThreadDownloader.download(
                                url = candidate,
                                target = tmp,
                                threads = DownloadPrefs.threadCount(),
                                enabled = DownloadPrefs.isEnabled(),
                                onProgress = { d, t, _ ->
                                    if (t > 0) onProgress(d.toFloat() / t.toFloat())
                                },
                                onLog = { msg -> android.util.Log.i("ApkDownloader", "[$candidate] $msg") }
                            )
                            candidate to tmp
                        } catch (e: Exception) {
                            errors.add("${candidate.take(60)}: ${e.message}")
                            tmp.delete()
                            null
                        }
                    }
                }
                // 等待第一个成功完成的源（其余取消）
                while (true) {
                    val success = jobs.firstOrNull { job ->
                        job.isCompleted && runCatching { job.getCompleted() }.getOrNull() != null
                    }
                    if (success != null) {
                        val r = runCatching { success.getCompleted() }.getOrNull()
                        if (r != null) {
                            winner = r
                            jobs.forEach { it.cancel() }
                            break
                        }
                    }
                    if (jobs.all { it.isCompleted }) break
                    delay(100)
                }
            }

            if (winner != null) {
                target.parentFile?.mkdirs()
                target.delete()
                winner.second.renameTo(target)
                onProgress(1f)
            } else {
                throw RuntimeException("所有下载源均失败：\n" + errors.joinToString("\n"))
            }
        }
}
