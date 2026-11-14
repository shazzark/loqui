package com.loqui.android.llm.cloud

import com.loqui.android.llm.CorrectionResult
import com.loqui.android.llm.LlmAvailability
import com.loqui.android.llm.LlmFailure
import com.loqui.android.llm.LlmPrompt
import com.loqui.android.settings.ProviderIds
import com.loqui.android.testing.InMemorySecretStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudLlmCorrectorTest {

    private val secretStore = InMemorySecretStore()
    private val transport = RecordingLlmTransport()
    private val corrector = CloudLlmCorrector(
        secretStore = secretStore,
        transport = transport,
        requestTimeoutMs = REQUEST_TIMEOUT_MS,
    )

    @Test
    fun `without a key the corrector reports MissingKey`() {
        assertEquals(LlmAvailability.MissingKey, corrector.availability())
    }

    @Test
    fun `a blank key is treated as no key`() {
        secretStore.writeKey(ProviderIds.CLOUD_LLM, "   ")

        assertEquals(LlmAvailability.MissingKey, corrector.availability())
    }

    @Test
    fun `a stored key makes cloud clean-up ready`() {
        secretStore.writeKey(ProviderIds.CLOUD_LLM, USER_KEY)

        assertEquals(LlmAvailability.Ready, corrector.availability())
    }

    @Test
    fun `without a key no request is ever made`() = runTest {
        val result = corrector.correct(TRANSCRIPT)

        assertEquals(CorrectionResult.Unavailable(LlmFailure.MISSING_KEY), result)
        assertEquals(0, transport.calls)
        assertNull(transport.lastRequest)
    }

    @Test
    fun `the user key carries a prompt that contains the transcript`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_LLM, USER_KEY)

        val result = corrector.correct(TRANSCRIPT)

        assertEquals(CorrectionResult.Corrected(CORRECTED), result)
        assertEquals(1, transport.calls)

        val request = transport.lastRequest!!
        assertEquals(USER_KEY.length, request.apiKey.length)
        assertTrue(request.prompt.startsWith(LlmPrompt.INSTRUCTION))
        assertTrue(request.prompt.endsWith(TRANSCRIPT))
        assertFalse(request.prompt.contains(USER_KEY))
        assertEquals(TRANSCRIPT, request.transcript)
    }

    @Test
    fun `a provider rejection is surfaced as Unavailable and not retried`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_LLM, USER_KEY)
        transport.results = listOf(CloudLlmResult.Failure(LlmFailure.PROVIDER_ERROR))

        val result = corrector.correct(TRANSCRIPT)

        assertEquals(CorrectionResult.Unavailable(LlmFailure.PROVIDER_ERROR), result)
        assertEquals(1, transport.calls)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `the corrector gives up at its own timeout`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_LLM, USER_KEY)
        transport.latencies = listOf(REQUEST_TIMEOUT_MS * 10)

        val result = corrector.correct(TRANSCRIPT)

        assertEquals(CorrectionResult.TimedOut, result)
        assertEquals(1, transport.calls)
        assertEquals(REQUEST_TIMEOUT_MS, testScheduler.currentTime)
    }

    private companion object {
        const val USER_KEY = "user-supplied-key"
        const val TRANSCRIPT = "this is a loqui test transcript"
        const val CORRECTED = "This is a Loqui test transcript."
        const val REQUEST_TIMEOUT_MS = 100L
    }
}

/** Hand-written transport double: scripted latency per call, canned results. */
private class RecordingLlmTransport : LlmTransport {

    var results: List<CloudLlmResult> =
        listOf(CloudLlmResult.Success("This is a Loqui test transcript."))
    var latencies: List<Long> = emptyList()

    var calls: Int = 0
        private set

    var lastRequest: CloudLlmRequest? = null
        private set

    override suspend fun correct(request: CloudLlmRequest): CloudLlmResult {
        val index = calls
        calls++
        lastRequest = request

        delay(latencies.getOrElse(index) { 0L })
        return results.getOrElse(index) { results.last() }
    }
}
