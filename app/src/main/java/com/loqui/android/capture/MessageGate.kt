package com.loqui.android.capture

/**
 * Suppresses a repeat of the same message inside a short window.
 *
 * Both the bubble and the mic service learn about an outcome at the same moment, so
 * without this gate the user would see every toast twice. Pure and clock-free so the
 * rule is unit-testable.
 */
class MessageGate(
    private val windowMs: Long = DEFAULT_WINDOW_MS,
) {

    private var lastMessage: String? = null
    private var lastShownAtMs: Long? = null

    fun allow(message: String, nowMs: Long): Boolean {
        val shownAt = lastShownAtMs
        if (message == lastMessage && shownAt != null && nowMs - shownAt < windowMs) {
            return false
        }
        lastMessage = message
        lastShownAtMs = nowMs
        return true
    }

    fun reset() {
        lastMessage = null
        lastShownAtMs = null
    }

    companion object {
        const val DEFAULT_WINDOW_MS = 1_500L
    }
}
