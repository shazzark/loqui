package com.loqui.android.llm.cloud

import com.loqui.android.llm.CorrectionResult
import com.loqui.android.llm.LlmAvailability
import com.loqui.android.llm.LlmCorrector
import com.loqui.android.llm.LlmFailure
import com.loqui.android.llm.LlmPrompt
import com.loqui.android.settings.LlmMode
import com.loqui.android.settings.ProviderIds
import com.loqui.android.settings.SecretStore
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Opt-in cloud clean-up, always on the **user's** key.
 *
 * Phase 4 scaffold: the key gating, the prompt and the timeout are real; only the
 * transport is a mock. Two rules hold regardless of transport:
 *
 * 1. No key means [LlmAvailability.MissingKey] — the "add your key" state. There is no
 *    operator-paid fallback and no silent switch to another path.
 * 2. The key is only ever handed to [transport].
 *
 * There is no retry: a second round trip cannot fit in the 1 s correction budget, and
 * the pipeline already has a usable transcript to insert.
 */
class CloudLlmCorrector(
    private val secretStore: SecretStore,
    private val transport: LlmTransport,
    private val providerId: String = ProviderIds.CLOUD_LLM,
    private val requestTimeoutMs: Long = DEFAULT_REQUEST_TIMEOUT_MS,
) : LlmCorrector {

    override val mode: LlmMode = LlmMode.CLOUD

    override fun availability(): LlmAvailability =
        if (userKey() != null) LlmAvailability.Ready else LlmAvailability.MissingKey

    override suspend fun correct(text: String): CorrectionResult {
        val apiKey = userKey()
        if (apiKey == null) {
            // Defence in depth: the coordinator resolves availability first and falls
            // back to the raw transcript without entering this method.
            return CorrectionResult.Unavailable(LlmFailure.MISSING_KEY)
        }

        val request = CloudLlmRequest(
            apiKey = apiKey,
            prompt = LlmPrompt.build(text),
            transcript = text,
        )

        val result = withTimeoutOrNull(requestTimeoutMs) { transport.correct(request) }
            ?: return CorrectionResult.TimedOut

        return when (result) {
            is CloudLlmResult.Success -> {
                Timber.d("Cloud clean-up returned %d chars for %d", result.text.length, text.length)
                CorrectionResult.Corrected(result.text)
            }

            is CloudLlmResult.Failure -> CorrectionResult.Unavailable(result.reason)
        }
    }

    /** The user's key, or `null` when none is stored (or the Keystore entry is unusable). */
    private fun userKey(): String? = runCatching { secretStore.readKey(providerId) }
        .getOrNull()
        ?.takeIf { it.isNotBlank() }

    companion object {
        /** Same budget as the pipeline guard: clean-up may add at most one second. */
        const val DEFAULT_REQUEST_TIMEOUT_MS = 1_000L
    }
}
