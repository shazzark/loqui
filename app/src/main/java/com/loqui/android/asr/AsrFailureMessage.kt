package com.loqui.android.asr

import com.loqui.android.settings.AsrMode

/**
 * Which sentence a failed session deserves, before resources turn it into text.
 *
 * [AsrFailure] is provider-neutral; the wording is not. Cloud mode talks about a provider
 * and the user's key, on-device mode has neither. Only [CLOUD_REJECTED] and
 * [CLOUD_KEY_MISSING] mention a key, and [messageFor] never returns either of them for
 * [AsrMode.ON_DEVICE]: a failed on-device dictation must not send the user looking for a
 * key that does not exist.
 */
enum class AsrFailureMessage {
    /** On-device recognition failed for a reason the user cannot act on. */
    ON_DEVICE_FAILED,

    /** On-device recognition ran past the session limit. */
    ON_DEVICE_TIMEOUT,

    NETWORK_TIMEOUT,

    /** A cloud provider rejected a request made with the user's own key. */
    CLOUD_REJECTED,

    /** Cloud mode was selected and no key is stored. */
    CLOUD_KEY_MISSING,

    MODEL_MISSING,

    NO_SPEECH,

    CANCELLED,
}

/**
 * Maps a failure onto the message the mode in use should show.
 *
 * Key and provider wording is unreachable for [AsrMode.ON_DEVICE] by construction: the
 * three provider-shaped reasons collapse into [AsrFailureMessage.ON_DEVICE_FAILED] or
 * [AsrFailureMessage.ON_DEVICE_TIMEOUT] there.
 */
fun messageFor(reason: AsrFailure, mode: AsrMode): AsrFailureMessage = when (reason) {
    AsrFailure.NO_SPEECH -> AsrFailureMessage.NO_SPEECH
    AsrFailure.MODEL_UNAVAILABLE -> AsrFailureMessage.MODEL_MISSING
    AsrFailure.CANCELLED -> AsrFailureMessage.CANCELLED
    AsrFailure.NETWORK_TIMEOUT, AsrFailure.PROVIDER_ERROR, AsrFailure.MISSING_KEY ->
        when (mode) {
            AsrMode.ON_DEVICE -> when (reason) {
                AsrFailure.NETWORK_TIMEOUT -> AsrFailureMessage.ON_DEVICE_TIMEOUT
                else -> AsrFailureMessage.ON_DEVICE_FAILED
            }

            AsrMode.CLOUD -> when (reason) {
                AsrFailure.NETWORK_TIMEOUT -> AsrFailureMessage.NETWORK_TIMEOUT
                AsrFailure.MISSING_KEY -> AsrFailureMessage.CLOUD_KEY_MISSING
                else -> AsrFailureMessage.CLOUD_REJECTED
            }
        }
}
