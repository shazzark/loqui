package com.loqui.android.asr.cloud

import com.loqui.android.asr.AsrFailure
import com.loqui.android.asr.AudioFrame
import com.loqui.android.asr.AsrAvailability
import com.loqui.android.asr.AsrEvent
import com.loqui.android.asr.AsrProvider
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.ProviderIds
import com.loqui.android.settings.SecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * One request to a cloud recognizer.
 *
 * [apiKey] is the user's own key, read from the secret store at call time and held
 * only for the duration of the request. It is never logged, never persisted here,
 * and never sent anywhere except the provider the user chose.
 */
data class CloudAsrRequest(
    val apiKey: String,
    val sampleRateHz: Int,
    val audioBytes: Long,
    val audioDurationMs: Long,
)

sealed interface CloudAsrResult {
    data class Success(val text: String) : CloudAsrResult

    data class Failure(val reason: AsrFailure) : CloudAsrResult
}

/** Transport for one cloud recognition request; replaced by the real client later. */
fun interface CloudAsrTransport {
    suspend fun transcribe(request: CloudAsrRequest): CloudAsrResult
}

/**
 * Opt-in cloud recognition, always on the **user's** key.
 *
 * Phase 2 scaffold: the audio pipeline, the key gating and the timeout/retry policy
 * are real; only the transport is a mock. Two rules hold regardless of transport:
 *
 * 1. No key means [AsrAvailability.MissingKey] — the "add your key" state. There is
 *    no operator-paid fallback and no silent switch to another path.
 * 2. The key is only ever handed to [transport].
 */
class CloudAsrProvider(
    private val secretStore: SecretStore,
    private val transport: CloudAsrTransport,
    private val providerId: String = ProviderIds.CLOUD_ASR,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
    private val maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
) : AsrProvider {

    override val mode: AsrMode = AsrMode.CLOUD

    override fun availability(): AsrAvailability =
        if (userKey() != null) AsrAvailability.Ready else AsrAvailability.MissingKey

    override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
        val apiKey = userKey()
        if (apiKey == null) {
            // Defence in depth: the coordinator normally resolves availability first
            // and reports NeedsKey without ever starting capture.
            emit(AsrEvent.Failure(AsrFailure.MISSING_KEY))
            return@flow
        }

        var frames = 0
        var bytes = 0L
        var sampleRateHz = AudioFrame.SAMPLE_RATE_HZ
        audio.collect { frame ->
            frames++
            bytes += frame.pcm16.size
            sampleRateHz = frame.sampleRateHz
        }

        if (frames == 0) {
            Timber.w("Cloud ASR received no audio")
            emit(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR))
            return@flow
        }

        val request = CloudAsrRequest(
            apiKey = apiKey,
            sampleRateHz = sampleRateHz,
            audioBytes = bytes,
            audioDurationMs = durationMs(bytes, sampleRateHz),
        )

        var attempt = 1
        while (true) {
            val result = withTimeoutOrNull(requestTimeoutMs) { transport.transcribe(request) }

            when (result) {
                is CloudAsrResult.Success -> {
                    Timber.d("Cloud ASR returned %d chars", result.text.length)
                    emit(AsrEvent.Final(result.text))
                    return@flow
                }

                is CloudAsrResult.Failure -> {
                    // A rejected request (bad key, bad audio) will not improve on retry.
                    emit(AsrEvent.Failure(result.reason))
                    return@flow
                }

                null -> {
                    // Timeouts are treated as transient and retried up to maxAttempts.
                    if (attempt >= maxAttempts) {
                        Timber.w("Cloud ASR timed out %d times", attempt)
                        emit(AsrEvent.Failure(AsrFailure.NETWORK_TIMEOUT))
                        return@flow
                    }
                    Timber.w(
                        "Cloud ASR timed out after %d ms; attempt %d of %d",
                        requestTimeoutMs,
                        attempt,
                        maxAttempts,
                    )
                    attempt++
                }
            }
        }
    }

    /** The user's key, or `null` when none is stored (or the Keystore entry is unusable). */
    private fun userKey(): String? = runCatching { secretStore.readKey(providerId) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

    private fun durationMs(audioBytes: Long, sampleRateHz: Int): Long {
        if (sampleRateHz <= 0) return 0L
        return audioBytes * MILLIS_PER_SECOND / (sampleRateHz.toLong() * BYTES_PER_SAMPLE)
    }

    companion object {
        const val DEFAULT_REQUEST_TIMEOUT_MS = 8_000L
        const val DEFAULT_MAX_ATTEMPTS = 2

        private const val BYTES_PER_SAMPLE = 2L
        private const val MILLIS_PER_SECOND = 1_000L
    }
}
