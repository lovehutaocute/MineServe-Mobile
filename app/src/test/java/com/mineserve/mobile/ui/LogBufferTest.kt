package com.mineserve.mobile.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LogBufferTest {
    @Test
    fun keepsOnlyTheNewestEntriesWithoutGrowingPastCapacity() {
        val buffer = LogBuffer<Int>(2)

        buffer.add(1)
        buffer.add(2)
        buffer.add(3)

        assertEquals(listOf(2, 3), buffer.snapshot())
        assertEquals(listOf(2, 3), buffer.snapshotAndClear())
        assertEquals(emptyList<Int>(), buffer.snapshot())
    }

    @Test
    fun canReadTheTailAndReplaceTheContents() {
        val buffer = LogBuffer<Int>(3)
        buffer.replace(listOf(1, 2, 3, 4))

        assertEquals(listOf(3, 4), buffer.last(2))
        buffer.clear()
        assertEquals(emptyList<Int>(), buffer.snapshot())
    }
}
