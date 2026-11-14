package com.loqui.android.trigger

import android.content.Context
import android.graphics.PixelFormat
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import com.loqui.android.R
import com.loqui.android.capture.CaptureState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import timber.log.Timber

/**
 * Attaches and detaches the floating trigger. This class only draws the overlay and
 * forwards taps — it never records audio.
 */
@Singleton
class BubbleController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var bubbleView: View? = null

    val isShowing: Boolean
        get() = bubbleView != null

    /** @return true when the overlay was attached. */
    fun show(onTap: () -> Unit): Boolean {
        if (bubbleView != null) return true
        val manager = windowManager ?: return false

        val view = LayoutInflater.from(context).inflate(R.layout.view_bubble, null)
        view.setOnClickListener { onTap() }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = INITIAL_X
            y = INITIAL_Y
        }

        return try {
            manager.addView(view, params)
            bubbleView = view
            true
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Could not attach the bubble overlay")
            false
        }
    }

    fun hide() {
        val view = bubbleView ?: return
        bubbleView = null
        runCatching { windowManager?.removeView(view) }
            .onFailure { Timber.w(it, "Could not detach the bubble overlay") }
    }

    /** Lightweight Phase 1 feedback: the bubble dims while Loqui is busy. */
    fun render(state: CaptureState) {
        val view = bubbleView ?: return
        view.alpha = when (state) {
            CaptureState.Idle -> IDLE_ALPHA
            CaptureState.Listening -> LISTENING_ALPHA
            CaptureState.Processing -> BUSY_ALPHA
            is CaptureState.Inserted -> RESULT_ALPHA
            is CaptureState.Copied -> RESULT_ALPHA
            CaptureState.NeedsKey -> ATTENTION_ALPHA
            is CaptureState.Error -> ATTENTION_ALPHA
        }
    }

    private companion object {
        const val INITIAL_X = 24
        const val INITIAL_Y = 480
        const val IDLE_ALPHA = 0.9f
        const val LISTENING_ALPHA = 1f
        const val BUSY_ALPHA = 0.55f
        const val RESULT_ALPHA = 0.9f
        const val ATTENTION_ALPHA = 0.7f
    }
}
