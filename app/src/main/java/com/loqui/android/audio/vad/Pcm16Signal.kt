package com.loqui.android.audio.vad

import kotlin.math.sqrt

/**
 * Signal helpers for little-endian PCM-16 audio.
 *
 * Kept separate so the arithmetic can be unit tested against known waveforms.
 */
internal object Pcm16Signal {

    private const val FULL_SCALE = 32_768f
    private const val BYTES_PER_SAMPLE = 2

    /**
     * Normalised root-mean-square level of [length] bytes from [offset], in `0..1`.
     *
     * A silent frame is `0`, a full-scale square wave is `1`.
     */
    fun rms(pcm: ByteArray, offset: Int = 0, length: Int = pcm.size - offset): Float {
        val end = offset + (length / BYTES_PER_SAMPLE) * BYTES_PER_SAMPLE
        val sampleCount = (end - offset) / BYTES_PER_SAMPLE
        if (sampleCount <= 0) return 0f

        var sumSquares = 0.0
        var index = offset
        while (index < end) {
            val raw = (pcm[index + 1].toInt() shl 8) or (pcm[index].toInt() and 0xFF)
            val normalised = raw.toShort() / FULL_SCALE
            sumSquares += normalised.toDouble() * normalised
            index += BYTES_PER_SAMPLE
        }
        return sqrt(sumSquares / sampleCount).toFloat()
    }
}
