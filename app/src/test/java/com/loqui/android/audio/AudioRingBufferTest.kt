package com.loqui.android.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class AudioRingBufferTest {

    private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

    @Test
    fun `rejects a non-positive capacity`() {
        assertThrows(IllegalArgumentException::class.java) { AudioRingBuffer(0) }
        assertThrows(IllegalArgumentException::class.java) { AudioRingBuffer(-8) }
    }

    @Test
    fun `reads back bytes in write order`() {
        val ring = AudioRingBuffer(8)
        ring.write(bytes(1, 2, 3, 4))

        val destination = ByteArray(4)
        val copied = ring.read(destination)

        assertEquals(4, copied)
        assertArrayEquals(bytes(1, 2, 3, 4), destination)
        assertEquals(0, ring.availableBytes)
    }

    @Test
    fun `wraps around the end of the buffer without reordering`() {
        val ring = AudioRingBuffer(8)
        ring.write(bytes(1, 2, 3, 4, 5, 6))

        val firstRead = ByteArray(4)
        assertEquals(4, ring.read(firstRead))
        assertArrayEquals(bytes(1, 2, 3, 4), firstRead)

        ring.write(bytes(7, 8, 9, 10, 11, 12))

        val secondRead = ByteArray(8)
        assertEquals(8, ring.read(secondRead))
        assertArrayEquals(bytes(5, 6, 7, 8, 9, 10, 11, 12), secondRead)
        assertEquals(0L, ring.droppedBytes)
    }

    @Test
    fun `drops the oldest bytes when the consumer falls behind`() {
        val ring = AudioRingBuffer(4)
        ring.write(bytes(1, 2, 3, 4))
        ring.write(bytes(5, 6))

        assertEquals(4, ring.availableBytes)
        assertEquals(2L, ring.droppedBytes)

        val destination = ByteArray(4)
        ring.read(destination)
        assertArrayEquals(bytes(3, 4, 5, 6), destination)
    }

    @Test
    fun `keeps only the newest bytes of a single oversized write`() {
        val ring = AudioRingBuffer(4)
        ring.write(bytes(1, 2, 3, 4, 5, 6))

        assertEquals(4, ring.availableBytes)
        assertEquals(2L, ring.droppedBytes)

        val destination = ByteArray(4)
        ring.read(destination)
        assertArrayEquals(bytes(3, 4, 5, 6), destination)
    }

    @Test
    fun `readFrame never splits a frame across calls`() {
        val ring = AudioRingBuffer(8)
        ring.write(bytes(1, 2, 3))

        assertNull(ring.readFrame(4))
        assertEquals(3, ring.availableBytes)

        ring.write(bytes(4))

        val frame = ring.readFrame(4)
        assertArrayEquals(bytes(1, 2, 3, 4), frame)
        assertEquals(0, ring.availableBytes)
    }

    @Test
    fun `clear discards buffered audio but keeps the drop count`() {
        val ring = AudioRingBuffer(4)
        ring.write(bytes(1, 2, 3, 4, 5))
        ring.clear()

        assertEquals(0, ring.availableBytes)
        assertEquals(1L, ring.droppedBytes)

        ring.write(bytes(9, 10))
        val destination = ByteArray(2)
        assertEquals(2, ring.read(destination))
        assertArrayEquals(bytes(9, 10), destination)
    }

    @Test
    fun `rejects slices outside the source array`() {
        val ring = AudioRingBuffer(8)
        assertThrows(IllegalArgumentException::class.java) {
            ring.write(ByteArray(4), offset = 2, length = 4)
        }
    }
}
