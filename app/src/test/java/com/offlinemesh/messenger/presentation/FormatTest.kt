package com.offlinemesh.messenger.presentation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class FormatTest {

    @Test
    fun `duration is minutes and seconds`() {
        assertEquals("0:00", formatDuration(0))
        assertEquals("0:09", formatDuration(9_400))
        assertEquals("1:05", formatDuration(65_000))
        assertEquals("12:34", formatDuration(754_000))
    }

    @Test
    fun `sizes use sensible units`() {
        assertEquals("512 B", formatSize(512))
        assertEquals("2 KB", formatSize(2048))
        assertEquals("1.5 MB", formatSize((1.5 * 1024 * 1024).toLong()))
    }

    @Test
    fun `keys are grouped for readability`() {
        assertEquals("abcd efgh", chunkKey("abcdefgh"))
        assertEquals("ab cd ef", chunkKey("abcdef", groupSize = 2))
        assertEquals("abcd efgh", chunkKey("abcdefghijkl", maxGroups = 2))
    }

    @Test
    fun `timestamp of today shows the time`() {
        val value = formatTimestamp(System.currentTimeMillis(), Locale.US)
        assertTrue(value.matches(Regex("\\d{1,2}:\\d{2}")))
    }

    @Test
    fun `old timestamps show the date`() {
        val value = formatTimestamp(0L, Locale.US)
        assertTrue(value.isNotBlank())
        assertTrue(!value.matches(Regex("\\d{1,2}:\\d{2}")))
    }
}
