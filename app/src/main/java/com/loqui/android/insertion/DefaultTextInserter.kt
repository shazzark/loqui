package com.loqui.android.insertion

import javax.inject.Inject
import javax.inject.Singleton

/**
 * Insertion strategy: copy first (guaranteed fallback), then try to paste into the
 * focused field through the accessibility service.
 */
@Singleton
class DefaultTextInserter @Inject constructor(
    private val clipboard: ClipboardTextInserter,
    private val accessibility: AccessibilityTextInserter,
) : TextInserter {

    override suspend fun insert(text: String): InsertResult {
        clipboard.copy(text)
        val pasted = accessibility.paste()
        return if (pasted) InsertResult.Pasted else InsertResult.CopiedOnly
    }
}
