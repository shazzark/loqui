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

    /** Drops any per-stream state (noise floor, model state) before a new utterance. */
    fun reset()

    fun isSpeech(frame: AudioFrame): Boolean
}

/**
 * Energy-based detector whose gate is measured per session.
 *
 * The first [VadConfig.calibrationFrames] frames of a session are treated as room tone:
 * their quiet fifth becomes the noise floor and the gate is set to
 * `max(minSpeechRmsThreshold, noiseFloor * noiseFloorMultiplier)`, capped at
 * [VadConfig.maxSpeechRmsThreshold]. No frame is reported as speech until the window
 * closes: before the room is measured the only gate available is
 * [VadConfig.minSpeechRmsThreshold], and room tone in a café sits above it — which is
 * exactly the false onset that used to run an utterance to its 10 s ceiling. The cost is
 * that speech beginning in the first three tenths of a second of a session is not part of
 * the utterance; the bubble needs those 300 ms to appear anyway.
 *
 * Why the 20th percentile and not the mean or the median: the window can contain the
 * start of the sentence. Four of the 15 frames (80 ms of quiet after the tap) are enough
 * to measure the room, while a burst of speech — or one zero-filled buffer from
 * `AudioRecord` settling — cannot move the estimate on its own.
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

    /** RMS of every frame in the calibration window, written once each. */
    private val calibrationWindow = FloatArray(config.calibrationFrames)
    private var calibratedFrames = 0

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
    val isCalibrated: Boolean
        get() = calibratedFrames >= calibrationWindow.size

    override fun reset() {
        calibratedFrames = 0
        noiseFloorRms = FALLBACK_NOISE_FLOOR
        speechRmsThreshold = config.minSpeechRmsThreshold
        peakSpeechRms = 0f
    }

    override fun isSpeech(frame: AudioFrame): Boolean {
        val rms = Pcm16Signal.rms(frame.pcm16)
        if (!isCalibrated) {
            calibrate(rms)
            // The frame that completes the window is classified with the measured gate;
            // every earlier one is unmeasurable by definition.
            if (!isCalibrated) return false
        }
        if (rms < speechRmsThreshold) return false

        // The relative half of the decision: far quieter than the loudest part of what is
        // already being transcribed is a pause, even when the room gate would accept it.
        if (peakSpeechRms > 0f && rms < peakSpeechRms * config.relativeSilenceFactor) {
            return false
        }
        if (rms > peakSpeechRms) peakSpeechRms = rms
        return true
    }

    /**
     * Records one frame of the opening window and applies the gate as soon as the window
     * is full. A window of `0` frames disables calibration entirely.
     */
    private fun calibrate(rms: Float) {
        if (calibrationWindow.isEmpty()) return

        calibrationWindow[calibratedFrames] = rms
        calibratedFrames++
        if (!isCalibrated) return

        val sorted = calibrationWindow.sortedArray()
        // The 20th percentile: the quiet part of the room, not the middle of the window
        // and not an average the speaker's own voice would dominate.
        noiseFloorRms = sorted[sorted.size / 5]
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
        /**
         * -84 dBFS: what a gate falls back to before calibration and when calibration is
         * disabled. Low enough that nothing a microphone delivers can hide under it.
         */
        const val FALLBACK_NOISE_FLOOR = 0.000061f
    }
}
