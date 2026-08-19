package com.mineserve.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalLogTranslatorTest {

    @Test
    fun translatesServerStartup() {
        val out = TerminalLogTranslator.translate("[Server thread/INFO]: Done (5.123s)! For help, type \"help\"")
        assertTrue(out.startsWith("服务端已启动完成"))
        assertTrue(out.contains("原文"))
    }

    @Test
    fun translatesJoinAndLeaveKeepingPlayerName() {
        assertEquals(
            "[Server thread/INFO]: 玩家 Steve 加入了游戏",
            TerminalLogTranslator.translate("[Server thread/INFO]: Steve joined the game")
        )
        assertEquals(
            "[Server thread/INFO]: 玩家 Alex 离开了游戏",
            TerminalLogTranslator.translate("[Server thread/INFO]: Alex left the game")
        )
    }

    @Test
    fun translatesOnlinePlayerCount() {
        assertEquals(
            "[Server thread/INFO]: 当前在线 3/20 人",
            TerminalLogTranslator.translate("[Server thread/INFO]: There are 3 of a max of 20 players online")
        )
    }

    @Test
    fun translatesCommonErrors() {
        assertTrue(TerminalLogTranslator.translate("[Server thread/ERROR]: Failed to bind: Address already in use").startsWith("端口已被占用"))
        assertTrue(TerminalLogTranslator.translate("[Server thread/WARN]: Can't keep up! Is the server overloaded?").startsWith("服务器过载或卡顿"))
        assertTrue(TerminalLogTranslator.translate("java.lang.OutOfMemoryError: Java heap space").startsWith("Java 内存不足"))
        assertTrue(TerminalLogTranslator.translate("[Server thread/ERROR]: Exception in thread \"Server thread\"").startsWith("线程发生异常"))
    }

    @Test
    fun prefixesErrorAndWarnWithOriginal() {
        // 真实日志中的独立 [ERROR] / [WARN] 标签（如 "[12:00:00] [ERROR] ..."）
        val err = TerminalLogTranslator.translate("[12:00:00] [ERROR] Some failure")
        assertTrue("ERR=[" + err + "]", err.startsWith("错误："))
        assertTrue(err.contains("原文"))
        val warn = TerminalLogTranslator.translate("[12:00:00] [WARN] Some warning")
        assertTrue("WARN=[" + warn + "]", warn.startsWith("警告："))
        assertTrue(warn.contains("原文"))
    }

    @Test
    fun leavesUnrelatedLinesUntouched() {
        val line = "[Server thread/INFO]: Custom extension diagnostic"
        assertEquals(line, TerminalLogTranslator.translate(line))
    }

    @Test
    fun translatesWhitelistAndEula() {
        assertTrue(TerminalLogTranslator.translate("[Server thread/WARN]: You need to agree to the EULA to run the server").startsWith("未接受 Minecraft EULA"))
        assertTrue(TerminalLogTranslator.translate("[Server thread/INFO]: You are not white-listed on this server").startsWith("您不在服务器白名单中"))
    }
}
