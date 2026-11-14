package com.loqui.android.models

import java.util.Locale
import kotlin.math.max

/**
 * Whether the device can hold a model download: the archive plus its unpacked copy.
 *
 * Pure on purpose — the storage probe only supplies [freeBytes], so the decision the
 * settings screen renders is testable on the JVM.
 */
data class ModelStorageReport(
    val requiredBytes: Long,
    val freeBytes: Long,
    val safetyMarginBytes: Long = DEFAULT_SAFETY_MARGIN_BYTES,
) {
    /** Download plus unpacking head-room; a full disk mid-extract corrupts a model. */
    val neededBytes: Long
        get() = requiredBytes + safetyMarginBytes

    val hasRoom: Boolean
        get() = freeBytes >= neededBytes

    /** Bytes still missing when there is no room, `0` otherwise. */
    val shortfallBytes: Long
        get() = max(neededBytes - freeBytes, 0L)

    companion object {
        /** Room for the extracted model, so the download is never the thing that fills the disk. */
        const val DEFAULT_SAFETY_MARGIN_BYTES = 64L * 1024 * 1024
    }
}

/**
 * Human-readable size for the model rows. Deliberately coarse: the number comes from a
 * catalogue estimate, so "120 MB" is honest where "119.7 MB" would pretend to precision.
 */
fun formatBytes(bytes: Long): String {
    if (bytes < 0) return "0 B"

    val kib = 1024.0
    val mib = kib * 1024
    val gib = mib * 1024

    return when {
        bytes >= gib -> String.format(Locale.US, "%.1f GB", bytes / gib)
        bytes >= mib -> String.format(Locale.US, "%.0f MB", bytes / mib)
        bytes >= kib -> String.format(Locale.US, "%.0f KB", bytes / kib)
        else -> "$bytes B"
    }
}
