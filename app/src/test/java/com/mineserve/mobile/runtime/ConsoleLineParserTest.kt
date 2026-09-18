package com.mineserve.mobile.runtime

import com.mineserve.mobile.data.StartupPhase
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享控制台解析器的行为约束。
 *
 * 这些用例的存在意义：解析器被 ViewModel 与前台服务**共用**，
 * 一旦行为漂移会导致"前台/后台看到的服务器状态不一致"，
 * 因此需要把关键输出的语义固定下来。
 */
class ConsoleLineParserTest {

    @Test
    fun `parses the player count line`() {
        val signals = ConsoleLineParser.parse("There are 3 of a max of 20 players online: Steve, Alex, Bob")

        val players = signals.players
        assertNotNull(players)
        assertEquals(3, players!!.online)
        assertEquals(20, players.max)
    }

    @Test
    fun `parses the tps line`() {
        val signals = ConsoleLineParser.parse("[12:00:00] [Server thread/INFO]: TPS from last 1m, 5m, 15m: 19.98, 20.0, 20.0")

        assertNotNull(signals.tps)
        assertEquals(19.98, signals.tps!!, 0.001)
    }

    @Test
    fun `marks the server as ready when the core announces completion`() {
        val signals = ConsoleLineParser.parse("[12:00:00] [Server thread/INFO]: Done (3.215s)! For help, type \"help\"")

        assertTrue(signals.isReady)
        assertEquals(StartupPhase.Ready, signals.startupPhase)
    }

    @Test
    fun `detects a java version requirement from an unsupported class version error`() {
        // class file version 61 == Java 17
        val signals = ConsoleLineParser.parse("java.lang.UnsupportedClassVersionError: class file version 61.0")

        assertEquals(17, signals.requiredJavaVersion)
    }

    @Test
    fun `ordinary log lines produce empty signals so callers can skip further work`() {
        val signals = ConsoleLineParser.parse("[12:00:00] [Server thread/INFO]: Saving chunks for level 'world'")

        assertTrue(signals.isEmpty)
        assertNull(signals.players)
        assertNull(signals.tps)
        assertFalse(signals.isReady)
    }

    @Test
    fun `does not treat a chat message as a player count`() {
        val signals = ConsoleLineParser.parse("<Steve> there are 99 of a max of 99 players online")

        // 该行不含标准 list 回复格式（大小写与措辞不同），不应被当作玩家数
        assertNull(signals.players)
    }

    @Test
    fun `startup phase is reported for intermediate stages`() {
        val signals = ConsoleLineParser.parse("[12:00:00] [Server thread/INFO]: Preparing spawn area: 42%")

        assertEquals(StartupPhase.CreatingWorld, signals.startupPhase)
        assertFalse(signals.isReady)
    }
}
