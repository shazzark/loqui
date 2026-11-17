package com.loqui.android.models

import com.loqui.android.settings.LoquiSettings
import com.loqui.android.settings.ModelQuality
import com.loqui.android.testing.FakeDeviceCapabilities
import com.loqui.android.testing.FakeSettingsRepository
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SpeechModelRepositoryTest {

    private lateinit var root: File

    private val model = SpeechModel(
        id = "test-model",
        displayName = "Test model",
        modelType = SpeechModels.ZIPFORMER2,
        approxBytes = 8,
        license = ModelLicense.MIT,
        checksum = "abc",
        downloadUrl = "https://example.invalid/model.tar.bz2",
        files = listOf(
            ModelFile(
                archivePath = "test-model/a.onnx",
                fileName = "a.onnx",
                sha256 = "def",
                sizeBytes = 4,
            ),
        ),
    )

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loqui-models").toFile()
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a completed install is verified, unpacked and moved into place`() = runTest {
        val downloader = FakeDownloader()
        val repository = repository(downloader)

        repository.download(model)
        advanceUntilIdle()

        assertEquals(ModelDownloadState.Installed, repository.downloadState.value)
        val paths = repository.paths(model)
        assertNotNull(paths)
        assertEquals(File(root, "test-model"), paths!!.directory)
        assertEquals(4L, File(root, "test-model/a.onnx").length())
        assertFalse(File(root, ".test-model.archive.part").exists())
        assertFalse(File(root, ".test-model.staging").exists())
        assertEquals(1, downloader.verifyCalls)
        assertEquals(1, downloader.extractCalls)
        assertEquals(model, downloader.extracted)
        assertEquals(8L, downloader.lastProgress)
    }

    @Test
    fun `a partial archive is resumed rather than restarted`() = runTest {
        val part = File(root, ".test-model.archive.part").apply { writeBytes(ByteArray(4)) }
        val downloader = FakeDownloader()
        val repository = repository(downloader)

        repository.download(model)
        advanceUntilIdle()

        assertEquals(ModelDownloadState.Installed, repository.downloadState.value)
        assertEquals(4L, downloader.startingLength)
        assertFalse(part.exists())
    }

    @Test
    fun `an incomplete model directory is not treated as installed`() = runTest {
        File(root, "test-model").mkdirs()
        File(root, "test-model/a.onnx").writeBytes(ByteArray(3))
        val repository = repository(FakeDownloader())

        assertNull(repository.paths(model))
        assertFalse(repository.isInstalled(model))
        assertEquals(ModelDownloadState.NotInstalled, repository.downloadState.value)
    }

    @Test
    fun `an installed model is not downloaded again`() = runTest {
        File(root, "test-model").mkdirs()
        File(root, "test-model/a.onnx").writeBytes(ByteArray(4))
        val downloader = FakeDownloader()
        val repository = repository(downloader)

        repository.download(model)
        advanceUntilIdle()

        assertEquals(ModelDownloadState.Installed, repository.downloadState.value)
        assertEquals(0, downloader.downloadCalls)
    }

    @Test
    fun `a model directory the router does not run is removed before the install`() = runTest {
        // The router runs exactly one model. A leftover directory — the previous quality's
        // model, or one that has left the catalogue — is space the new archive needs, so the
        // install reclaims it before the free-space check counts anything.
        val superseded = File(root, "sherpa-onnx-streaming-zipformer-en-20m-fp32").apply { mkdirs() }
        File(superseded, "encoder.onnx").writeBytes(ByteArray(4))
        val previous = File(root, SpeechModels.onDeviceFast.id).apply { mkdirs() }
        val downloader = FakeDownloader()

        repository(downloader).download(model)
        advanceUntilIdle()

        assertFalse("the replaced model must not survive the swap", superseded.exists())
        assertFalse("the previous quality's model must not compete for space", previous.exists())
    }

    @Test
    fun `a model with no download source fails without touching the network`() = runTest {
        val downloader = FakeDownloader()
        val repository = repository(downloader)

        repository.download(model.copy(downloadUrl = null))
        advanceUntilIdle()

        assertEquals(
            ModelDownloadState.Failed(ModelDownloadFailure.UNSUPPORTED),
            repository.downloadState.value,
        )
        assertEquals(0, downloader.downloadCalls)
    }

    @Test
    fun `not enough free space fails before the first byte`() = runTest {
        val downloader = FakeDownloader()
        val repository = repository(downloader, freeBytes = 0L)

        repository.download(model)
        advanceUntilIdle()

        assertEquals(
            ModelDownloadState.Failed(ModelDownloadFailure.NO_SPACE),
            repository.downloadState.value,
        )
        assertEquals(0, downloader.downloadCalls)
    }
    @Test
    fun `a checksum mismatch deletes the archive and never unpacks it`() = runTest {
        val downloader = FakeDownloader(verifyFailure = ModelDownloadFailure.CHECKSUM_MISMATCH)
        val repository = repository(downloader)

        repository.download(model)
        advanceUntilIdle()

        assertEquals(
            ModelDownloadState.Failed(ModelDownloadFailure.CHECKSUM_MISMATCH),
            repository.downloadState.value,
        )
        assertFalse(File(root, ".test-model.archive.part").exists())
        assertEquals(0, downloader.extractCalls)
        assertNull(repository.paths(model))
    }

    @Test
    fun `a network failure keeps the partial archive so the next tap resumes`() = runTest {
        val downloader = FakeDownloader(downloadFailure = ModelDownloadFailure.NETWORK)
        val repository = repository(downloader)

        repository.download(model)
        advanceUntilIdle()

        assertEquals(
            ModelDownloadState.Failed(ModelDownloadFailure.NETWORK),
            repository.downloadState.value,
        )
        assertTrue(File(root, ".test-model.archive.part").exists())
        assertFalse(File(root, ".test-model.staging").exists())
    }

    @Test
    fun `cancelling removes the partial archive and returns to not installed`() = runTest {
        val downloader = FakeDownloader(blockForever = true)
        val repository = repository(downloader)

        repository.download(model)
        assertEquals(1, downloader.downloadCalls)
        repository.cancelDownload()
        advanceUntilIdle()

        assertEquals(ModelDownloadState.NotInstalled, repository.downloadState.value)
        assertFalse(File(root, ".test-model.archive.part").exists())
    }

    @Test
    fun `a progress report never exceeds the archive size`() = runTest {
        val downloader = FakeDownloader(progressOverrun = true)
        val repository = repository(downloader)

        repository.download(model)
        advanceUntilIdle()

        assertEquals(ModelDownloadState.Installed, repository.downloadState.value)
    }

    @Test
    fun `the active model follows the device's RAM while the choice is automatic`() = runTest {
        val small = repository(FakeDownloader(), totalRamBytes = FakeDeviceCapabilities.FOUR_GIGABYTES)
        val large = repository(FakeDownloader(), totalRamBytes = FakeDeviceCapabilities.EIGHT_GIGABYTES)

        assertEquals(SpeechModels.onDeviceFast, small.activeModel.value)
        assertEquals(SpeechModels.onDeviceAccurate, large.activeModel.value)
    }

    @Test
    fun `an explicit quality overrides the device's RAM`() = runTest {
        val repository = repository(
            FakeDownloader(),
            settings = FakeSettingsRepository(LoquiSettings(modelQuality = ModelQuality.ACCURATE)),
            totalRamBytes = FakeDeviceCapabilities.FOUR_GIGABYTES,
        )

        assertEquals(SpeechModels.onDeviceAccurate, repository.activeModel.value)
    }

    @Test
    fun `switching quality re-routes the model and frees the old install`() = runTest {
        // Both models on disk: the switch must make the new one active and reclaim the old
        // one's 43 MiB rather than leaving two models installed forever.
        installOnDisk(SpeechModels.onDeviceFast)
        installOnDisk(SpeechModels.onDeviceAccurate)
        val settings = FakeSettingsRepository(LoquiSettings(modelQuality = ModelQuality.FAST))
        val repository = repository(FakeDownloader(), settings = settings)
        assertEquals(SpeechModels.onDeviceFast, repository.activeModel.value)
        assertEquals(ModelDownloadState.Installed, repository.downloadState.value)

        settings.setModelQuality(ModelQuality.ACCURATE)
        advanceUntilIdle()

        assertEquals(SpeechModels.onDeviceAccurate, repository.activeModel.value)
        assertEquals(ModelDownloadState.Installed, repository.downloadState.value)
        assertNotNull(repository.activePaths())
        assertFalse(
            "the fast model's directory must be reclaimed",
            File(root, SpeechModels.onDeviceFast.id).exists(),
        )
        assertTrue(File(root, SpeechModels.onDeviceAccurate.id).isDirectory)
    }

    @Test
    fun `a quality switch keeps the old install until the new model is on disk`() = runTest {
        // Deleting the only usable model on the way to a download would leave the phone
        // with nothing to recognise with — and no way to dictate while it downloads.
        installOnDisk(SpeechModels.onDeviceFast)
        val settings = FakeSettingsRepository(LoquiSettings(modelQuality = ModelQuality.FAST))
        val repository = repository(FakeDownloader(), settings = settings)

        settings.setModelQuality(ModelQuality.ACCURATE)
        advanceUntilIdle()

        assertEquals(SpeechModels.onDeviceAccurate, repository.activeModel.value)
        assertEquals(ModelDownloadState.NotInstalled, repository.downloadState.value)
        assertTrue(
            "the old install is still the fallback until the new one lands",
            File(root, SpeechModels.onDeviceFast.id).isDirectory,
        )
    }

    @Test
    fun `an install that does not match the active model is cancelled by a switch`() = runTest {
        val downloader = FakeDownloader(blockForever = true)
        val settings = FakeSettingsRepository(LoquiSettings(modelQuality = ModelQuality.FAST))
        val repository = repository(
            downloader,
            settings = settings,
            totalRamBytes = FakeDeviceCapabilities.EIGHT_GIGABYTES,
        )

        // The explicit setting wins over the 8 GB device, so the fast model is active and
        // its install is what the switch has to interrupt.
        assertEquals(SpeechModels.onDeviceFast, repository.activeModel.value)
        repository.downloadActiveModel()
        assertEquals(1, downloader.downloadCalls)

        settings.setModelQuality(ModelQuality.ACCURATE)
        advanceUntilIdle()

        assertEquals(SpeechModels.onDeviceAccurate, repository.activeModel.value)
        assertEquals(ModelDownloadState.NotInstalled, repository.downloadState.value)
        assertFalse(File(root, ".${SpeechModels.onDeviceFast.id}.archive.part").exists())
    }

    private fun TestScope.repository(
        downloader: ModelDownloader,
        freeBytes: Long = Long.MAX_VALUE,
        settings: FakeSettingsRepository = FakeSettingsRepository(),
        totalRamBytes: Long = FakeDeviceCapabilities.FOUR_GIGABYTES,
    ): FileSpeechModelRepository = FileSpeechModelRepository(
        modelStorage = object : ModelStorage {
            override val modelsDirectory: File = root
        },
        downloader = downloader,
        storageProbe = object : ModelStorageProbe {
            override fun freeBytes(): Long = freeBytes
        },
        settingsRepository = settings,
        capabilities = FakeDeviceCapabilities(totalRamBytes),
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        // The settings collector runs for the life of the process, so it cannot own a job
        // `runTest` waits for. It also has to run eagerly: an unconfined dispatcher applies
        // the saved setting the moment the repository is built, which is what the real
        // collector does on the application scope within milliseconds of process start.
        scope = CoroutineScope(
            backgroundScope.coroutineContext + UnconfinedTestDispatcher(testScheduler),
        ),
    )

    /**
     * Puts [model] on disk exactly as a completed install leaves it.
     *
     * Sparse files (`setLength`) instead of written buffers: the accurate model's encoder is
     * 70 MB, and the repository only ever asks for a file's length.
     */
    private fun installOnDisk(model: SpeechModel) {
        val directory = File(root, model.id).apply { mkdirs() }
        model.files.forEach { spec ->
            RandomAccessFile(File(directory, spec.fileName), "rw").use { it.setLength(spec.sizeBytes) }
        }
    }

    private class FakeDownloader(
        private val verifyFailure: ModelDownloadFailure? = null,
        private val downloadFailure: ModelDownloadFailure? = null,
        private val blockForever: Boolean = false,
        private val progressOverrun: Boolean = false,
    ) : ModelDownloader {

        var downloadCalls = 0
        var verifyCalls = 0
        var extractCalls = 0
        var extracted: SpeechModel? = null
        var startingLength = -1L
        var lastProgress = -1L

        override suspend fun download(
            url: String,
            destination: File,
            expectedBytes: Long,
            onProgress: (bytesReceived: Long) -> Unit,
        ) {
            downloadCalls++
            startingLength = destination.length()
            onProgress(destination.length())

            if (downloadFailure != null) {
                destination.writeBytes(ByteArray((expectedBytes / 2).toInt()))
                throw ModelDownloadException(downloadFailure, "simulated $downloadFailure")
            }
            if (blockForever) awaitCancellation()

            destination.writeBytes(ByteArray(expectedBytes.toInt()))
            lastProgress = if (progressOverrun) expectedBytes * 2 else expectedBytes
            onProgress(lastProgress)
        }

        override suspend fun verify(file: File, sha256: String) {
            verifyCalls++
            if (verifyFailure != null) {
                throw ModelDownloadException(verifyFailure, "simulated $verifyFailure")
            }
        }

        override suspend fun extract(archive: File, model: SpeechModel, destination: File) {
            extractCalls++
            extracted = model
            model.files.forEach { spec ->
                val output = File(destination, spec.fileName)
                output.parentFile?.mkdirs()
                output.writeBytes(ByteArray(spec.sizeBytes.toInt()))
            }
        }
    }
}

