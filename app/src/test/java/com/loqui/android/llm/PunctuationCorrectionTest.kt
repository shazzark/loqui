package com.loqui.android.llm

import org.junit.Assert.assertEquals
import org.junit.Test

class PunctuationCorrectionTest {

    @Test
    fun `a lowercase transcript gets a capital and a period`() {
        assertEquals(
            "This is a loqui test transcript.",
            PunctuationCorrection.apply("this is a loqui test transcript"),
        )
    }

    @Test
    fun `text that already ends in punctuation is not double terminated`() {
        assertEquals("Already done.", PunctuationCorrection.apply("already done."))
        assertEquals("Is it ready?", PunctuationCorrection.apply("is it ready?"))
        assertEquals("Stop!", PunctuationCorrection.apply("stop!"))
    }

    @Test
    fun `a trailing comma becomes a period instead of comma period`() {
        assertEquals("Yes.", PunctuationCorrection.apply("yes,"))
        assertEquals("First.", PunctuationCorrection.apply("first;"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("Hello there.", PunctuationCorrection.apply("  hello there  "))
    }

    @Test
    fun `a transcript that starts with a number keeps its first character`() {
        assertEquals("24 hours later.", PunctuationCorrection.apply("24 hours later"))
    }

    @Test
    fun `already capitalised text keeps its capital`() {
        assertEquals("HELLO.", PunctuationCorrection.apply("HELLO"))
    }

    @Test
    fun `an ellipsis counts as a terminal mark`() {
        assertEquals("Well…", PunctuationCorrection.apply("well…"))
    }

    @Test
    fun `blank input is returned unchanged rather than turned into a period`() {
        assertEquals("", PunctuationCorrection.apply(""))
        assertEquals("   ", PunctuationCorrection.apply("   "))
    }
}
