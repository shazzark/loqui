package com.loqui.android.testing

import com.loqui.android.audio.PcmAudioSource

/**
 * Scripted microphone for JVM tests: hands out [chunks] one read at a time, then
 * reports end-of-source, mirroring how `AudioRecord.read` behaves when recording
 * stops.
 */
class FakePcmAudioSource(
    private val chunks: List<ByteArray>,
) : PcmAudioSource {

    var started: Int = 0
        private set

    var releaseCount: Int = 0
        private set

    var reads: Int = 0
        private set

    private var index = 0

    override fun start() {
        started++
    }

    override fun read(destination: ByteArray, offset: Int, size: Int): Int {
        reads++
        if (index >= chunks.size) return READ_FAILED

        val chunk = chunks[index]
        index++
        val copied = minOf(size, chunk.size)
        chunk.copyInto(destination, offset, 0, copied)
        return copied
    }

    override fun release() {
        releaseCount++
    }

    private companion object {
        const val READ_FAILED = -1
    }
}
