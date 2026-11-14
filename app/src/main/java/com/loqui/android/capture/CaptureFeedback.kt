package com.loqui.android.capture

import android.content.Context
import android.os.SystemClock
import android.widget.Toast
import com.loqui.android.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * The one place that turns a capture outcome into a Toast, so the bubble and the mic
 * service can both report the same result without showing it twice.
 *
 * The clipboard fallback is the message that matters most here: when no field is
 * focused the transcript is already on the clipboard and the user needs to know that
 * before they paste.
 */
@Singleton
class CaptureFeedback @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val gate = MessageGate()

    fun report(state: CaptureState) {
        val message = messageFor(state) ?: return
        if (!gate.allow(message, SystemClock.elapsedRealtime())) return

        runCatching { Toast.makeText(context, message, Toast.LENGTH_SHORT).show() }
            .onFailure { Timber.w(it, "Could not show the capture toast") }
    }

    private fun messageFor(state: CaptureState): String? = when (state) {
        CaptureState.Idle, CaptureState.Listening, CaptureState.Processing -> null
        is CaptureState.Inserted -> context.getString(R.string.state_inserted)
        is CaptureState.Copied -> context.getString(R.string.state_copied_toast)
        CaptureState.NeedsKey -> context.getString(R.string.state_needs_key)
        is CaptureState.Error -> state.message
    }
}
