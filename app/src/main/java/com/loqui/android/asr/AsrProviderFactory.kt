package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.ProviderIds
import com.loqui.android.settings.SecretStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the provider for a mode. Cloud providers are only ever constructed with
 * the *user's* key, and report [AsrAvailability.MissingKey] when there is none —
 * there is no fallback to any operator-paid path.
 */
interface AsrProviderFactory {
    fun onDevice(): AsrProvider

    fun cloud(): AsrProvider
}

@Singleton
class FakeAsrProviderFactory @Inject constructor(
    private val secretStore: SecretStore,
) : AsrProviderFactory {

    override fun onDevice(): AsrProvider = FakeAsrProvider(mode = AsrMode.ON_DEVICE)

    override fun cloud(): AsrProvider = FakeAsrProvider(
        mode = AsrMode.CLOUD,
        availability = when {
            secretStore.hasKey(ProviderIds.CLOUD_ASR) -> AsrAvailability.Ready
            else -> AsrAvailability.MissingKey
        },
    )
}
