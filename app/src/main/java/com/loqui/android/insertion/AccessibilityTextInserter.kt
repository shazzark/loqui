package com.loqui.android.insertion

import javax.inject.Inject
import javax.inject.Singleton

/** What the accessibility route managed to do with the transcript. */
enum class AccessibilityInsertOutcome {
    /** `ACTION_PASTE` landed in the focused field. */
    PASTED,

    /** Paste was refused, so the field was rewritten with `ACTION_SET_TEXT`. */
    TYPED,

    /** A field exists but refuses both routes. */
    FAILED,

    /** Nothing editable has focus (launcher, image viewer, browser chrome…). */
    NO_EDITABLE_FIELD,

    /** The user has not enabled Loqui's accessibility service. */
    SERVICE_DISABLED,

    /** Nothing to insert. */
    NOTHING_TO_INSERT,
    ;

    /** True when the transcript really reached a text field. */
    val inserted: Boolean
        get() = this == PASTED || this == TYPED
}

/** Thin wrapper so callers do not hold the service reference directly. */
@Singleton
class AccessibilityTextInserter @Inject constructor() {

    /** True when the user has switched the insertion service on in system settings. */
    fun isConnected(): Boolean = LoquiAccessibilityService.instance != null

    fun insert(text: String): AccessibilityInsertOutcome =
        LoquiAccessibilityService.instance?.insertIntoFocusedField(text)
            ?: AccessibilityInsertOutcome.SERVICE_DISABLED
}
