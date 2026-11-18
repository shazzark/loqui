package com.loqui.android.audio.vad

import com.loqui.android.asr.AudioFrame
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import timber.log.Timber

/**
 * Cuts a continuous microphone stream into utterances.
 *
 * Frames before the first confirmed speech are dropped, so a recognizer never pays
 * for silence or a door slam. Once speech is confirmed the frames flow straight
 * through — including the trailing silence that triggered the endpoint — and the
 * flow **completes** at the endpoint, which is what tells the ASR provider to emit
 * its final transcript.
 *
 * A segment always ends, even when the detector never fires: [VadConfig.noSpeechTimeoutMs]
 * closes a session that never hears speech and [VadConfig.maxUtteranceMs] cuts one that
 * never falls silent. Without those ceilings a mis-tuned gate leaves the bubble on
 * "Listening" until the ASR hard limit.
 *
 * Completion cancels the upstream collection, so the microphone is released as soon
 * as the utterance ends.
 *
 * Stateless per call: every [segment] invocation gets its own state, so one
 * instance is safe to reuse across sessions.
 */
class SpeechEndpointer @Inject constructor(
    private val config: VadConfig,
    private val detector: VoiceActivityDetector,
) {

    fun segment(frames: Flow<AudioFrame>): Flow<AudioFrame> = flow {
        val session = Segmentation(config, detector)
        try {
            frames.collect { frame ->
                for (ready in session.accept(frame)) {
                    emit(ready)
                }
                if (session.isComplete) throw SegmentComplete()
            }
            Timber.d(
                "Capture ended without an endpoint after %d frames (%d ms), %d in the utterance",
                session.framesSeen,
                session.framesSeen * config.frameDurationMs,
                session.utteranceFrames,
            )
        } catch (_: SegmentComplete) {
            // Expected: the endpoint closed the utterance and cancelled the
            // upstream capture flow, which releases the microphone.
            Timber.d(
                "Utterance closed by %s after %d frames (%d ms), %d in the utterance; " +
                    "%d ms consecutive silence",
                session.endReason,
                session.framesSeen,
                session.framesSeen * config.frameDurationMs,
                session.utteranceFrames,
                session.consecutiveSilenceMs,
            )
        }
    }
}

/** Signals "utterance finished" out of the collector so the upstream can cancel. */
private class SegmentComplete : Exception("utterance endpoint reached")

/** Why a segment closed. Diagnostic only; every reason ends the session the same way. */
internal enum class EndpointReason {
    /** No frame reached the speech gate before [VadConfig.noSpeechTimeoutFrames]. */
    NO_SPEECH_TIMEOUT,

    /** [VadConfig.silenceHangoverFrames] consecutive frames fell below the gate. */
    SILENCE_HANGOVER,

    /** The utterance reached [VadConfig.maxUtteranceFrames] and was cut short. */
    MAX_DURATION,
}


/**
 * Endpointing state machine for one utterance.
 *
 * Silence → buffering onset frames → speech → hangover silence → complete, with a
 * no-speech deadline before the onset and a duration ceiling after it.
 */
private class Segmentation(
    private val config: VadConfig,
    private val detector: VoiceActivityDetector,
) {

    private val pending = ArrayList<AudioFrame>(config.onsetFrames)
    private var onsetCount = 0
    private var started = false
    private var trailingSilenceFrames = 0

    /** Frames this segment has looked at, including the ones it dropped. */
    var framesSeen = 0
        private set

    /** Frames emitted for the recogniser so far. */
    var utteranceFrames = 0
        private set

    var isComplete = false
        private set

    var endReason: EndpointReason? = null
        private set

    val consecutiveSilenceMs: Int
        get() = trailingSilenceFrames * config.frameDurationMs

    init {
        detector.reset()
    }

    /** @return frames that became part of the utterance because of [frame]. */
    fun accept(frame: AudioFrame): List<AudioFrame> {
        val speech = detector.isSpeech(frame)
        framesSeen++

        if (!started) {
            if (!speech) {
                pending.clear()
                onsetCount = 0
            } else {
                pending += frame
                onsetCount++
                if (onsetCount >= config.onsetFrames) {
                    started = true
                    utteranceFrames = pending.size
                    trailingSilenceFrames = 0
                    val onset = ArrayList(pending)
                    pending.clear()
                    return onset
                }
            }

            // The bubble must not wait forever for a first word: a session with no
            // confirmed speech closes here and the recogniser reports NO_SPEECH.
            if (framesSeen >= config.noSpeechTimeoutFrames) {
                complete(EndpointReason.NO_SPEECH_TIMEOUT)
            }
            return emptyList()
        }

        utteranceFrames++
        trailingSilenceFrames = if (speech) 0 else trailingSilenceFrames + 1

        if (trailingSilenceFrames >= config.silenceHangoverFrames) {
            complete(EndpointReason.SILENCE_HANGOVER)
        } else if (utteranceFrames >= config.maxUtteranceFrames) {
            complete(EndpointReason.MAX_DURATION)
        }
        return listOf(frame)
    }

    private fun complete(reason: EndpointReason) {
        isComplete = true
        endReason = reason
    }
}

