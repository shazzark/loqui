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
    fun `galaxy microphone room levels above the old fallback do not open speech`() = runTest {
        val config = VadConfig(noSpeechTimeoutMs = 1_000)
        val endpointer = SpeechEndpointer(config, EnergyVad(config))
        val roomTone = List(100) { index -> audioFrame(if (index % 2 == 0) 120 else 220) }

        val emitted = endpointer.segment(roomTone.asFlow()).toList()

        assertTrue("measured room tone must not reach ASR", emitted.isEmpty())
    }

    @Test
    fun `a window full of speech cannot raise the gate past the ceiling`() {
        val vad = detector()

        measured(vad, SPEECH_AMPLITUDE, frames = 50)

        assertEquals(VadConfig.MIN_SPEECH_RMS, vad.speechRmsThreshold, 0f)
        assertTrue(
            "normal speech must still pass the highest gate calibration may produce",
            vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)),
        )
    }

    @Test
    fun `speech during sparse calibration remains buffered to the one second bound`() {
        val vad = detector()

        // A few room samples cannot represent the noise floor. Keep the user's speech
        // buffered until the one-second calibration cap instead of locking a low gate.
        measured(vad, roomToneAmplitude, frames = 6)
        measured(vad, SPEECH_AMPLITUDE, frames = 44)

        assertTrue(vad.isCalibrated)
        // 0.001007 x 2, measured from the quiet frames at the head of the window; the
        // louder speech frames are rejected as room measurements.
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
        // Speech energy is kept out of the room estimate; it stays buffered until the
        // bounded calibration window has enough room samples or reaches its one-second cap.
        repeat(14) { assertFalse(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE))) }
        assertFalse(vad.isCalibrated)

        repeat(36) { assertFalse(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE))) }
        assertTrue(vad.isCalibrated)
        assertTrue(vad.classifyCalibrated(audioFrame(SPEECH_AMPLITUDE)))
    }

    @Test
    fun `startup zeroes do not become the measured room floor`() {
        val vad = detector()

        repeat(15) { vad.isSpeech(audioFrame(0)) }
        assertFalse(vad.isCalibrated)
        repeat(4) { vad.isSpeech(audioFrame(roomToneAmplitude)) }

        assertFalse("four readings must not lock a noisy room gate", vad.isCalibrated)
        repeat(8) { vad.isSpeech(audioFrame(roomToneAmplitude)) }

        assertTrue(vad.isCalibrated)
        assertTrue("room tone after startup zeroes must raise the gate", vad.speechRmsThreshold > 0.001f)
        assertFalse(vad.classifyCalibrated(audioFrame(roomToneAmplitude)))
        assertTrue(vad.classifyCalibrated(audioFrame(SPEECH_AMPLITUDE)))
    }

    @Test
    fun `sparse room samples at the calibration cap use their median`() {
        val vad = detector()

        repeat(15) { vad.isSpeech(audioFrame(0)) }
        vad.isSpeech(audioFrame(120))
        vad.isSpeech(audioFrame(220))
        repeat(33) { vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)) }

        assertTrue("calibration must stop at one second", vad.isCalibrated)
        assertEquals(220 / 32_768f, vad.noiseFloorRms, 1e-6f)
        assertFalse("a lower startup reading must not dominate sparse room data", vad.isSpeech(audioFrame(120)))
    }

    @Test
    fun `speech onset levels do not set the room floor`() {
        val vad = detector()

        repeat(VadConfig().calibrationFrames) { vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)) }
        assertFalse("speech at startup must remain buffered", vad.isCalibrated)
        repeat(50 - VadConfig().calibrationFrames) { vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)) }

        assertTrue("calibration remains bounded to one second", vad.isCalibrated)
        assertEquals(VadConfig.MIN_SPEECH_RMS, vad.speechRmsThreshold, 0f)
        assertTrue(vad.classifyCalibrated(audioFrame(SPEECH_AMPLITUDE)))
    }

    @Test
    fun `quiet speech just below the gate is retained until onset confirms`() = runTest {
        val config = VadConfig(onsetFrames = 3, silenceHangoverFrames = 5)
        val earlySpeech = List(4) { audioFrame(50) }
        val louderSpeech = List(3) { audioFrame(SPEECH_AMPLITUDE) }
        val endpointer = SpeechEndpointer(config, EnergyVad(config))
        val input = List(15) { audioFrame(roomToneAmplitude) } +
            earlySpeech + louderSpeech + List(5) { audioFrame(QUIET_AMPLITUDE) }

        val emitted = endpointer.segment(input.asFlow()).toList()

        assertEquals(
            (List(15) { audioFrame(roomToneAmplitude) } + earlySpeech + louderSpeech +
                List(5) { audioFrame(QUIET_AMPLITUDE) })
                .map { it.pcm16.toList() },
            emitted.map { it.pcm16.toList() },
        )
    }

    @Test
    fun `speech included in room calibration is replayed before the louder onset`() = runTest {
        val config = VadConfig(onsetFrames = 3, silenceHangoverFrames = 5)
        val calibrationSpeech = List(15) { audioFrame(150) }
        val quietOpening = List(4) { audioFrame(100) }
        val confirmation = List(3) { audioFrame(SPEECH_AMPLITUDE) }
        val trailingSilence = List(5) { audioFrame(QUIET_AMPLITUDE) }
        val endpointer = SpeechEndpointer(config, EnergyVad(config))

        val emitted = endpointer.segment(
            (calibrationSpeech + quietOpening + confirmation + trailingSilence).asFlow(),
        ).toList()

        assertEquals(
            (calibrationSpeech + quietOpening + confirmation + trailingSilence)
                .map { it.pcm16.toList() },
            emitted.map { it.pcm16.toList() },
        )
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
     * The gate is measured from the room, so it cannot tell a pause from a quiet phrase: a
     * talker who leans back still sits above it. These cases pin the second half of the
     * decision — a frame has to be within [VadConfig.relativeSilenceFactor] of this
     * utterance's own peak.
     */
    @Test
    fun `a frame far below the utterance's peak is a pause even above the gate`() {
        val vad = detector(VadConfig(calibrationMs = 0))

        assertTrue(
            "the loud frame has to be accepted first",
            vad.isSpeech(audioFrame(SPEECH_AMPLITUDE)),
        )
        assertEquals(SPEECH_AMPLITUDE / 32_768f, vad.peakSpeechRms, 1e-6f)

        // ~0.003 RMS: above the room gate but below five percent of the peak. Without the
        // relative test this opened a 10 s utterance that transcribed nothing.
        val trailedOff = audioFrame(100)
        assertTrue(Pcm16Signal.rms(trailedOff.pcm16) > vad.speechRmsThreshold)
        assertFalse("a trailing-off tail must read as silence", vad.isSpeech(trailedOff))
    }

    @Test
    fun `a soft word that is genuinely speech still counts`() {
        val vad = detector(VadConfig(calibrationMs = 0))

        vad.isSpeech(audioFrame(SPEECH_AMPLITUDE))

        // Half the peak is a normal quiet word — ~6 dB down, not 16.
        assertTrue(vad.isSpeech(audioFrame(SPEECH_AMPLITUDE / 2)))
    }

    @Test
    fun `the peak is the loudest frame accepted, not the loudest frame seen`() {
        val vad = detector(VadConfig(calibrationMs = 0))

        // A frame that fails the gate must not raise the reference: otherwise one door slam
        // would make the rest of the sentence look like a pause.
        vad.isSpeech(audioFrame(SPEECH_AMPLITUDE))
        vad.isSpeech(audioFrame(QUIET_AMPLITUDE))

        assertEquals(SPEECH_AMPLITUDE / 32_768f, vad.peakSpeechRms, 1e-6f)
    }

    @Test
    fun `a reset clears the utterance's peak`() {
        val vad = detector(VadConfig(calibrationMs = 0))
        vad.isSpeech(audioFrame(SPEECH_AMPLITUDE))
        assertTrue(vad.peakSpeechRms > 0f)

        vad.reset()

        // The next utterance starts at a new distance; carrying the last peak over would
        // make a user who moved back from the phone inaudible for the whole session.
        assertEquals(0f, vad.peakSpeechRms, 0f)
    }

    /**
     * The failure the calibrated gate exists to prevent: with room tone above the gate,
     * onset confirms on noise, the utterance runs to the ceiling, and the recogniser is
     * handed 15 s of nothing.
     */
    @Test
    fun `room tone above the old gate no longer opens an utterance`() = runTest {
        // The shipping calibration window, not an override: the fix has to hold with the
        // defaults the app actually runs.
        val config = VadConfig(noSpeechTimeoutMs = 1_000)
        val endpointer = SpeechEndpointer(config, EnergyVad(config))

        val emitted = endpointer.segment(
            (List(25) { roomToneAmplitude } + List(25) { roomToneAmplitude })
                .map { audioFrame(it) }
                .asFlow(),
        ).toList()

        assertTrue("noise must never reach the recogniser", emitted.isEmpty())
    }
}
