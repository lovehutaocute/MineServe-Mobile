package com.mineserve.mobile.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class McProcessCpuMathTest {

    @Test
    fun fullSingleCoreWindowIs100Percent() {
        // 100Hz 时钟下 1000 jiffies == 10 秒 CPU，窗口恰好 10 秒 → 100%
        assertEquals(100, McProcessCpuMath.percent(2000L, 1000L, 10_000L, 100L))
    }

    @Test
    fun oneCoreHalfUsedIs50Percent() {
        // 500 jiffies == 5 秒 CPU，窗口 10 秒 → 50%
        assertEquals(50, McProcessCpuMath.percent(1500L, 1000L, 10_000L, 100L))
    }

    @Test
    fun multiThreadedServerCanExceed100() {
        // 8 线程跑满：8000 jiffies == 80 秒 CPU，窗口 10 秒 → 800%
        assertEquals(800, McProcessCpuMath.percent(9000L, 1000L, 10_000L, 100L))
    }

    @Test
    fun percentIsClampedTo999() {
        // 极端多线程/极小窗口值也稳定收敛在显示上限 999。
        assertEquals(999, McProcessCpuMath.percent(2_000_000L, 1_000_000L, 1L, 1L))
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
}
