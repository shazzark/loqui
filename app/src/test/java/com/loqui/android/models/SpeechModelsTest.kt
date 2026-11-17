package com.loqui.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelsTest {

    private val mebibyte = 1024L * 1024L

    private val models = listOf(SpeechModels.onDeviceFast, SpeechModels.onDeviceAccurate)

    @Test
    fun `the catalogue is not empty and ids are unique`() {
        val all = SpeechModels.all

        assertTrue(all.isNotEmpty())
        assertEquals(all.size, all.map { it.id }.toSet().size)
        assertEquals(models, all)
    }

    @Test
    fun `both models record a downloadable archive, checksum and file manifest`() {
        models.forEach { model ->
            assertTrue("${model.id} is not downloadable", model.isDownloadable)
            assertTrue("${model.id} has no https source", model.downloadUrl!!.startsWith("https://"))
            assertEquals("${model.id} checksum is not a SHA-256", 64, model.checksum!!.length)
            assertTrue(model.checksum!!.all { it in "0123456789abcdef" })
        }
        assertEquals(SpeechModels.FAST_ARCHIVE_BYTES, SpeechModels.onDeviceFast.approxBytes)
        assertEquals(SpeechModels.FAST_ARCHIVE_SHA256, SpeechModels.onDeviceFast.checksum)
        assertEquals(SpeechModels.ACCURATE_ARCHIVE_BYTES, SpeechModels.onDeviceAccurate.approxBytes)
        assertEquals(SpeechModels.ACCURATE_ARCHIVE_SHA256, SpeechModels.onDeviceAccurate.checksum)
    }

    @Test
    fun `only the int8 transducer and the token table are installed`() {
        models.forEach { model ->
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
            assertTrue(
                "${model.id} unpacks an fp32 model",
                model.files.none { it.archivePath.contains("fp32") },
            )
            assertTrue(model.file(SpeechModels.ENCODER_FILE)!!.archivePath.endsWith(".int8.onnx"))
            model.files.forEach { file ->
                assertEquals(64, file.sha256.length)
                assertTrue("${file.fileName} has no size", file.sizeBytes > 0L)
            }
            assertEquals(model.files.sumOf { it.sizeBytes }, model.installedBytes)
        }
    }

    @Test
    fun `each model declares the model type its encoder reports`() {
        // sherpa-onnx builds a different transducer class per `model_type`; asking for the
        // wrong one throws instead of recognising, so the catalogue has to carry it.
        assertEquals(SpeechModels.ZIPFORMER, SpeechModels.onDeviceFast.modelType)
        assertEquals(SpeechModels.ZIPFORMER2, SpeechModels.onDeviceAccurate.modelType)
        models.forEach { model ->
            assertTrue("${model.id} has no model type", model.modelType.isNotBlank())
        }
    }

    @Test
    fun `the accurate model is the chunk-16 left-128 zipformer2 export`() {
        val model = SpeechModels.onDeviceAccurate

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
    fun `the fast model is the 20M export with the legacy model type`() {
        val model = SpeechModels.onDeviceFast

        listOf(
            SpeechModels.ENCODER_FILE,
            SpeechModels.DECODER_FILE,
            SpeechModels.JOINER_FILE,
        ).forEach { name ->
            val path = model.file(name)!!.archivePath
            assertTrue("$name is $path, not a 20M export", path.endsWith("-epoch-99-avg-1.int8.onnx"))
            assertFalse(
                "$name comes from the chunked export, so this is not the 20M model",
                path.contains("chunk-16-left-128"),
            )
        }
        // The id Phase 6 installed on disk: keeping it means a phone that already holds this
        // model does not download it again when the router sends it back to the fast one.
        assertEquals("sherpa-onnx-streaming-zipformer-en-20m", model.id)
    }

    @Test
    fun `the accurate model is the larger export, not the 20M stand-in`() {
        val encoder = SpeechModels.onDeviceAccurate.file(SpeechModels.ENCODER_FILE)!!
        val fastEncoder = SpeechModels.onDeviceFast.file(SpeechModels.ENCODER_FILE)!!

        // The 20M model's int8 encoder was 42.8 MB. Anything at or below that is a silent
        // downgrade back to the model whose transcripts prompted the swap to the larger one.
        assertTrue(
            "encoder is ${encoder.sizeBytes} bytes: this is not the larger model",
            encoder.sizeBytes > 60L * mebibyte,
        )
        assertTrue(
            "the accurate archive is too small to contain the 2023-06-26 export",
            SpeechModels.onDeviceAccurate.approxBytes > 250L * mebibyte,
        )
        // The whole point of two entries: the fallback really is cheaper to run and store.
        assertTrue(
            "the fast model's encoder is not smaller than the accurate one",
            fastEncoder.sizeBytes < encoder.sizeBytes,
        )
        assertTrue(
            SpeechModels.onDeviceFast.installedBytes < SpeechModels.onDeviceAccurate.installedBytes,
        )
        assertTrue(SpeechModels.onDeviceFast.approxBytes < SpeechModels.onDeviceAccurate.approxBytes)
    }

    @Test
    fun `the install budget covers the archive plus the unpacked copy`() {
        models.forEach { model ->
            assertTrue(
                "${model.id} install is not smaller than its archive",
                model.installedBytes < model.approxBytes,
            )
            assertEquals(model.approxBytes + model.installedBytes, model.requiredBytes)
        }
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
        val incomplete = SpeechModels.onDeviceAccurate.copy(downloadUrl = null)
        val unverified = SpeechModels.onDeviceAccurate.copy(checksum = "")

        assertFalse(incomplete.isDownloadable)
        assertFalse(unverified.isDownloadable)
    }
}
