package com.loqui.android.models

import java.io.File
import java.security.MessageDigest

/**
 * A download/install failure that already knows which user-facing message it maps to.
 * Everything else that escapes the downloader is classified by the repository.
 */
class ModelDownloadException(
    val failure: ModelDownloadFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The network + archive half of a model install, split from the state machine so the
 * repository can be tested with a fake that never touches OkHttp or disk.
 */
interface ModelDownloader {
    /**
     * Streams [url] into [destination], resuming from its current length when the server
     * honours a byte range. [onProgress] receives the cumulative bytes on disk.
     */
    suspend fun download(
        url: String,
        destination: File,
        expectedBytes: Long,
        onProgress: (bytesReceived: Long) -> Unit,
    )

    /** Verifies [file] against the lowercase hex [sha256], deleting it when it differs. */
    suspend fun verify(file: File, sha256: String)

    /**
     * Unpacks only the [model]'s int8 files from the `.tar.bz2` [archive] into
     * [destination], verifying each file's size and SHA-256 while streaming.
     */
    suspend fun extract(archive: File, model: SpeechModel, destination: File)
}

/** Lowercase hex SHA-256 of a file, read in chunks so a 128 MiB archive stays off the heap. */
internal fun sha256Hex(file: File): String {
    val digest = MessageDigest.getInstance("SHA-256")
    file.inputStream().buffered().use { input ->
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            if (read > 0) digest.update(buffer, 0, read)
        }
    }
    return digest.digest().toHex()
}

/** Lowercase hex SHA-256 of an in-memory payload (catalogue checks, tests). */
internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

internal fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    return buildString(size * 2) {
        for (byte in this@toHex) {
            val value = byte.toInt() and 0xff
            append(digits[value ushr 4])
            append(digits[value and 0x0f])
        }
    }
}
