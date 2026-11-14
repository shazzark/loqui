package com.loqui.android.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.loqui.android.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map

interface SettingsRepository {
    val settings: Flow<LoquiSettings>

    suspend fun setAsrMode(mode: AsrMode)

    suspend fun setLlmEnabled(enabled: Boolean)

    suspend fun setLlmMode(mode: LlmMode)
}

@Singleton
class DataStoreSettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : SettingsRepository {

    override val settings: Flow<LoquiSettings> = context.settingsDataStore.data
        .catch { throwable ->
            if (throwable is IOException) emit(emptyPreferences()) else throw throwable
        }
        .map { preferences -> preferences.toSettings() }
        .flowOn(ioDispatcher)

    override suspend fun setAsrMode(mode: AsrMode) {
        context.settingsDataStore.edit { it[Keys.ASR_MODE] = mode.storageValue }
    }

    override suspend fun setLlmEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.LLM_ENABLED] = enabled }
    }

    override suspend fun setLlmMode(mode: LlmMode) {
        context.settingsDataStore.edit { it[Keys.LLM_MODE] = mode.storageValue }
    }

    private fun Preferences.toSettings(): LoquiSettings = LoquiSettings(
        asrMode = AsrMode.fromStorage(this[Keys.ASR_MODE]),
        llmEnabled = this[Keys.LLM_ENABLED] ?: false,
        llmMode = LlmMode.fromStorage(this[Keys.LLM_MODE]),
    )

    private object Keys {
        val ASR_MODE = stringPreferencesKey("asr.mode")
        val LLM_ENABLED = booleanPreferencesKey("llm.enabled")
        val LLM_MODE = stringPreferencesKey("llm.mode")
    }
}

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "loqui_settings",
)
