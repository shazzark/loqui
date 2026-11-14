package com.loqui.android.asr.cloud

import app.cash.turbine.test
import com.loqui.android.asr.AsrAvailability
import com.loqui.android.asr.AsrEvent
import com.loqui.android.asr.AsrFailure
import com.loqui.android.settings.ProviderIds
import com.loqui.android.testing.InMemorySecretStore
import com.loqui.android.testing.audioFrame
import com.loqui.android.testing.SPEECH_AMPLITUDE
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CloudAsrProviderTest {

    private val secretStore = InMemorySecretStore()
    private val transport = RecordingTransport()
    private val provider = CloudAsrProvider(
        secretStore = secretStore,
        transport = transport,
        requestTimeoutMs = REQUEST_TIMEOUT_MS,
        maxAttempts = MAX_ATTEMPTS,
    )

    @Test
    fun `without a key the provider reports MissingKey`() {
        assertEquals(AsrAvailability.MissingKey, provider.availability())
    }

    @Test
    fun `a blank key is treated as no key`() {
        secretStore.writeKey(ProviderIds.CLOUD_ASR, "   ")

        assertEquals(AsrAvailability.MissingKey, provider.availability())
    }

    @Test
    fun `a stored key makes cloud ready`() {
        secretStore.writeKey(ProviderIds.CLOUD_ASR, USER_KEY)

        assertEquals(AsrAvailability.Ready, provider.availability())
    }

    @Test
    fun `without a key no request is ever made`() = runTest {
        provider.transcribe(speech()).test {
            assertEquals(AsrEvent.Failure(AsrFailure.MISSING_KEY), awaitItem())
            awaitComplete()
        }

        assertEquals(0, transport.calls)
        assertNull(transport.lastRequest)
    }

    @Test
    fun `a user key carries the utterance and returns the final transcript`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_ASR, USER_KEY)

        provider.transcribe(speech()).test {
            assertEquals(AsrEvent.Final(CANNED), awaitItem())
            awaitComplete()
        }

        assertEquals(1, transport.calls)
        val request = transport.lastRequest
        assertEquals(USER_KEY.length, request?.apiKey?.length)
        assertEquals(16_000, request?.sampleRateHz)
        assertEquals(FRAME_COUNT * 640L, request?.audioBytes)
        assertEquals(FRAME_COUNT * 20L, request?.audioDurationMs)
    }

    @Test
    fun `a provider rejection is surfaced immediately and not retried`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_ASR, USER_KEY)
        transport.results = listOf(CloudAsrResult.Failure(AsrFailure.PROVIDER_ERROR))

        provider.transcribe(speech()).test {
            assertEquals(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR), awaitItem())
            awaitComplete()
        }

        assertEquals(1, transport.calls)
    }

    @Test
    fun `a timeout is retried and can still succeed`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_ASR, USER_KEY)
        transport.latencies = listOf(REQUEST_TIMEOUT_MS * 5, 0L)

        provider.transcribe(speech()).test {
            assertEquals(AsrEvent.Final(CANNED), awaitItem())
            awaitComplete()
        }

        assertEquals(MAX_ATTEMPTS, transport.calls)
    }

    @Test
    fun `repeated timeouts surface as a network timeout`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_ASR, USER_KEY)
        transport.latencies = List(MAX_ATTEMPTS) { REQUEST_TIMEOUT_MS * 5 }

        provider.transcribe(speech()).test {
            assertEquals(AsrEvent.Failure(AsrFailure.NETWORK_TIMEOUT), awaitItem())
            awaitComplete()
        }

        assertEquals(MAX_ATTEMPTS, transport.calls)
    }

    @Test
    fun `an empty utterance never reaches the provider`() = runTest {
        secretStore.writeKey(ProviderIds.CLOUD_ASR, USER_KEY)

        provider.transcribe(emptyAudio()).test {
            assertEquals(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR), awaitItem())
            awaitComplete()
        }

        assertEquals(0, transport.calls)
    }

    private fun speech(): Flow<com.loqui.android.asr.AudioFrame> =
        flowOf(*Array(FRAME_COUNT) { audioFrame(SPEECH_AMPLITUDE) })

    private fun emptyAudio(): Flow<com.loqui.android.asr.AudioFrame> = flowOf()

    private companion object {
        const val USER_KEY = "user-supplied-key"
        const val CANNED = "cloud transcript"
        const val FRAME_COUNT = 3
        const val REQUEST_TIMEOUT_MS = 100L
        const val MAX_ATTEMPTS = 2
    }
}

/** Hand-written transport double: scripted latency per call, canned results. */
private class RecordingTransport : CloudAsrTransport {

    var results: List<CloudAsrResult> = listOf(CloudAsrResult.Success("cloud transcript"))
    var latencies: List<Long> = emptyList()

    var calls: Int = 0
        private set

    var lastRequest: CloudAsrRequest? = null
        private set

    override suspend fun transcribe(request: CloudAsrRequest): CloudAsrResult {
        val index = calls
        calls++
        lastRequest = request

        delay(latencies.getOrElse(index) { 0L })
        return results.getOrElse(index) { results.last() }
    }
}
