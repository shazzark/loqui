package com.loqui.android.asr

import com.loqui.android.models.ModelDownloadState
import com.loqui.android.models.ModelPaths
import com.loqui.android.models.SpeechModel
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.models.SpeechModels
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxAsrProviderTest {

    private val paths = ModelPaths(
        File("build/tmp/models/test-model"),
        SpeechModels.ZIPFORMER2,
    )

    @Test
    fun `a missing model is reported instead of falling back to a server`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = null, engine = engine)

        assertEquals(AsrAvailability.ModelMissing, provider.availability())
        assertEquals(
            listOf(AsrEvent.Failure(AsrFailure.MODEL_UNAVAILABLE)),
            provider.transcribe(flowOf(frame(2))).toList(),
        )
        assertFalse(engine.opened)
    }

    @Test
    fun `installed paths make the provider ready`() {
        val provider = provider(paths = paths, engine = FakeEngine())

        assertEquals(AsrAvailability.Ready, provider.availability())
    }

    @Test
    fun `warm up opens the recogniser before any audio exists`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = paths, engine = engine)

        provider.warmUp()

        // The graphs are mapped and the decoder runtime initialised, so the dictation that
        // follows opens its stream immediately and records from the first frame.
        assertTrue(engine.opened)
        assertEquals(paths, engine.openedPaths)
        assertEquals(0, engine.session.acceptedSamples)
        assertTrue(engine.session.finished)
        assertTrue("the warm-up stream must be released", engine.session.released)
    }

    @Test
    fun `warm up without an installed model is a no-op`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = null, engine = engine)

        provider.warmUp()

        assertFalse(engine.opened)
    }

    @Test
    fun `a failed warm up never reaches the user`() = runTest {
        val engine = FakeEngine(failToOpen = true)
        val provider = provider(paths = paths, engine = engine)

        // Throwing here would crash the application scope for an optimisation; the failure
        // belongs to the next dictation, which reports it with the session's own context.
        provider.warmUp()

        assertTrue(engine.opened)
    }

    @Test
    fun `frames are streamed into one session and the tail becomes the final result`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = paths, engine = engine)

        val events = provider.transcribe(flowOf(frame(2), frame(2))).toList()

        assertEquals(
            listOf(
                AsrEvent.Partial("hello 2"),
                AsrEvent.Partial("hello 4"),
                AsrEvent.Final("hello 4804"),
            ),
            events,
        )
        assertEquals(4_804, engine.session.acceptedSamples)
        assertEquals(listOf(2, 2, 4_800), engine.session.acceptSizes)
        assertEquals(AudioFrame.SAMPLE_RATE_HZ, engine.session.sampleRateHz)
        assertTrue(engine.session.finished)
        assertEquals(1, engine.session.finishCount)
        assertTrue(engine.session.released)
        assertEquals(paths, engine.openedPaths)
        assertTrue(engine.session.events.indexOf("accept:4_800") < engine.session.events.indexOf("finish"))
        assertTrue(engine.session.events.indexOf("finish") < engine.session.events.lastIndexOf("decode"))
        assertTrue(engine.session.events.lastIndexOf("decode") < engine.session.events.lastIndexOf("text"))
    }

    @Test
    fun `unchanged partials are not re-emitted`() = runTest {
        val engine = FakeEngine(textForSamples = { "constant" })
        val provider = provider(paths = paths, engine = engine)

        val events = provider.transcribe(flowOf(frame(2), frame(2), frame(2))).toList()

        assertEquals(
            listOf(AsrEvent.Partial("constant"), AsrEvent.Final("constant")),
            events,
        )
    }

    @Test
    fun `an empty transcript is reported as no speech, not a provider error`() = runTest {
        val provider = provider(paths = paths, engine = FakeEngine(textForSamples = { "" }))

        // The user can act on "no speech detected"; "transcription failed" is a dead end.
        assertEquals(
            listOf(AsrEvent.Failure(AsrFailure.NO_SPEECH)),
            provider.transcribe(flowOf(frame(2))).toList(),
        )
    }

    @Test
    fun `cancellation releases the stream without padding or finishing it`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = paths, engine = engine)
        val audio = flow {
            emit(frame(2))
            throw CancellationException("user cancelled")
        }

        var cancelled = false
        try {
            provider.transcribe(audio).toList()
        } catch (_: CancellationException) {
            cancelled = true
        }

        assertTrue(cancelled)
        assertEquals(listOf(2), engine.session.acceptSizes)
        assertEquals(0, engine.session.finishCount)
        assertTrue(engine.session.released)
    }

    @Test
    fun `torn frames leave no audio and are reported as no speech`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = paths, engine = engine)

        val events = provider.transcribe(flowOf(AudioFrame(ByteArray(1)))).toList()

        assertEquals(listOf(AsrEvent.Failure(AsrFailure.NO_SPEECH)), events)
        assertEquals(0, engine.session.acceptedSamples)
    }

    @Test
    fun `an empty audio flow reports no speech rather than a blank result`() = runTest {
        val provider = provider(paths = paths, engine = FakeEngine())

        assertEquals(
            listOf(AsrEvent.Failure(AsrFailure.NO_SPEECH)),
            provider.transcribe(flowOf()).toList(),
        )
    }
    @Test
    fun `an empty final result falls back to the last partial`() = runTest {
        val provider = provider(paths = paths, engine = FakeEngine(emptyTail = true))

        assertEquals(
            listOf(AsrEvent.Partial("hello 2"), AsrEvent.Final("hello 2")),
            provider.transcribe(flowOf(frame(2))).toList(),
        )
    }

    @Test
    fun `a decode failure after speech still emits the partial transcript`() = runTest {
        val engine = FakeEngine(failAfterAcceptedSamples = 2)
        val provider = provider(paths = paths, engine = engine)

        assertEquals(
            listOf(AsrEvent.Partial("hello 2"), AsrEvent.Final("hello 2")),
            provider.transcribe(flowOf(frame(2), frame(2))).toList(),
        )
        assertTrue(engine.session.released)
    }

    @Test
    fun `a recogniser that cannot open fails without leaking a session`() = runTest {
        val engine = FakeEngine(failToOpen = true)
        val provider = provider(paths = paths, engine = engine)

        assertEquals(
            listOf(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR)),
            provider.transcribe(flowOf(frame(2))).toList(),
        )
        assertFalse(engine.session.released)
    }

    @Test
    fun `a session failure releases the recogniser`() = runTest {
        val engine = FakeEngine(failOnAccept = true)
        val provider = provider(paths = paths, engine = engine)

        assertEquals(
            listOf(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR)),
            provider.transcribe(flowOf(frame(2))).toList(),
        )
        assertTrue(engine.session.released)
    }

    @Test
    fun `release frees the cached recogniser without touching a session`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = paths, engine = engine)

        // Called when the user leaves on-device mode: the graphs must go, but nothing here
        // may pretend a dictation was released on its behalf.
        provider.release()

        assertEquals(1, engine.releaseCount)
        assertFalse(engine.opened)
        assertFalse(engine.session.released)
    }

    private fun provider(paths: ModelPaths?, engine: StreamingAsrEngine) = SherpaOnnxAsrProvider(
        models = FakeRepository(paths),
        engine = engine,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private fun frame(samples: Int) = AudioFrame(ByteArray(samples * 2))

    private class FakeRepository(private val paths: ModelPaths?) : SpeechModelRepository {
        override val downloadState = MutableStateFlow<ModelDownloadState>(
            if (paths == null) ModelDownloadState.NotInstalled else ModelDownloadState.Installed,
        )
        override val activeModel = MutableStateFlow(SpeechModels.onDeviceAccurate)

        override fun isInstalled(model: SpeechModel): Boolean = paths != null

        override fun paths(model: SpeechModel): ModelPaths? = paths

        override fun download(model: SpeechModel) = Unit

        override fun cancelDownload() = Unit
    }

    private class FakeEngine(
        private val textForSamples: (Int) -> String = { "hello $it" },
        private val failToOpen: Boolean = false,
        private val failOnAccept: Boolean = false,
        private val failAfterAcceptedSamples: Int = Int.MAX_VALUE,
        private val emptyTail: Boolean = false,
    ) : StreamingAsrEngine {

        var opened = false
        var openedPaths: ModelPaths? = null
        val session = FakeSession(
            textForSamples = textForSamples,
            failOnAccept = failOnAccept,
            failAfterAcceptedSamples = failAfterAcceptedSamples,
            emptyTail = emptyTail,
        )

        override fun open(paths: ModelPaths, sampleRateHz: Int): StreamingAsrSession {
            opened = true
            openedPaths = paths
            if (failToOpen) throw IllegalStateException("no native library")
            return session
        }

        var releaseCount = 0
            private set

        override fun release() {
            releaseCount++
        }
    }

    private class FakeSession(
        private val textForSamples: (Int) -> String,
        private val failOnAccept: Boolean,
        private val failAfterAcceptedSamples: Int,
        private val emptyTail: Boolean,
    ) : StreamingAsrSession {

        var acceptedSamples = 0
        var sampleRateHz = 0
        var finished = false
        var finishCount = 0
        var released = false
        val acceptSizes = mutableListOf<Int>()
        val events = mutableListOf<String>()

        private var pending = false
        private var text = ""

        override fun accept(samples: FloatArray, sampleRateHz: Int) {
            events += "accept:${samples.size}"
            if (samples.size == 4_800) assertTrue(samples.all { it == 0f })
            acceptSizes += samples.size
            if (failOnAccept) throw IllegalStateException("decode exploded")
            acceptedSamples += samples.size
            this.sampleRateHz = sampleRateHz
            if (acceptedSamples > failAfterAcceptedSamples) {
                throw IllegalStateException("decode exploded after $acceptedSamples samples")
            }
            text = textForSamples(acceptedSamples)
            pending = text.isNotEmpty()
        }

        override fun isReady(): Boolean = pending

        override fun decode() {
            events += "decode"
            pending = false
        }

        override fun text(): String {
            events += "text"
            return text
        }

        override fun finish() {
            events += "finish"
            finished = true
            finishCount++
            if (emptyTail) text = ""
            pending = text.isNotEmpty()
        }

        override fun release() {
            released = true
        }
    }
}

