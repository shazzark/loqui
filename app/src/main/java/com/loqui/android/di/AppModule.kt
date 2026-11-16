package com.loqui.android.di

import com.loqui.android.asr.AsrProvider
import com.loqui.android.asr.AsrProviderFactory
import com.loqui.android.asr.DefaultAsrProviderFactory
import com.loqui.android.asr.SherpaOnnxAsrProvider
import com.loqui.android.asr.SherpaOnnxStreamingEngine
import com.loqui.android.asr.StreamingAsrEngine
import com.loqui.android.audio.AudioRecordPcmSource
import com.loqui.android.audio.PcmAudioSourceFactory
import com.loqui.android.audio.vad.EnergyVad
import com.loqui.android.audio.vad.VadConfig
import com.loqui.android.audio.vad.VoiceActivityDetector
import com.loqui.android.insertion.DefaultTextInserter
import com.loqui.android.insertion.TextInserter
import com.loqui.android.llm.DefaultLlmProviderFactory
import com.loqui.android.llm.LlmProviderFactory
import com.loqui.android.models.AppModelStorage
import com.loqui.android.models.DeviceModelStorageProbe
import com.loqui.android.models.FileSpeechModelRepository
import com.loqui.android.models.KtorModelDownloader
import com.loqui.android.models.ModelDownloader
import com.loqui.android.models.ModelStorage
import com.loqui.android.models.ModelStorageProbe
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.settings.DataStoreSettingsRepository
import com.loqui.android.settings.KeystoreSecretStore
import com.loqui.android.settings.SecretStore
import com.loqui.android.settings.SettingsRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}

@Module
@InstallIn(SingletonComponent::class)
object AudioModule {

    /**
     * Endpointing defaults: 20 ms frames, 60 ms of speech to start an utterance, 800 ms
     * of silence to end it, and a 500 ms opening window in which the detector measures
     * the room before it decides what speech is.
     */
    @Provides
    @Singleton
    fun provideVadConfig(): VadConfig = VadConfig()

    /**
     * Swappable detector: a calibrated energy baseline now, Silero ONNX once models ship.
     * It reads its gate from the same [VadConfig] as the endpointer, so the calibration
     * window and its bounds are tuned in one place.
     */
    @Provides
    fun provideVoiceActivityDetector(config: VadConfig): VoiceActivityDetector =
        EnergyVad(config)

    /**
     * The microphone is only ever opened through this factory; the permission gate
     * lives in `VoiceInputService` before capture is started.
     */
    @Provides
    fun providePcmAudioSourceFactory(): PcmAudioSourceFactory =
        PcmAudioSourceFactory { AudioRecordPcmSource() }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class AppBindingsModule {

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindSecretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    abstract fun bindTextInserter(impl: DefaultTextInserter): TextInserter

    /** Phase 6: on-device sherpa-onnx plus the cloud scaffold, selected in settings. */
    @Binds
    abstract fun bindAsrProviderFactory(impl: DefaultAsrProviderFactory): AsrProviderFactory

    /** Loaded once and shared by every dictation; see `DefaultAsrProviderFactory`. */
    @Binds
    abstract fun bindStreamingAsrEngine(impl: SherpaOnnxStreamingEngine): StreamingAsrEngine

    /** Phase 4: deterministic on-device clean-up plus the opt-in cloud scaffold. */
    @Binds
    abstract fun bindLlmProviderFactory(impl: DefaultLlmProviderFactory): LlmProviderFactory

    /** Phase 5: free-space check before a model download can start. */
    @Binds
    abstract fun bindModelStorageProbe(impl: DeviceModelStorageProbe): ModelStorageProbe

    /** Phase 6: app-private model directory, download pipeline and install state. */
    @Binds
    abstract fun bindModelStorage(impl: AppModelStorage): ModelStorage

    @Binds
    abstract fun bindModelDownloader(impl: KtorModelDownloader): ModelDownloader

    @Binds
    abstract fun bindSpeechModelRepository(impl: FileSpeechModelRepository): SpeechModelRepository
}

@Module
@InstallIn(SingletonComponent::class)
object AsrModule {

    /** The on-device provider is app-scoped, so it carries the recogniser between screens. */
    @Provides
    @Singleton
    @OnDeviceAsrProvider
    fun provideOnDeviceAsrProvider(impl: SherpaOnnxAsrProvider): AsrProvider = impl
}
