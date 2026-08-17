package com.mineserve.mobile.server

import kotlin.test.Test
import kotlin.test.assertEquals

class NeoForgeVersionResolverTest {
    @Test
    fun `keeps minecraft patch boundary when selecting NeoForge`() {
        val selected = selectNeoForgeVersion(
            "1.21.1",
            listOf("21.1.9", "21.1.99", "21.11.9-beta")
        )

        assertEquals("21.1.99", selected)
    }

    @Test
    fun `selects numeric highest build rather than lexical highest`() {
        val selected = selectNeoForgeVersion("1.21.1", listOf("21.1.9", "21.1.10"))

        assertEquals("21.1.10", selected)
    }
}
