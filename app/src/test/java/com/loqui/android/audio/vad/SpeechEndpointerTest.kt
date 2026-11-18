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
        minSpeechRmsThreshold = 0.02f,
        maxSpeechRmsThreshold = 0.5f,
        calibrationMs = 0,
        onsetFrames = 3,
        silenceHangoverFrames = 5,
        maxUtteranceMs = 1_000,
    )

    private fun endpointer(config: VadConfig = this.config) =
        SpeechEndpointer(config, EnergyVad(config))

    private fun speech(count: Int = 1) = List(count) { audioFrame(SPEECH_AMPLITUDE) }

    private fun silence(count: Int = 1) = List(count) { audioFrame(QUIET_AMPLITUDE) }

    @Test
    fun `energy detection separates speech from quiet and from silence`() {
        val detector = EnergyVad(config)

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
    fun `calibration preserves immediate speech once in original order`() = runTest {
        val input = List(15) { audioFrame(0) } + speech(40) + silence(5)

        val emitted = endpointer(VadConfig(onsetFrames = 3, silenceHangoverFrames = 5))
            .segment(input.asFlow())
            .toList()

        // The first voiced frames are replayed from calibration, then the onset confirms.
        // No frame is duplicated, reordered, or lost at the calibration edge.
        assertEquals(40 + 5, emitted.size)
        assertEquals(
            (speech(40) + silence(5)).map { it.pcm16.toList() },
            emitted.map { it.pcm16.toList() },
        )
    }

    @Test
    fun `zero startup buffers then room tone still closes after silence hangover`() = runTest {
        val shipping = VadConfig()
        val input = List(15) { audioFrame(0) } +
            List(4) { audioFrame(33) } +
            speech(3) +
            List(shipping.silenceHangoverFrames + 10) { audioFrame(33) }

        val emitted = SpeechEndpointer(shipping, EnergyVad(shipping))
            .segment(input.asFlow())
            .toList()

        assertEquals(3 + shipping.silenceHangoverFrames, emitted.size)
        assertEquals(
            (speech(3) + List(shipping.silenceHangoverFrames) { audioFrame(33) })
                .map { it.pcm16.toList() },
            emitted.map { it.pcm16.toList() },
        )
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
    fun `one second mid-sentence pause preserves later speech and resets silence`() = runTest {
        val shipping = VadConfig(relativeSilenceFactor = 0.05f)
        val emitted = endpointer(shipping).segment(
            (silence(15) + speech(3) + silence(50) + speech(3) + silence(75)).asFlow(),
        ).toList()

        assertEquals(3 + 50 + 3 + 75, emitted.size)
    }

    @Test
    fun `shipping hangover closes after 1500 milliseconds of silence`() = runTest {
        val shipping = VadConfig()
        var pulled = 0
        val microphone = flow {
            repeat(15) {
                pulled++
                emit(audioFrame(QUIET_AMPLITUDE))
            }
            repeat(3) {
                pulled++
                emit(audioFrame(SPEECH_AMPLITUDE))
            }
            repeat(100) {
                pulled++
                emit(audioFrame(QUIET_AMPLITUDE))
            }
        }

        val emitted = endpointer(shipping).segment(microphone).toList()

        assertEquals(78, emitted.size)
        assertEquals(93, pulled)
        assertEquals(1_500, shipping.silenceHangoverFrames * shipping.frameDurationMs)
    }

    @Test
    fun `brief room noise spikes do not restart the silence hangover`() = runTest {
        val shipping = VadConfig(calibrationMs = 0)
        val roomWithTransientSpikes = List(100) { index ->
            if (index > 0 && index % 25 == 0) audioFrame(400) else audioFrame(QUIET_AMPLITUDE)
        }
        var pulled = 0
        val microphone = flow {
            (speech(3) + roomWithTransientSpikes).forEach {
                pulled++
                emit(it)
            }
        }

        val emitted = SpeechEndpointer(shipping, EnergyVad(shipping))
            .segment(microphone)
            .toList()

        assertEquals(3 + shipping.silenceHangoverFrames, emitted.size)
        assertEquals(3 + shipping.silenceHangoverFrames, pulled)
    }

    @Test
    fun `the default ceilings are ten seconds of speech and eight without`() {
        val defaults = VadConfig()

        // Ten seconds, not fifteen: the old ceiling was long enough that a talker who
        // trailed off into the gate's blind spot left the bubble listening for a quarter of
        // a minute. Anything still running at 10 s is better served by an early final.
        assertEquals(500, defaults.maxUtteranceFrames)
        assertEquals(400, defaults.noSpeechTimeoutFrames)
        assertEquals(8_000, defaults.noSpeechTimeoutMs)
        // The 1,500 ms hangover preserves sentence pauses and closes on sustained silence.
        assertEquals(75, defaults.silenceHangoverFrames)
        assertEquals(1_500, defaults.silenceHangoverFrames * defaults.frameDurationMs)
        assertEquals(0.05f, defaults.relativeSilenceFactor, 0f)
        assertTrue(
            "the gate must sit below what a phone mic delivers at arm's length",
            defaults.minSpeechRmsThreshold <= 0.01f,
        )
        // A Galaxy A13 delivered ~0.0002 RMS of room tone *and* ~0.0003 RMS of speech
        // through VOICE_RECOGNITION: no single constant separates those, which is why the
        // gate is measured per session and only bounded by these two values.
        assertEquals(0.0005f, defaults.minSpeechRmsThreshold, 0f)
        assertEquals(2f, defaults.noiseFloorMultiplier, 0f)
        assertTrue(
            "the highest gate a bad calibration may reach must still hear normal speech",
            defaults.maxSpeechRmsThreshold <= 0.02f,
        )
        assertEquals(300, defaults.calibrationMs)
        assertEquals(15, defaults.calibrationFrames)
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
