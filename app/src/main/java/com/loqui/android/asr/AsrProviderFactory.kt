package com.loqui.android.asr

import com.loqui.android.asr.cloud.CloudAsrProvider
import com.loqui.android.asr.cloud.MockCloudAsrTransport
import com.loqui.android.di.OnDeviceAsrProvider
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

/**
 * Phase 6 selection: on-device is the real sherpa-onnx recogniser, built once because
 * loading ~43 MiB of ONNX graphs per dictation would be wasteful; cloud is the real
 * [CloudAsrProvider] wired to a mock transport until a real endpoint ships.
 */
@Singleton
class DefaultAsrProviderFactory @Inject constructor(
    private val secretStore: SecretStore,
    @OnDeviceAsrProvider private val onDeviceProvider: AsrProvider,
) : AsrProviderFactory {

    override fun onDevice(): AsrProvider = onDeviceProvider

    /** Rebuilt per session so the user's key is read at call time, never cached. */
    override fun cloud(): AsrProvider = CloudAsrProvider(
        secretStore = secretStore,
        transport = MockCloudAsrTransport(),
    )
}
