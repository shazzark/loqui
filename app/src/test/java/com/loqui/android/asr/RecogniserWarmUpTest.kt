package com.loqui.android.asr

import com.loqui.android.models.ModelDownloadState
import com.loqui.android.models.ModelPaths
import com.loqui.android.models.SpeechModel
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.LoquiSettings
import com.loqui.android.settings.SettingsRepository
import com.loqui.android.testing.FakeSettingsRepository
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The recogniser has to be loaded before the user taps, not while they are already talking:
 * `SherpaOnnxAsrProvider.transcribe` opens it before the first frame is collected, so on a
 * cold process the microphone stays closed for the whole load.
 */
class RecogniserWarmUpTest {

    private val paths = ModelPaths(File("build/tmp/models/test-model"))

    @Test
    fun `an installed model is warmed for the next dictation`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)

        settled(warmUp(engine, models))

        assertEquals(1, engine.openCount)
    }

    @Test
    fun `a missing model is not opened`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.NotInstalled, paths = null)

        settled(warmUp(engine, models))

        assertEquals(0, engine.openCount)
    }

    @Test
    fun `cloud mode does not spend memory on the on-device recogniser`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)
        val settings = FakeSettingsRepository(LoquiSettings(asrMode = AsrMode.CLOUD))

        settled(warmUp(engine, models, settings))

        assertEquals(0, engine.openCount)
    }

    @Test
    fun `a finished install warms immediately`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.NotInstalled, paths)
        val observer = warmUp(engine, models).start()

        advanceUntilIdle()
        assertEquals(0, engine.openCount)

        // The user tapped "download" on Home: the first dictation after the install must not
        // pay for the load either.
        models.state.value = ModelDownloadState.Installed
        advanceUntilIdle()

        assertEquals(1, engine.openCount)
        observer.cancelAndJoin()
    }

    @Test
    fun `switching back from cloud warms the recogniser`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)
        val settings = FakeSettingsRepository(LoquiSettings(asrMode = AsrMode.CLOUD))
        val observer = warmUp(engine, models, settings).start()

        advanceUntilIdle()
        assertEquals(0, engine.openCount)

        settings.setAsrMode(AsrMode.ON_DEVICE)
        advanceUntilIdle()

        assertEquals(1, engine.openCount)
        observer.cancelAndJoin()
    }

    /**
     * Runs the observer until it has nothing left to do, then cancels it.
     *
     * The observer collects for the life of the process, so it is opened on the test scope and
     * cancelled before the body ends — `runTest` waits for its children.
     */
    private suspend fun TestScope.settled(observer: RecogniserWarmUp): Job {
        val job = observer.start()
        advanceUntilIdle()
        job.cancelAndJoin()
        return job
    }

    private fun TestScope.warmUp(
        engine: StreamingAsrEngine,
        models: SpeechModelRepository,
        settings: SettingsRepository = FakeSettingsRepository(),
    ): RecogniserWarmUp = RecogniserWarmUp(settings, models, provider(engine, models), this)

    private fun provider(
        engine: StreamingAsrEngine,
        models: SpeechModelRepository,
    ) = SherpaOnnxAsrProvider(
        models = models,
        engine = engine,
        ioDispatcher = Dispatchers.Unconfined,
    )

    private class FakeModels(
        initial: ModelDownloadState,
        private val paths: ModelPaths?,
    ) : SpeechModelRepository {

        val state = MutableStateFlow(initial)
        override val downloadState: StateFlow<ModelDownloadState> = state

        override fun isInstalled(model: SpeechModel): Boolean = paths != null

        override fun paths(model: SpeechModel): ModelPaths? = paths

        override fun download(model: SpeechModel) = Unit

        override fun cancelDownload() = Unit
    }

    private class CountingEngine : StreamingAsrEngine {

        var openCount = 0
            private set

        override fun open(paths: ModelPaths, sampleRateHz: Int): StreamingAsrSession {
            openCount++
            return QuietSession
        }
    }

    /** A session that decodes nothing: the warm-up only has to open, drain and release. */
    private object QuietSession : StreamingAsrSession {

        override fun accept(samples: FloatArray, sampleRateHz: Int) = Unit

        override fun isReady(): Boolean = false

        override fun decode() = Unit

        override fun text(): String = ""

        override fun finish() = Unit

        override fun release() = Unit
    }
}
