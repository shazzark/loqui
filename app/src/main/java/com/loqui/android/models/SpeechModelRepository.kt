package com.loqui.android.models

import com.loqui.android.di.ApplicationScope
import com.loqui.android.di.IoDispatcher
import com.loqui.android.settings.ModelQuality
import com.loqui.android.settings.SettingsRepository
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the installed on-device model: which model this device should run, what is on disk,
 * how a download progresses, and where the recogniser can find its files.
 *
 * The active model is not a constant. [ModelRouting] resolves it from the user's
 * [ModelQuality] setting and the device's RAM, and the repository follows that decision:
 * when a switch is made it stops any download for the old model, frees the old model's
 * directory, and reports the new model's install state.
 *
 * The download lives in the application scope, not a ViewModel: rotating the screen or
 * leaving Home must not cancel a 310 MiB transfer. The staging directory is renamed into
 * place only after every file's SHA-256 matches, so [paths] never returns a half-written
 * model.
 */
interface SpeechModelRepository {
    /** The model the router wants installed and running on this device. */
    val activeModel: StateFlow<SpeechModel>

    val downloadState: StateFlow<ModelDownloadState>

    fun isInstalled(model: SpeechModel): Boolean

    /** Installed and complete, or null while the model is missing. */
    fun paths(model: SpeechModel): ModelPaths?

    fun download(model: SpeechModel)

    fun cancelDownload()

    /** [paths] for [activeModel], which is what the recogniser and Home both want. */
    fun activePaths(): ModelPaths? = paths(activeModel.value)

    fun downloadActiveModel() = download(activeModel.value)
}

@Singleton
class FileSpeechModelRepository @Inject constructor(
    private val modelStorage: ModelStorage,
    private val downloader: ModelDownloader,
    private val storageProbe: ModelStorageProbe,
    private val settingsRepository: SettingsRepository,
    private val capabilities: DeviceCapabilities,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : SpeechModelRepository {

    /**
     * Auto until the user's saved choice arrives. DataStore's first emission lands within
     * milliseconds of process start, and Auto is the default setting, so this is the
     * correct answer for everyone who never changed it and a one-emission placeholder for
     * everyone else.
     */
    private val resolvedModel = MutableStateFlow(route(ModelQuality.AUTO))

    override val activeModel: StateFlow<SpeechModel> = resolvedModel.asStateFlow()

    private val state = MutableStateFlow<ModelDownloadState>(
        if (paths(resolvedModel.value) != null) {
            ModelDownloadState.Installed
        } else {
            ModelDownloadState.NotInstalled
        },
    )

    override val downloadState: StateFlow<ModelDownloadState> = state.asStateFlow()

    @Volatile
    private var job: Job? = null

    /** The model [job] is installing; null when nothing is in flight. */
    @Volatile
    private var downloading: SpeechModel? = null

    init {
        scope.launch {
            settingsRepository.settings
                .map { it.modelQuality }
                .distinctUntilChanged()
                .collect { quality -> onQualityChanged(quality) }
        }
    }

    override fun isInstalled(model: SpeechModel): Boolean = paths(model) != null

    override fun paths(model: SpeechModel): ModelPaths? {
        if (model.files.isEmpty()) return null

        val directory = File(modelStorage.modelsDirectory, model.id)
        val complete = model.files.all { spec ->
            val file = File(directory, spec.fileName)
            file.isFile && file.length() == spec.sizeBytes
        }
        return if (complete) ModelPaths(directory, model.modelType) else null
    }

    override fun download(model: SpeechModel) {
        if (!model.isDownloadable) {
            state.value = ModelDownloadState.Failed(ModelDownloadFailure.UNSUPPORTED)
            return
        }
        if (job?.isActive == true) return
        if (isInstalled(model)) {
            state.value = ModelDownloadState.Installed
            return
        }

        // A model the router no longer wants is dead weight the space check would otherwise
        // count against the new archive: a quality switch leaves the previous directory
        // behind, and on a phone that is the difference between "download" and "not enough
        // space". Only the model being installed survives.
        pruneInactiveModels(model)

        val onDisk = partFile(model).length().coerceAtLeast(0L)
        val remaining = (model.requiredBytes - onDisk).coerceAtLeast(0L)
        if (!ModelStorageReport(remaining, storageProbe.freeBytes()).hasRoom) {
            state.value = ModelDownloadState.Failed(ModelDownloadFailure.NO_SPACE)
            return
        }

        downloading = model
        job = scope.launch(ioDispatcher) { install(model) }
    }

    override fun cancelDownload() {
        job?.cancel()
        downloading = null
    }

    /**
     * Follows the router when the user changes quality.
     *
     * Three things have to happen in order: the new model becomes active so the recogniser
     * and Home stop pointing at the old one, any install for the old model is abandoned
     * (its bytes are about to be deleted), and the old model's directory is removed — but
     * only once the new model is known to be installed, because a phone with neither model
     * on disk has nothing to recognise with while the new one downloads.
     */
    private fun onQualityChanged(quality: ModelQuality) {
        val model = route(quality)
        if (model.id == resolvedModel.value.id) return

        Timber.i(
            "On-device quality %s resolves to %s (was %s)",
            quality,
            model.id,
            resolvedModel.value.id,
        )
        resolvedModel.value = model
        if (downloading != null && downloading?.id != model.id) {
            cancelDownload()
        }

        val installed = paths(model) != null
        state.value = if (installed) ModelDownloadState.Installed else ModelDownloadState.NotInstalled
        if (installed) pruneInactiveModels(model)
    }

    private fun route(quality: ModelQuality): SpeechModel =
        ModelRouting.resolve(quality, capabilities.totalRamBytes())

    private suspend fun install(model: SpeechModel) {
        val part = partFile(model)
        val staging = stagingDirectory(model)
        val url = checkNotNull(model.downloadUrl)
        val checksum = checkNotNull(model.checksum)

        try {
            modelStorage.modelsDirectory.mkdirs()
            staging.deleteRecursively()
            if (part.length() > model.approxBytes) part.delete()

            if (part.length() == model.approxBytes) {
                // A previous attempt downloaded everything but failed while unpacking.
                state.value = ModelDownloadState.Downloading(model.approxBytes, model.approxBytes)
            } else {
                state.value = ModelDownloadState.Downloading(part.length(), model.approxBytes)
                downloader.download(url, part, model.approxBytes) { received ->
                    state.value = ModelDownloadState.Downloading(
                        bytesReceived = received.coerceIn(0L, model.approxBytes),
                        totalBytes = model.approxBytes,
                    )
                }
            }

            state.value = ModelDownloadState.Verifying
            downloader.verify(part, checksum)

            state.value = ModelDownloadState.Installing
            if (!staging.mkdirs() && !staging.isDirectory) {
                throw ModelDownloadException(
                    ModelDownloadFailure.STORAGE,
                    "Could not create the model staging directory",
                )
            }
            downloader.extract(part, model, staging)

            val target = File(modelStorage.modelsDirectory, model.id)
            target.deleteRecursively()
            if (!staging.renameTo(target)) {
                throw ModelDownloadException(
                    ModelDownloadFailure.STORAGE,
                    "Could not move the model into place",
                )
            }

            part.delete()
            state.value = ModelDownloadState.Installed
            Timber.i("Installed %s (%d bytes)", model.id, model.installedBytes)
        } catch (cancellation: CancellationException) {
            part.delete()
            staging.deleteRecursively()
            state.value = ModelDownloadState.NotInstalled
            throw cancellation
        } catch (throwable: Throwable) {
            staging.deleteRecursively()

            val failure = (throwable as? ModelDownloadException)?.failure
                ?: ModelDownloadFailure.STORAGE
            // A network failure keeps the partial file so the next tap resumes; anything
            // else means the bytes on disk are unusable and must not be trusted.
            if (failure != ModelDownloadFailure.NETWORK) {
                part.delete()
            }

            state.value = ModelDownloadState.Failed(failure)
            Timber.w(throwable, "On-device model install failed: %s", failure)
        } finally {
            downloading = null
            job = null
        }
    }

    private fun partFile(model: SpeechModel): File =
        File(modelStorage.modelsDirectory, ".${model.id}.archive.part")

    private fun stagingDirectory(model: SpeechModel): File =
        File(modelStorage.modelsDirectory, ".${model.id}.staging")

    /**
     * Deletes every model directory except [active]'s.
     *
     * Two models can never be useful at once: the router runs exactly one, and keeping the
     * other costs its installed size — 43 MiB for the 20M export, 68 MiB for the accurate
     * one — on a phone where that is the difference between "download" and "not enough
     * space". Only directories are touched: a `.part` file belongs to a download in flight
     * and dot-directories are staging, so a prune can never race an install.
     */
    private fun pruneInactiveModels(active: SpeechModel) {
        modelStorage.modelsDirectory.listFiles()?.forEach { entry ->
            val inactive = entry.isDirectory &&
                !entry.name.startsWith(".") &&
                entry.name != active.id
            if (inactive && entry.deleteRecursively()) {
                Timber.i("Removed inactive on-device model %s", entry.name)
            }
        }
    }
}
