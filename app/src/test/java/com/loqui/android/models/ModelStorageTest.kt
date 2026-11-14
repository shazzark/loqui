package com.loqui.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelStorageTest {

    private val mebibyte = 1024L * 1024L

    @Test
    fun `exactly the needed space counts as room`() {
        val report = ModelStorageReport(requiredBytes = 100, freeBytes = 110, safetyMarginBytes = 10)

        assertTrue(report.hasRoom)
        assertEquals(0L, report.shortfallBytes)
    }

    @Test
    fun `one byte short is not room`() {
        val report = ModelStorageReport(requiredBytes = 100, freeBytes = 109, safetyMarginBytes = 10)

        assertFalse(report.hasRoom)
        assertEquals(1L, report.shortfallBytes)
    }

    @Test
    fun `an unreadable free space reports the whole download as missing`() {
        val report = ModelStorageReport(requiredBytes = 100, freeBytes = 0, safetyMarginBytes = 10)

        assertFalse(report.hasRoom)
        assertEquals(110L, report.shortfallBytes)
    }

    @Test
    fun `the default margin leaves room for the unpacked copy`() {
        val model = 120L * mebibyte

        assertFalse(
            "the archive alone is not enough: unpacking needs head-room",
            ModelStorageReport(requiredBytes = model, freeBytes = model).hasRoom,
        )
        assertTrue(
            ModelStorageReport(
                requiredBytes = model,
                freeBytes = model + ModelStorageReport.DEFAULT_SAFETY_MARGIN_BYTES,
            ).hasRoom,
        )
    }

    @Test
    fun `sizes are formatted coarsely`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("2 KB", formatBytes(2 * 1024L))
        assertEquals("120 MB", formatBytes(120 * mebibyte))
        assertEquals("2.5 GB", formatBytes((2.5 * 1024 * mebibyte).toLong()))
    }

    @Test
    fun `a negative size never renders as a negative number`() {
        assertEquals("0 B", formatBytes(-1))
    }
}
