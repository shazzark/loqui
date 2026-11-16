package com.loqui.android.testing

import com.loqui.android.asr.AudioFrame

/** Amplitude that sits well above the default VAD threshold (~0.18 RMS). */
const val SPEECH_AMPLITUDE = 6_000

/** Amplitude that sits well below the default VAD threshold (~0.00006 RMS). */
const val QUIET_AMPLITUDE = 2

/** 20 ms of 16 kHz mono PCM-16. */
const val FRAME_BYTES = 640

/** Builds [sampleCount] little-endian PCM-16 samples from a per-sample generator. */
fun pcm16Samples(sampleCount: Int, sample: (Int) -> Int): ByteArray {
    val bytes = ByteArray(sampleCount * 2)
    for (index in 0 until sampleCount) {
        val value = sample(index).coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
        bytes[index * 2] = (value and 0xFF).toByte()
        bytes[index * 2 + 1] = ((value shr 8) and 0xFF).toByte()
    }
    return bytes
}

/** One 20 ms frame at a constant amplitude (a square wave), so RMS == amplitude / 32768. */
fun audioFrame(
    amplitude: Int = 0,
    sampleRateHz: Int = AudioFrame.SAMPLE_RATE_HZ,
): AudioFrame {
    val samples = sampleRateHz / 50
    return AudioFrame(pcm16Samples(samples) { amplitude }, sampleRateHz)
}

/** One raw capture chunk of [FRAME_BYTES] bytes at [amplitude]. */
fun pcmChunk(amplitude: Int): ByteArray = pcm16Samples(FRAME_BYTES / 2) { amplitude }
