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

    /**
     * The user's second tap. [AudioCaptureStream.stop] has to *end* the flow, not cancel
     * it: a cancelled collector throws the utterance away before the recogniser sees it,
     * which is how a manual stop used to lose the transcript.
     */
    @Test
    fun `stop closes the microphone after the current frame`() = runTest {
        lateinit var stream: AudioCaptureStream
        var reads = 0
        var releases = 0
        val chunk = pcm16Samples(320) { 5 }
        // The tap lands inside the read loop, which is where a real one arrives.
        val source = object : PcmAudioSource {
            override fun start() = Unit

            override fun read(destination: ByteArray, offset: Int, size: Int): Int {
                reads++
                if (reads == 3) {
                    stream.stop()
                    stream.stop() // idempotent: a double tap must not close anything twice
                }
                chunk.copyInto(destination, offset)
                return chunk.size
            }

            override fun release() {
                releases++
            }
        }
        stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )

        val frames = stream.frames().toList()

        assertEquals("the loop must exit on the stop, not drain the microphone", 3, frames.size)
        assertEquals("no read after the stop", 3, reads)
        assertEquals(1, releases)
    }

    @Test
    fun `a stop from the previous session does not end the next one`() = runTest {
        val source = FakePcmAudioSource(List(4) { pcm16Samples(320) { 5 } })
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )
        // The tap that stopped the last session landed after that session had finished.
        stream.stop()

        val frames = stream.frames().toList()

        assertEquals(4, frames.size)
    }
}
