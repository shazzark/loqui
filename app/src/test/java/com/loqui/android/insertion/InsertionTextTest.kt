package com.loqui.android.insertion

import org.junit.Assert.assertEquals
import org.junit.Test

class InsertionTextTest {

    @Test
    fun `inserts into an empty field and leaves the caret after the text`() {
        val plan = planInsertion(existing = null, selectionStart = 0, selectionEnd = 0, insert = "hello")

        assertEquals("hello", plan.text)
        assertEquals(5, plan.cursor)
    }

    @Test
    fun `inserts at the caret without dropping the existing text`() {
        val plan = planInsertion(
            existing = "hello world",
            selectionStart = 5,
            selectionEnd = 5,
            insert = " brave",
        )

        assertEquals("hello brave world", plan.text)
        assertEquals(11, plan.cursor)
    }

    @Test
    fun `replaces the current selection`() {
        val plan = planInsertion(
            existing = "hello world",
            selectionStart = 6,
            selectionEnd = 11,
            insert = "there",
        )

        assertEquals("hello there", plan.text)
        assertEquals(11, plan.cursor)
    }

    @Test
    fun `appends when the caret position is unknown`() {
        val plan = planInsertion(
            existing = "hello",
            selectionStart = -1,
            selectionEnd = -1,
            insert = " world",
        )

        assertEquals("hello world", plan.text)
        assertEquals(11, plan.cursor)
    }

    @Test
    fun `clamps a selection that runs past the end of the field`() {
        val plan = planInsertion(
            existing = "hello",
            selectionStart = 2,
            selectionEnd = 99,
            insert = "!",
        )

        assertEquals("he!llo", plan.text)
        assertEquals(3, plan.cursor)
    }

    @Test
    fun `an inverted selection collapses to the start`() {
        val plan = planInsertion(
            existing = "hello",
            selectionStart = 4,
            selectionEnd = 1,
            insert = "y",
        )

        assertEquals("hellyo", plan.text)
        assertEquals(5, plan.cursor)
    }
}
