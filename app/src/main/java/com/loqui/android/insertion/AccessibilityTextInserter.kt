package com.loqui.android.insertion

import javax.inject.Inject
import javax.inject.Singleton

/** Thin wrapper so callers do not hold the service reference directly. */
@Singleton
class AccessibilityTextInserter @Inject constructor() {
    fun paste(): Boolean = LoquiAccessibilityService.instance?.pasteIntoFocusedField() == true
}
