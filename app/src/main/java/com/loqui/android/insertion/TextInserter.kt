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
    /** `ACTION_PASTE` or `ACTION_SET_TEXT` landed in the focused field. */
    data object Pasted : InsertResult

    /** Text is on the clipboard; no field was focused, or insertion was refused. */
    data object CopiedOnly : InsertResult

    /** Nothing could be done at all. Not expected in practice. */
    data object Unavailable : InsertResult
}

interface TextInserter {
    /** Must never throw for expected conditions; every outcome is an [InsertResult]. */
    suspend fun insert(text: String): InsertResult
}
