package com.loqui.android.audio.vad

/**
 * Tuning for [SpeechEndpointer]. Expressed in frames so the numbers stay readable
 * against the 20 ms capture frame size.
 *
 * @param frameDurationMs audio carried by one frame; must match the capture stream.
 * @param speechRmsThreshold normalised RMS (0..1) above which a frame counts as speech.
 * @param onsetFrames consecutive speech frames needed before an utterance starts;
 *   filters single-frame clicks and key taps.
 * @param silenceHangoverFrames consecutive silent frames that end the utterance;
 *   800 ms tolerates natural pauses without splitting a sentence.
 * @param maxUtteranceMs hard ceiling so one utterance cannot hang a session.
 */
data class VadConfig(
    val frameDurationMs: Int = 20,
    val speechRmsThreshold: Float = 0.015f,
    val onsetFrames: Int = 3,
    val silenceHangoverFrames: Int = 40,
    val maxUtteranceMs: Int = 30_000,
) {

    init {
        require(frameDurationMs > 0) { "frameDurationMs must be positive" }
        require(speechRmsThreshold > 0f) { "speechRmsThreshold must be positive" }
        require(onsetFrames > 0) { "onsetFrames must be positive" }
        require(silenceHangoverFrames > 0) { "silenceHangoverFrames must be positive" }
        require(maxUtteranceMs > 0) { "maxUtteranceMs must be positive" }
    }

    val maxUtteranceFrames: Int
        get() = (maxUtteranceMs / frameDurationMs).coerceAtLeast(1)
}
