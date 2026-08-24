package com.mineserve.mobile.data

import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap

internal data class DownloadSpeedSample(val bytes: Long, val timestampMs: Long)

/** EdgeCube-style recent-window rate; avoids startup spikes and long-term drag. */
internal fun speedBytesPerSecond(samples: MutableList<DownloadSpeedSample>, nowMs: Long): Long {
    val cutoff = nowMs - 3_000L
    samples.removeAll { it.timestampMs < cutoff }
    if (samples.size < 2) return 0L
    val first = samples.first()
    val last = samples.last()
    val elapsed = last.timestampMs - first.timestampMs
    val bytes = last.bytes - first.bytes
    return if (elapsed > 0L && bytes > 0L) bytes * 1_000L / elapsed else 0L
}

/**
 * 内置多线程下载模块（基于 HTTP Range 分段并行，开源思路：分块下载 + 合并）。
 *
 * 特性：
 *  - 探测 Content-Length 后按线程数切分 [start,end) 区间，每线程一个 Range 请求并行下载到 .part 文件
 *  - 全部片段就绪后顺序合并为最终文件，删除临时片段
 *  - 服务端不支持 Range / 未知大小 / 小文件时自动降级为单流下载
 *  - 统一的进度回调 (已下载, 总字节, 速度 bytes/s) 与日志回调，与各业务层签名一致
 *
 * 调用方通过 [DownloadPrefs] 控制开关与线程数；也可显式传参覆盖。
 */
object MultiThreadDownloader {

    private const val TAG = "MultiThreadDownloader"

    /** 小于该尺寸不启用多线程（收益低），走单流 */
    private const val MIN_MULTI_BYTES = 1 * 1024 * 1024L

    /** 单流下载时的缓冲区 */
    private const val BUFFER = 64 * 1024

    /** 每个分片的最小尺寸，避免分片过多 */
    private const val MIN_CHUNK_BYTES = 256 * 1024L

    /** A caller-owned download session that can stop every active HTTP stream. */
    class DownloadSession {
        private val cancelled = AtomicBoolean(false)
        private val connections = ConcurrentHashMap.newKeySet<HttpURLConnection>()
        @Volatile private var executor: ExecutorService? = null

        fun cancel() {
            cancelled.set(true)
            connections.forEach { runCatching { it.disconnect() } }
            executor?.shutdownNow()
        }

        internal fun isCancelled() = cancelled.get()
        internal fun register(connection: HttpURLConnection) { connections += connection }
        internal fun unregister(connection: HttpURLConnection) { connections -= connection }
        internal fun setExecutor(value: ExecutorService?) { executor = value }
    }

    /**
     * 下载 url 到 target。自动读取 [DownloadPrefs] 开关与线程数。
     *
     * @param onProgress (已下载, 总字节, 速度 bytes/s)；总字节未知为 -1
     * @param onLog 可选日志回调（失败/降级提示等）
     */
    suspend fun download(
        url: String,
        target: File,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        onLog: ((String) -> Unit)? = null,
        session: DownloadSession? = null
    ) {
        val enabled = DownloadPrefs.isEnabled()
        val threads = DownloadPrefs.threadCount()
        download(url, target, threads, enabled, onProgress, onLog, session)
    }

    /**
     * 下载 url 到 target，显式指定线程数与开关（覆盖 [DownloadPrefs]）。
     */
    suspend fun download(
        url: String,
        target: File,
        threads: Int,
        enabled: Boolean,
        onProgress: (Long, Long, Long) -> Unit = { _, _, _ -> },
        onLog: ((String) -> Unit)? = null,
        session: DownloadSession? = null
    ) {
        checkCancelled(session)
        val threadCount = threads.coerceAtLeast(1)
        val total = probeLength(url, session)
        if (!enabled || threadCount <= 1 || total <= 0 || total < MIN_MULTI_BYTES) {
            if (!enabled) onLog?.invoke("多线程下载未启用，使用单流下载")
            if (total in 1 until MIN_MULTI_BYTES) onLog?.invoke("文件较小，使用单流下载")
            singleStream(url, target, total, onProgress, session)
            return
        }

        // 计算分片数量（受最小分片尺寸约束）
        val chunkCount = minOf(threadCount, ((total + MIN_CHUNK_BYTES - 1) / MIN_CHUNK_BYTES).toInt())
        val ranges = buildRanges(total, chunkCount)
        target.parentFile?.mkdirs()
        onLog?.invoke("多线程下载：$chunkCount 线程，总大小 ${total / 1024 / 1024}MB")

        val pool: ExecutorService = Executors.newFixedThreadPool(chunkCount)
        session?.setExecutor(pool)
        val done = AtomicLong(0L)
        val errors = java.util.concurrent.ConcurrentLinkedQueue<Exception>()
        val speedSamples = mutableListOf<DownloadSpeedSample>()

        try {
            ranges.forEach { range ->
                pool.execute {
                    try {
                        downloadRange(url, target, range.first, range.second, onProgress, done, session)
                    } catch (e: Exception) {
                        errors.add(e)
                        Log.w(TAG, "range ${range.first}-${range.second} failed: ${e.message}")
                    }
                }
            }
            // 等待所有分片完成
            pool.shutdown()
            while (!pool.awaitTermination(200, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                checkCancelled(session)
                reportSpeed(done.get(), total, speedSamples, onProgress)
            }
            checkCancelled(session)
            if (!errors.isEmpty()) {
                throw RuntimeException("多线程分片下载失败: ${errors.peek()?.message ?: "未知错误"}")
            }

            // 合并分片为最终文件
            mergeRanges(target, ranges)
            onProgress(target.length(), total, 0L)
        } finally {
            pool.shutdownNow()
            session?.setExecutor(null)
            ranges.forEach { (start, _) ->
                val part = partFile(target, start)
                if (part.exists()) part.delete()
            }
        }
    }

    // ── 分片辅助 ──────────────────────────────────────────────

    private fun buildRanges(total: Long, count: Int): List<Pair<Long, Long>> {
        val chunk = total / count
        val ranges = mutableListOf<Pair<Long, Long>>()
        var start = 0L
        for (i in 0 until count) {
            val end = if (i == count - 1) total - 1 else start + chunk - 1
            ranges.add(start to end)
            start = end + 1
        }
        return ranges
    }

    private fun partFile(target: File, start: Long): File =
        File(target.parentFile, "${target.name}.part$start")

    /** 探测 Content-Length（Range: bytes=0-0 + Content-Range 响应头） */
    private fun probeLength(urlStr: String, session: DownloadSession?): Long {
        var conn: HttpURLConnection? = null
        return try {
            conn = openConn(urlStr).apply {
                setRequestProperty("Range", "bytes=0-0")
            }
            session?.register(conn)
            checkCancelled(session)
            val code = conn.responseCode
            if (code == 206) {
                // Content-Range: bytes 0-0/12345
                val cr = conn.getHeaderField("Content-Range") ?: return -1L
                val slash = cr.lastIndexOf('/')
                if (slash < 0) return -1L
                cr.substring(slash + 1).trim().toLongOrNull() ?: -1L
            } else if (code in 200..299) {
                // A 200 response ignored the Range header; use one stream so a mirror
                // cannot be mistaken for a range-capable endpoint and corrupt the JAR.
                -1L
            } else {
                -1L
            }
        } catch (e: java.util.concurrent.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "probeLength failed: ${e.message}")
            -1L
        } finally {
            conn?.let { session?.unregister(it) }
            conn?.disconnect()
        }
    }

    /** 下载单个分片 [start,end]（含端点），追加写入 .part 文件 */
    private fun downloadRange(
        urlStr: String,
        target: File,
        start: Long,
        end: Long,
        onProgress: (Long, Long, Long) -> Unit,
        done: AtomicLong,
        session: DownloadSession?
    ) {
        var conn: HttpURLConnection? = null
        try {
            conn = openConn(urlStr).apply {
                setRequestProperty("Range", "bytes=$start-$end")
            }
            session?.register(conn)
            checkCancelled(session)
            val code = conn.responseCode
            if (code != 206) {
                throw RuntimeException("Range 请求返回 HTTP $code")
            }
            val part = partFile(target, start)
            part.parentFile?.mkdirs()
            val output = RandomAccessFile(part, "rw")
            try {
                val buf = ByteArray(BUFFER)
                conn.inputStream.use { input ->
                    while (true) {
                        checkCancelled(session)
                        val read = input.read(buf)
                        if (read <= 0) break
                        output.write(buf, 0, read)
                        done.addAndGet(read.toLong())
                    }
                }
            } finally {
                output.close()
            }
        } finally {
            conn?.let { session?.unregister(it) }
            conn?.disconnect()
        }
    }

    /** 顺序合并分片到目标文件 */
    private fun mergeRanges(target: File, ranges: List<Pair<Long, Long>>) {
        FileOutputStream(target).use { out ->
            ranges.forEach { (start, _) ->
                val part = partFile(target, start)
                if (!part.exists()) {
                    throw RuntimeException("分片缺失: ${part.name}")
                }
                part.inputStream().use { input ->
                    val buf = ByteArray(BUFFER)
                    while (true) {
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                    }
                }
            }
        }
    }

    /** 每 500ms 报告一次总进度与速度（total 用真实长度，避免进度无法计算） */
    private fun reportSpeed(downloaded: Long, total: Long, samples: MutableList<DownloadSpeedSample>, onProgress: (Long, Long, Long) -> Unit) {
        val now = System.currentTimeMillis()
        samples += DownloadSpeedSample(downloaded, now)
        val speed = speedBytesPerSecond(samples, now)
        onProgress(downloaded, total, speed)
    }

    /** 单流下载（Range 不支持 / 未启用 / 小文件时的降级路径） */
    private fun singleStream(urlStr: String, target: File, total: Long, onProgress: (Long, Long, Long) -> Unit, session: DownloadSession?) {
        var conn: HttpURLConnection? = null
        try {
            conn = openConn(urlStr)
            session?.register(conn)
            checkCancelled(session)
            val code = conn.responseCode
            if (code !in 200..299) {
                throw RuntimeException("HTTP $code 下载失败: $urlStr")
            }
            val actualTotal = if (total > 0) total else conn.contentLengthLong
            target.parentFile?.mkdirs()
            var downloaded = 0L
            val speedSamples = mutableListOf<DownloadSpeedSample>()
            conn.inputStream.use { input ->
                FileOutputStream(target).use { fos ->
                    val buffer = ByteArray(BUFFER)
                    while (true) {
                        checkCancelled(session)
                        val read = input.read(buffer)
                        if (read <= 0) break
                        fos.write(buffer, 0, read)
                        downloaded += read
                        val now = System.currentTimeMillis()
                        if (speedSamples.isEmpty() || now - speedSamples.last().timestampMs >= 500) {
                            speedSamples += DownloadSpeedSample(downloaded, now)
                            val speed = speedBytesPerSecond(speedSamples, now)
                            onProgress(downloaded, actualTotal, speed)
                        }
                    }
                }
            }
            onProgress(downloaded, actualTotal, 0L)
        } finally {
            conn?.let { session?.unregister(it) }
            conn?.disconnect()
        }
    }

    private fun checkCancelled(session: DownloadSession?) {
        if (session?.isCancelled() == true) throw java.util.concurrent.CancellationException("Download cancelled")
    }

    private fun openConn(urlStr: String): HttpURLConnection {
        return (URL(urlStr).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            // 快速失败：连接 8s、读间隔 20s，挂梯子/镜像异常时快速切源
            connectTimeout = 8_000
            readTimeout = 20_000
            setRequestProperty("User-Agent", "MineServeMobile/1.0 (Android)")
        }
    }
}
