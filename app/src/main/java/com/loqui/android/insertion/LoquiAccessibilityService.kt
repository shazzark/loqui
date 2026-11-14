package com.loqui.android.insertion

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Phase 1 stub. It declares the service (so onboarding can send the user to the
 * accessibility settings) and performs a single ACTION_PASTE into the focused
 * editable node. It never reads, collects, or transmits screen content.
 */
class LoquiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.d("Accessibility insertion connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        super.onDestroy()
    }

    /**
     * Pastes the current clipboard into the focused editable node.
     * Returns false when no editable field is focused — the caller then keeps the
     * clipboard fallback state.
     */
    fun pasteIntoFocusedField(): Boolean {
        val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: return false

        return try {
            if (!focused.isEditable) {
                false
            } else {
                focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        } finally {
            @Suppress("DEPRECATION")
            focused.recycle()
        }
    }

    companion object {
        @Volatile
        var instance: LoquiAccessibilityService? = null
            private set
    }
}
