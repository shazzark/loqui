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
    /**
     * The `model_type` the encoder declares in its ONNX metadata. Not cosmetic: asking
     * sherpa-onnx for `zipformer` when the graph is a `zipformer2` (or the reverse) builds
     * the wrong transducer class against the outputs and throws instead of recognising.
     */
    val modelType: String,
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
 * The on-device speech catalogue: two English streaming Zipformers, both int8, both taken
 * from the official sherpa-onnx `asr-models` releases.
 *
 * Two entries exist because one model cannot serve every phone. [onDeviceAccurate] is the
 * 2023-06-26 export — 18 encoder layers, `chunk-16-left-128`, ~68 MiB on disk — and it is
 * the accuracy step the transcripts needed. Its decoder also costs roughly 1.6x the compute
 * of the 20M export, which on a 4 GB phone is heard as lag: partials arrive behind the
 * speaker and the backlog never clears. [onDeviceFast] is the 20M export of 2023-02-17,
 * ~43 MiB, and keeps up on that hardware. [ModelRouting] chooses from the device's RAM and
 * the user can override the choice in settings.
 *
 * Each archive carries fp32 and int8 variants plus test wavs; only the int8 transducer and
 * the token table are installed. The token table is byte-identical in both archives (the
 * same librispeech BPE vocabulary), which is why its hash and size repeat below.
 */
object SpeechModels {

    /** `model_type` of the 2023-06-26 export: `zipformer2`, chunk-16 with 128 left frames. */
    const val ZIPFORMER2 = "zipformer2"

    /** `model_type` of the 2023-02-17 20M export: the legacy streaming Zipformer. */
    const val ZIPFORMER = "zipformer"

    /** SHA-256 of `sherpa-onnx-streaming-zipformer-en-2023-06-26.tar.bz2`. */
    const val ACCURATE_ARCHIVE_SHA256 =
        "639e25b578e9e997131402199419c13a941f8e4e198e2da1ce57dbf5cf401282"
    const val ACCURATE_ARCHIVE_BYTES = 310_414_022L
    private const val ACCURATE_ARCHIVE_DIR = "sherpa-onnx-streaming-zipformer-en-2023-06-26"

    /** SHA-256 of `sherpa-onnx-streaming-zipformer-en-20M-2023-02-17.tar.bz2`. */
    const val FAST_ARCHIVE_SHA256 =
        "9c559283e8498d3fe95913c79ca1cb454bb26281ac2b102b41306c7d752765d9"
    const val FAST_ARCHIVE_BYTES = 127_887_156L
    private const val FAST_ARCHIVE_DIR = "sherpa-onnx-streaming-zipformer-en-20M-2023-02-17"

    /**
     * Directory the verified files land in, and the model's id in settings, logs and the
     * Home card. The accurate id is its archive directory; the fast id is the one Phase 6
     * already used, so a phone that still holds that install does not download 122 MiB
     * again when the router sends it back to the fast model.
     */
    private const val ACCURATE_MODEL_ID = ACCURATE_ARCHIVE_DIR
    private const val FAST_MODEL_ID = "sherpa-onnx-streaming-zipformer-en-20m"

    const val TOKENS_FILE = "tokens.txt"
    const val ENCODER_FILE = "encoder.onnx"
    const val DECODER_FILE = "decoder.onnx"
    const val JOINER_FILE = "joiner.onnx"

    /** Installed on devices the router keeps on the smaller model. */
    val onDeviceFast = SpeechModel(
        id = FAST_MODEL_ID,
        displayName = "English (streaming Zipformer, 20M)",
        modelType = ZIPFORMER,
        approxBytes = FAST_ARCHIVE_BYTES,
        license = ModelLicense.APACHE_2_0,
        checksum = FAST_ARCHIVE_SHA256,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "$FAST_ARCHIVE_DIR.tar.bz2",
        files = listOf(
            ModelFile(
                archivePath = "$FAST_ARCHIVE_DIR/$TOKENS_FILE",
                fileName = TOKENS_FILE,
                sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                sizeBytes = 5_048L,
            ),
            ModelFile(
                archivePath = "$FAST_ARCHIVE_DIR/encoder-epoch-99-avg-1.int8.onnx",
                fileName = ENCODER_FILE,
                sha256 = "3810755ce7c3ab26b42a8bcf39d191308fa27fb0f53358823ba46141d03b7eb3",
                sizeBytes = 42_845_182L,
            ),
            ModelFile(
                archivePath = "$FAST_ARCHIVE_DIR/decoder-epoch-99-avg-1.int8.onnx",
                fileName = DECODER_FILE,
                sha256 = "21e2a2acd961b3ac72f55be2f10f1a285e1b0b0ba010d7c0b6eab141411b163c",
                sizeBytes = 539_499L,
            ),
            ModelFile(
                archivePath = "$FAST_ARCHIVE_DIR/joiner-epoch-99-avg-1.int8.onnx",
                fileName = JOINER_FILE,
                sha256 = "e085d73b593cf9b0707f370dbd656d58327d3fe36d80d849202ef81df02cb01e",
                sizeBytes = 259_572L,
            ),
        ),
    )

    /** Installed on devices with enough RAM to run the larger decoder in real time. */
    val onDeviceAccurate = SpeechModel(
        id = ACCURATE_MODEL_ID,
        displayName = "English (streaming Zipformer, high accuracy)",
        modelType = ZIPFORMER2,
        approxBytes = ACCURATE_ARCHIVE_BYTES,
        license = ModelLicense.APACHE_2_0,
        checksum = ACCURATE_ARCHIVE_SHA256,
        downloadUrl = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/" +
            "$ACCURATE_ARCHIVE_DIR.tar.bz2",
        files = listOf(
            ModelFile(
                archivePath = "$ACCURATE_ARCHIVE_DIR/$TOKENS_FILE",
                fileName = TOKENS_FILE,
                sha256 = "49e3c2646595fd907228b3c6787069658f67b17377c60aeb8619c4551b2316fb",
                sizeBytes = 5_048L,
            ),
            ModelFile(
                archivePath = "$ACCURATE_ARCHIVE_DIR/encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                fileName = ENCODER_FILE,
                sha256 = "5022b2eca5b19d1bc104fcf33e26bc32604b7df553cd2e1f62e31dc7b05e9c87",
                sizeBytes = 70_108_816L,
            ),
            ModelFile(
                archivePath = "$ACCURATE_ARCHIVE_DIR/decoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                fileName = DECODER_FILE,
                sha256 = "780c63ee94c7cfa314211172e5d09b406c0da2beab5c40ea2f54cc95670b76a5",
                sizeBytes = 540_688L,
            ),
            ModelFile(
                archivePath = "$ACCURATE_ARCHIVE_DIR/joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx",
                fileName = JOINER_FILE,
                sha256 = "abd5e30f3f16fc510605c6029dba33f10e4386bd75c5bdc30cf94076864db10d",
                sizeBytes = 259_416L,
            ),
        ),
    )

    /** Everything the app can install; [ModelRouting] picks one of these. */
    val all: List<SpeechModel> = listOf(onDeviceFast, onDeviceAccurate)
}

