package com.loqui.android.models

import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber

/**
 * Streams the model files out of the `.tar.bz2` [archive] into [destination].
 *
 * Split out of the Ktor downloader so the unpack path — the half that decides whether a
 * phone ever has a usable recogniser — is exercised by a JVM test against a real bzip2 +
 * tar stream, not only by a fake. The archive is ~296 MiB and carries fp32 variants and
 * test wavs, so nothing but the catalogue's exact entries is written and every entry is
 * hashed while it streams to disk.
 *
 * A file is only left on disk after its size **and** SHA-256 match the catalogue, and the
 * whole extraction fails if any catalogue entry is missing: a model directory with one
 * 0-byte file would otherwise look installed to everything downstream.
 */
internal suspend fun extractModelArchive(
    archive: File,
    model: SpeechModel,
    destination: File,
) {
    if (!destination.isDirectory && !destination.mkdirs()) {
        throw ModelDownloadException(
            ModelDownloadFailure.STORAGE,
            "Could not create the model staging directory",
        )
    }

    val byArchivePath = model.files.associateBy { it.archivePath }
    // Fallback for an archive whose paths moved (e.g. GitHub re-wrapping the release in an
    // extra directory): match the entry's base name against the catalogue's base name.
    val byArchiveName = model.files.associateBy { it.archivePath.substringAfterLast('/') }
    val extracted = mutableSetOf<String>()

    try {
        BZip2CompressorInputStream(
            BufferedInputStream(FileInputStream(archive)),
        ).use { bzip ->
            TarArchiveInputStream(bzip).use { tar ->
                var entry = tar.nextEntry
                while (entry != null) {
                    currentCoroutineContext().ensureActive()

                    if (!entry.isDirectory && tar.canReadEntryData(entry)) {
                        // Exact path first; the base-name fallback keeps the manifest honest
                        // if GitHub ever wraps the release in an extra directory.
                        val spec = byArchivePath[entry.name]
                            ?: byArchiveName[entry.name.substringAfterLast('/')]

                        if (spec != null && spec.fileName !in extracted) {
                            unpack(tar, File(destination, spec.fileName), spec)
                            extracted += spec.fileName
                        }
                    }
                    entry = tar.nextEntry
                }
            }
        }
    } catch (cancellation: CancellationException) {
        throw cancellation
    } catch (failure: ModelDownloadException) {
        throw failure
    } catch (throwable: Throwable) {
        throw ModelDownloadException(
            ModelDownloadFailure.STORAGE,
            "Could not unpack the model archive",
            throwable,
        )
    }

    if (extracted.size != model.files.size) {
        throw ModelDownloadException(
            ModelDownloadFailure.STORAGE,
            "Model archive is missing ${model.files.size - extracted.size} file(s)",
        )
    }

    // One line with the exact byte count of every file: "0 MB" for a 539 KiB decoder once
    // hid a healthy install, and a genuinely short file has to be visible in logcat.
    Timber.i(
        "Unpacked %s: %s",
        model.id,
        model.files.joinToString { spec ->
            "${spec.fileName} ${File(destination, spec.fileName).length()} B"
        },
    )
}

/** Streams one entry to disk while hashing it; nothing is kept in memory. */
private suspend fun unpack(
    tar: TarArchiveInputStream,
    target: File,
    spec: ModelFile,
) {
    val digest = MessageDigest.getInstance("SHA-256")
    var written = 0L

    FileOutputStream(target).buffered().use { output ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = tar.read(buffer)
            if (read < 0) break
            if (read == 0) continue

            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            written += read
        }
    }

    if (written != spec.sizeBytes) {
        target.delete()
        throw ModelDownloadException(
            ModelDownloadFailure.STORAGE,
            "${spec.fileName} was $written bytes, expected ${spec.sizeBytes}",
        )
    }

    val actual = digest.digest().toHex()
    if (!actual.equals(spec.sha256, ignoreCase = true)) {
        target.delete()
        throw ModelDownloadException(
            ModelDownloadFailure.CHECKSUM_MISMATCH,
            "${spec.fileName} did not match the catalogue checksum",
        )
    }
}
