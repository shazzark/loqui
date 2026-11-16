package com.loqui.android.asr

/**
 * Converts little-endian signed 16-bit PCM (what `AudioRecord` captures) to the float
 * samples sherpa-onnx expects, normalised to [-1, 1).
 *
 * A trailing odd byte is ignored: a torn frame is not audio, and inventing a sample
 * would put a click into the recogniser.
 */
fun pcm16ToFloatSamples(pcm16: ByteArray): FloatArray {
    val sampleCount = pcm16.size / 2
    if (sampleCount == 0) return FloatArray(0)

    val samples = FloatArray(sampleCount)
    for (index in 0 until sampleCount) {
        val offset = index * 2
        val bits = (pcm16[offset].toInt() and 0xff) or
            (pcm16[offset + 1].toInt() shl 8)
        samples[index] = bits.toShort() / 32768f
    }
    return samples
}
