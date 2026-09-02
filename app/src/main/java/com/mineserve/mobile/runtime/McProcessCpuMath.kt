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
     * 基线缺失/计数器回退/窗口无效/时钟频率无效时返回 null（不可用）。
     */
    fun percent(
        jiffiesNow: Long,
        jiffiesPrev: Long,
        elapsedMs: Long,
        tickHertz: Long,
        cores: Int = 1
    ): Int? {
        if (jiffiesPrev <= 0L || jiffiesNow < jiffiesPrev) return null
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
