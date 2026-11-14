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
 * One downloadable on-device model. Nothing is bundled in the APK: the app only ever
 * shows what it *would* fetch, and [checksum] is what the downloader verifies before a
 * file is used.
 */
data class SpeechModel(
    val id: String,
    val displayName: String,
    val approxBytes: Long,
    val license: ModelLicense,
    /** Lowercase hex SHA-256 of the archive; null until the download manifest exists. */
    val checksum: String? = null,
)

/**
 * The on-device speech catalogue.
 *
 * Phase 5 ships the entry and the license record; the sherpa-onnx engine and the
 * downloader land with the model work, which is why no URL is wired yet.
 */
object SpeechModels {

    /** The recogniser the on-device mode will use once it ships. */
    val onDeviceDefault = SpeechModel(
        id = "sherpa-onnx-streaming-zipformer-en-20m",
        displayName = "English (streaming Zipformer, 20M)",
        approxBytes = 120L * 1024 * 1024,
        license = ModelLicense.APACHE_2_0,
    )

    val all: List<SpeechModel> = listOf(onDeviceDefault)
}
