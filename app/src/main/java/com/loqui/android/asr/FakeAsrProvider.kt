package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Phase 2 stand-in for a real recognizer: it ingests the captured audio stream and
 * emits a canned transcript. No network, no key, no model.
 *
 * The ingested byte/frame counters let tests prove that audio really travelled from
 * the capture pipeline into the provider, rather than the provider inventing text
 * from an empty stream.
 */
class FakeAsrProvider(
    override val mode: AsrMode,
    private val availability: AsrAvailability = AsrAvailability.Ready,
    private val transcript: String = DEFAULT_TRANSCRIPT,
    private val failure: AsrFailure? = null,
) : AsrProvider {

    var ingestedFrames: Int = 0
        private set

    var ingestedBytes: Long = 0
        private set

    override fun availability(): AsrAvailability = availability

    override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
        // The audio must be consumed so the capture loop is exercised end to end.
        var frames = 0
        var bytes = 0L
        audio.collect { frame ->
            frames++
            bytes += frame.pcm16.size
        }
        ingestedFrames = frames
        ingestedBytes = bytes

        if (failure != null) {
            emit(AsrEvent.Failure(failure))
            return@flow
        }

        if (frames == 0) {
            // Never invent a transcript for an empty utterance.
            emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
            return@flow
        }

        emit(AsrEvent.Partial(transcript.substringBefore(' ')))
        emit(AsrEvent.Final(transcript))
    }

    companion object {
        const val DEFAULT_TRANSCRIPT = "This is a Loqui test transcript."
    }
}
