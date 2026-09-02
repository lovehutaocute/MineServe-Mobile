package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McProcessCpuMathTest {

    @Test
    fun fullSingleCoreWindowIs100Percent() {
        // 100Hz 时钟下 1000 jiffies == 10 秒 CPU，窗口恰好 10 秒 → 100%
        assertEquals(100, McProcessCpuMath.percent(2000L, 1000L, 10_000L, 100L, cores = 1))
    }

    @Test
    fun oneCoreHalfUsedIs50Percent() {
        // 500 jiffies == 5 秒 CPU，窗口 10 秒 → 50%
        assertEquals(50, McProcessCpuMath.percent(1500L, 1000L, 10_000L, 100L, cores = 1))
    }

    @Test
    fun eightThreadsOnEightCoresIs100Percent() {
        // 8 线程跑满：8000 jiffies == 80 秒 CPU，窗口 10 秒，8 核 → 100%
        assertEquals(100, McProcessCpuMath.percent(9000L, 1000L, 10_000L, 100L, cores = 8))
    }

    @Test
    fun twoThreadsOnEightCoresIs25Percent() {
        // 2 线程满载：2000 jiffies == 20 秒 CPU，窗口 10 秒，8 核 → 25%
        assertEquals(25, McProcessCpuMath.percent(3000L, 1000L, 10_000L, 100L, cores = 8))
    }

    @Test
    fun oneCoreEquivalentOnManyCoresIsLowPercent() {
        // 单核满载：1000 jiffies == 10 秒 CPU，窗口 10 秒，8 核 → 12.5 → 12
        assertEquals(12, McProcessCpuMath.percent(2000L, 1000L, 10_000L, 100L, cores = 8))
    }

    @Test
    fun percentIsClampedTo100() {
        // 极端多线程/极小窗口值也稳定收敛在显示上限 100。
        assertEquals(100, McProcessCpuMath.percent(2_000_000L, 1_000_000L, 1L, 1L, cores = 1))
        assertEquals(100, McProcessCpuMath.percent(2_000_000L, 1_000_000L, 1L, 1L, cores = 4))
    }

    @Test
    fun baselineMissingReturnsNull() {
        assertNull(McProcessCpuMath.percent(1000L, 0L, 10_000L, 100L))
    }

    @Test
    fun counterRollbackReturnsNull() {
        assertNull(McProcessCpuMath.percent(500L, 1000L, 10_000L, 100L))
    }

    @Test
    fun zeroElapsedWindowReturnsNull() {
        assertNull(McProcessCpuMath.percent(1000L, 500L, 0L, 100L))
    }

    @Test
    fun zeroTickHertzReturnsNull() {
        assertNull(McProcessCpuMath.percent(1000L, 500L, 10_000L, 0L))
    }

    @Test
    fun idleProcessIsZeroPercent() {
        assertEquals(0, McProcessCpuMath.percent(1000L, 1000L, 10_000L, 100L))
    }

    @Test
    fun zeroOrNegativeCoresFallBackToOneCore() {
        assertEquals(100, McProcessCpuMath.percent(2000L, 1000L, 10_000L, 100L, cores = 0))
        assertEquals(100, McProcessCpuMath.percent(2000L, 1000L, 10_000L, 100L, cores = -2))
    }
}
