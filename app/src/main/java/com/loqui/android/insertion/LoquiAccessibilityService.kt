package com.loqui.android.insertion

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import timber.log.Timber

/**
 * Insertion-only accessibility service.
 *
 * It performs exactly one job: put the transcript the user just dictated into the
 * text field they have focused. `ACTION_PASTE` is preferred because it behaves like a
 * real paste (IME history, undo). Fields that refuse a paste get `ACTION_SET_TEXT`
 * with `ACTION_SET_SELECTION` to keep the caret after the inserted text.
 *
 * The service never reads, collects, or transmits screen content.
 */
class LoquiAccessibilityService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Timber.i("Accessibility insertion connected")
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
     * Inserts [text] into the focused editable field. The clipboard is already primed
     * by [ClipboardTextInserter], so the paste route needs no extra state.
     */
    fun insertIntoFocusedField(text: String): AccessibilityInsertOutcome {
        if (text.isEmpty()) return AccessibilityInsertOutcome.NOTHING_TO_INSERT

        val target = findEditableTarget() ?: return AccessibilityInsertOutcome.NO_EDITABLE_FIELD
        return try {
            target.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            when {
                paste(target) -> AccessibilityInsertOutcome.PASTED
                setText(target, text) -> AccessibilityInsertOutcome.TYPED
                else -> AccessibilityInsertOutcome.FAILED
            }
        } catch (throwable: Throwable) {
            Timber.w(throwable, "Accessibility insertion failed")
            AccessibilityInsertOutcome.FAILED
        } finally {
            recycleQuietly(target)
        }
    }

    private fun findEditableTarget(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null) {
            if (focused.isInsertionTarget) return focused
            recycleQuietly(focused)
        }
        return firstInsertionTarget(root)
    }

    /**
     * Bounded breadth-first walk. Some apps (notably web views and chat composers)
     * expose an editable node without ever reporting it as the focused one.
     */
    private fun firstInsertionTarget(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.addLast(root)
        var visited = 0

        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (node !== root && node.isInsertionTarget) return node

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let(queue::addLast)
            }
        }
        return null
    }

    private val AccessibilityNodeInfo.isInsertionTarget: Boolean
        get() = isVisibleToUser &&
            (isEditable || actionList.any { it.id == AccessibilityNodeInfo.ACTION_SET_TEXT })

    private fun paste(node: AccessibilityNodeInfo): Boolean =
        node.performAction(AccessibilityNodeInfo.ACTION_PASTE)

    private fun setText(node: AccessibilityNodeInfo, text: String): Boolean {
        val plan = planInsertion(node.text, node.textSelectionStart, node.textSelectionEnd, text)
        val arguments = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, plan.text)
        }
        if (!node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)) return false

        // Best effort: leave the caret after the transcript instead of at position 0.
        node.performAction(
            AccessibilityNodeInfo.ACTION_SET_SELECTION,
            Bundle().apply {
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, plan.cursor)
                putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, plan.cursor)
            },
        )
        return true
    }

    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        @Suppress("DEPRECATION")
        node.recycle()
    }

    companion object {
        @Volatile
        var instance: LoquiAccessibilityService? = null
            private set

        /** Depth guard: walking a whole browser tree would be slow and pointless. */
        private const val MAX_NODES = 80
    }
}
