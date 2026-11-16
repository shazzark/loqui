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
