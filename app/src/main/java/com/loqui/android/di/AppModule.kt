package com.loqui.android.di

import com.loqui.android.asr.AsrProviderFactory
import com.loqui.android.asr.FakeAsrProviderFactory
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
abstract class AppBindingsModule {

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    @Binds
    abstract fun bindSecretStore(impl: KeystoreSecretStore): SecretStore

    @Binds
    abstract fun bindTextInserter(impl: DefaultTextInserter): TextInserter

    /** Phase 1 uses the fake recognizer; Phase 2 swaps in the real factory. */
    @Binds
    abstract fun bindAsrProviderFactory(impl: FakeAsrProviderFactory): AsrProviderFactory
}
