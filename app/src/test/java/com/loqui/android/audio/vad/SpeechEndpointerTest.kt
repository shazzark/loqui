package com.loqui.android.audio.vad

import com.loqui.android.testing.QUIET_AMPLITUDE
import com.loqui.android.testing.SPEECH_AMPLITUDE
import com.loqui.android.testing.audioFrame
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechEndpointerTest {

    /** Tight timings so each case stays readable: 60 ms onset, 100 ms hangover. */
    private val config = VadConfig(
        frameDurationMs = 20,
        speechRmsThreshold = 0.02f,
        onsetFrames = 3,
        silenceHangoverFrames = 5,
        maxUtteranceMs = 1_000,
    )

    private fun endpointer(config: VadConfig = this.config) =
        SpeechEndpointer(config, EnergyVad(config.speechRmsThreshold))

    private fun speech(count: Int = 1) = List(count) { audioFrame(SPEECH_AMPLITUDE) }

    private fun silence(count: Int = 1) = List(count) { audioFrame(QUIET_AMPLITUDE) }

    @Test
    fun `energy detection separates speech from quiet and from silence`() {
        val detector = EnergyVad(config.speechRmsThreshold)

        assertTrue(detector.isSpeech(audioFrame(SPEECH_AMPLITUDE)))
        assertFalse(detector.isSpeech(audioFrame(QUIET_AMPLITUDE)))
        assertFalse(detector.isSpeech(audioFrame(0)))
    }

    @Test
    fun `silence sends nothing to the recognizer`() = runTest {
        val emitted = endpointer().segment(silence(100).asFlow()).toList()

        assertTrue(emitted.isEmpty())
    }

    @Test
    fun `a single loud frame is treated as noise and ignored`() = runTest {
        val emitted = endpointer().segment((speech(1) + silence(20)).asFlow()).toList()

        assertTrue("a click must not open an utterance", emitted.isEmpty())
    }

    @Test
    fun `onset then hangover emits exactly the utterance`() = runTest {
        val emitted = endpointer().segment((speech(3) + silence(10)).asFlow()).toList()

        // 3 onset frames + 5 frames of hangover; the segment closes on the fifth.
        assertEquals(8, emitted.size)
    }

    @Test
    fun `the endpoint stops pulling audio from the microphone`() = runTest {
        var pulled = 0
        val microphone = flow {
            repeat(3) {
                pulled++
                emit(audioFrame(SPEECH_AMPLITUDE))
            }
            repeat(100) {
                pulled++
                emit(audioFrame(QUIET_AMPLITUDE))
            }
        }

        endpointer().segment(microphone).toList()

        assertEquals(8, pulled)
    }

    @Test
    fun `a very long utterance is cut at the configured ceiling`() = runTest {
        val capsAtFiveFrames = config.copy(maxUtteranceMs = 100)
        val emitted = endpointer(capsAtFiveFrames).segment(speech(50).asFlow()).toList()

        assertEquals(5, emitted.size)
    }

    @Test
    fun `an utterance that never hits the endpoint still reaches the recognizer`() = runTest {
        // The microphone stopped (bubble released, source error) while the user spoke.
        val emitted = endpointer().segment(speech(4).asFlow()).toList()

        assertEquals(4, emitted.size)
    }

    @Test
    fun `a session with no speech closes at the no-speech timeout`() = runTest {
        val quickTimeout = config.copy(noSpeechTimeoutMs = 100)
        var pulled = 0
        val microphone = flow {
            repeat(100) {
                pulled++
                emit(audioFrame(QUIET_AMPLITUDE))
            }
        }

        val emitted = endpointer(quickTimeout).segment(microphone).toList()

        assertTrue("silence must not reach the recogniser", emitted.isEmpty())
        // Five 20 ms frames: the session ends instead of listening forever.
        assertEquals(5, pulled)
    }

    @Test
    fun `speech inside the no-speech window keeps the session open`() = runTest {
        val quickTimeout = config.copy(noSpeechTimeoutMs = 100, maxUtteranceMs = 2_000)

        val emitted = endpointer(quickTimeout)
            .segment((silence(2) + speech(3) + silence(10)).asFlow())
            .toList()

        // The timeout never fires once onset has confirmed: 3 onset + 5 hangover.
        assertEquals(8, emitted.size)
    }

    @Test
    fun `the default ceilings are fifteen seconds of speech and eight without`() {
        val defaults = VadConfig()

        assertEquals(750, defaults.maxUtteranceFrames)
        assertEquals(400, defaults.noSpeechTimeoutFrames)
        assertEquals(8_000, defaults.noSpeechTimeoutMs)
        assertTrue(
            "the gate must sit below what a phone mic delivers at arm's length",
            defaults.speechRmsThreshold <= 0.01f,
        )
        assertEquals(0.003f, defaults.speechRmsThreshold, 0f)
    }

    @Test
    fun `one endpointer instance can serve consecutive sessions`() = runTest {
        val shared = endpointer()

        val first = shared.segment(speech(3).asFlow()).toList()
        val second = shared.segment((speech(3) + silence(10)).asFlow()).toList()

        assertEquals(3, first.size)
        assertEquals(8, second.size)
    }

    @Test
    fun `speech after a long pause restarts the utterance`() = runTest {
        val emitted = endpointer().segment(
            (speech(3) + silence(2) + speech(4) + silence(10)).asFlow(),
        ).toList()

        // The 40 ms pause is shorter than the hangover, so it stays one utterance.
        assertEquals(3 + 2 + 4 + 5, emitted.size)
    }
}
