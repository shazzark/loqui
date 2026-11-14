package com.loqui.android.insertion

/**
 * How the transcript reached the focused field. Both routes are normal; the
 * clipboard route is a fallback, not an error.
 */
enum class InsertRoute {
    ACCESSIBILITY_PASTE,
    CLIPBOARD_FALLBACK,
}

sealed interface InsertResult {
    data object Pasted : InsertResult

    /** Text is on the clipboard; the user pastes it themselves. */
    data object CopiedOnly : InsertResult

    data object Unavailable : InsertResult
}

interface TextInserter {
    suspend fun insert(text: String): InsertResult
}
