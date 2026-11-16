package com.loqui.android.models

import java.io.File
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Test

class ModelDownloaderHashTest {

    @Test
    fun `sha256 is lowercase hex of the file contents`() {
        val file = File.createTempFile("loqui-hash", ".bin")
        try {
            file.writeText("abc")
            val expected = MessageDigest.getInstance("SHA-256")
                .digest("abc".toByteArray())
                .toHex()

            assertEquals(
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                expected,
            )
            assertEquals(expected, sha256Hex(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun `byte arrays render two hex digits per byte`() {
        assertEquals("00ff10", byteArrayOf(0x00, 0xff.toByte(), 0x10).toHex())
    }
}
