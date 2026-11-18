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
                    "%d ms hangover budget, %d ms consecutive VAD silence",
                session.endReason,
                session.framesSeen,
                session.framesSeen * config.frameDurationMs,
                session.utteranceFrames,
                session.hangoverSilenceMs,
                session.consecutiveSilenceMs,
            )
            session.levelDiagnostics?.let { Timber.d("VAD endpoint levels: %s", it) }
        }
    }
}

/** Signals "utterance finished" out of the collector so the upstream can cancel. */
private class SegmentComplete : Exception("utterance endpoint reached")

/** Why a segment closed. Diagnostic only; every reason ends the session the same way. */
internal enum class EndpointReason {
    /** No frame reached the speech gate before [VadConfig.noSpeechTimeoutFrames]. */
    NO_SPEECH_TIMEOUT,

    /** The silent-frame budget reached [VadConfig.silenceHangoverFrames]. */
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

    private val preSpeech = ArrayList<AudioFrame>(PRE_SPEECH_PREROLL_FRAMES)
    private var onsetCount = 0
    private var started = false
    private var hangoverBudgetFrames = 0
    private var consecutiveVadSilenceFrames = 0
    private var speechResumptionFrames = 0

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
        get() = consecutiveVadSilenceFrames * config.frameDurationMs

    val hangoverSilenceMs: Int
        get() = hangoverBudgetFrames * config.frameDurationMs

    val levelDiagnostics: String?
        get() = (detector as? EnergyVad)?.let {
            (
                "last RMS %.5f, noise floor %.5f, absolute gate %.5f, relative gate %.5f, " +
                    "classified speech %s"
                ).format(
                    it.lastFrameRms,
                    it.noiseFloorRms,
                    it.speechRmsThreshold,
                    it.peakSpeechRms * config.relativeSilenceFactor,
                    it.lastFrameWasSpeech,
                )
        }

    init {
        detector.reset()
    }

    /** @return frames that became part of the utterance because of [frame]. */
    fun accept(frame: AudioFrame): List<AudioFrame> {
        framesSeen++

        if (!started) {
            val wasCalibrating = !detector.isCalibrated
            val speech = detector.isSpeech(frame)
            if (wasCalibrating) {
                calibrationPreroll += frame
                if (!detector.isCalibrated) return emptyList()

                // AudioRecord can begin with zero-filled buffers. Retain the bounded
                // calibration window, then classify it once with the measured room gate.
                val buffered = calibrationPreroll.toList()
                calibrationPreroll.clear()
                val emitted = ArrayList<AudioFrame>()
                for (bufferedFrame in buffered) {
                    if (isComplete) break
                    emitted += acceptClassified(
                        bufferedFrame,
                        detector.classifyCalibrated(bufferedFrame),
                    )
                }
                return emitted
            }
            return acceptClassified(frame, speech)
        }

        return acceptClassified(frame, detector.isSpeech(frame))
    }

    private val calibrationPreroll = ArrayList<AudioFrame>(MAX_CALIBRATION_FRAMES)

    private fun acceptClassified(frame: AudioFrame, speech: Boolean): List<AudioFrame> {
        if (!started) {
            if (detector.isPreRollCandidate(frame, speech)) {
                preSpeech += frame
                if (preSpeech.size > PRE_SPEECH_PREROLL_FRAMES) preSpeech.removeAt(0)
            }

            if (!speech) {
                onsetCount = 0
            } else {
                onsetCount++
                if (onsetCount >= config.onsetFrames) {
                    started = true
                    utteranceFrames = preSpeech.size
                    hangoverBudgetFrames = 0
                    val onset = ArrayList(preSpeech)
                    preSpeech.clear()
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
        consecutiveVadSilenceFrames = if (speech) 0 else consecutiveVadSilenceFrames + 1
        val confirmedResumption = when {
            !speech -> {
                speechResumptionFrames = 0
                false
            }

            hangoverBudgetFrames == 0 -> true

            else -> {
                speechResumptionFrames++
                if (speechResumptionFrames >= config.onsetFrames) {
                    speechResumptionFrames = 0
                    true
                } else {
                    false
                }
            }
        }
        hangoverBudgetFrames = if (confirmedResumption) 0 else hangoverBudgetFrames + 1

        if (hangoverBudgetFrames >= config.silenceHangoverFrames) {
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

    private companion object {
        const val MAX_CALIBRATION_FRAMES = 50
        const val PRE_SPEECH_PREROLL_FRAMES = 50
    }
}

