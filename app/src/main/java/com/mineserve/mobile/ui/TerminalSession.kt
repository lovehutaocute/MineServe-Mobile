package com.mineserve.mobile.ui

import java.util.concurrent.atomic.AtomicLong
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

enum class TerminalSessionType { Termux, Minecraft }

enum class TerminalLogTone { Default, Command, Error, Warning, Info, Debug, Download, Success }

data class TerminalDisplayLine(
    val text: String,
    val tone: TerminalLogTone = TerminalLogTone.Default,
    val id: Long = nextId.incrementAndGet()
) {
    private companion object {
        val nextId = AtomicLong()
    }
}

data class TerminalSession(
    val id: String,
    val name: String,
    val type: TerminalSessionType,
    val lines: ImmutableList<TerminalDisplayLine> = persistentListOf(),
    val busy: Boolean = false
)
