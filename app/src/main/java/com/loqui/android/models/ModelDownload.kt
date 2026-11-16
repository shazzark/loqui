package com.loqui.android.models

/**
 * The install lifecycle the model card renders. A download is never a fire-and-forget
 * side effect: every stage is visible, and [Failed] always carries a reason the user can
 * act on.
 */
sealed interface ModelDownloadState {
    data object NotInstalled : ModelDownloadState

    data class Downloading(
        val bytesReceived: Long,
        val totalBytes: Long,
    ) : ModelDownloadState {
        val fraction: Float
            get() = if (totalBytes <= 0L) {
                0f
            } else {
                (bytesReceived.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f)
            }
    }

    /** The archive arrived; its SHA-256 is being checked. */
    data object Verifying : ModelDownloadState

    /** Verified; the four int8 files are being unpacked into a staging directory. */
    data object Installing : ModelDownloadState

    data object Installed : ModelDownloadState

    data class Failed(val failure: ModelDownloadFailure) : ModelDownloadState
}

enum class ModelDownloadFailure {
    /** The connection dropped or the server refused the request. A partial file is kept. */
    NETWORK,

    /** The downloaded archive did not match the checksum recorded in the catalogue. */
    CHECKSUM_MISMATCH,

    /** The install would not fit, including the unpacked copy and the safety margin. */
    NO_SPACE,

    /** Writing, unpacking or renaming the model failed. */
    STORAGE,

    /** The catalogue has no URL or checksum for this model. */
    UNSUPPORTED,
}
