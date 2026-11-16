package com.loqui.android.models

import com.loqui.android.di.IoDispatcher
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.utils.io.jvm.javaio.toInputStream
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream
import timber.log.Timber

/**
 * HTTPS download + `.tar.bz2` unpack against the official sherpa-onnx release.
 *
 * The archive is ~122 MiB on GitHub's CDN, so the download is resumable and progress is
 * throttled to ~512 KiB steps: a Compose state update per 64 KiB read would be 2000
 * recompositions for no visible benefit. A checksum mismatch deletes the file rather
 * than letting a corrupt model reach the recogniser.
 */
@Singleton
class KtorModelDownloader @Inject constructor(
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ModelDownloader {

    private val client = HttpClient(OkHttp) {
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = 30_000
            socketTimeoutMillis = 60_000
            // 128 MiB on a slow-but-alive connection: let the socket timeout decide.
            requestTimeoutMillis = 30 * 60_000
        }
    }

    override suspend fun download(
        url: String,
        destination: File,
        expectedBytes: Long,
        onProgress: (bytesReceived: Long) -> Unit,
    ): Unit = withContext(ioDispatcher) {
        val parent = destination.parentFile
        if (parent != null && !parent.isDirectory && !parent.mkdirs()) {
            throw ModelDownloadException(ModelDownloadFailure.STORAGE, "No model directory")
        }

        val resumeFrom = if (destination.isFile) destination.length() else 0L
        try {
            client.prepareGet(url) {
                if (resumeFrom > 0L) {
                    header(HttpHeaders.Range, "bytes=$resumeFrom-")
                }
            }.execute { response ->
                if (response.status == HttpStatusCode.RequestedRangeNotSatisfiable) {
                    // The partial file is longer than the object; start over next attempt.
                    destination.delete()
                    throw ModelDownloadException(
                        ModelDownloadFailure.NETWORK,
                        "Server rejected resume offset",
                    )
                }
                if (!response.status.isSuccess()) {
                    throw ModelDownloadException(
                        ModelDownloadFailure.NETWORK,
                        "Model download returned HTTP ${response.status.value}",
                    )
                }

                val appending = resumeFrom > 0L &&
                    response.status == HttpStatusCode.PartialContent
                if (!appending && destination.exists()) destination.delete()

                val start = if (appending) resumeFrom else 0L
                val bodyBytes = response.headers[HttpHeaders.ContentLength]?.toLongOrNull()
                val total = when {
                    bodyBytes == null -> expectedBytes
                    appending -> start + bodyBytes
                    else -> bodyBytes
                }

                var received = start
                var lastReported = Long.MIN_VALUE
                onProgress(received)

                FileOutputStream(destination, appending).use { output ->
                    response.bodyAsChannel().toInputStream().use { input ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue

                            output.write(buffer, 0, read)
                            received += read
                            if (received - lastReported >= PROGRESS_STEP_BYTES) {
                                lastReported = received
                                onProgress(received)
                            }
                        }
                    }
                    output.fd.sync()
                }

                onProgress(received)
                verifyDownloadLength(destination, received, total, expectedBytes)
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: ModelDownloadException) {
            throw failure
        } catch (throwable: Throwable) {
            throw ModelDownloadException(
                ModelDownloadFailure.NETWORK,
                "Model download failed",
                throwable,
            )
        }
    }

    private fun verifyDownloadLength(
        destination: File,
        received: Long,
        total: Long,
        expectedBytes: Long,
    ) {
        if (total > 0L && received != total) {
            throw ModelDownloadException(
                ModelDownloadFailure.NETWORK,
                "Model download ended at $received of $total bytes",
            )
        }
        if (expectedBytes > 0L && received != expectedBytes) {
            destination.delete()
            throw ModelDownloadException(
                ModelDownloadFailure.NETWORK,
                "Model download ended at $received of $expectedBytes bytes",
            )
        }
    }

    override suspend fun verify(file: File, sha256: String): Unit = withContext(ioDispatcher) {
        if (!file.isFile) {
            throw ModelDownloadException(ModelDownloadFailure.STORAGE, "Archive is missing")
        }

        val actual = sha256Hex(file)
        if (!actual.equals(sha256, ignoreCase = true)) {
            file.delete()
            throw ModelDownloadException(
                ModelDownloadFailure.CHECKSUM_MISMATCH,
                "Archive checksum mismatch",
            )
        }
        Timber.i("Model archive verified (%d bytes)", file.length())
    }

    override suspend fun extract(
        archive: File,
        model: SpeechModel,
        destination: File,
    ): Unit = withContext(ioDispatcher) {
        if (!destination.isDirectory && !destination.mkdirs()) {
            throw ModelDownloadException(
                ModelDownloadFailure.STORAGE,
                "Could not create the model staging directory",
            )
        }

        val byArchivePath = model.files.associateBy { it.archivePath }
        val byFileName = model.files.associateBy { it.fileName }
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
                            // Exact path first; the fallback keeps the manifest honest even
                            // if GitHub ever wraps the release in an extra directory.
                            val spec = byArchivePath[entry.name]
                                ?: byFileName[entry.name.substringAfterLast('/')]

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

    private companion object {
        const val PROGRESS_STEP_BYTES = 512L * 1024
    }
}
