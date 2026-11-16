package com.loqui.android.asr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class Pcm16Test {

    @Test
    fun `little-endian samples are normalised to the minus one to one range`() {
        val pcm = byteArrayOf(
            0x00, 0x00, // 0
            0x01, 0x00, // 1
            0xff.toByte(), 0x7f, // 32767, the largest positive sample
            0x00, 0x80.toByte(), // -32768, the largest negative sample
            0xff.toByte(), 0xff.toByte(), // -1
        )

        val samples = pcm16ToFloatSamples(pcm)

        assertEquals(5, samples.size)
        assertEquals(0f, samples[0], 0f)
        assertEquals(1f / 32768f, samples[1], 1e-7f)
        assertEquals(32767f / 32768f, samples[2], 1e-7f)
        assertEquals(-1f, samples[3], 0f)
        assertEquals(-1f / 32768f, samples[4], 1e-7f)
    }

    @Test
    fun `a trailing odd byte is ignored instead of inventing a sample`() {
        assertEquals(0, pcm16ToFloatSamples(byteArrayOf(0x01)).size)
        assertEquals(1, pcm16ToFloatSamples(byteArrayOf(0x01, 0x00, 0x02)).size)
    }

    @Test
    fun `silence stays silent at the recogniser scale`() {
        val samples = pcm16ToFloatSamples(ByteArray(320))

        assertEquals(160, samples.size)
        assertTrue(samples.all { it == 0f })
    }
}
