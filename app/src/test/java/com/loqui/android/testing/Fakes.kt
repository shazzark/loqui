package com.loqui.android.testing

import com.loqui.android.models.DeviceCapabilities
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.LlmMode
import com.loqui.android.settings.LoquiSettings
import com.loqui.android.settings.ModelQuality
import com.loqui.android.settings.SecretStore
import com.loqui.android.settings.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** In-memory settings, so tests never touch DataStore. */
class FakeSettingsRepository(
    initial: LoquiSettings = LoquiSettings(),
) : SettingsRepository {

    private val state = MutableStateFlow(initial)

    override val settings: Flow<LoquiSettings> = state.asStateFlow()

    override suspend fun setAsrMode(mode: AsrMode) {
        state.value = state.value.copy(asrMode = mode)
    }

    override suspend fun setLlmEnabled(enabled: Boolean) {
        state.value = state.value.copy(llmEnabled = enabled)
    }

    override suspend fun setLlmMode(mode: LlmMode) {
        state.value = state.value.copy(llmMode = mode)
    }

    override suspend fun setModelQuality(quality: ModelQuality) {
        state.value = state.value.copy(modelQuality = quality)
    }

    override suspend fun setAccessibilityDisclosureAccepted(accepted: Boolean) {
        state.value = state.value.copy(accessibilityDisclosureAccepted = accepted)
    }

    fun currentMode(): AsrMode = state.value.asrMode
}

/**
 * A device that reports [totalRamBytes] and nothing else.
 *
 * The real implementation reads `ActivityManager.totalMem`, which does not exist on the
 * JVM; the routing rule only ever asks this one question, so this is the whole interface.
 */
class FakeDeviceCapabilities(
    private val totalRamBytes: Long = FOUR_GIGABYTES,
) : DeviceCapabilities {

    override fun totalRamBytes(): Long = totalRamBytes

    companion object {
        /** Typical 4 GB phone: below the accurate model's threshold. */
        const val FOUR_GIGABYTES = 4_000_000_000L

        /** Typical 8 GB phone: comfortably above it. */
        const val EIGHT_GIGABYTES = 8_000_000_000L
    }
}

/** In-memory secret store; the real one is Keystore-backed and untestable on the JVM. */
class InMemorySecretStore(
    private val keys: MutableMap<String, String> = mutableMapOf(),
) : SecretStore {

    override fun hasKey(providerId: String): Boolean = keys.containsKey(providerId)

    override fun readKey(providerId: String): String? = keys[providerId]

    override fun writeKey(providerId: String, apiKey: String) {
        keys[providerId] = apiKey
    }

    override fun deleteKey(providerId: String) {
        keys.remove(providerId)
    }
}
