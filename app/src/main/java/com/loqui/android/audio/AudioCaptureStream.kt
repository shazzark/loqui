package com.loqui.android.audio

import android.annotation.SuppressLint
import com.loqui.android.asr.AudioFrame
import com.loqui.android.audio.vad.Pcm16Signal
import com.loqui.android.di.IoDispatcher
import java.util.concurrent.atomic.AtomicBoolean
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
 *
 * [stop] is the third way out and the one a user's second tap uses: it ends the flow
 * *normally* after the current frame, so the endpointer and the recogniser still get to
 * flush what was already spoken. Cancelling the collector instead throws the utterance
 * away — which is why a manual stop used to lose the transcript.
 */
class AudioCaptureStream @Inject constructor(
    private val sourceFactory: PcmAudioSourceFactory,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) {

    private val stopRequested = AtomicBoolean(false)

    /**
     * Asks the running capture to finish after the current frame.
     *
     * The flow completes as if the microphone had ended, so the utterance reaches the
     * recogniser instead of being cancelled. Idempotent, and reset when a new session
     * starts collecting [frames].
     */
    fun stop() {
        if (!stopRequested.getAndSet(true)) {
            Timber.d("Capture stop requested; closing the microphone after the current frame")
        }
    }

    /**
     * The caller must hold `RECORD_AUDIO`; [com.loqui.android.capture.VoiceInputService]
     * gates capture on the permission before collecting this flow.
     */
    @SuppressLint("MissingPermission")
    fun frames(): Flow<AudioFrame> = flow {
        stopRequested.set(false)
        val ring = AudioRingBuffer(RING_CAPACITY_BYTES)
        val source = sourceFactory.create()
        val chunk = ByteArray(READ_CHUNK_BYTES)
        val sampleRateHz = AudioFrame.SAMPLE_RATE_HZ
        var frames = 0
        var peakRms = 0f

        try {
            source.start()
            Timber.d("Microphone opened: %d Hz mono PCM-16", sampleRateHz)

            while (currentCoroutineContext().isActive && !stopRequested.get()) {
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
                    frames++
                    val frameRms = Pcm16Signal.rms(frame)
                    if (frameRms > peakRms) peakRms = frameRms
                    if (frames % LOG_EVERY_FRAMES == 0) {
                        // Level, never audio: this is the number that explains a
                        // session that never endpoints on a quiet or noisy device.
                        Timber.d(
                            "Captured %d frames (%d ms), last frame RMS %.4f, peak %.4f",
                            frames,
                            frames * FRAME_MS,
                            frameRms,
                            peakRms,
                        )
                    }
                    emit(AudioFrame(frame, sampleRateHz))
                }
            }
        } finally {
            source.release()
            // One line per session with the loudest frame that was ever delivered: a peak
            // below the VAD gate means the microphone never gave the gate a chance.
            Timber.d(
                "Microphone released after %d frames (%d ms), peak RMS %.4f%s",
                frames,
                frames * FRAME_MS,
                peakRms,
                if (stopRequested.get()) ", stopped by the user" else "",
            )
        }
    }.flowOn(ioDispatcher)

    companion object {
        /** 20 ms of 16 kHz mono PCM-16: 320 samples, 640 bytes. */
        const val FRAME_BYTES = AudioFrame.SAMPLE_RATE_HZ / 50 * 2

        /** Duration of one [FRAME_BYTES] frame. */
        const val FRAME_MS = 20

        /** Read one frame per call so endpointing latency stays at one frame. */
        const val READ_CHUNK_BYTES = FRAME_BYTES

        /** 30 s of audio: the hard ceiling for a single utterance. */
        const val RING_CAPACITY_BYTES = AudioFrame.SAMPLE_RATE_HZ * 2 * 30

        /** One diagnostic line per second of audio. */
        private const val LOG_EVERY_FRAMES = 1_000 / FRAME_MS
    }
}
