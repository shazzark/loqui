package com.loqui.android.asr

import com.loqui.android.di.ApplicationScope
import com.loqui.android.di.DefaultDispatcher
import com.loqui.android.models.ModelDownloadState
import com.loqui.android.models.SpeechModel
import com.loqui.android.models.SpeechModelRepository
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber

/**
 * Keeps the recogniser loaded exactly while it can be used, and gone when it cannot.
 *
 * `SherpaOnnxAsrProvider.transcribe` opens the recogniser before it collects a single audio
 * frame, so on a cold process the microphone stays closed for as long as the ONNX graphs
 * take to map — the first words of the first dictation are lost and the first partial
 * arrives that much later. Warming up as soon as the model is installed (and again when the
 * user switches back from cloud mode) moves that cost off the dictation path entirely.
 *
 * Two things this deliberately does not do:
 *
 * - **It never blocks the caller.** [start] only subscribes; the load runs on
 *   [DefaultDispatcher] inside the application scope, so `Application.onCreate` returns and
 *   the first screen draws while the graphs are still mapping.
 * - **It does not keep a recogniser nothing can use.** `collectLatest` cancels a warm-up
 *   that a newer state supersedes — the user toggling cloud mode no longer queues a second
 *   load behind the first — and releasing drops the graphs when the model is uninstalled or
 *   on-device mode is off. On a 4 GB phone those are tens of megabytes of int8 weights.
 *
 * A *different* active model is a new target: switching quality re-warms the model the
 * router picked, so the switch costs the next user a reload only once.
 *
 * Deliberately quiet: a warm-up is an optimisation, so a failure is logged and retried on
 * the next trigger, never surfaced.
 */
@Singleton
class RecogniserWarmUp @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val models: SpeechModelRepository,
    private val provider: SherpaOnnxAsrProvider,
    @DefaultDispatcher private val computeDispatcher: CoroutineDispatcher,
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
            models.activeModel,
            // Only the installed/not-installed edge matters; download progress would
            // otherwise re-trigger the release path at every 512 KiB step.
            models.downloadState.map { it == ModelDownloadState.Installed },
        ) { mode, model, installed -> WarmTarget(mode, model, installed) }
            .distinctUntilChanged()
            .collectLatest { target ->
                withContext(computeDispatcher) {
                    if (target.usable) {
                        Timber.d("Warming the on-device recogniser for %s", target.model.id)
                        provider.warmUp()
                    } else {
                        Timber.d(
                            "Recogniser is not usable (%s, %s); releasing it",
                            target.mode,
                            target.model.id,
                        )
                        provider.release()
                    }
                }
            }
    }

    /** The three facts that decide whether the recogniser should be resident. */
    private data class WarmTarget(
        val mode: AsrMode,
        val model: SpeechModel,
        val installed: Boolean,
    ) {
        val usable: Boolean
            get() = mode == AsrMode.ON_DEVICE && installed
    }
}
