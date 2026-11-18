package com.loqui.android.asr

import android.util.Log
import com.loqui.android.BuildConfig
import com.loqui.android.audio.vad.Pcm16Signal
import com.loqui.android.di.IoDispatcher
import com.loqui.android.models.ModelFile
import com.loqui.android.models.ModelPaths
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.models.SpeechModels
import com.loqui.android.settings.AsrMode
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
        if (models.activePaths() != null) AsrAvailability.Ready else AsrAvailability.ModelMissing

    /**
     * Loads the recogniser before the first dictation needs it.
     *
     * Opening the transducer is the most expensive step of a session — seconds on a cold
     * phone for ~68 MiB of ONNX graphs — and it happens *inside* `transcribe`, before the
     * first audio frame is collected. Between the tap and the first recorded frame the
     * microphone is therefore closed, and the opening words of the first sentence of the
     * day never reach the recogniser at all: the transcript starts mid-sentence, and the
     * first partial arrives that much later. `RecogniserWarmUp` calls this as soon as the
     * model is installed so the graphs are mapped before the bubble is tapped; a warm-up
     * that fails is not a user-visible failure, because the next dictation reports the same
     * problem with the session's own context.
     */
    suspend fun warmUp() {
        val paths = models.activePaths() ?: return

        withContext(ioDispatcher) {
            sessionLock.withLock {
                val startedAt = System.currentTimeMillis()
                try {
                    val session = engine.open(paths, AudioFrame.SAMPLE_RATE_HZ)
                    try {
                        // Drain once: mapping the graphs is half the cost, initialising the
                        // decoder runtime on the first `decode()` is the other half.
                        session.finish()
                        drain(session)
                    } finally {
                        session.release()
                    }
                    Timber.d(
                        "Recogniser warmed in %d ms for %s",
                        System.currentTimeMillis() - startedAt,
                        paths.directory.name,
                    )
                } catch (cancellation: CancellationException) {
                    throw cancellation
                } catch (throwable: Throwable) {
                    Timber.w(
                        throwable,
                        "Could not warm the recogniser (%s)",
                        describeFailure(throwable),
                    )
                }
            }
        }
    }

    /**
     * Frees the recogniser when nothing can use it.
     *
     * `RecogniserWarmUp` calls this when the user leaves on-device mode or switches to the
     * other model: the graphs are tens of megabytes of int8 weights that stay resident for
     * the life of the process otherwise, on a device where the next app launch will be
     * fighting for the same RAM. The lock keeps a release from tearing a session out from
     * under an in-flight dictation; a dictation that starts afterwards simply reloads.
     */
    suspend fun release() {
        withContext(ioDispatcher) {
            sessionLock.withLock { engine.release() }
        }
    }

    override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
        val paths = models.activePaths()
        if (paths == null) {
            emit(AsrEvent.Failure(AsrFailure.MODEL_UNAVAILABLE))
            return@flow
        }

        sessionLock.withLock {
            logEnvironment(paths)

            val openStartedAt = System.currentTimeMillis()
            val session = try {
                engine.open(paths, AudioFrame.SAMPLE_RATE_HZ)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                // A recogniser that will not open is the failure a logcat line has to nail
                // down: a missing JNI symbol, a truncated model file and a 32-bit
                // address-space limit all end here and all look like "no transcript".
                Timber.e(
                    throwable,
                    "Could not open the on-device recogniser in %d ms (%s)",
                    System.currentTimeMillis() - openStartedAt,
                    describeFailure(throwable),
                )
                emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
                return@withLock
            }
            Timber.d(
                "Recogniser ready in %d ms for %s",
                System.currentTimeMillis() - openStartedAt,
                paths.directory.name,
            )

            var lastPartial = ""
            var frames = 0
            var samples = 0L
            var partials = 0
            var peakRms = 0f

            try {
                audio.collect { frame ->
                    val frameSamples = pcm16ToFloatSamples(frame.pcm16)
                    if (frameSamples.isEmpty()) {
                        Timber.w("Dropped a torn audio frame (%d bytes)", frame.pcm16.size)
                        return@collect
                    }

                    frames++
                    samples += frameSamples.size
                    // Level only, never audio: the number that says whether the recogniser
                    // was given speech or a room tone it could never transcribe.
                    peakRms = maxOf(peakRms, Pcm16Signal.rms(frame.pcm16))
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
                        logTranscript("Partial", text)
                        emit(AsrEvent.Partial(text))
                    }
                }

                Timber.d(
                    "Audio ended: %d frames, %d ms, %d partials, peak RMS %.4f",
                    frames,
                    millisFor(samples),
                    partials,
                    peakRms,
                )

                if (frames == 0) {
                    // The endpointer closes a silent session on purpose; nothing was fed to
                    // the model, so this is an outcome rather than a provider failure.
                    Timber.w("No audio reached the recogniser; reporting NO_SPEECH")
                    emit(AsrEvent.Failure(AsrFailure.NO_SPEECH))
                    return@withLock
                }

                // Give streaming Zipformer terminal acoustic context before signalling EOF.
                session.accept(FINAL_PADDING, AudioFrame.SAMPLE_RATE_HZ)
                session.finish()
                val tail = drain(session).trim()

                // Streaming decoders can settle on nothing after `inputFinished()` even
                // though they had text a frame earlier. The last partial is still the best
                // available transcript, so it becomes the final result instead of a failure
                // the user cannot act on.
                val finalText = tail.ifEmpty { lastPartial.trim() }
                if (tail.isEmpty() && finalText.isNotEmpty()) {
                    Timber.w(
                        "Empty final result; falling back to the last partial (%d chars)",
                        finalText.length,
                    )
                }

                if (finalText.isEmpty()) {
                    // Audio was fed to the model and it recognised nothing: that is "no
                    // speech detected", not a broken provider. The user cannot act on a
                    // PROVIDER_ERROR here, and the session is the same shape as a silent
                    // utterance that the endpointer closed on purpose.
                    Timber.w(
                        "Recogniser produced no text from %d frames (%d ms, %d partials); " +
                            "reporting NO_SPEECH",
                        frames,
                        millisFor(samples),
                        partials,
                    )
                    emit(AsrEvent.Failure(AsrFailure.NO_SPEECH))
                } else {
                    Timber.d(
                        "Final transcript: %d chars from %d frames (%d ms)",
                        finalText.length,
                        frames,
                        millisFor(samples),
                    )
                    logTranscript("Final", finalText)
                    emit(AsrEvent.Final(finalText))
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (throwable: Throwable) {
                if (lastPartial.isBlank()) {
                    Timber.e(
                        throwable,
                        "On-device recognition failed after %d frames with no text (%s)",
                        frames,
                        describeFailure(throwable),
                    )
                    emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
                } else {
                    // Keep what was recognised before the failure: a partial transcript is
                    // still useful, an error toast is not.
                    Timber.e(
                        throwable,
                        "On-device recognition failed after %d frames; emitting the last " +
                            "partial (%d chars)",
                        frames,
                        lastPartial.length,
                    )
                    emit(AsrEvent.Final(lastPartial.trim()))
                }
            } finally {
                session.release()
            }
        }
    }.flowOn(ioDispatcher)

    private fun millisFor(samples: Long): Long = samples * 1_000L / AudioFrame.SAMPLE_RATE_HZ

    /** Transcript text is available only in explicitly enabled debug builds. */
    private fun logTranscript(kind: String, text: String) {
        if (BuildConfig.DEBUG && Log.isLoggable(TRANSCRIPT_LOG_TAG, Log.DEBUG)) {
            Timber.tag(TRANSCRIPT_LOG_TAG).d("%s: %s", kind, text)
        }
    }

    private fun drain(session: StreamingAsrSession): String {
        while (session.isReady()) {
            session.decode()
        }
        return session.text()
    }

    /**
     * The numbers needed to explain a recogniser that will not run on a real phone: the
     * installed files (a truncated extract shows up here as a size), the process ABI, and
     * the heap a 32-bit device has left for ~68 MB of int8 graphs.
     */
    private fun logEnvironment(paths: ModelPaths) {
        val runtime = Runtime.getRuntime()
        Timber.d(
            "Model %s: encoder %s, decoder %s, joiner %s, tokens %s; ABI %s; " +
                "heap max %d MB, used %d MB",
            paths.directory.name,
            describeFile(paths.encoder),
            describeFile(paths.decoder),
            describeFile(paths.joiner),
            describeFile(paths.tokens),
            System.getProperty("os.arch"),
            runtime.maxMemory() / MB,
            (runtime.totalMemory() - runtime.freeMemory()) / MB,
        )
    }

    /**
     * Exact bytes plus megabytes: whole megabytes printed the 539 KiB decoder and the 5 KiB
     * token table as "0 MB", which reads like a broken extract when the install is healthy.
     * A size the catalogue disagrees with is called out instead of rounded away.
     */
    private fun describeFile(file: File): String {
        if (!file.isFile) return "MISSING"

        val bytes = file.length()
        // The file's own directory identifies the model: `encoder.onnx` is a different size
        // in each catalogue entry, so matching on the file name alone would report a healthy
        // 20M encoder as a truncated accurate one.
        val expected = SpeechModels.all
            .firstOrNull { it.id == file.parentFile?.name }
            ?.file(file.name)
            ?.sizeBytes
        if (expected != null && bytes != expected) {
            return "$bytes B, expected $expected B"
        }
        return "$bytes B (${String.format(Locale.US, "%.1f", bytes.toDouble() / MB)} MB)"
    }

    /** Class and message on one line, so a filtered capture still shows what threw. */
    private fun describeFailure(throwable: Throwable): String = buildString {
        append(throwable.javaClass.name)
        throwable.message?.let { append(": ").append(it) }
    }

    private companion object {
        /** One diagnostic line per second of audio (50 frames of 20 ms). */
        const val LOG_EVERY_FRAMES = 50

        /** Set `log.tag.LoquiTranscript=DEBUG` on a debuggable build to opt in. */
        const val TRANSCRIPT_LOG_TAG = "LoquiTranscript"

        /** 300 ms of terminal context for streaming decoders at 16 kHz. */
        val FINAL_PADDING = FloatArray(AudioFrame.SAMPLE_RATE_HZ * 300 / 1_000)

        const val MB = 1024L * 1024L
    }
}
