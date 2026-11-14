package com.loqui.android.insertion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class DefaultTextInserterTest {

    private val clipboard = mockk<ClipboardTextInserter>(relaxed = true)
    private val accessibility = mockk<AccessibilityTextInserter>(relaxed = true)
    private val inserter = DefaultTextInserter(clipboard = clipboard, accessibility = accessibility)

    private val transcript = "This is a Loqui test transcript."

    @Test
    fun `copies before trying to paste, so the fallback is always ready`() = runTest {
        every { accessibility.insert(transcript) } returns AccessibilityInsertOutcome.PASTED

        val result = inserter.insert(transcript)

        assertEquals(InsertResult.Pasted, result)
        verifyOrder {
            clipboard.copy(transcript)
            accessibility.insert(transcript)
        }
    }

    @Test
    fun `a setText fallback also counts as inserted`() = runTest {
        every { accessibility.insert(transcript) } returns AccessibilityInsertOutcome.TYPED

        assertEquals(InsertResult.Pasted, inserter.insert(transcript))
        verify { clipboard.copy(transcript) }
    }

    @Test
    fun `no focused field leaves the transcript on the clipboard`() = runTest {
        every { accessibility.insert(transcript) } returns AccessibilityInsertOutcome.NO_EDITABLE_FIELD

        assertEquals(InsertResult.CopiedOnly, inserter.insert(transcript))
        verify { clipboard.copy(transcript) }
    }

    @Test
    fun `an accessibility service that is switched off falls back to the clipboard`() = runTest {
        every { accessibility.insert(transcript) } returns AccessibilityInsertOutcome.SERVICE_DISABLED

        assertEquals(InsertResult.CopiedOnly, inserter.insert(transcript))
        verify { clipboard.copy(transcript) }
    }

    @Test
    fun `a refused insertion still leaves the transcript on the clipboard`() = runTest {
        every { accessibility.insert(transcript) } returns AccessibilityInsertOutcome.FAILED

        assertEquals(InsertResult.CopiedOnly, inserter.insert(transcript))
        verify { clipboard.copy(transcript) }
    }
}
