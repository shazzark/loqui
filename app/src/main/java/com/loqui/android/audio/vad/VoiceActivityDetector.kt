package com.loqui.android.audio.vad

import com.loqui.android.asr.AudioFrame
import timber.log.Timber

/**
 * Decides whether one frame of audio contains speech.
 *
 * The interface exists so the endpointing state machine is independent of the
 * detector: Phase 2 ships [EnergyVad], and a Silero ONNX detector can replace it
 * without touching [SpeechEndpointer] or its tests.
 */
interface VoiceActivityDetector {

    /** Whether the opening calibration window has completed. */
    val isCalibrated: Boolean get() = true

    /** Drops any per-stream state (noise floor, model state) before a new utterance. */
    fun reset()

    fun isSpeech(frame: AudioFrame): Boolean

    /** Re-evaluates buffered frames after calibration without adding them twice. */
    fun classifyCalibrated(frame: AudioFrame): Boolean = isSpeech(frame)
}

/**
 * Energy-based detector whose gate is measured per session.
 *
 * The opening [VadConfig.calibrationFrames] frames are sampled for room tone. Only levels
 * up to [MAX_ROOM_CALIBRATION_RMS] count: louder frames may be the user's first words.
 * If fewer than four room-level frames arrive, calibration extends to
 * [MAX_CALIBRATION_FRAMES] frames (one second), then uses the fallback floor if no room
 * sample arrived. The endpointer retains and classifies every frame in that window once
 * the gate is known. The quiet fifth of accepted room samples becomes the noise floor and
 * the gate is set to `max(minSpeechRmsThreshold, noiseFloor * noiseFloorMultiplier)`,
 * capped at [VadConfig.maxSpeechRmsThreshold]. No frame is reported as speech while
 * calibration is open, so an unmeasured room cannot open an utterance.
 *
 * The 20th percentile resists brief noises in the measurement window. Four qualifying
 * frames are enough to finish the nominal window; zero-filled startup buffers and speech
 * levels do not count as room measurements.
 *
 * The absolute gate is only half of the decision. A gate sits where the room is, and a
 * talker who leans back or trails off mid-sentence lands in the band between the room and
 * the gate: every frame is still louder than the café, so the gate calls it speech, the
 * utterance never falls silent, and it drags on to [VadConfig.maxUtteranceMs] with the
 * recogniser producing nothing new. So a frame must also be within
 * [VadConfig.relativeSilenceFactor] of the loudest frame of the current utterance to count
 * as speech. The peak makes the test relative to *this* voice at *this* distance instead of
 * to the room, which is the only reference that stays correct as a user moves.
 *
 * Deliberately dependency-free and deterministic; the accuracy-critical Silero model
 * slots in behind [VoiceActivityDetector] when the on-device runtime lands.
 */
class EnergyVad(
    private val config: VadConfig = VadConfig(),
) : VoiceActivityDetector {

    /** Bounded RMS history, extended if AudioRecord starts with zero-filled frames. */
    private val calibrationWindow = FloatArray(maxOf(config.calibrationFrames, MAX_CALIBRATION_FRAMES))
    private var calibratedFrames = 0
    private var roomCalibrationFrames = 0

    /** Measured room tone; [FALLBACK_NOISE_FLOOR] until the window closes. */
    var noiseFloorRms: Float = FALLBACK_NOISE_FLOOR
        private set

    /** Gate currently in force, always inside the configured bounds. */
    var speechRmsThreshold: Float = config.minSpeechRmsThreshold
        private set

    /**
     * Loudest frame accepted as speech since [reset]. Zero before the first one.
     *
     * Kept for the whole utterance rather than over a sliding window: a window would let
     * the reference sink during a pause and start calling room tone speech again, which is
     * the failure this exists to stop.
     */
    var peakSpeechRms: Float = 0f
        private set

    /** True once [config]'s window has been measured in this session. */
    override val isCalibrated: Boolean
        get() = config.calibrationFrames == 0 ||
            (calibratedFrames >= config.calibrationFrames &&
                (roomCalibrationFrames >= MIN_ROOM_CALIBRATION_FRAMES ||
                    calibratedFrames >= calibrationWindow.size))

    var lastFrameRms: Float = 0f
        private set

    var lastFrameWasSpeech: Boolean = false
        private set

    override fun reset() {
        calibratedFrames = 0
        roomCalibrationFrames = 0
        noiseFloorRms = FALLBACK_NOISE_FLOOR
        speechRmsThreshold = config.minSpeechRmsThreshold
        peakSpeechRms = 0f
        lastFrameRms = 0f
        lastFrameWasSpeech = false
    }

    override fun isSpeech(frame: AudioFrame): Boolean {
        val rms = Pcm16Signal.rms(frame.pcm16)
        lastFrameRms = rms
        if (!isCalibrated) {
            calibrate(rms)
            return false
        }
        return classify(rms)
    }

    override fun classifyCalibrated(frame: AudioFrame): Boolean {
        val rms = Pcm16Signal.rms(frame.pcm16)
        lastFrameRms = rms
        return classify(rms)
    }

    private fun classify(rms: Float): Boolean {
        lastFrameWasSpeech = false
        if (rms < speechRmsThreshold) return false

        // The relative half of the decision: far quieter than the loudest part of what is
        // already being transcribed is a pause, even when the room gate would accept it.
        if (peakSpeechRms > 0f && rms < peakSpeechRms * config.relativeSilenceFactor) {
            return false
        }
        if (rms > peakSpeechRms) peakSpeechRms = rms
        lastFrameWasSpeech = true
        return true
    }

    /**
     * Records one frame of the opening window and applies the gate as soon as the window
     * is full. A window of `0` frames disables calibration entirely.
     */
    private fun calibrate(rms: Float) {
        if (config.calibrationFrames == 0) return

        if (rms in MIN_ROOM_CALIBRATION_RMS..MAX_ROOM_CALIBRATION_RMS) {
            roomCalibrationFrames++
        }
        calibrationWindow[calibratedFrames] = rms
        calibratedFrames++
        if (!isCalibrated) return

        val sorted = calibrationWindow.take(calibratedFrames)
            .filter { it in MIN_ROOM_CALIBRATION_RMS..MAX_ROOM_CALIBRATION_RMS }
            .sorted()
        // The 20th percentile: the quiet part of the room, not the middle of the window
        // and not an average the speaker's own voice would dominate.
        noiseFloorRms = sorted.getOrNull(sorted.size / 5) ?: FALLBACK_NOISE_FLOOR
        speechRmsThreshold = (noiseFloorRms * config.noiseFloorMultiplier).coerceIn(
            config.minSpeechRmsThreshold,
            config.maxSpeechRmsThreshold,
        )
        // The one line that explains both halves of the gate problem on a real device:
        // a floor near the old gate means the onset used to trip on room tone, a gate
        // above the speech RMS means it used to hear nothing.
        Timber.d(
            "VAD calibrated on %d frames (%d ms): noise floor %.5f, speech gate %.5f",
            calibratedFrames,
            calibratedFrames * config.frameDurationMs,
            noiseFloorRms,
            speechRmsThreshold,
        )
    }

    private companion object {
        const val MAX_CALIBRATION_FRAMES = 50
        const val MIN_ROOM_CALIBRATION_FRAMES = 4
        const val MIN_ROOM_CALIBRATION_RMS = 0.000001f
        const val MAX_ROOM_CALIBRATION_RMS = 0.002f
        /**
         * -84 dBFS: what a gate falls back to before calibration and when calibration is
         * disabled. Low enough that nothing a microphone delivers can hide under it.
         */
        const val FALLBACK_NOISE_FLOOR = 0.000061f
    }
}
