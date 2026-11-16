package com.loqui.android.audio.vad

/**
 * Tuning for [SpeechEndpointer]. Expressed in frames so the numbers stay readable
 * against the 20 ms capture frame size.
 *
 * @param frameDurationMs audio carried by one frame; must match the capture stream.
 * @param speechRmsThreshold normalised RMS (0..1) above which a frame counts as speech.
 *   `0.003` is about -50 dBFS. Phone microphones differ by roughly an order of magnitude
 *   once the platform's `VOICE_RECOGNITION` chain has applied its noise suppression, and
 *   both `0.015` and `0.008` sat above what a Galaxy A13 delivers at arm's length: onset
 *   never confirmed, so no audio ever reached the recogniser. Too low is the cheaper
 *   failure: room noise trips the onset and the utterance ends at the duration ceiling,
 *   which still produces a transcript.
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
    val speechRmsThreshold: Float = 0.003f,
    val onsetFrames: Int = 3,
    val silenceHangoverFrames: Int = 40,
    val maxUtteranceMs: Int = 15_000,
    val noSpeechTimeoutMs: Int = 8_000,
) {

    init {
        require(frameDurationMs > 0) { "frameDurationMs must be positive" }
        require(speechRmsThreshold > 0f) { "speechRmsThreshold must be positive" }
        require(onsetFrames > 0) { "onsetFrames must be positive" }
        require(silenceHangoverFrames > 0) { "silenceHangoverFrames must be positive" }
        require(maxUtteranceMs > 0) { "maxUtteranceMs must be positive" }
        require(noSpeechTimeoutMs > 0) { "noSpeechTimeoutMs must be positive" }
    }

    val maxUtteranceFrames: Int
        get() = (maxUtteranceMs / frameDurationMs).coerceAtLeast(1)

    val noSpeechTimeoutFrames: Int
        get() = (noSpeechTimeoutMs / frameDurationMs).coerceAtLeast(1)
}
