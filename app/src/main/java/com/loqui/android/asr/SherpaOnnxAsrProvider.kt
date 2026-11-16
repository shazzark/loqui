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
            val openStartedAt = System.currentTimeMillis()
            val session = try {
                engine.open(paths, AudioFrame.SAMPLE_RATE_HZ)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                Timber.w(throwable, "Could not open the on-device recogniser")
                emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
                return@withLock
            }
            Timber.d(
                "Recogniser ready in %d ms for %s",
                System.currentTimeMillis() - openStartedAt,
                paths.directory.name,
            )

            try {
                var lastPartial = ""
                var frames = 0
                var samples = 0L
                var partials = 0

                audio.collect { frame ->
                    val frameSamples = pcm16ToFloatSamples(frame.pcm16)
                    if (frameSamples.isEmpty()) {
                        Timber.w("Dropped a torn audio frame (%d bytes)", frame.pcm16.size)
                        return@collect
                    }

                    frames++
                    samples += frameSamples.size
                    session.accept(frameSamples, frame.sampleRateHz)

                    if (frames % LOG_EVERY_FRAMES == 0) {
                        Timber.d(
                            "Fed %d frames (%d ms of audio) into the recogniser",
                            frames,
                            millisFor(samples),
                        )
                    }

                    val text = drain(session)
                    if (text.isNotBlank() && text != lastPartial) {
                        lastPartial = text
                        partials++
                        // Length only: transcript content is never logged.
                        Timber.d(
                            "Partial #%d after %d frames (%d chars)",
                            partials,
                            frames,
                            text.length,
                        )
                        emit(AsrEvent.Partial(text))
                    }
                }

                Timber.d(
                    "Audio ended: %d frames, %d ms, %d partials",
                    frames,
                    millisFor(samples),
                    partials,
                )

                if (frames == 0) {
                    // The endpointer closes a silent session on purpose; nothing was fed to
                    // the model, so this is an outcome rather than a provider failure.
                    Timber.w("No audio reached the recogniser; reporting NO_SPEECH")
                    emit(AsrEvent.Failure(AsrFailure.NO_SPEECH))
                    return@withLock
                }

                session.finish()
                val finalText = drain(session).trim()
                if (finalText.isEmpty()) {
                    Timber.w(
                        "Recogniser produced no text from %d frames (%d ms)",
                        frames,
                        millisFor(samples),
                    )
                    emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
                } else {
                    Timber.d(
                        "Final transcript: %d chars from %d frames (%d ms)",
                        finalText.length,
                        frames,
                        millisFor(samples),
                    )
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

    private fun millisFor(samples: Long): Long = samples * 1_000L / AudioFrame.SAMPLE_RATE_HZ

    private fun drain(session: StreamingAsrSession): String {
        while (session.isReady()) {
            session.decode()
        }
        return session.text()
    }

    private companion object {
        /** One diagnostic line per second of audio (50 frames of 20 ms). */
        const val LOG_EVERY_FRAMES = 50
    }
}
