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
 * Phase 3 selection: on-device stays the [FakeAsrProvider] stand-in until the
 * sherpa-onnx recognizer lands, while cloud is the real [CloudAsrProvider] wired to
 * a mock transport. This is also what makes the Phase 3 device check possible with no
 * model and no key: the stand-in consumes the real microphone audio and returns a
 * fixed sentence that can be seen landing in another app's text field.
 */
@Singleton
class DefaultAsrProviderFactory @Inject constructor(
    private val secretStore: SecretStore,
) : AsrProviderFactory {

    /**
     * A real on-device engine loads its model once and then serves every dictation, so
     * the stand-in is reused too. Rebuilding it per session would hide that cost.
     */
    private val onDeviceProvider: AsrProvider by lazy {
        FakeAsrProvider(mode = AsrMode.ON_DEVICE)
    }

    override fun onDevice(): AsrProvider = onDeviceProvider

    /** Rebuilt per session so the user's key is read at call time, never cached. */
    override fun cloud(): AsrProvider = CloudAsrProvider(
        secretStore = secretStore,
        transport = MockCloudAsrTransport(),
    )
}
