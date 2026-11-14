package com.loqui.android.audio

/**
 * Bounded circular buffer for raw little-endian PCM-16 bytes.
 *
 * The capture loop writes from a blocking `AudioRecord.read`; consumers drain whole
 * frames. When the consumer falls behind, the **oldest** audio is dropped (bad
 * dictation, never a crash) and [droppedBytes] records how much, so the loss is
 * observable instead of silent.
 *
 * Pure Kotlin with no Android types, so it is fully unit testable on the JVM.
 */
class AudioRingBuffer(val capacityBytes: Int) {

    init {
        require(capacityBytes > 0) { "capacityBytes must be positive, was $capacityBytes" }
    }

    private val buffer = ByteArray(capacityBytes)
    private val lock = Any()

    private var readIndex = 0
    private var size = 0
    private var dropped = 0L

    /** Bytes currently buffered and ready to read. */
    val availableBytes: Int
        get() = synchronized(lock) { size }

    /** Total bytes discarded because the consumer was slower than the microphone. */
    val droppedBytes: Long
        get() = synchronized(lock) { dropped }

    /**
     * Appends [length] bytes from [source] starting at [offset]. Bytes that do not
     * fit are discarded oldest-first and counted in [droppedBytes].
     */
    fun write(source: ByteArray, offset: Int = 0, length: Int = source.size - offset) {
        require(offset >= 0 && length >= 0 && offset <= source.size - length) {
            "Invalid slice offset=$offset length=$length for ${source.size} bytes"
        }
        if (length == 0) return

        synchronized(lock) {
            var sourceOffset = offset
            var remaining = length

            // A single write larger than the buffer keeps only its newest bytes.
            if (remaining > capacityBytes) {
                val overflow = remaining - capacityBytes
                dropped += overflow.toLong()
                sourceOffset += overflow
                remaining = capacityBytes
                readIndex = 0
                size = 0
            }

            val free = capacityBytes - size
            if (remaining > free) {
                val evicted = remaining - free
                readIndex = (readIndex + evicted) % capacityBytes
                size -= evicted
                dropped += evicted.toLong()
            }

            val writeIndex = (readIndex + size) % capacityBytes
            val firstChunk = minOf(remaining, capacityBytes - writeIndex)
            System.arraycopy(source, sourceOffset, buffer, writeIndex, firstChunk)
            if (remaining > firstChunk) {
                System.arraycopy(
                    source,
                    sourceOffset + firstChunk,
                    buffer,
                    0,
                    remaining - firstChunk,
                )
            }
            size += remaining
        }
    }

    /**
     * Copies up to [maxBytes] of the oldest buffered bytes into [destination].
     *
     * @return the number of bytes copied, `0` when the buffer is empty.
     */
    fun read(
        destination: ByteArray,
        offset: Int = 0,
        maxBytes: Int = destination.size - offset,
    ): Int {
        require(offset >= 0 && maxBytes >= 0 && offset <= destination.size - maxBytes) {
            "Invalid slice offset=$offset maxBytes=$maxBytes for ${destination.size} bytes"
        }

        synchronized(lock) {
            val toCopy = minOf(maxBytes, size)
            if (toCopy == 0) return 0

            val firstChunk = minOf(toCopy, capacityBytes - readIndex)
            System.arraycopy(buffer, readIndex, destination, offset, firstChunk)
            if (toCopy > firstChunk) {
                System.arraycopy(buffer, 0, destination, offset + firstChunk, toCopy - firstChunk)
            }
            readIndex = (readIndex + toCopy) % capacityBytes
            size -= toCopy
            return toCopy
        }
    }

    /**
     * Removes exactly [frameBytes] bytes as a new array.
     *
     * @return `null` when fewer than [frameBytes] bytes are buffered, leaving the
     * buffer untouched (a frame is never split across calls).
     */
    fun readFrame(frameBytes: Int): ByteArray? {
        require(frameBytes > 0) { "frameBytes must be positive, was $frameBytes" }

        synchronized(lock) {
            if (size < frameBytes) return null
        }

        val frame = ByteArray(frameBytes)
        val copied = read(frame, 0, frameBytes)
        return if (copied == frameBytes) frame else null
    }

    /** Discards everything buffered. Cumulative [droppedBytes] is preserved. */
    fun clear() {
        synchronized(lock) {
            readIndex = 0
            size = 0
        }
    }
}
