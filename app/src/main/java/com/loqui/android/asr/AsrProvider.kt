package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import kotlinx.coroutines.flow.Flow

/**
 * One block of captured audio. Phase 2 fills this from `AudioRecord`;
 * Phase 1 only uses the default sample rate with silent blocks.
 */
class AudioFrame(
    val pcm16: ByteArray,
    val sampleRateHz: Int = SAMPLE_RATE_HZ,
) {
    companion object {
        const val SAMPLE_RATE_HZ = 16_000
    }
}

/** Whether a provider can transcribe right now, without throwing. */
sealed interface AsrAvailability {
    data object Ready : AsrAvailability

    /** Cloud mode selected but the user has not supplied a key yet. */
    data object MissingKey : AsrAvailability

    /** The on-device model has not been downloaded yet. */
    data object ModelMissing : AsrAvailability
}

sealed interface AsrEvent {
    data class Partial(val text: String) : AsrEvent

    data class Final(val text: String) : AsrEvent

    data class Failure(val reason: AsrFailure) : AsrEvent
}

enum class AsrFailure {
    NETWORK_TIMEOUT,
    PROVIDER_ERROR,
    CANCELLED,
    MODEL_UNAVAILABLE,
}

interface AsrProvider {
    val mode: AsrMode

    fun availability(): AsrAvailability

    fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent>
}
