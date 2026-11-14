package com.loqui.android.audio.vad

import com.loqui.android.asr.AudioFrame
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Cuts a continuous microphone stream into utterances.
 *
 * Frames before the first confirmed speech are dropped, so a recognizer never pays
 * for silence or a door slam. Once speech is confirmed the frames flow straight
 * through — including the trailing silence that triggered the endpoint — and the
 * flow **completes** at the endpoint, which is what tells the ASR provider to emit
 * its final transcript.
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
        } catch (_: SegmentComplete) {
            // Expected: the endpoint closed the utterance and cancelled the
            // upstream capture flow, which releases the microphone.
        }
    }
}

/** Signals "utterance finished" out of the collector so the upstream can cancel. */
private class SegmentComplete : Exception("utterance endpoint reached")

/**
 * Endpointing state machine for one utterance.
 *
 * Silence → buffering onset frames → speech → hangover silence → complete.
 */
private class Segmentation(
    private val config: VadConfig,
    private val detector: VoiceActivityDetector,
) {

    private val pending = ArrayList<AudioFrame>(config.onsetFrames)
    private var onsetCount = 0
    private var started = false
    private var utteranceFrames = 0
    private var trailingSilenceFrames = 0

    var isComplete = false
        private set

    init {
        detector.reset()
    }

    /** @return frames that became part of the utterance because of [frame]. */
    fun accept(frame: AudioFrame): List<AudioFrame> {
        val speech = detector.isSpeech(frame)

        if (!started) {
            if (!speech) {
                pending.clear()
                onsetCount = 0
                return emptyList()
            }

            pending += frame
            onsetCount++
            if (onsetCount < config.onsetFrames) return emptyList()

            started = true
            utteranceFrames = pending.size
            trailingSilenceFrames = 0
            val onset = ArrayList(pending)
            pending.clear()
            return onset
        }

        utteranceFrames++
        trailingSilenceFrames = if (speech) 0 else trailingSilenceFrames + 1

        if (trailingSilenceFrames >= config.silenceHangoverFrames ||
            utteranceFrames >= config.maxUtteranceFrames
        ) {
            isComplete = true
        }
        return listOf(frame)
    }
}
