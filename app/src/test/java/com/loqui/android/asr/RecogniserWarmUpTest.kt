package com.loqui.android.asr

import com.loqui.android.models.ModelDownloadState
import com.loqui.android.models.ModelPaths
import com.loqui.android.models.SpeechModel
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.models.SpeechModels
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
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recogniser has to be loaded before the user taps, not while they are already talking:
 * `SherpaOnnxAsrProvider.transcribe` opens it before the first frame is collected, so on a
 * cold process the microphone stays closed for the whole load.
 *
 * The mirror image matters just as much: a recogniser nothing can use is 240 MB of int8
 * graphs held for the life of the process, so cloud mode, an uninstalled model and a model
 * switch all have to free it.
 */
class RecogniserWarmUpTest {

    private val paths = ModelPaths(
        File("build/tmp/models/test-model"),
        SpeechModels.ZIPFORMER2,
    )

    @Test
    fun `an installed model is warmed for the next dictation`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)

        settled(warmUp(engine, models))
        advanceUntilIdle()

        assertEquals(1, engine.openCount)
    }

    @Test
    fun `a missing model is not opened`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.NotInstalled, paths = null)

        settled(warmUp(engine, models))
        advanceUntilIdle()

        assertEquals(0, engine.openCount)
    }

    @Test
    fun `cloud mode does not spend memory on the on-device recogniser`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)
        val settings = FakeSettingsRepository(LoquiSettings(asrMode = AsrMode.CLOUD))

        settled(warmUp(engine, models, settings))
        advanceUntilIdle()

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

    @Test
    fun `leaving for cloud mode releases the warm recogniser`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)
        val settings = FakeSettingsRepository()
        val observer = warmUp(engine, models, settings).start()

        advanceUntilIdle()
        assertEquals(1, engine.openCount)

        // Cloud dictation never opens these graphs again, so keeping them mapped costs every
        // other app on the phone RAM that nothing reclaims.
        settings.setAsrMode(AsrMode.CLOUD)
        advanceUntilIdle()

        assertEquals(1, engine.releaseCount)
        observer.cancelAndJoin()
    }

    @Test
    fun `uninstalling the model releases the recogniser`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)
        val observer = warmUp(engine, models).start()

        advanceUntilIdle()
        assertEquals(1, engine.openCount)

        models.state.value = ModelDownloadState.NotInstalled
        advanceUntilIdle()

        assertEquals(1, engine.releaseCount)
        observer.cancelAndJoin()
    }

    @Test
    fun `switching quality re-warms the model the router picked`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths, SpeechModels.onDeviceAccurate)
        val observer = warmUp(engine, models).start()

        advanceUntilIdle()
        assertEquals(1, engine.openCount)

        // A model switch is the one case where the old warm-up is not reusable: the graphs
        // are different, so the pick has to be loaded before the next dictation.
        models.model.value = SpeechModels.onDeviceFast
        advanceUntilIdle()

        assertEquals(2, engine.openCount)
        observer.cancelAndJoin()
    }

    @Test
    fun `start returns before the graphs are mapped`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)

        // `Application.onCreate` calls start(). If it waited for the load, the first screen
        // would appear seconds late on the very launch that has to download nothing.
        val observer = warmUp(engine, models).start()
        assertEquals("start() must not block on the load", 0, engine.openCount)

        advanceUntilIdle()
        assertEquals(1, engine.openCount)
        observer.cancelAndJoin()
    }

    @Test
    fun `an unchanged state does not reload the recogniser`() = runTest {
        val engine = CountingEngine()
        val models = FakeModels(ModelDownloadState.Installed, paths)
        val settings = FakeSettingsRepository()
        val observer = warmUp(engine, models, settings).start()

        advanceUntilIdle()
        // Progress reports and repeated settings writes must not walk into the lock and
        // throw away a warm recogniser for an identical target.
        settings.setAsrMode(AsrMode.ON_DEVICE)
        settings.setAsrMode(AsrMode.ON_DEVICE)
        advanceUntilIdle()

        assertEquals(1, engine.openCount)
        assertEquals(0, engine.releaseCount)
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
    ): RecogniserWarmUp = RecogniserWarmUp(
        settingsRepository = settings,
        models = models,
        provider = provider(engine, models),
        computeDispatcher = UnconfinedTestDispatcher(testScheduler),
        scope = this,
    )

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
        model: SpeechModel = SpeechModels.onDeviceAccurate,
    ) : SpeechModelRepository {

        val state = MutableStateFlow(initial)
        override val downloadState: StateFlow<ModelDownloadState> = state

        val model = MutableStateFlow(model)
        override val activeModel: StateFlow<SpeechModel> = this.model

        override fun isInstalled(model: SpeechModel): Boolean = paths != null

        override fun paths(model: SpeechModel): ModelPaths? = paths

        override fun download(model: SpeechModel) = Unit

        override fun cancelDownload() = Unit
    }

    private class CountingEngine : StreamingAsrEngine {

        var openCount = 0
            private set

        var releaseCount = 0
            private set

        override fun open(paths: ModelPaths, sampleRateHz: Int): StreamingAsrSession {
            openCount++
            return QuietSession
        }

        override fun release() {
            releaseCount++
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
