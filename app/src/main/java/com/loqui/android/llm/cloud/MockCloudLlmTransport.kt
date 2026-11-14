package com.loqui.android.llm.cloud

import com.loqui.android.llm.PunctuationCorrection
import kotlinx.coroutines.delay

/**
 * Stand-in for the OpenAI / DeepSeek client.
 *
 * It performs **no network I/O** — no `INTERNET` permission is requested yet — and
 * answers with the same local punctuation pass the on-device corrector uses, so the
 * cloud path is demoable end to end before a real client exists without inventing text
 * the user did not say.
 *
 * It records only shape metadata about the request (never the key itself), which is
 * what the unit tests assert on.
 */
class MockCloudLlmTransport(
    private val simulatedLatencyMs: Long = SIMULATED_LATENCY_MS,
) : LlmTransport {

    /** Number of simulated requests; proves the network path was or was not entered. */
    var calls: Int = 0
        private set

    var lastKeyLength: Int = 0
        private set

    var lastPromptLength: Int = 0
        private set

    var lastTranscript: String = ""
        private set

    override suspend fun correct(request: CloudLlmRequest): CloudLlmResult {
        calls++
        lastKeyLength = request.apiKey.length
        lastPromptLength = request.prompt.length
        lastTranscript = request.transcript

        delay(simulatedLatencyMs)
        return CloudLlmResult.Success(PunctuationCorrection.apply(request.transcript))
    }

    companion object {
        private const val SIMULATED_LATENCY_MS = 120L
    }
}
