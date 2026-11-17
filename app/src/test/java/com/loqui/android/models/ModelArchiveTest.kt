package com.loqui.android.models

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The unpack path is the half that decides whether a phone ever gets a recogniser, so it is
 * exercised against a real bzip2 + tar stream rather than a fake downloader.
 */
class ModelArchiveTest {

    private lateinit var root: File
    private lateinit var destination: File

    private val tokens = "a\nb\nc\n".toByteArray()
    private val encoder = ByteArray(12_345) { (it % 251).toByte() }
    private val decoder = ByteArray(539) { (it * 7 % 251).toByte() }
    private val joiner = ByteArray(259) { (it * 13 % 251).toByte() }

    @Before
    fun setUp() {
        root = Files.createTempDirectory("loqui-archive").toFile()
        destination = File(root, "staging")
    }

    @After
    fun tearDown() {
        root.deleteRecursively()
    }

    @Test
    fun `a real archive unpacks all four files at their exact sizes`() = runBlocking {
        val model = model(catalogue())

        extractModelArchive(archive(*entries()), model, destination)

        assertEquals(
            model.files.map { it.fileName }.sorted(),
            destination.listFiles()!!.map { it.name }.sorted(),
        )
        model.files.forEach { spec ->
            val onDisk = File(destination, spec.fileName)
            assertEquals("${spec.fileName} size", spec.sizeBytes, onDisk.length())
            assertEquals("${spec.fileName} hash", spec.sha256, sha256Hex(onDisk))
        }
        assertArrayEquals(encoder, File(destination, SpeechModels.ENCODER_FILE).readBytes())
        assertArrayEquals(tokens, File(destination, SpeechModels.TOKENS_FILE).readBytes())
    }

    @Test
    fun `fp32 models and test wavs in the same archive are not installed`() = runBlocking {
        extractModelArchive(archive(*entries()), model(catalogue()), destination)

        val installed = destination.listFiles()!!.map { it.name }
        assertEquals(4, installed.size)
        assertFalse(installed.any { it.endsWith(".wav") })
        assertFalse(File(destination, "encoder-epoch-99-avg-1.onnx").exists())
    }

    @Test
    fun `a short entry fails the install instead of being left on disk`() = runBlocking {
        // The catalogue remembers the full token table; the archive carries a truncated copy.
        val tokenSpec = spec(
            "zipformer/tokens.txt",
            SpeechModels.TOKENS_FILE,
            tokens + ByteArray(10),
        )
        val failure = assertThrows(ModelDownloadException::class.java) {
            runBlocking {
                extractModelArchive(archive(*entries()), model(listOf(tokenSpec)), destination)
            }
        }

        assertEquals(ModelDownloadFailure.STORAGE, failure.failure)
        assertTrue(failure.message!!.contains("expected ${tokens.size + 10}"))
        assertFalse(File(destination, SpeechModels.TOKENS_FILE).exists())
    }

    @Test
    fun `a checksum mismatch fails the install and deletes the file`() = runBlocking {
        val expected = catalogue().first { it.fileName == SpeechModels.DECODER_FILE }
        val spec = expected.copy(sha256 = "00".repeat(32))
        val failure = assertThrows(ModelDownloadException::class.java) {
            runBlocking { extractModelArchive(archive(*entries()), model(listOf(spec)), destination) }
        }

        assertEquals(ModelDownloadFailure.CHECKSUM_MISMATCH, failure.failure)
        assertFalse(File(destination, SpeechModels.DECODER_FILE).exists())
    }

    @Test
    fun `an archive without every catalogue file is rejected`() = runBlocking {
        val incomplete = archive(
            "zipformer/" to ByteArray(0),
            "zipformer/tokens.txt" to tokens,
            "zipformer/encoder-epoch-99-avg-1.int8.onnx" to encoder,
        )

        val failure = assertThrows(ModelDownloadException::class.java) {
            runBlocking { extractModelArchive(incomplete, model(catalogue()), destination) }
        }

        assertEquals(ModelDownloadFailure.STORAGE, failure.failure)
        assertTrue(failure.message!!.contains("missing 2 file(s)"))
    }

    @Test
    fun `a file wrapped in an extra directory is still found`() = runBlocking {
        val moved = archive(
            "release-2023-02-17/tokens.txt" to tokens,
            "release-2023-02-17/encoder-epoch-99-avg-1.int8.onnx" to encoder,
            "release-2023-02-17/decoder-epoch-99-avg-1.int8.onnx" to decoder,
            "release-2023-02-17/joiner-epoch-99-avg-1.int8.onnx" to joiner,
        )

        extractModelArchive(moved, model(catalogue()), destination)

        assertArrayEquals(decoder, File(destination, SpeechModels.DECODER_FILE).readBytes())
        assertArrayEquals(joiner, File(destination, SpeechModels.JOINER_FILE).readBytes())
    }

    /** The four int8 files plus the fp32 variants and wavs the real archive also carries. */
    private fun entries(): Array<Pair<String, ByteArray>> = arrayOf(
        "zipformer/" to ByteArray(0),
        "zipformer/tokens.txt" to tokens,
        "zipformer/encoder-epoch-99-avg-1.int8.onnx" to encoder,
        "zipformer/decoder-epoch-99-avg-1.int8.onnx" to decoder,
        "zipformer/joiner-epoch-99-avg-1.int8.onnx" to joiner,
        "zipformer/encoder-epoch-99-avg-1.onnx" to ByteArray(777),
        "zipformer/test_wavs/0.wav" to ByteArray(64),
    )

    private fun catalogue(): List<ModelFile> = listOf(
        spec("zipformer/tokens.txt", SpeechModels.TOKENS_FILE, tokens),
        spec("zipformer/encoder-epoch-99-avg-1.int8.onnx", SpeechModels.ENCODER_FILE, encoder),
        spec("zipformer/decoder-epoch-99-avg-1.int8.onnx", SpeechModels.DECODER_FILE, decoder),
        spec("zipformer/joiner-epoch-99-avg-1.int8.onnx", SpeechModels.JOINER_FILE, joiner),
    )

    private fun spec(archivePath: String, fileName: String, bytes: ByteArray) = ModelFile(
        archivePath = archivePath,
        fileName = fileName,
        sha256 = sha256Hex(bytes),
        sizeBytes = bytes.size.toLong(),
    )

    private fun model(files: List<ModelFile>) = SpeechModel(
        id = "test-zipformer",
        displayName = "Test Zipformer",
        modelType = SpeechModels.ZIPFORMER2,
        approxBytes = 1_000,
        license = ModelLicense.APACHE_2_0,
        files = files,
    )

    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val file = File(root, "model.tar.bz2")
        TarArchiveOutputStream(
            BZip2CompressorOutputStream(FileOutputStream(file).buffered()),
        ).use { tar ->
            for ((name, bytes) in entries) {
                val entry = TarArchiveEntry(name)
                if (name.endsWith("/")) {
                    tar.putArchiveEntry(entry)
                    tar.closeArchiveEntry()
                    continue
                }
                entry.size = bytes.size.toLong()
                tar.putArchiveEntry(entry)
                tar.write(bytes)
                tar.closeArchiveEntry()
            }
        }
        return file
    }
}
