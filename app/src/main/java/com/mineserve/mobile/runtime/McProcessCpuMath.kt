package com.mineserve.mobile.runtime

/**
 * 进程 CPU 百分比计算的纯逻辑（无 Android 依赖，便于 JVM 单元测试）。
 * 输入为两次采样之间进程累计的 utime+stime jiffies 增量与真实经过毫秒数。
 */
internal object McProcessCpuMath {

    /**
     * 计算窗口内的进程 CPU 占用率（%，可超过 100）。
     * 基线缺失/回退、窗口无效或时钟频率无效时返回 null，表示“不可用”。
     */
    fun percent(jiffiesNow: Long, jiffiesPrev: Long, elapsedMs: Long, tickHertz: Long): Int? {
        if (jiffiesPrev <= 0L || jiffiesNow < jiffiesPrev) return null
        if (elapsedMs <= 0L || tickHertz <= 0L) return null
        // 全程 Long/Double 运算：多线程 Java 进程可超过 100%，只做显示上限裁剪。
        val delta = jiffiesNow - jiffiesPrev
        val cpuMs = delta * 1000L / tickHertz
        return (cpuMs.toDouble() * 100.0 / elapsedMs.toDouble())
            .toInt()
            .coerceIn(0, 999)
    }
}
