package com.loqui.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelsTest {

    private val mebibyte = 1024L * 1024L

    @Test
    fun `the catalogue is not empty and ids are unique`() {
        val models = SpeechModels.all

        assertTrue(models.isNotEmpty())
        assertEquals(models.size, models.map { it.id }.toSet().size)
    }

    @Test
    fun `the default model is part of the catalogue`() {
        assertTrue(SpeechModels.onDeviceDefault in SpeechModels.all)
    }

    @Test
    fun `the default model records a downloadable archive, checksum and file manifest`() {
        val model = SpeechModels.onDeviceDefault

        assertTrue("model is not downloadable", model.isDownloadable)
        assertTrue(model.downloadUrl!!.startsWith("https://"))
        assertEquals(64, model.checksum!!.length)
        assertTrue(model.checksum!!.all { it in "0123456789abcdef" })
        assertEquals(SpeechModels.ARCHIVE_BYTES, model.approxBytes)
        assertEquals(SpeechModels.ARCHIVE_SHA256, model.checksum)
    }

    @Test
    fun `only the int8 transducer and the token table are installed`() {
        val model = SpeechModels.onDeviceDefault
        val names = model.files.map { it.fileName }

        assertEquals(
            listOf(
                SpeechModels.TOKENS_FILE,
                SpeechModels.ENCODER_FILE,
                SpeechModels.DECODER_FILE,
                SpeechModels.JOINER_FILE,
            ),
            names,
        )
        assertTrue(model.files.none { it.archivePath.contains("fp32") })
        assertTrue(model.file(SpeechModels.ENCODER_FILE)!!.archivePath.endsWith(".int8.onnx"))
        model.files.forEach { file ->
            assertEquals(64, file.sha256.length)
            assertTrue("${file.fileName} has no size", file.sizeBytes > 0L)
        }
        assertEquals(model.files.sumOf { it.sizeBytes }, model.installedBytes)
    }

    @Test
    fun `the transducer is the chunk-16 left-128 int8 export`() {
        val model = SpeechModels.onDeviceDefault

        // All three transducer files have to come from the same export: mixing the encoder
        // of one model with the joiner of another produces a recogniser that runs and emits
        // nonsense, which is far harder to diagnose than a missing file.
        listOf(
            SpeechModels.ENCODER_FILE,
            SpeechModels.DECODER_FILE,
            SpeechModels.JOINER_FILE,
        ).forEach { name ->
            val path = model.file(name)!!.archivePath
            assertTrue(
                "$name comes from $path, not the chunk-16-left-128 export",
                path.endsWith("-chunk-16-left-128.int8.onnx"),
            )
        }
        assertTrue(
            "${model.id} is not the 2023-06-26 release",
            model.id.contains("2023-06-26"),
        )
    }

    @Test
    fun `the default model is the larger export, not the 20M stand-in`() {
        val encoder = SpeechModels.onDeviceDefault.file(SpeechModels.ENCODER_FILE)!!

        // The 20M model's int8 encoder was 42.8 MB. Anything at or below that is a silent
        // downgrade back to the model whose transcripts prompted this swap.
        assertTrue(
            "encoder is ${encoder.sizeBytes} bytes: this is not the larger model",
            encoder.sizeBytes > 60L * mebibyte,
        )
        assertTrue(
            "the archive is too small to contain the 2023-06-26 export",
            SpeechModels.onDeviceDefault.approxBytes > 250L * mebibyte,
        )
    }

    @Test
    fun `the install budget covers the archive plus the unpacked copy`() {
        val model = SpeechModels.onDeviceDefault

        assertTrue(model.installedBytes < model.approxBytes)
        assertEquals(model.approxBytes + model.installedBytes, model.requiredBytes)
    }

    @Test
    fun `every model records a usable license and a plausible size`() {
        SpeechModels.all.forEach { model ->
            assertTrue("blank id", model.id.isNotBlank())
            assertTrue("blank name", model.displayName.isNotBlank())
            assertTrue(
                "${model.id}: ${model.approxBytes} bytes is not a plausible model size",
                model.approxBytes in (10 * mebibyte)..(2L * 1024 * mebibyte),
            )
            assertTrue(
                "${model.id} ships under a license that forbids commercial use",
                model.license.commercialUseAllowed,
            )
        }
    }

    @Test
    fun `a model without a URL or checksum is never offered for download`() {
        val incomplete = SpeechModels.onDeviceDefault.copy(downloadUrl = null)
        val unverified = SpeechModels.onDeviceDefault.copy(checksum = "")

        assertFalse(incomplete.isDownloadable)
        assertFalse(unverified.isDownloadable)
    }
}
