package com.loqui.android.audio

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import androidx.annotation.RequiresPermission
import com.loqui.android.asr.AudioFrame
import timber.log.Timber

/**
 * [PcmAudioSource] backed by the platform `AudioRecord`: 16 kHz, mono, PCM-16, the
 * format every on-device recognizer in this project expects.
 *
 * The source is [MediaRecorder.AudioSource.MIC], not `VOICE_RECOGNITION`. The
 * voice-recognition chain on a Galaxy A13 applies a noise suppressor and an AGC keyed to
 * a voice trigger, and it was measurably eating the input: speech arrived at ~0.0003 RMS
 * in one session while the room tone it left behind sat at ~0.0002, which no single
 * energy gate can separate. `MIC` is the plain capture path and keeps the recogniser fed.
 *
 * `UNPROCESSED` (API 24+) is the second candidate because it removes the OEM processing
 * entirely; it is not the first choice because it also removes the high-pass filter and
 * AGC that keep levels usable on a quiet device. `VOICE_RECOGNITION` stays as the last
 * resort so a device that refuses both still records.
 */
class AudioRecordPcmSource(
    private val sampleRateHz: Int = AudioFrame.SAMPLE_RATE_HZ,
) : PcmAudioSource {

    private var recorder: AudioRecord? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun start() {
        val active = recorder ?: buildRecorder().also { recorder = it }
        check(active.state == AudioRecord.STATE_INITIALIZED) {
            "AudioRecord could not initialise at $sampleRateHz Hz"
        }
        active.startRecording()
    }

    override fun read(destination: ByteArray, offset: Int, size: Int): Int {
        val active = recorder ?: return READ_ERROR_NOT_STARTED
        return active.read(destination, offset, size)
    }

    override fun release() {
        val active = recorder ?: return
        recorder = null
        runCatching { active.stop() }
            .onFailure { Timber.w(it, "AudioRecord.stop() failed during release") }
        runCatching { active.release() }
            .onFailure { Timber.w(it, "AudioRecord.release() failed") }
    }

    /**
     * The permission is declared on [start] and enforced by `VoiceInputService` before a
     * session ever begins; lint cannot follow that gate into this helper.
     *
     * Sources are tried in [audioSourceCandidates] order and the first one that
     * initialises wins, so a device that refuses `MIC` still records instead of failing
     * the session.
     */
    @SuppressLint("MissingPermission")
    private fun buildRecorder(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBufferBytes, MIN_BUFFER_BYTES)

        var lastError: Throwable? = null
        for (source in audioSourceCandidates(Build.VERSION.SDK_INT)) {
            val recorder = try {
                build(source, bufferBytes)
            } catch (throwable: Throwable) {
                lastError = throwable
                Timber.w(throwable, "AudioRecord refused %s", describe(source))
                continue
            }

            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Timber.d(
                    "Microphone source %s: %d Hz mono PCM-16, buffer %d B",
                    describe(source),
                    sampleRateHz,
                    bufferBytes,
                )
                return recorder
            }

            lastError = IllegalStateException("${describe(source)} did not initialise")
            Timber.w("AudioRecord source %s reported STATE_UNINITIALIZED", describe(source))
            runCatching { recorder.release() }
                .onFailure { Timber.w(it, "AudioRecord.release() failed for an unused source") }
        }

        throw IllegalStateException(
            "AudioRecord could not initialise at $sampleRateHz Hz with any source",
            lastError,
        )
    }

    @SuppressLint("MissingPermission")
    private fun build(source: Int, bufferBytes: Int): AudioRecord = AudioRecord.Builder()
        .setAudioSource(source)
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(sampleRateHz)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build(),
        )
        .setBufferSizeInBytes(bufferBytes)
        .build()

    private fun describe(source: Int): String = when (source) {
        MediaRecorder.AudioSource.MIC -> "MIC"
        MediaRecorder.AudioSource.UNPROCESSED -> "UNPROCESSED"
        MediaRecorder.AudioSource.VOICE_RECOGNITION -> "VOICE_RECOGNITION"
        else -> source.toString()
    }

    private companion object {
        /** Four 20 ms frames, so a scheduling hiccup does not drop audio. */
        const val MIN_BUFFER_BYTES = 2_560
        const val READ_ERROR_NOT_STARTED = -1
    }
}

/**
 * `AudioRecord` sources in the order they are tried for a dictation.
 *
 * Pure and SDK-parameterised so the order is tested on the JVM: `MIC` first (no
 * voice-recognition tuning swallowing quiet speech), `UNPROCESSED` on API 24+ where it
 * exists, and `VOICE_RECOGNITION` last so an OEM build that refuses the other two still
 * records something.
 */
internal fun audioSourceCandidates(sdkInt: Int): List<Int> = buildList {
    add(MediaRecorder.AudioSource.MIC)
    if (sdkInt >= UNPROCESSED_MIN_SDK) add(MediaRecorder.AudioSource.UNPROCESSED)
    add(MediaRecorder.AudioSource.VOICE_RECOGNITION)
}

/** `MediaRecorder.AudioSource.UNPROCESSED` was added in Android 7.0 (API 24). */
internal const val UNPROCESSED_MIN_SDK = 24
