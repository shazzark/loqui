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

data class LoquiSettings(
    val asrMode: AsrMode = AsrMode.ON_DEVICE,
    val llmEnabled: Boolean = false,
    val llmMode: LlmMode = LlmMode.ON_DEVICE,
)

/** Identifiers used by the Keystore-backed secret store; one entry per provider. */
object ProviderIds {
    const val CLOUD_ASR = "cloud-asr"
    const val CLOUD_LLM = "cloud-llm"
}
