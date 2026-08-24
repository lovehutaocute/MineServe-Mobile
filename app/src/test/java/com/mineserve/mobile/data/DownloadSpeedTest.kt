package com.mineserve.mobile.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DownloadSpeedTest {

    @Test
    fun usesRecentThreeSecondWindow() {
        val samples = mutableListOf(
            DownloadSpeedSample(0, 0),
            DownloadSpeedSample(3_000, 1_000),
            DownloadSpeedSample(9_000, 3_000),
            DownloadSpeedSample(15_000, 4_000)
        )
        assertEquals(4_000L, speedBytesPerSecond(samples, 4_000))
        assertEquals(3, samples.size)
        assertEquals(1_000L, samples.first().timestampMs)
    }
}
