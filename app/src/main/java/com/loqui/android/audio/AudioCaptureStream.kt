package com.loqui.android.audio

import android.annotation.SuppressLint
import com.loqui.android.asr.AudioFrame
import com.loqui.android.di.IoDispatcher
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import timber.log.Timber

/**
 * Turns the blocking `AudioRecord` loop into a cold [Flow] of [AudioFrame]s.
 *
 * Every byte the microphone hands over goes through an [AudioRingBuffer], which
 * absorbs the mismatch between the blocking read and a consumer that may be busy
 * (VAD, ASR, back-pressure). Frames are 20 ms of 16 kHz mono PCM-16, small enough
 * for responsive endpointing.
 *
 * The flow owns the microphone for exactly as long as it is collected: the source
 * is released in `finally`, so cancelling the session (bubble stop, service
 * destroy) always releases the mic.
 */
class AudioCaptureStream @Inject constructor(
    private val sourceFactory: PcmAudioSourceFactory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    /**
     * The caller must hold `RECORD_AUDIO`; [com.loqui.android.capture.VoiceInputService]
     * gates capture on the permission before collecting this flow.
     */
    @SuppressLint("MissingPermission")
    fun frames(): Flow<AudioFrame> = flow {
        val ring = AudioRingBuffer(RING_CAPACITY_BYTES)
        val source = sourceFactory.create()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val sampleRateHz = AudioFrame.SAMPLE_RATE_HZ

        try {
            source.start()

            while (currentCoroutineContext().isActive) {
                val read = source.read(chunk, 0, chunk.size)
                if (read <= 0) {
                    Timber.w("Microphone read returned %d; ending capture", read)
                    break
                }

                ring.write(chunk, 0, read)

                if (ring.droppedBytes > 0) {
                    Timber.w("Audio ring buffer dropped %d bytes", ring.droppedBytes)
                }

                while (ring.availableBytes >= FRAME_BYTES) {
                    val frame = ring.readFrame(FRAME_BYTES) ?: break
                    emit(AudioFrame(frame, sampleRateHz))
                }
            }
        } finally {
            source.release()
        }
    }.flowOn(ioDispatcher)

    companion object {
        /** 20 ms of 16 kHz mono PCM-16: 320 samples, 640 bytes. */
        const val FRAME_BYTES = AudioFrame.SAMPLE_RATE_HZ / 50 * 2

        /** Read one frame per call so endpointing latency stays at one frame. */
        const val READ_CHUNK_BYTES = FRAME_BYTES

        /** 30 s of audio: the hard ceiling for a single utterance. */
        const val RING_CAPACITY_BYTES = AudioFrame.SAMPLE_RATE_HZ * 2 * 30
    }
}
