package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Phase 1 stand-in for a real recognizer: it consumes the audio flow and emits a
 * canned transcript. No network, no key, no model. Phase 2 replaces it with the
 * sherpa-onnx (on-device) and cloud implementations behind the same interface.
 */
class FakeAsrProvider(
    override val mode: AsrMode,
    private val availability: AsrAvailability = AsrAvailability.Ready,
    private val transcript: String = DEFAULT_TRANSCRIPT,
    private val failure: AsrFailure? = null,
) : AsrProvider {

    override fun availability(): AsrAvailability = availability

    override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
        // Nothing to decode in Phase 1, but the audio stream must be consumed so
        // the capture loop is exercised end to end.
        audio.collect { /* audio content is ignored by the fake */ }

        if (failure != null) {
            emit(AsrEvent.Failure(failure))
            return@flow
        }

        emit(AsrEvent.Partial(transcript.substringBefore(' ')))
        emit(AsrEvent.Final(transcript))
    }

    companion object {
        const val DEFAULT_TRANSCRIPT = "This is a Loqui test transcript."
    }
}
