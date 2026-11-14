package com.loqui.android.capture

import com.loqui.android.insertion.InsertRoute
import org.junit.Assert.assertEquals
import org.junit.Test

class DictationTapTest {

    @Test
    fun `a tap starts a session when nothing is running`() {
        assertEquals(DictationTap.START, dictationTapFor(CaptureState.Idle))
    }

    @Test
    fun `a tap after an outcome starts a new session`() {
        assertEquals(DictationTap.START, dictationTapFor(CaptureState.Inserted(InsertRoute.ACCESSIBILITY_PASTE)))
        assertEquals(DictationTap.START, dictationTapFor(CaptureState.Copied("text")))
        assertEquals(DictationTap.START, dictationTapFor(CaptureState.NeedsKey))
        assertEquals(DictationTap.START, dictationTapFor(CaptureState.Error("boom")))
    }

    @Test
    fun `a tap while capturing stops the session`() {
        assertEquals(DictationTap.STOP, dictationTapFor(CaptureState.Listening))
        assertEquals(DictationTap.STOP, dictationTapFor(CaptureState.Processing))
    }
}
