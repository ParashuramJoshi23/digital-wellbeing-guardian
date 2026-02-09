package com.example.digitalwellbeingguardian.util

import org.junit.Assert.assertEquals
import org.junit.Test

class TimeFormatTest {

    @Test
    fun `formats seconds when under a minute`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("45s", formatDuration(45_000))
    }

    @Test
    fun `formats minutes and seconds`() {
        assertEquals("1m 05s", formatDuration(65_000))
        assertEquals("20m 00s", formatDuration(20 * 60_000L))
    }

    @Test
    fun `formats hours and minutes`() {
        assertEquals("1h 00m", formatDuration(60 * 60_000L))
        assertEquals("2h 15m", formatDuration((2 * 60 + 15) * 60_000L))
    }

    @Test
    fun `negative duration clamps to zero`() {
        assertEquals("0s", formatDuration(-1_000))
    }
}
