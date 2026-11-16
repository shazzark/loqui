package com.loqui.android.models

import com.loqui.android.di.ApplicationScope
import com.loqui.android.di.IoDispatcher
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
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Owns the installed on-device model: what is on disk, how a download progresses, and
 * where the recogniser can find its files.
 *
 * The download lives in the application scope, not a ViewModel: rotating the screen or
 * leaving Home must not cancel a 296 MiB transfer. The staging directory is renamed into
 * place only after every file's SHA-256 matches, so [paths] never returns a half-written
 * model.
 */
interface SpeechModelRepository {
    val downloadState: StateFlow<ModelDownloadState>

    fun isInstalled(model: SpeechModel = SpeechModels.onDeviceDefault): Boolean

    /** Installed and complete, or null while the model is missing. */
    fun paths(model: SpeechModel = SpeechModels.onDeviceDefault): ModelPaths?

    fun download(model: SpeechModel = SpeechModels.onDeviceDefault)

    fun cancelDownload()
}

@Singleton
class FileSpeechModelRepository @Inject constructor(
    private val modelStorage: ModelStorage,
    private val downloader: ModelDownloader,
    private val storageProbe: ModelStorageProbe,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    @ApplicationScope private val scope: CoroutineScope,
) : SpeechModelRepository {

    private val state = MutableStateFlow<ModelDownloadState>(
        if (paths(SpeechModels.onDeviceDefault) != null) {
            ModelDownloadState.Installed
        } else {
            ModelDownloadState.NotInstalled
        },
    )

    override val downloadState: StateFlow<ModelDownloadState> = state.asStateFlow()

    @Volatile
    private var job: Job? = null

    override fun isInstalled(model: SpeechModel): Boolean = paths(model) != null

    override fun paths(model: SpeechModel): ModelPaths? {
        if (model.files.isEmpty()) return null

        val directory = File(modelStorage.modelsDirectory, model.id)
        val complete = model.files.all { spec ->
            val file = File(directory, spec.fileName)
            file.isFile && file.length() == spec.sizeBytes
        }
        return if (complete) ModelPaths(directory) else null
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

        // A superseded model is dead weight the space check would otherwise count against the
        // new archive: swapping the default model leaves the previous directory behind, and
        // on a phone that is the difference between "download" and "not enough space".
        pruneSupersededModels(model)

        val onDisk = partFile(model).length().coerceAtLeast(0L)
        val remaining = (model.requiredBytes - onDisk).coerceAtLeast(0L)
        if (!ModelStorageReport(remaining, storageProbe.freeBytes()).hasRoom) {
            state.value = ModelDownloadState.Failed(ModelDownloadFailure.NO_SPACE)
            return
        }

        job = scope.launch(ioDispatcher) { install(model) }
    }

    override fun cancelDownload() {
        job?.cancel()
    }

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
        }
    }

    private fun partFile(model: SpeechModel): File =
        File(modelStorage.modelsDirectory, ".${model.id}.archive.part")

    private fun stagingDirectory(model: SpeechModel): File =
        File(modelStorage.modelsDirectory, ".${model.id}.staging")

    /**
     * Deletes model directories the catalogue no longer offers.
     *
     * Nothing else removes them: the id is the directory name, so a swapped default model
     * becomes an orphan the moment the catalogue changes — 42 MB of the 20M model when the
     * 2023-06-26 export replaced it. Only directories are touched (a `.part` file belongs to
     * a download in flight, and dot-directories are staging), and every model the catalogue
     * still lists is kept.
     */
    private fun pruneSupersededModels(current: SpeechModel) {
        val keep = SpeechModels.all.mapTo(HashSet()) { it.id }.apply { add(current.id) }

        modelStorage.modelsDirectory.listFiles()?.forEach { entry ->
            val superseded = entry.isDirectory &&
                !entry.name.startsWith(".") &&
                entry.name !in keep
            if (superseded && entry.deleteRecursively()) {
                Timber.i("Removed superseded on-device model %s", entry.name)
            }
        }
    }
}
