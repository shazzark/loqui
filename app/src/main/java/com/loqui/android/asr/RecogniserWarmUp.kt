package com.loqui.android.asr

import com.loqui.android.di.ApplicationScope
import com.loqui.android.models.ModelDownloadState
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Keeps the on-device recogniser loaded while it can actually be used.
 *
 * `SherpaOnnxAsrProvider.transcribe` opens the recogniser before it collects a single audio
 * frame, so on a cold process the microphone stays closed for as long as the ONNX graphs
 * take to map — the first words of the first dictation are lost and the first partial
 * arrives that much later. Warming up as soon as the model is installed (and again when the
 * user switches back from cloud mode) moves that cost off the dictation path entirely.
 *
 * Deliberately quiet: a warm-up is an optimisation, so a failure is logged and retried on
 * the next trigger, never surfaced. Cloud mode is skipped — loading ~68 MiB of graphs for a
 * recogniser the user has opted out of would be spent memory.
 */
@Singleton
class RecogniserWarmUp @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val models: SpeechModelRepository,
    private val provider: SherpaOnnxAsrProvider,
    @ApplicationScope private val scope: CoroutineScope,
) {

    /**
     * Starts observing settings and install state.
     *
     * @return the observer job. It only ends with the scope, so a caller that wants to stop
     *   it (a test, or a future screen that wants the memory back) can cancel this instead of
     *   cancelling the application scope.
     */
    fun start(): Job = scope.launch {
        combine(
            settingsRepository.settings.map { it.asrMode },
            models.downloadState,
        ) { mode, download ->
            mode == AsrMode.ON_DEVICE && download == ModelDownloadState.Installed
        }
            .distinctUntilChanged()
            .collect { usable ->
                if (!usable) return@collect
                Timber.d("On-device model is installed; warming the recogniser")
                provider.warmUp()
            }
    }
}
