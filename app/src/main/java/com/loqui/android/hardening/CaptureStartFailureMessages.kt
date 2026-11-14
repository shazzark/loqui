package com.loqui.android.hardening

import androidx.annotation.StringRes
import com.loqui.android.R

/**
 * One message per start failure, so the bubble and the in-app button explain a refused
 * start the same way. Android never says *why* in the exception, only the user can fix
 * these, so every branch points at the fix rather than at the platform.
 */
@StringRes
fun captureStartFailureMessage(failure: CaptureStartFailure): Int = when (failure) {
    CaptureStartFailure.MICROPHONE_PERMISSION_MISSING -> R.string.capture_error_mic_permission
    CaptureStartFailure.BACKGROUND_START_BLOCKED -> R.string.capture_error_background_blocked
    CaptureStartFailure.FOREGROUND_PROMOTION_REFUSED -> R.string.capture_error_foreground_denied
    CaptureStartFailure.UNKNOWN -> R.string.capture_error_generic
}
