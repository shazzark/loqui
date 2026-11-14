package com.loqui.android.llm

/**
 * Deterministic stand-in for an on-device model: capitalise the first letter and end
 * the transcript with a period.
 *
 * Pure on purpose — it lets the correction step of the pipeline be unit-tested with no
 * model, no key and no device, exactly like `InsertionText` does for insertion.
 */
object PunctuationCorrection {

    /**
     * @return the transcript with an initial capital and a terminal period, or the
     * input unchanged when there is nothing to correct (blank text).
     */
    fun apply(transcript: String): String {
        val text = transcript.trim()
        if (text.isEmpty()) return transcript

        val corrected = StringBuilder(text)

        // Capitalise only when the text starts with a letter: "24 hours" and
        // ""quoted"" keep their first character.
        val firstVisible = corrected.indexOfFirst { !it.isWhitespace() }
        if (firstVisible >= 0 && corrected[firstVisible].isLetter()) {
            corrected.setCharAt(firstVisible, corrected[firstVisible].uppercaseChar())
        }

        val lastVisible = corrected.indexOfLast { !it.isWhitespace() }
        if (lastVisible < 0) return transcript

        when (corrected[lastVisible]) {
            '.', '!', '?', '…' -> Unit
            ',', ';', ':' -> corrected.setCharAt(lastVisible, '.')
            else -> corrected.append('.')
        }

        return corrected.toString()
    }
}
