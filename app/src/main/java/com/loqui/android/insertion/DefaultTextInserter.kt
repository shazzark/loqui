package com.loqui.android.insertion

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Insertion strategy: copy first, then try the accessibility paste into the focused
 * field.
 *
 * Copying first is deliberate — it costs nothing and guarantees that whatever happens
 * next the transcript is on the clipboard. The caller renders [InsertResult.CopiedOnly]
 * as "copied to clipboard", which is a normal outcome, not an error.
 */
@Singleton
class DefaultTextInserter @Inject constructor(
    private val clipboard: ClipboardTextInserter,
    private val accessibility: AccessibilityTextInserter,
) : TextInserter {

    override suspend fun insert(text: String): InsertResult {
        clipboard.copy(text)
        val outcome = accessibility.insert(text)
        return if (outcome.inserted) InsertResult.Pasted else InsertResult.CopiedOnly
    }
}
