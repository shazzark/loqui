package com.loqui.android.models

/**
 * What a model download is allowed to do with the app. Recorded per model so the
 * `03-architecture.md` rule — "verify the license before shipping a model reference" —
 * is enforced by the catalogue rather than by reviewer memory.
 */
enum class ModelLicense(
    val displayName: String,
    val commercialUseAllowed: Boolean,
    val attributionRequired: Boolean,
) {
    APACHE_2_0("Apache 2.0", commercialUseAllowed = true, attributionRequired = true),
    MIT("MIT", commercialUseAllowed = true, attributionRequired = true),
}

/**
 * One file inside a model archive, and the name it gets on disk.
 *
 * [archivePath] is the exact tar entry name, not a suffix match: the archive also
 * carries fp32 models and test wavs, and none of those may be unpacked on a phone.
 */
data class ModelFile(
    val archivePath: String,
    val fileName: String,
    /** Lowercase hex SHA-256 of the unpacked file. */
    val sha256: String,
    val sizeBytes: Long,
)

/**
 * One downloadable on-device model. Nothing is bundled in the APK: the URL, archive
 * checksum and per-file checksums below are what the downloader verifies before a file
 * is used.
 */
data class SpeechModel(
    val id: String,
    val displayName: String,
    /** Archive download size, shown to the user and used for the progress bar. */
    val approxBytes: Long,
    val license: ModelLicense,
    /** Lowercase hex SHA-256 of the archive; null until the download manifest exists. */
    val checksum: String? = null,
    val downloadUrl: String? = null,
    val files: List<ModelFile> = emptyList(),
    /** Bytes left on the device after installation (the int8 subset only). */
    val installedBytes: Long = files.sumOf { it.sizeBytes },
) {
    /** Download plus unpacked copy: the space the install needs before it starts. */
    val requiredBytes: Long
        get() = approxBytes + installedBytes

    val isDownloadable: Boolean
        get() = !downloadUrl.isNullOrBlank() && !checksum.isNullOrBlank() && files.isNotEmpty()

    fun file(fileName: String): ModelFile? = files.firstOrNull { it.fileName == fileName }
}

/**
 * The on-device speech catalogue.
 *
 * The default entry is the English streaming Zipformer exported for sherpa-onnx on
 * 2023-06-26 (`chunk-16-left-128`). It replaces the 20M model of 2023-02-17 that Phase 6
 * shipped: 18 encoder layers instead of 10 and 128 frames of left context instead of 64,
 * which is the accuracy step the transcripts needed. Its encoder reports
 * `model_type: zipformer2`, so [com.loqui.android.asr.SherpaOnnxStreamingEngine] must build
 * the recogniser with that model type; the old export reports plain `zipformer`.
 *
 * The archive is ~296 MiB and contains fp32 and int8 variants, a second `left-64` export
 * and test wavs; only the int8 `left-128` transducer and the token table are installed,
 * which is ~68 MiB on disk.
 *
 * Checksums were measured from the published archive at
 * `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/…-2023-06-26.tar.bz2`
 * (SHA-256 `639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282`). The token
 * table is byte-identical to the 20M model's — both are the same librispeech BPE vocabulary
 * — which is why its hash did not change with the swap.
 */
object SpeechModels {

    const val ARCHIVE_SHA256 =
        "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282"
    const val ARCHIVE_BYTES = 310_414_022L

    private const val ARCHIVE_DIR = "sherpa-onnx-streaming-zipformer-en-2023-06-26"

    /**
     * Directory the verified files land in. Also the model's id in settings, logs and the
     * Home card; [ARCHIVE_DIR] happens to match, and an install of the old 20M model is
     * simply a different directory that [FileSpeechModelRepository.paths] will not accept.
     */
    private const val MODEL_ID = ARCHIVE_DIR

    const val TOKENS_FILE = "tokens.txt"
    const val ENCODER_FILE = "encoder.onnx"
    const val DECODER_FILE = "decoder.onnx"
    const val JOINER_FILE = "joiner.onnx"

    val onDeviceDefault = SpeechModel(
        id = MODEL_ID,
        displayName = "English (streaming Zipformer, high accuracy)",
        approxBytes = ARCHIVE_BYTES,
        license = ModelLicense.APACHE_2_0,
        checksum = ARCHIVE_SHA256,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "$ARCHIVE_DIR.tar.bz2",
        files = listOf(
            ModelFile(
                archivePath = "$ARCHIVE_DIR/$TOKENS_FILE",
                fileName = TOKENS_FILE,
                sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                sizeBytes = 5_048L,
            ),
            ModelFile(
                archivePath = "$ARCHIVE_DIR/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                fileName = ENCODER_FILE,
                sha256 = "5022b2eca5b19d1bc104fcf33e26bc32604b7df553cd2e1f62e31dc7b05e9c87",
                sizeBytes = 70_108_816L,
            ),
            ModelFile(
                archivePath = "$ARCHIVE_DIR/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                fileName = DECODER_FILE,
                sha256 = "780c63ee94c7cfa314211172e5d09b406c0da2beab5c40ea2f54cc95670b76a5",
                sizeBytes = 540_688L,
            ),
            ModelFile(
                archivePath = "$ARCHIVE_DIR/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                fileName = JOINER_FILE,
                sha256 = "abd5e30f3f16fc510605c6029dba33f10e4386bd75c5bdc30cf94076864db10d",
                sizeBytes = 259_416L,
            ),
        ),
    )

    val all: List<SpeechModel> = listOf(onDeviceDefault)
}
