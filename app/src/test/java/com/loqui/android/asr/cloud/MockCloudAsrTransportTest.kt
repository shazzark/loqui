package com.loqui.android.asr.cloud

import com.loqui.android.asr.AsrFailure
import com.loqui.android.testing.SPEECH_AMPLITUDE
import com.loqui.android.testing.audioFrame
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class MockCloudAsrTransportTest {

    @Test
    fun `returns the canned transcript and performs no network call`() = runTest {
        val transport = MockCloudAsrTransport(simulatedLatencyMs = 1)

        val result = transport.transcribe(
            CloudAsrRequest(
                apiKey = "user-key",
                sampleRateHz = 16_000,
                audioBytes = 1_920,
                audioDurationMs = 60,
            ),
        )

        assertEquals(CloudAsrResult.Success(MockCloudAsrTransport.CANNED_TRANSCRIPT), result)
        assertEquals(1, transport.calls)
    }

    @Test
    fun `records request shape without copying the key`() = runTest {
        val transport = MockCloudAsrTransport(simulatedLatencyMs = 1)

        transport.transcribe(
            CloudAsrRequest(
                apiKey = "abcd",
                sampleRateHz = 16_000,
                audioBytes = audioFrame(SPEECH_AMPLITUDE).pcm16.size.toLong(),
                audioDurationMs = 20,
            ),
        )

        assertEquals(16_000, transport.lastSampleRateHz)
        assertEquals(640L, transport.lastAudioBytes)
        assertEquals(4, transport.lastKeyLength)
    }

    @Test
    fun `the mock never reports a failure`() = runTest {
        val transport = MockCloudAsrTransport(simulatedLatencyMs = 1)

        val failing = (1..5).map {
            transport.transcribe(CloudAsrRequest("k", 16_000, 640, 20))
        }.filterIsInstance<CloudAsrResult.Failure>()

        assertEquals(emptyList<AsrFailure>(), failing.map { it.reason })
    }
}
