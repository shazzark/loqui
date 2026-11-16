package com.loqui.android.audio

import android.media.MediaRecorder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Which microphone source a dictation opens, and the order it falls back in.
 *
 * The Galaxy A13 that motivated this delivered ~0.0003 RMS for *speech* through the
 * `VOICE_RECOGNITION` chain — its tuning suppresses quiet talkers — so `MIC` is tried
 * first and `VOICE_RECOGNITION` is kept only as the last resort.
 */
class AudioRecordPcmSourceTest {

    @Test
    fun `mic is tried first and voice recognition last`() {
        val candidates = audioSourceCandidates(sdkInt = 30)

        assertEquals(MediaRecorder.AudioSource.MIC, candidates.first())
        assertEquals(MediaRecorder.AudioSource.VOICE_RECOGNITION, candidates.last())
    }

    @Test
    fun `unprocessed sits between the two on api 24 and up`() {
        val modern = audioSourceCandidates(sdkInt = UNPROCESSED_MIN_SDK)
        val legacy = audioSourceCandidates(sdkInt = UNPROCESSED_MIN_SDK - 1)

        assertEquals(
            listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.UNPROCESSED,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
            ),
            modern,
        )
        // Android 6 and older have no UNPROCESSED source; the chain simply skips it.
        assertEquals(
            listOf(
                MediaRecorder.AudioSource.MIC,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
            ),
            legacy,
        )
    }
}
