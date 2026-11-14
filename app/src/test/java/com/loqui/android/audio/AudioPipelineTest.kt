package com.loqui.android.audio

import com.loqui.android.asr.AsrEvent
import com.loqui.android.asr.AsrFailure
import com.loqui.android.asr.FakeAsrProvider
import com.loqui.android.audio.vad.EnergyVad
import com.loqui.android.audio.vad.SpeechEndpointer
import com.loqui.android.audio.vad.VadConfig
import com.loqui.android.settings.AsrMode
import com.loqui.android.testing.FakePcmAudioSource
import com.loqui.android.testing.QUIET_AMPLITUDE
import com.loqui.android.testing.SPEECH_AMPLITUDE
import com.loqui.android.testing.pcmChunk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The Phase 2 acceptance test, on the JVM: scripted microphone → ring buffer → VAD
 * endpointing → `AsrProvider` → final transcript, with no API key anywhere.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AudioPipelineTest {

    @Test
    fun `microphone audio reaches the provider as one utterance`() = runTest {
        // 60 ms of speech opens the utterance, then silence trips the 100 ms hangover.
        val source = FakePcmAudioSource(
            List(3) { pcmChunk(SPEECH_AMPLITUDE) } + List(10) { pcmChunk(QUIET_AMPLITUDE) },
        )
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )
        val vadConfig = VadConfig(
            frameDurationMs = 20,
            speechRmsThreshold = 0.02f,
            onsetFrames = 3,
            silenceHangoverFrames = 5,
        )
        val endpointer = SpeechEndpointer(vadConfig, EnergyVad(vadConfig.speechRmsThreshold))
        val provider = FakeAsrProvider(mode = AsrMode.ON_DEVICE)

        val events = provider.transcribe(endpointer.segment(stream.frames())).toList()

        assertEquals(2, events.size)
        assertEquals(AsrEvent.Partial("This"), events.first())
        assertEquals(AsrEvent.Final(FakeAsrProvider.DEFAULT_TRANSCRIPT), events.last())

        // 3 onset frames + 5 hangover frames reached the recognizer, nothing else.
        assertEquals(8, provider.ingestedFrames)
        assertEquals(8L * AudioCaptureStream.FRAME_BYTES, provider.ingestedBytes)

        // The endpoint released the microphone instead of recording until stopped.
        assertEquals(1, source.releaseCount)
    }

    @Test
    fun `pure silence never opens a session`() = runTest {
        val source = FakePcmAudioSource(List(20) { pcmChunk(QUIET_AMPLITUDE) })
        val stream = AudioCaptureStream(
            PcmAudioSourceFactory { source },
            UnconfinedTestDispatcher(testScheduler),
        )
        val endpointer = SpeechEndpointer(VadConfig(), EnergyVad(0.02f))
        val provider = FakeAsrProvider(mode = AsrMode.ON_DEVICE)

        val events = provider.transcribe(endpointer.segment(stream.frames())).toList()

        assertEquals(
            listOf<AsrEvent>(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR)),
            events,
        )
        assertEquals(0, provider.ingestedFrames)
        assertEquals(1, source.releaseCount)
    }
}
