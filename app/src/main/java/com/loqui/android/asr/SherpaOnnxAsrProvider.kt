package com.loqui.android.asr

import com.loqui.android.di.IoDispatcher
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.settings.AsrMode
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * On-device recognition through sherpa-onnx. Audio never leaves the process: each
 * [AudioFrame] is converted to floats and pushed into the streaming recogniser, which
 * returns a growing transcript. Partial results are only emitted when the text actually
 * changed, so the bubble is not repainted on every 20 ms frame.
 *
 * A missing or incomplete model is reported as [AsrFailure.MODEL_UNAVAILABLE] — never as
 * a network fallback; there is no server behind this provider.
 */
@Singleton
class SherpaOnnxAsrProvider @Inject constructor(
    private val models: SpeechModelRepository,
    private val engine: StreamingAsrEngine,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : AsrProvider {

    override val mode: AsrMode = AsrMode.ON_DEVICE

    /**
     * Loqui runs one dictation at a time (`VoiceInputService` is the only capture path),
     * and the recogniser is not documented as safe for concurrent decodes. The lock
     * makes that constraint explicit rather than relying on the service.
     */
    private val sessionLock = Mutex()

    override fun availability(): AsrAvailability =
        if (models.paths() != null) AsrAvailability.Ready else AsrAvailability.ModelMissing

    override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
        val paths = models.paths()
        if (paths == null) {
            emit(AsrEvent.Failure(AsrFailure.MODEL_UNAVAILABLE))
            return@flow
        }

        sessionLock.withLock {
            val session = try {
                engine.open(paths, AudioFrame.SAMPLE_RATE_HZ)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.w(throwable, "Could not open the on-device recogniser")
                emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
                return@withLock
            }

            try {
                var lastPartial = ""
                audio.collect { frame ->
                    val samples = pcm16ToFloatSamples(frame.pcm16)
                    if (samples.isEmpty()) return@collect

                    session.accept(samples, frame.sampleRateHz)
                    val text = drain(session)
                    if (text.isNotBlank() && text != lastPartial) {
                        lastPartial = text
                        emit(AsrEvent.Partial(text))
                    }
                }

                session.finish()
                val finalText = drain(session).trim()
                if (finalText.isEmpty()) {
                    emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
                } else {
                    emit(AsrEvent.Final(finalText))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.w(throwable, "On-device recognition failed")
                emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
            } finally {
                session.release()
            }
        }
    }.flowOn(ioDispatcher)

    private fun drain(session: StreamingAsrSession): String {
        while (session.isReady()) {
            session.decode()
        }
        return session.text()
    }
}
