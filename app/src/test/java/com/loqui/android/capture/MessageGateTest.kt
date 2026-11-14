package com.loqui.android.capture

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageGateTest {

    @Test
    fun `the first message always shows`() {
        val gate = MessageGate(windowMs = 1_000)

        assertTrue(gate.allow("Copied to clipboard", nowMs = 0))
    }

    @Test
    fun `the same message inside the window is suppressed`() {
        val gate = MessageGate(windowMs = 1_000)

        assertTrue(gate.allow("Copied to clipboard", nowMs = 100))
        assertFalse(gate.allow("Copied to clipboard", nowMs = 900))
    }

    @Test
    fun `the same message shows again once the window has passed`() {
        val gate = MessageGate(windowMs = 1_000)

        assertTrue(gate.allow("Copied to clipboard", nowMs = 100))
        assertTrue(gate.allow("Copied to clipboard", nowMs = 1_100))
    }

    @Test
    fun `a different message is never suppressed`() {
        val gate = MessageGate(windowMs = 1_000)

        assertTrue(gate.allow("Copied to clipboard", nowMs = 100))
        assertTrue(gate.allow("Inserted into the focused field", nowMs = 101))
    }

    @Test
    fun `reset lets the last message through again`() {
        val gate = MessageGate(windowMs = 1_000)

        assertTrue(gate.allow("Copied to clipboard", nowMs = 100))
        gate.reset()

        assertTrue(gate.allow("Copied to clipboard", nowMs = 200))
    }
}
