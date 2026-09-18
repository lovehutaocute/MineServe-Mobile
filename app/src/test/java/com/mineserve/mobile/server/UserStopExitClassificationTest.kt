package com.mineserve.mobile.server

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「停止时误弹崩溃报告」回归测试。
 *
 * ## 用户反馈
 * > 为啥每次点停止就会弹出一个崩溃报告
 * > java.lang.NoClassDefFoundError: Could not initialize class com.sun.jna.Native
 * > 退出码: 0
 *
 * ## 根因
 * `createExitHandler` 的异常退出判据是：
 * ```
 * abnormalExit = code != 0 || failedDuringStartup
 * ```
 * 其中 `failedDuringStartup` 表示"退出发生在启动后 20 秒的窗口内"。
 * 于是**用户在启动后 20 秒内点停止**时，即使退出码是 0、服务端是被
 * `stop` 命令正常关掉的，也会被判成异常退出 → 生成崩溃报告。
 *
 * 报告里那条 JNA 错误是**启动过程中**某个 OSHI/JNA 模组留下的历史日志
 * （Android 无 glibc，JNA 找不到 libc.so.6，这是平台固有限制），
 * 被 captureCrash 当作"最近日志"一并打包。它与"停止"毫无因果，
 * 但出现在一份题为"崩溃报告"的文档里，极易被误读成停止导致的崩溃。
 *
 * ## 修法
 * 在 `TermuxRuntime.stopMc()`（所有主动停止入口的汇聚点）打上
 * 时间戳标记，退出处理器见到标记即按正常停止处理。
 *
 * 下面把这个判定抽成纯函数来测 —— 它必须与生产代码的语义保持一致。
 */
class UserStopExitClassificationTest {

    /** 与 McServerController.createExitHandler 的判定逐字等价。 */
    private fun isAbnormalExit(
        code: Int,
        failedDuringStartup: Boolean,
        userStopRequestedAtMs: Long,
        nowMs: Long,
        graceMs: Long = 5 * 60_000L
    ): Boolean {
        val userStoppedRecently = userStopRequestedAtMs != 0L &&
            nowMs - userStopRequestedAtMs <= graceMs
        return !userStoppedRecently && (code != 0 || failedDuringStartup)
    }

    @Test
    fun stopWithinStartupWindowIsNotACrash() {
        // 核心回归：启动后 3 秒点停止 → 退出码 0、仍在 20s 窗口内。
        // 旧逻辑会判异常（弹崩溃报告），新逻辑必须判正常。
        assertFalse(
            "启动窗口内点停止被误判为崩溃（用户反馈的 bug）",
            isAbnormalExit(
                code = 0,
                failedDuringStartup = true,
                userStopRequestedAtMs = 1_000_000L,
                nowMs = 1_003_000L
            )
        )
    }

    @Test
    fun stopAfterStartupWindowIsNotACrash() {
        // 启动窗口过后点停止：旧逻辑本来就是对的，不能改坏
        assertFalse(
            isAbnormalExit(
                code = 0,
                failedDuringStartup = false,
                userStopRequestedAtMs = 1_000_000L,
                nowMs = 1_030_000L
            )
        )
    }

    @Test
    fun realCrashDuringStartupIsStillDetected() {
        // ⚠️ 关键反向用例：没有停止请求时，启动窗口内的退出**必须**仍判异常，
        // 否则会掩盖"核心不匹配/配置错误"这类真实失败。
        assertTrue(
            "启动窗口内的真实崩溃被漏报了",
            isAbnormalExit(
                code = 1,
                failedDuringStartup = true,
                userStopRequestedAtMs = 0L,
                nowMs = 1_003_000L
            )
        )
        // exit=0 但没点停止、还在窗口内 → 仍是异常（测试核心/缺 EULA 等）
        assertTrue(
            isAbnormalExit(
                code = 0,
                failedDuringStartup = true,
                userStopRequestedAtMs = 0L,
                nowMs = 1_003_000L
            )
        )
    }

    @Test
    fun crashLongAfterStopIsStillDetected() {
        // 标记有有效期：停完之后过了很久才崩，必须仍判异常。
        // 否则用户停服→几小时后进程自己崩了，会被静默忽略。
        assertTrue(
            "停止标记过期后，真实崩溃被漏报",
            isAbnormalExit(
                code = 1,
                failedDuringStartup = false,
                userStopRequestedAtMs = 1_000_000L,
                nowMs = 1_000_000L + 6 * 60_000L   // 超过 5 分钟宽限
            )
        )
    }

    @Test
    fun slowShutdownAfterStopIsCovered() {
        // 大世界关停很慢：点了停止后 90 秒进程才退出，宽限窗口必须覆盖。
        assertFalse(
            "关停耗时较长时又被误判为崩溃",
            isAbnormalExit(
                code = 0,
                failedDuringStartup = true,
                userStopRequestedAtMs = 1_000_000L,
                nowMs = 1_000_000L + 90_000L
            )
        )
    }
}
