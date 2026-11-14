package com.loqui.android.audio

/**
 * A blocking source of raw PCM-16 microphone bytes.
 *
 * `AudioRecord` itself is an Android stub under JVM unit tests, so the capture loop
 * depends on this interface and tests drive it with a fake. The real implementation
 * is [AudioRecordPcmSource].
 */
interface PcmAudioSource {

    /** Starts filling the platform buffer. Throws if the microphone cannot start. */
    fun start()

    /**
     * Blocks until [size] bytes (or fewer) are available, then copies them into
     * [destination] at [offset].
     *
     * @return bytes copied, or a negative value when the source is stopped, has
     * failed, or the read was invalid.
     */
    fun read(destination: ByteArray, offset: Int, size: Int): Int

    /** Stops the microphone and releases platform resources. Safe to call twice. */
    fun release()
}

/** Creates one source per capture session; the microphone is never shared. */
fun interface PcmAudioSourceFactory {
    fun create(): PcmAudioSource
}
