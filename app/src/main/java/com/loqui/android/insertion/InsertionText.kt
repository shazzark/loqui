package com.loqui.android.insertion

/** Text plus caret position after a `setText`-based insertion. */
data class TextInsertion(val text: String, val cursor: Int)

/**
 * Computes the field contents for the `ACTION_SET_TEXT` path (the fallback used when
 * `ACTION_PASTE` is refused by the target app).
 *
 * Accessibility reports `-1` when it cannot tell where the caret is; in that case the
 * transcript is appended, which is the least surprising behaviour for dictation.
 */
fun planInsertion(
    existing: CharSequence?,
    selectionStart: Int,
    selectionEnd: Int,
    insert: String,
): TextInsertion {
    val base = existing?.toString().orEmpty()
    val length = base.length
    val start = if (selectionStart in 0..length) selectionStart else length
    val end = if (selectionEnd in start..length) selectionEnd else start

    val merged = base.substring(0, start) + insert + base.substring(end)
    return TextInsertion(text = merged, cursor = start + insert.length)
}
