package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import com.loqui.android.testing.pcmChunk
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FakeAsrProviderTest {

    private val provider = FakeAsrProvider(mode = AsrMode.ON_DEVICE)

    @Test
    fun `a real utterance produces a partial and a final transcript`() = runTest {
        val frames = List(4) { AudioFrame(pcmChunk(amplitude = 4_000)) }

        val events = provider.transcribe(flowOf(*frames.toTypedArray())).toList()

        assertEquals(2, events.size)
        assertEquals(AsrEvent.Partial("This"), events[0])
        assertEquals(AsrEvent.Final(FakeAsrProvider.DEFAULT_TRANSCRIPT), events[1])
        assertEquals(4, provider.ingestedFrames)
        assertEquals(4L * frames.first().pcm16.size, provider.ingestedBytes)
        assertEquals(1, provider.utterances)
    }

    @Test
    fun `an empty stream fails instead of inventing a transcript`() = runTest {
        val events = provider.transcribe(emptyFlow()).toList()

        assertEquals(listOf(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR)), events)
        assertEquals(0, provider.utterances)
    }

    @Test
    fun `a configured failure is reported after the audio is consumed`() = runTest {
        val failing = FakeAsrProvider(
            mode = AsrMode.ON_DEVICE,
            failure = AsrFailure.NETWORK_TIMEOUT,
        )

        val events = failing.transcribe(flowOf(AudioFrame(pcmChunk(4_000)))).toList()

        assertEquals(listOf(AsrEvent.Failure(AsrFailure.NETWORK_TIMEOUT)), events)
        assertEquals(1, failing.ingestedFrames)
        assertEquals(0, failing.utterances)
    }

    @Test
    fun `each executed session counts exactly one utterance`() = runTest {
        val audio = flowOf(AudioFrame(pcmChunk(4_000)))

        provider.transcribe(audio).toList()
        provider.transcribe(audio).toList()

        assertTrue(provider.utterances == 2)
    }

    @Test
    fun `availability is reported without transcribing`() {
        val missingKey = FakeAsrProvider(mode = AsrMode.CLOUD, availability = AsrAvailability.MissingKey)

        assertEquals(AsrAvailability.MissingKey, missingKey.availability())
        assertEquals(AsrMode.CLOUD, missingKey.mode)
    }
}
