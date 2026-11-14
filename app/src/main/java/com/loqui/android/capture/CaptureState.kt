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

/** What a tap on a trigger (bubble or in-app button) should do next. */
enum class DictationTap {
    START,
    STOP,
}

/**
 * One toggle rule for every trigger: a tap while Loqui is listening or working stops
 * the session, a tap anywhere else starts a new one. Pure, so both the overlay and the
 * app UI share it and the rule is tested without a device.
 */
fun dictationTapFor(state: CaptureState): DictationTap = when (state) {
    CaptureState.Listening, CaptureState.Processing -> DictationTap.STOP
    else -> DictationTap.START
}

