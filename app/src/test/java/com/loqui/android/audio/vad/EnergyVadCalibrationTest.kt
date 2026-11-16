package com.loqui.android.audio.vad

import com.loqui.android.testing.QUIET_AMPLITUDE
import com.loqui.android.testing.SPEECH_AMPLITUDE
import com.loqui.android.testing.audioFrame
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The gate a real phone needs, reproduced from device measurements.
 *
 * A Galaxy A13 reported ~0.0002 RMS of room tone and only ~0.0003 RMS of speech through
 * the `VOICE_RECOGNITION` chain. A single constant cannot separate those, so these tests
 * pin the two properties that matter: room tone never reaches the gate, and normal speech
 * always does.
 */
class EnergyVadCalibrationTest {

    /** ~0.001 RMS: a café, or a fan in the room. Above the minimum gate. */
    private val roomToneAmplitude = 33

    private fun detector(config: VadConfig = VadConfig()) = EnergyVad(config)

    private fun measured(detector: EnergyVad, amplitude: Int, frames: Int = 25) {
        repeat(frames) { detector.isSpeech(audioFrame(amplitude)) }
    }

    @Test
    fun `a silent room leaves the gate at its minimum`() {
        val vad = detector()

        measured(vad, QUIET_AMPLITUDE)

        assertTrue(vad.isCalibrated)
        // Doubling the quietest measurable room lands below the floor, so the floor wins.
        assertEquals(VadConfig.MIN_SPEECH_RMS, vad.speechRmsThreshold, 1e-6f)
        assertFalse(vad.isSpeech(audioFrame(QUIET_AMPLITUDE)))
        assertTrue(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)))
    }

    @Test
    fun `a noisy room pushes the gate above its own noise`() {
        val vad = detector()

        measured(vad, roomToneAmplitude)

        assertTrue("the floor must be measured, not configured", vad.noiseFloorRms > 0.0005f)
        assertTrue(
            "the gate must clear the noise it was measured from",
            vad.speechRmsThreshold > vad.noiseFloorRms,
        )
        // Room tone stays out, speech gets in: the whole point of calibrating.
        assertFalse(vad.isSpeech(audioFrame(roomToneAmplitude)))
        assertTrue(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)))
    }

    @Test
    fun `a window full of speech cannot raise the gate past the ceiling`() {
        val vad = detector()

        measured(vad, SPEECH_AMPLITUDE)

        assertEquals(VadConfig().maxSpeechRmsThreshold, vad.speechRmsThreshold, 0f)
        assertTrue(
            "normal speech must still pass the highest gate calibration may produce",
            vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)),
        )
    }

    @Test
    fun `a few frames of room tone are enough when the user starts talking at once`() {
        val vad = detector()

        // 120 ms of room tone, then the user is already mid-sentence.
        measured(vad, roomToneAmplitude, frames = 6)
        measured(vad, SPEECH_AMPLITUDE, frames = 19)

        assertTrue(vad.isCalibrated)
        // 0.001007 x 2, measured from the quiet sixth of the window and not from the 19
        // frames of speech that dominate it.
        assertEquals(0.002f, vad.speechRmsThreshold, 1e-4f)
        assertTrue(
            "speech must still pass a gate measured out of a window it dominated",
            vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)),
        )
    }

    @Test
    fun `no frame is speech until the room has been measured`() {
        val vad = detector()

        // Even a shout is unmeasurable during the window: there is no gate to judge it by.
        repeat(24) { assertFalse(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE))) }
        assertFalse(vad.isCalibrated)

        // The frame that closes the window is judged by the measured gate.
        assertTrue(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)))
        assertTrue(vad.isCalibrated)
    }

    @Test
    fun `calibration can be disabled`() {
        val vad = detector(VadConfig(calibrationMs = 0))

        // No window, so the gate is the configured minimum from the first frame.
        assertTrue(vad.isCalibrated)
        assertEquals(VadConfig.MIN_SPEECH_RMS, vad.speechRmsThreshold, 0f)
        assertFalse(vad.isSpeech(audioFrame(QUIET_AMPLITUDE)))
        assertTrue(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)))
    }

    @Test
    fun `a reset returns the detector to its uncalibrated state`() {
        val vad = detector()
        measured(vad, roomToneAmplitude)
        val calibratedGate = vad.speechRmsThreshold

        vad.reset()

        assertFalse(vad.isCalibrated)
        assertEquals(VadConfig.MIN_SPEECH_RMS, vad.speechRmsThreshold, 0f)
        assertTrue("the previous session's floor must not leak", calibratedGate > vad.speechRmsThreshold)
    }

    /**
     * The failure the calibrated gate exists to prevent: with room tone above the gate,
     * onset confirms on noise, the utterance runs to the ceiling, and the recogniser is
     * handed 15 s of nothing.
     */
    @Test
    fun `room tone above the old gate no longer opens an utterance`() = runTest {
        val config = VadConfig(
            frameDurationMs = 20,
            minSpeechRmsThreshold = 0.0005f,
            calibrationMs = 500,
            noSpeechTimeoutMs = 1_000,
        )
        val endpointer = SpeechEndpointer(config, EnergyVad(config))

        val emitted = endpointer.segment(
            (List(25) { roomToneAmplitude } + List(25) { roomToneAmplitude })
                .map { audioFrame(it) }
                .asFlow(),
        ).toList()

        assertTrue("noise must never reach the recogniser", emitted.isEmpty())
    }
}
