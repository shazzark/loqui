package com.loqui.android.di

import com.loqui.android.asr.AsrProviderFactory
import com.loqui.android.asr.DefaultAsrProviderFactory
import com.loqui.android.audio.AudioRecordPcmSource
import com.loqui.android.audio.PcmAudioSourceFactory
import com.loqui.android.audio.vad.EnergyVad
import com.loqui.android.audio.vad.VadConfig
import com.loqui.android.audio.vad.VoiceActivityDetector
import com.loqui.android.insertion.DefaultTextInserter
import com.loqui.android.insertion.TextInserter
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
     * Endpointing defaults: 20 ms frames, 60 ms of speech to start an utterance and
     * 800 ms of silence to end it.
     */
    @Provides
    @Singleton
    fun provideVadConfig(): VadConfig = VadConfig()

    /** Swappable detector: an energy baseline now, Silero ONNX once models ship. */
    @Provides
    fun provideVoiceActivityDetector(config: VadConfig): VoiceActivityDetector =
        EnergyVad(config.speechRmsThreshold)

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

    /** Phase 2: on-device fake plus the cloud scaffold, selected through the settings. */
    @Binds
    abstract fun bindAsrProviderFactory(impl: DefaultAsrProviderFactory): AsrProviderFactory
}
