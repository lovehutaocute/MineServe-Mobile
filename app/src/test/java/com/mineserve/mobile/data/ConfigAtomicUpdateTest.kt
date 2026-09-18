package com.mineserve.mobile.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 配置写入的原子性约束。
 *
 * 背景：`updateConfig` 的旧实现是「读 config.value → 改 → 写」，
 * 基准取自 DataStore 异步流的最新发射值。由于落盘有 300ms debounce，
 * 窗口内的第二次修改会读到尚未更新的旧值，把第一次改动整体覆盖
 * （现场表现：切到 PHP 后 runtimeKind 又被写回 Java）。
 *
 * 新实现把读改写交给 `MutableStateFlow.updateAndGet`，基准是权威内存态，
 * 不再依赖时间窗口。这里用纯并发模型把该语义固定下来，
 * 不需要 Android Context 即可验证。
 */
class ConfigAtomicUpdateTest {

    @Test
    fun `sequential updates accumulate instead of overwriting`() {
        val config = MutableStateFlow(TestConfig())

        // 模拟两次快速连续修改：旧实现下第二次会以过期基准覆盖第一次
        config.updateAndGet { it.copy(runtimeKind = "Php") }
        val after = config.updateAndGet { it.copy(selectedPhpVersion = "1.21") }

        assertEquals("Php", after.runtimeKind)
        assertEquals("1.21", after.selectedPhpVersion)
    }

    @Test
    fun `concurrent updates do not lose any field change`() = runBlocking {
        val config = MutableStateFlow(TestConfig())

        // 每个字段由独立协程并发修改；若读改写不是原子的，就会有更新丢失
        val jobs = (1..50).map { i ->
            launch(Dispatchers.Default) {
                config.updateAndGet { it.copy(counter = it.counter + 1) }
            }
        }
        jobs.joinAll()

        assertEquals(50, config.value.counter)
    }

    @Test
    fun `each update sees the result of the previous one`() {
        val config = MutableStateFlow(TestConfig())

        repeat(10) { i ->
            config.updateAndGet { it.copy(log = it.log + i) }
        }

        assertEquals("0123456789", config.value.log)
    }

    private data class TestConfig(
        val runtimeKind: String = "Java",
        val selectedPhpVersion: String = "",
        val counter: Int = 0,
        val log: String = ""
    )
}
