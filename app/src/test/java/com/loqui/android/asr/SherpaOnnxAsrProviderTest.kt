package com.loqui.android.asr

import com.loqui.android.models.ModelDownloadState
import com.loqui.android.models.ModelPaths
import com.loqui.android.models.SpeechModel
import com.loqui.android.models.SpeechModelRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SherpaOnnxAsrProviderTest {

    private val paths = ModelPaths(File("build/tmp/models/test-model"))

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
    fun `frames are streamed into one session and the tail becomes the final result`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = paths, engine = engine)

        val events = provider.transcribe(flowOf(frame(2), frame(2))).toList()

        assertEquals(
            listOf(
                AsrEvent.Partial("hello 2"),
                AsrEvent.Partial("hello 4"),
                AsrEvent.Final("hello 4"),
            ),
            events,
        )
        assertEquals(4, engine.session.acceptedSamples)
        assertEquals(AudioFrame.SAMPLE_RATE_HZ, engine.session.sampleRateHz)
        assertTrue(engine.session.finished)
        assertTrue(engine.session.released)
        assertEquals(paths, engine.openedPaths)
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
    fun `an empty transcript is a provider error, not a blank insertion`() = runTest {
        val provider = provider(paths = paths, engine = FakeEngine(textForSamples = { "" }))

        assertEquals(
            listOf(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR)),
            provider.transcribe(flowOf(frame(2))).toList(),
        )
    }

    @Test
    fun `a torn frame is dropped before it reaches the recogniser`() = runTest {
        val engine = FakeEngine()
        val provider = provider(paths = paths, engine = engine)

        val events = provider.transcribe(flowOf(AudioFrame(ByteArray(1)))).toList()

        assertEquals(listOf(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR)), events)
        assertEquals(0, engine.session.acceptedSamples)
    }

    @Test
    fun `an empty audio flow fails rather than finishing on a blank result`() = runTest {
        val provider = provider(paths = paths, engine = FakeEngine())

        assertEquals(
            listOf(AsrEvent.Failure(AsrFailure.PROVIDER_ERROR)),
            provider.transcribe(flowOf()).toList(),
        )
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

        override fun isInstalled(model: SpeechModel): Boolean = paths != null

        override fun paths(model: SpeechModel): ModelPaths? = paths

        override fun download(model: SpeechModel) = Unit

        override fun cancelDownload() = Unit
    }

    private class FakeEngine(
        private val textForSamples: (Int) -> String = { "hello $it" },
        private val failToOpen: Boolean = false,
        private val failOnAccept: Boolean = false,
    ) : StreamingAsrEngine {

        var opened = false
        var openedPaths: ModelPaths? = null
        val session = FakeSession(textForSamples, failOnAccept)

        override fun open(paths: ModelPaths, sampleRateHz: Int): StreamingAsrSession {
            opened = true
            openedPaths = paths
            if (failToOpen) throw IllegalStateException("no native library")
            return session
        }
    }

    private class FakeSession(
        private val textForSamples: (Int) -> String,
        private val failOnAccept: Boolean,
    ) : StreamingAsrSession {

        var acceptedSamples = 0
        var sampleRateHz = 0
        var finished = false
        var released = false

        private var pending = false
        private var text = ""

        override fun accept(samples: FloatArray, sampleRateHz: Int) {
            if (failOnAccept) throw IllegalStateException("decode exploded")
            acceptedSamples += samples.size
            this.sampleRateHz = sampleRateHz
            text = textForSamples(acceptedSamples)
            pending = text.isNotEmpty()
        }

        override fun isReady(): Boolean = pending

        override fun decode() {
            pending = false
        }

        override fun text(): String = text

        override fun finish() {
            finished = true
            pending = text.isNotEmpty()
        }

        override fun release() {
            released = true
        }
    }
}

