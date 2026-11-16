package com.loqui.android.models

import java.io.File
import java.nio.file.Files
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
    fun `a superseded model directory is removed before the download starts`() = runTest {
        // The 20M model the 2023-06-26 default replaced: its directory keeps its files (and
        // its 42 MB) after the catalogue moved on, and that free space is counted against the
        // new archive.
        val superseded = File(root, "sherpa-onnx-streaming-zipformer-en-20m").apply { mkdirs() }
        File(superseded, "encoder.onnx").writeBytes(ByteArray(4))
        val kept = File(root, SpeechModels.onDeviceDefault.id).apply { mkdirs() }
        val downloader = FakeDownloader()

        repository(downloader).download(model)

        assertFalse("the replaced model must not survive the swap", superseded.exists())
        assertTrue("a model the catalogue still lists must be kept", kept.exists())
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

    private fun TestScope.repository(
        downloader: ModelDownloader,
        freeBytes: Long = Long.MAX_VALUE,
    ): FileSpeechModelRepository = FileSpeechModelRepository(
        modelStorage = object : ModelStorage {
            override val modelsDirectory: File = root
        },
        downloader = downloader,
        storageProbe = object : ModelStorageProbe {
            override fun freeBytes(): Long = freeBytes
        },
        ioDispatcher = UnconfinedTestDispatcher(testScheduler),
        scope = this,
    )

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

