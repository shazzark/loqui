package com.loqui.android.llm

import com.loqui.android.llm.cloud.CloudLlmCorrector
import com.loqui.android.llm.cloud.MockCloudLlmTransport
import com.loqui.android.settings.SecretStore
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Creates the corrector for a mode. Cloud correction is only ever constructed with the
 * *user's* key, and reports [LlmAvailability.MissingKey] when there is none — there is
 * no fallback to any operator-paid path.
 */
interface LlmProviderFactory {
    fun onDevice(): LlmCorrector

    fun cloud(): LlmCorrector
}

/**
 * Phase 4 selection: on-device is the deterministic [OnDeviceLlmCorrector] until a
 * language model ships, cloud is [CloudLlmCorrector] wired to a mock transport.
 */
@Singleton
class DefaultLlmProviderFactory @Inject constructor(
    private val secretStore: SecretStore,
) : LlmProviderFactory {

    /** A model loads once and then serves every dictation; the stand-in is reused too. */
    private val onDeviceCorrector: LlmCorrector by lazy {
        OnDeviceLlmCorrector()
    }

    override fun onDevice(): LlmCorrector = onDeviceCorrector

    /** Rebuilt per session so the user's key is read at call time, never cached. */
    override fun cloud(): LlmCorrector = CloudLlmCorrector(
        secretStore = secretStore,
        transport = MockCloudLlmTransport(),
    )
}
