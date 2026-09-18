package com.mineserve.mobile.runtime

/**
 * 进程 CPU 百分比计算的纯逻辑（无 Android 依赖，便于 JVM 单元测试）。
 * 输入为两次采样之间进程累计的 utime+stime jiffies 增量、真实经过毫秒数、
 * 系统时钟频率（HZ）与可用于归一化的逻辑核心数。
 */
internal object McProcessCpuMath {

    /**
     * 计算窗口内进程 CPU 占用率（%）。
     * 多线程聚合 CPU 时间按可用核心数归一化，返回值限制在 0..100。
     * 计数器回退/窗口无效/时钟频率无效时返回 null（不可用）。
     *
     * ## 为什么不能因为 jiffiesPrev == 0 就返回 null
     * 早期实现写的是 `jiffiesPrev <= 0L -> null`，把「基线为 0」当成「没有基线」。
     * 但进程**刚启动时 utime+stime 真的可能就是 0**：JVM 从 exec 到第一次被采样
     * 之间还没累积任何 CPU 时间。于是：
     *   1. 首次采样建立基线 `cpuBaselineJiffies = 0`
     *   2. 之后每次都在这一行被 `jiffiesPrev <= 0` 判为不可用
     *   3. CPU 长期返回 null → UI 掉到 "--" 或 0%
     *
     * 实测表现为「CPU 占用率显示 0%」且长时间不恢复。
     *
     * 现在改为只把**负值**视为非法（那才是真的计数器异常），
     * 0 是合法基线；同时用 `jiffiesNow >= jiffiesPrev` 兜住回退。
     */
    fun percent(
        jiffiesNow: Long,
        jiffiesPrev: Long,
        elapsedMs: Long,
        tickHertz: Long,
        cores: Int = 1
    ): Int? {
        // 负值才是非法输入；0 是刚启动进程的合法基线
        if (jiffiesPrev < 0L || jiffiesNow < 0L) return null
        if (jiffiesNow < jiffiesPrev) return null
        if (elapsedMs <= 0L || tickHertz <= 0L) return null
        val coreCount = cores.coerceAtLeast(1)
        val delta = jiffiesNow - jiffiesPrev
        // 进程聚合 CPU 毫秒；除以核数后得到“相对整机可用算力”的占用率。
        val cpuMs = delta * 1000L / tickHertz / coreCount
        return (cpuMs.toDouble() * 100.0 / elapsedMs.toDouble())
            .toInt()
            .coerceIn(0, 100)
    }
}
