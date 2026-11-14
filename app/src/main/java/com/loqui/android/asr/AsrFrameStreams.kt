package com.loqui.android.asr

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Placeholder audio source for Phase 1: five seconds of 16 kHz PCM-16 silence,
 * delivered in 100 ms frames. Phase 2 replaces it with an `AudioRecord`-backed
 * flow that respects the capture service lifecycle.
 */
object AsrFrameStreams {

    fun phaseOneSilence(durationMs: Int = 5_000): Flow<AudioFrame> = flow {
        val frames = durationMs / FRAME_DURATION_MS
        repeat(frames) {
            emit(AudioFrame(ByteArray(BYTES_PER_FRAME)))
            delay(FRAME_DURATION_MS.toLong())
        }
    }

    private const val FRAME_DURATION_MS = 100
    private const val BYTES_PER_FRAME = FRAME_DURATION_MS * AudioFrame.SAMPLE_RATE_HZ * 2 / 1_000
}
