package com.loqui.android.audio

import android.Manifest
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import com.loqui.android.asr.AudioFrame
import timber.log.Timber

/**
 * [PcmAudioSource] backed by the platform `AudioRecord`: 16 kHz, mono, PCM-16, the
 * format every on-device recognizer in this project expects.
 *
 * `VOICE_RECOGNITION` is used rather than `MIC` so the platform applies its speech
 * pre-processing chain before the samples reach the recognizer.
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

    private fun buildRecorder(): AudioRecord {
        val minBufferBytes = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val bufferBytes = maxOf(minBufferBytes, MIN_BUFFER_BYTES)

        return AudioRecord.Builder()
            .setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRateHz)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build(),
            )
            .setBufferSizeInBytes(bufferBytes)
            .build()
    }

    private companion object {
        /** Four 20 ms frames, so a scheduling hiccup does not drop audio. */
        const val MIN_BUFFER_BYTES = 2_560
        const val READ_ERROR_NOT_STARTED = -1
    }
}
