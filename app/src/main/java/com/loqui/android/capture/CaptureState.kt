package com.loqui.android.capture

import com.loqui.android.insertion.InsertRoute

/** Everything the bubble and the app UI need to render a dictation attempt. */
sealed interface CaptureState {
    data object Idle : CaptureState

    data object Listening : CaptureState

    data object Processing : CaptureState

    data class Inserted(val route: InsertRoute) : CaptureState

    /** No editable field: the text is on the clipboard, waiting to be pasted. */
    data class Copied(val text: String) : CaptureState

    /** Cloud ASR selected with no user key — a first-class state, not an error. */
    data object NeedsKey : CaptureState

    data class Error(val message: String) : CaptureState
}
