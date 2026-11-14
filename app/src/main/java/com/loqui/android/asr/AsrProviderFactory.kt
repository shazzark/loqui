package com.loqui.android.asr

import com.loqui.android.asr.cloud.CloudAsrProvider
import com.loqui.android.asr.cloud.MockCloudAsrTransport
import com.loqui.android.settings.AsrMode
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
 * Phase 2 selection: on-device stays the [FakeAsrProvider] stand-in until the
 * sherpa-onnx recognizer lands, while cloud is the real [CloudAsrProvider] wired to
 * a mock transport.
 */
@Singleton
class DefaultAsrProviderFactory @Inject constructor(
    private val secretStore: SecretStore,
) : AsrProviderFactory {

    override fun onDevice(): AsrProvider = FakeAsrProvider(mode = AsrMode.ON_DEVICE)

    override fun cloud(): AsrProvider = CloudAsrProvider(
        secretStore = secretStore,
        transport = MockCloudAsrTransport(),
    )
}
