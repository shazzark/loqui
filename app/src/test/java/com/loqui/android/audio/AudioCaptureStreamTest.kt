package com.loqui.android.audio

import com.loqui.android.asr.AudioFrame
import com.loqui.android.testing.FakePcmAudioSource
import com.loqui.android.testing.pcm16Samples
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AudioCaptureStreamTest {

    @Test
    fun `streams one 20 ms frame per read at 16 kHz`() = runTest {
        val chunks = listOf(
            pcm16Samples(320) { 1 },
            pcm16Samples(320) { 2 },
            pcm16Samples(320) { 3 },
        )
        val source = FakePcmAudioSource(chunks)
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )

        val frames = stream.frames().toList()

        assertEquals(3, frames.size)
        assertTrue(frames.all { it.pcm16.size == AudioCaptureStream.FRAME_BYTES })
        assertTrue(frames.all { it.sampleRateHz == AudioFrame.SAMPLE_RATE_HZ })

        val concatenated = frames.drop(1).fold(frames.first().pcm16) { acc, frame -> acc + frame.pcm16 }
        assertArrayEquals(chunks[0] + chunks[1] + chunks[2], concatenated)
    }

    @Test
    fun `assembles a frame from two partial reads`() = runTest {
        val source = FakePcmAudioSource(
            listOf(
                pcm16Samples(160) { 7 },
                pcm16Samples(160) { 8 },
            ),
        )
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )

        val frames = stream.frames().toList()

        assertEquals(1, frames.size)
        assertEquals(AudioCaptureStream.FRAME_BYTES, frames.single().pcm16.size)
        // Two partial reads plus the read that reports the end of the source.
        assertEquals(3, source.reads)
    }

    @Test
    fun `releases the microphone when the source ends`() = runTest {
        val source = FakePcmAudioSource(listOf(pcm16Samples(320) { 0 }))
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )

        stream.frames().toList()

        assertEquals(1, source.started)
        assertEquals(1, source.releaseCount)
    }

    @Test
    fun `releases the microphone when the collector cancels`() = runTest {
        val source = FakePcmAudioSource(List(64) { pcm16Samples(320) { 5 } })
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )

        val firstFrame = stream.frames().first()
        advanceUntilIdle()

        assertEquals(AudioCaptureStream.FRAME_BYTES, firstFrame.pcm16.size)
        assertEquals(1, source.releaseCount)
    }

    @Test
    fun `ends quietly when the microphone fails immediately`() = runTest {
        val source = FakePcmAudioSource(emptyList())
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )

        assertTrue(stream.frames().toList().isEmpty())
        assertEquals(1, source.releaseCount)
    }
}
