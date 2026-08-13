package com.mineserve.mobile.ui

enum class TerminalSessionType { Termux, Minecraft }

data class TerminalSession(
    val id: String,
    val name: String,
    val type: TerminalSessionType,
    val lines: List<String> = emptyList(),
    val busy: Boolean = false
)
