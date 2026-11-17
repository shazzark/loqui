package com.loqui.android.settings

/**
 * `asr.mode`. On-device is the default and needs no key; cloud is opt-in and
 * always uses the user's own provider key.
 */
enum class AsrMode(val storageValue: String) {
    ON_DEVICE("on-device"),
    CLOUD("cloud"),
    ;

    companion object {
        fun fromStorage(value: String?): AsrMode =
            entries.firstOrNull { it.storageValue == value } ?: ON_DEVICE
    }
}

/** `llm.mode`. Only meaningful while `llm.enabled` is true. */
enum class LlmMode(val storageValue: String) {
    ON_DEVICE("on-device"),
    CLOUD("cloud"),
    ;

    companion object {
        fun fromStorage(value: String?): LlmMode =
            entries.firstOrNull { it.storageValue == value } ?: ON_DEVICE
    }
}

/**
 * `asr.model.quality`. What the user asked the on-device recogniser to optimise for.
 *
 * [AUTO] is the default and the only value that looks at the phone: it picks the accurate
 * export on high-memory devices and the 20M export on the rest. The other two exist
 * because the automatic call is a guess about someone else's phone — a user who prefers
 * more accurate text on a small phone, or faster text on a big one, should be able to say
 * so without a hidden developer flag.
 */
enum class ModelQuality(val storageValue: String) {
    AUTO("auto"),
    FAST("fast"),
    ACCURATE("accurate"),
    ;

    companion object {
        fun fromStorage(value: String?): ModelQuality =
            entries.firstOrNull { it.storageValue == value } ?: AUTO
    }
}

data class LoquiSettings(
    val asrMode: AsrMode = AsrMode.ON_DEVICE,
    val llmEnabled: Boolean = false,
    val llmMode: LlmMode = LlmMode.ON_DEVICE,
    /** Which on-device model to run; [ModelQuality.AUTO] defers to the device's RAM. */
    val modelQuality: ModelQuality = ModelQuality.AUTO,

    /**
     * The Play accessibility disclosure was accepted.
     *
     * Persisted so the dialog is shown once per install: a second trip to the
     * accessibility settings is a settings change, not a new consent.
     */
    val accessibilityDisclosureAccepted: Boolean = false,
)

/** Identifiers used by the Keystore-backed secret store; one entry per provider. */
object ProviderIds {
    const val CLOUD_ASR = "cloud-asr"
    const val CLOUD_LLM = "cloud-llm"
}
