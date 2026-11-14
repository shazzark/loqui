package com.loqui.android.llm.cloud

import com.loqui.android.llm.LlmFailure

/**
 * One clean-up request to a cloud model.
 *
 * [apiKey] is the **user's own** key, read from the secret store at call time and held
 * only for the duration of the request. It is never logged, never persisted here, and
 * never sent anywhere except the provider the user chose.
 *
 * [prompt] is the only field a real client posts; [transcript] is carried alongside so
 * transports can stay deterministic before the HTTP client exists, and so tests can
 * assert on it without parsing the prompt apart.
 */
data class CloudLlmRequest(
    val apiKey: String,
    val prompt: String,
    val transcript: String,
)

sealed interface CloudLlmResult {
    data class Success(val text: String) : CloudLlmResult

    data class Failure(val reason: LlmFailure) : CloudLlmResult
}

/** Transport for one clean-up request; replaced by the real HTTP client later. */
fun interface LlmTransport {
    suspend fun correct(request: CloudLlmRequest): CloudLlmResult
}
