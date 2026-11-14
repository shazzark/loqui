package com.loqui.android.trigger

import com.loqui.android.R
import com.loqui.android.capture.CaptureState
import com.loqui.android.insertion.InsertRoute
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BubbleAppearanceTest {

    @Test
    fun `idle is a bare disc with no label and no animation`() {
        val appearance = BubbleAppearances.forState(CaptureState.Idle)

        assertEquals(BubbleVisual.IDLE, appearance.visual)
        assertEquals(BubbleAppearances.IDLE_ACCENT, appearance.accent)
        assertEquals(BubbleLabel.None, appearance.label)
        assertFalse(appearance.showsProgress)
        assertFalse(appearance.pulses)
    }

    @Test
    fun `listening shows the listening label and pulses`() {
        val appearance = BubbleAppearances.forState(CaptureState.Listening)

        assertEquals(BubbleVisual.LISTENING, appearance.visual)
        assertEquals(BubbleLabel.Res(R.string.bubble_state_listening), appearance.label)
        assertTrue(appearance.pulses)
        assertFalse(appearance.showsProgress)
    }

    @Test
    fun `processing shows a spinner instead of the microphone`() {
        val appearance = BubbleAppearances.forState(CaptureState.Processing)

        assertEquals(BubbleVisual.PROCESSING, appearance.visual)
        assertEquals(BubbleLabel.Res(R.string.bubble_state_processing), appearance.label)
        assertTrue(appearance.showsProgress)
        assertFalse(appearance.pulses)
    }

    @Test
    fun `the three trigger states are visually distinct`() {
        val idle = BubbleAppearances.forState(CaptureState.Idle)
        val listening = BubbleAppearances.forState(CaptureState.Listening)
        val processing = BubbleAppearances.forState(CaptureState.Processing)

        assertEquals(3, setOf(idle.accent, listening.accent, processing.accent).size)
        assertNotEquals(idle.label, listening.label)
        assertNotEquals(listening.label, processing.label)
        assertNotEquals(listening.showsProgress, processing.showsProgress)
    }

    @Test
    fun `outcomes get their own accent and short label`() {
        val inserted = BubbleAppearances.forState(CaptureState.Inserted(InsertRoute.ACCESSIBILITY_PASTE))
        val copied = BubbleAppearances.forState(CaptureState.Copied("hello"))
        val needsKey = BubbleAppearances.forState(CaptureState.NeedsKey)

        assertEquals(BubbleLabel.Res(R.string.bubble_state_inserted), inserted.label)
        assertEquals(BubbleAppearances.INSERTED_ACCENT, inserted.accent)

        assertEquals(BubbleLabel.Res(R.string.bubble_state_copied), copied.label)
        assertEquals(BubbleAppearances.COPIED_ACCENT, copied.accent)

        assertEquals(BubbleLabel.Res(R.string.bubble_state_needs_key), needsKey.label)
        assertEquals(BubbleAppearances.NEEDS_KEY_ACCENT, needsKey.accent)
    }

    @Test
    fun `errors carry the provider message`() {
        val appearance = BubbleAppearances.forState(CaptureState.Error("The provider timed out"))

        assertEquals(BubbleVisual.ERROR, appearance.visual)
        assertEquals(BubbleLabel.Text("The provider timed out"), appearance.label)
        assertEquals(BubbleAppearances.ERROR_ACCENT, appearance.accent)
        assertFalse(appearance.pulses)
        assertFalse(appearance.showsProgress)
    }
}
