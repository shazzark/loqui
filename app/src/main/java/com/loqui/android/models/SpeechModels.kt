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
 * The default entry is the English streaming Zipformer (20M parameters) from the
 * official sherpa-onnx `asr-models` release. The archive is ~122 MiB and contains fp32
 * and int8 variants plus test wavs; only the int8 transducer and the token table are
 * installed, which is ~42 MiB on disk.
 *
 * Checksums were measured from the published archive at
 * `https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/…-2023-02-17.tar.bz2`
 * (SHA-256 `9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9`).
 */
object SpeechModels {

    const val ARCHIVE_SHA256 =
        "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9"
    const val ARCHIVE_BYTES = 127_887_156L

    private const val ARCHIVE_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"
    private const val MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20m"

    const val TOKENS_FILE = "tokens.txt"
    const val ENCODER_FILE = "encoder.onnx"
    const val DECODER_FILE = "decoder.onnx"
    const val JOINER_FILE = "joiner.onnx"

    val onDeviceDefault = SpeechModel(
        id = MODEL_ID,
        displayName = "English (streaming Zipformer, 20M)",
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
                archivePath = "$ARCHIVE_DIR/encoder-epoch-99-avg-1.int8.onnx",
                fileName = ENCODER_FILE,
                sha256 = "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3",
                sizeBytes = 42_845_182L,
            ),
            ModelFile(
                archivePath = "$ARCHIVE_DIR/decoder-epoch-99-avg-1.int8.onnx",
                fileName = DECODER_FILE,
                sha256 = "21e2a2acd961b3ac72f55be2f10f1a285e1b0b0ba010d7c0b6eab141411b163c",
                sizeBytes = 539_499L,
            ),
            ModelFile(
                archivePath = "$ARCHIVE_DIR/joiner-epoch-99-avg-1.int8.onnx",
                fileName = JOINER_FILE,
                sha256 = "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e",
                sizeBytes = 259_572L,
            ),
        ),
    )

    val all: List<SpeechModel> = listOf(onDeviceDefault)
}
