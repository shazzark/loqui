package com.loqui.android.audio.vad

import com.loqui.android.testing.pcm16Samples
import org.junit.Assert.assertEquals
import org.junit.Test

class Pcm16SignalTest {

    @Test
    fun `silence measures zero`() {
        val rms = Pcm16Signal.rms(pcm16Samples(320) { 0 })
        assertEquals(0f, rms, 0.0001f)
    }

    @Test
    fun `a full scale square wave measures one`() {
        val rms = Pcm16Signal.rms(pcm16Samples(320) { Short.MAX_VALUE.toInt() })
        assertEquals(1f, rms, 0.001f)
    }

    @Test
    fun `half scale measures a half regardless of sign`() {
        assertEquals(0.5f, Pcm16Signal.rms(pcm16Samples(320) { 16_384 }), 0.001f)
        assertEquals(0.5f, Pcm16Signal.rms(pcm16Samples(320) { -16_384 }), 0.001f)
    }

    @Test
    fun `a dangling odd byte is ignored instead of misread`() {
        assertEquals(0f, Pcm16Signal.rms(ByteArray(3)), 0.0001f)
    }

    @Test
    fun `an empty frame is silent`() {
        assertEquals(0f, Pcm16Signal.rms(ByteArray(0)), 0.0001f)
    }
}
