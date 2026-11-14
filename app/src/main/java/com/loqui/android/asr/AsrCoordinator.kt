package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow

/** Outcome of one dictation attempt, in terms the UI can render. */
sealed interface AsrSessionEvent {
    data class Transcript(val text: String, val provider: AsrMode) : AsrSessionEvent

    /** Cloud mode selected, but no user key: show the "add your key" state. */
    data object NeedsKey : AsrSessionEvent

    data class Failed(val reason: AsrFailure) : AsrSessionEvent
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

    fun transcribe(audio: Flow<AudioFrame>): Flow<AsrSessionEvent> = flow {
        val mode = settingsRepository.settings.first().asrMode
        val provider = providerFor(mode)

        when (provider.availability()) {
            AsrAvailability.Ready -> provider.transcribe(audio).collect { event ->
                when (event) {
                    // Partials are surfaced on the bubble in Phase 2/3.
                    is AsrEvent.Partial -> Unit
                    is AsrEvent.Final -> emit(AsrSessionEvent.Transcript(event.text, provider.mode))
                    is AsrEvent.Failure -> emit(AsrSessionEvent.Failed(event.reason))
                }
            }

            AsrAvailability.MissingKey -> emit(AsrSessionEvent.NeedsKey)
            AsrAvailability.ModelMissing -> emit(AsrSessionEvent.Failed(AsrFailure.MODEL_UNAVAILABLE))
        }
    }

    private fun providerFor(mode: AsrMode): AsrProvider = when (mode) {
        AsrMode.ON_DEVICE -> providerFactory.onDevice()
        AsrMode.CLOUD -> providerFactory.cloud()
    }
}
