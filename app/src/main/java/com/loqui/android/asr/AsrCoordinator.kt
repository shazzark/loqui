package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/** Outcome of one dictation attempt, in terms the UI can render. */
sealed interface AsrSessionEvent {
    data class Transcript(val text: String, val provider: AsrMode) : AsrSessionEvent

    /** Cloud mode selected, but no user key: show the "add your key" state. */
    data object NeedsKey : AsrSessionEvent

    /**
     * A session failed. [mode] travels with the reason because the message depends on it:
     * the same [AsrFailure.PROVIDER_ERROR] must never mention a key on the on-device path.
     */
    data class Failed(val reason: AsrFailure, val mode: AsrMode) : AsrSessionEvent
}

/**
 * Settings-aware entry point for transcription. Reads `asr.mode` at session start
 * and never silently switches to a different (paid) path.
 */
@Singleton
class AsrCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerFactory: AsrProviderFactory,
) {

    suspend fun availability(): AsrAvailability =
        providerFor(settingsRepository.settings.first().asrMode).availability()

    /**
     * Runs one dictation session against the provider selected by `asr.mode`.
     *
     * Text that the provider has already recognised is never thrown away: a partial is
     * kept and delivered as the transcript when the session is cut off or the provider
     * fails afterwards. A dictation that produced "hello wor…" must not become an error
     * toast just because the last event was a failure.
     *
     * @param sessionHardLimitMs final safety net, not the latency budget: it only
     * exists so a wedged provider cannot hold the microphone forever. It is longer
     * than the capture pipeline's own utterance ceiling.
     */
    fun transcribe(
        audio: Flow<AudioFrame>,
        sessionHardLimitMs: Long = SESSION_HARD_LIMIT_MS,
    ): Flow<AsrSessionEvent> = flow {
        val mode = settingsRepository.settings.first().asrMode
        val provider = providerFor(mode)

        when (provider.availability()) {
            AsrAvailability.Ready -> {
                var partial = ""
                var delivered = false

                val finished = withTimeoutOrNull(sessionHardLimitMs) {
                    provider.transcribe(audio).collect { event ->
                        when (event) {
                            is AsrEvent.Partial -> partial = event.text
                            is AsrEvent.Final -> {
                                delivered = true
                                emit(AsrSessionEvent.Transcript(event.text, provider.mode))
                            }

                            is AsrEvent.Failure -> {
                                val salvaged = partial.takeIf { !delivered && it.isNotBlank() }
                                if (salvaged != null && event.reason.keepsRecognisedText) {
                                    Timber.w(
                                        "Provider reported %s after %d chars of partial text; " +
                                            "delivering the partial instead of an error",
                                        event.reason,
                                        salvaged.length,
                                    )
                                    delivered = true
                                    emit(AsrSessionEvent.Transcript(salvaged.trim(), provider.mode))
                                } else {
                                    emit(AsrSessionEvent.Failed(event.reason, provider.mode))
                                }
                            }
                        }
                    }
                }

                if (finished == null) {
                    Timber.w("ASR session exceeded the %d ms hard limit", sessionHardLimitMs)
                    val salvaged = partial.takeIf { !delivered && it.isNotBlank() }
                    if (salvaged != null) {
                        // The user's second tap or a wedged flush: whatever was recognised
                        // before the cut is still what they said.
                        emit(AsrSessionEvent.Transcript(salvaged.trim(), provider.mode))
                    } else {
                        emit(AsrSessionEvent.Failed(AsrFailure.NETWORK_TIMEOUT, provider.mode))
                    }
                }
            }

            AsrAvailability.MissingKey -> emit(AsrSessionEvent.NeedsKey)
            AsrAvailability.ModelMissing ->
                emit(AsrSessionEvent.Failed(AsrFailure.MODEL_UNAVAILABLE, provider.mode))
        }
    }

    private fun providerFor(mode: AsrMode): AsrProvider = when (mode) {
        AsrMode.ON_DEVICE -> providerFactory.onDevice()
        AsrMode.CLOUD -> providerFactory.cloud()
    }

    companion object {
        const val SESSION_HARD_LIMIT_MS = 60_000L
    }
}

/**
 * Whether a failure is worth overriding with text the provider already produced.
 *
 * `PROVIDER_ERROR` and a timeout mean "the recogniser broke or stalled mid-sentence", so
 * a partial is the best transcript available. `NO_SPEECH`, `MODEL_UNAVAILABLE`,
 * `MISSING_KEY` and `CANCELLED` are verdicts on the session itself and must stand.
 */
private val AsrFailure.keepsRecognisedText: Boolean
    get() = this == AsrFailure.PROVIDER_ERROR || this == AsrFailure.NETWORK_TIMEOUT
