package com.loqui.android.audio.vad

/**
 * Tuning for [SpeechEndpointer]. Expressed in frames so the numbers stay readable
 * against the 20 ms capture frame size.
 *
 * The speech gate is deliberately *not* a constant. A phone microphone's noise floor
 * differs by an order of magnitude between a quiet room and a café, so [EnergyVad]
 * measures it during the opening [calibrationMs] of every session and puts speech at
 * [noiseFloorMultiplier] times that floor, bounded by [minSpeechRmsThreshold] and
 * [maxSpeechRmsThreshold]. A fixed gate only ever moves the failure: too high and the
 * onset never confirms (a Galaxy A13 delivered ~0.0003 RMS for *speech* through the
 * `VOICE_RECOGNITION` chain, so `0.003`-`0.015` gates heard nothing at all), too low and
 * room tone at ~0.0002 trips the onset, the utterance runs to the 15 s ceiling and the
 * recogniser is handed noise.
 *
 * @param frameDurationMs audio carried by one frame; must match the capture stream.
 * @param minSpeechRmsThreshold lowest gate calibration may produce, about -66 dBFS
 *   ([MIN_SPEECH_RMS]). A silent room measures ~0.00006 RMS, and doubling that would put
 *   the gate below the microphone's own self-noise, where a quiet talker disappears.
 * @param maxSpeechRmsThreshold highest gate calibration may produce, about -34 dBFS. A
 *   window that is nothing but speech must not raise the gate above normal speech.
 * @param noiseFloorMultiplier how far above the measured floor speech has to sit. Room
 *   tone wobbles frame to frame; 2x clears the wobble without demanding a loud voice.
 * @param calibrationMs length of the opening window used to measure the floor. 300 ms is
 *   the shortest window whose 20th percentile still comes from quiet frames only (4 of 15)
 *   rather than from the speaker's own first word. Every frame inside the window is
 *   withheld from the recogniser, so the window is also the dead air in front of the first
 *   word: 300 ms, and 200 ms less than the half second it replaces. `0` disables
 *   calibration (the gate stays at [minSpeechRmsThreshold]).
 * @param onsetFrames consecutive speech frames needed before an utterance starts;
 *   filters single-frame clicks and key taps.
 * @param silenceHangoverFrames consecutive silent frames that end the utterance;
 *   800 ms tolerates natural pauses without splitting a sentence.
 * @param maxUtteranceMs hard ceiling so one utterance cannot hang a session; a monologue
 *   is cut at 15 s and whatever was captured still reaches the recogniser.
 * @param noSpeechTimeoutMs how long a session may wait for the first confirmed speech
 *   before it is closed. A tap that is never followed by speech still has to produce an
 *   outcome, otherwise the bubble sits on "Listening" until the ASR hard limit. 8 s
 *   leaves room for a user who taps the bubble before they start talking.
 */
data class VadConfig(
    val frameDurationMs: Int = 20,
    val minSpeechRmsThreshold: Float = MIN_SPEECH_RMS,
    val maxSpeechRmsThreshold: Float = 0.02f,
    val noiseFloorMultiplier: Float = 2f,
    val calibrationMs: Int = 300,
    val onsetFrames: Int = 3,
    val silenceHangoverFrames: Int = 40,
    val maxUtteranceMs: Int = 15_000,
    val noSpeechTimeoutMs: Int = 8_000,
) {

    init {
        require(frameDurationMs > 0) { "frameDurationMs must be positive" }
        require(minSpeechRmsThreshold > 0f) { "minSpeechRmsThreshold must be positive" }
        require(maxSpeechRmsThreshold >= minSpeechRmsThreshold) {
            "maxSpeechRmsThreshold must not be below minSpeechRmsThreshold"
        }
        require(noiseFloorMultiplier >= 1f) { "noiseFloorMultiplier must be at least 1" }
        require(calibrationMs >= 0) { "calibrationMs must not be negative" }
        require(onsetFrames > 0) { "onsetFrames must be positive" }
        require(silenceHangoverFrames > 0) { "silenceHangoverFrames must be positive" }
        require(maxUtteranceMs > 0) { "maxUtteranceMs must be positive" }
        require(noSpeechTimeoutMs > 0) { "noSpeechTimeoutMs must be positive" }
    }

    /** Frames in the noise-floor window; `0` when calibration is disabled. */
    val calibrationFrames: Int
        get() = calibrationMs / frameDurationMs

    val maxUtteranceFrames: Int
        get() = (maxUtteranceMs / frameDurationMs).coerceAtLeast(1)

    val noSpeechTimeoutFrames: Int
        get() = (noSpeechTimeoutMs / frameDurationMs).coerceAtLeast(1)

    companion object {
        /**
         * 0.0005 is about -66 dBFS: the lowest gate a measured-silent room may produce.
         * A quieter room must not lower the gate into the microphone's own noise.
         */
        const val MIN_SPEECH_RMS = 0.0005f
    }
}
