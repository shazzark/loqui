package com.loqui.android.trigger

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import com.loqui.android.R
import com.loqui.android.capture.CaptureState
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import timber.log.Timber

/**
 * Attaches and detaches the floating trigger. This class only draws the overlay,
 * moves it, and forwards taps — it never records audio.
 *
 * The look comes from [BubbleAppearances] so the state machine stays testable off
 * the device; this file is the only place in the trigger package that touches `View`.
 */
@Singleton
class BubbleController @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val windowManager: WindowManager? =
        context.getSystemService(WindowManager::class.java)

    private var bubbleView: View? = null
    private var disc: View? = null
    private var discBackground: GradientDrawable? = null
    private var icon: View? = null
    private var progress: View? = null
    private var label: TextView? = null
    private var pulse: ObjectAnimator? = null

    val isShowing: Boolean
        get() = bubbleView != null

    /** @return true when the overlay was attached. */
    fun show(onTap: () -> Unit): Boolean {
        if (bubbleView != null) return true
        val manager = windowManager ?: return false

        val view = LayoutInflater.from(context).inflate(R.layout.view_bubble, null)
        val background = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(BubbleAppearances.IDLE_ACCENT)
            setStroke(OVERLAY_RING_PX, OVERLAY_RING_COLOR)
        }

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
            view.findViewById<View>(R.id.bubble_disc).background = background
            view.setOnClickListener { onTap() }
            attachDrag(view, params)

            manager.addView(view, params)

            bubbleView = view
            disc = view.findViewById(R.id.bubble_disc)
            discBackground = background
            icon = view.findViewById(R.id.bubble_icon)
            progress = view.findViewById(R.id.bubble_progress)
            label = view.findViewById(R.id.bubble_label)
            render(CaptureState.Idle)
            true
        } catch (throwable: Throwable) {
            Timber.e(throwable, "Could not attach the bubble overlay")
            false
        }
    }

    fun hide() {
        val view = bubbleView ?: return
        bubbleView = null
        stopPulse()
        runCatching { windowManager?.removeView(view) }
            .onFailure { Timber.w(it, "Could not detach the bubble overlay") }
        disc = null
        discBackground = null
        icon = null
        progress = null
        label = null
    }

    /** Paints the bubble for a capture state. Safe to call while hidden. */
    fun render(state: CaptureState) = render(BubbleAppearances.forState(state))

    fun render(appearance: BubbleAppearance) {
        val view = bubbleView ?: return

        discBackground?.setColor(appearance.accent)
        icon?.visibility = if (appearance.showsProgress) View.GONE else View.VISIBLE
        progress?.visibility = if (appearance.showsProgress) View.VISIBLE else View.GONE

        val text = when (val bubbleLabel = appearance.label) {
            BubbleLabel.None -> null
            is BubbleLabel.Res -> context.getString(bubbleLabel.id)
            is BubbleLabel.Text -> bubbleLabel.value
        }
        label?.text = text
        label?.visibility = if (text == null) View.GONE else View.VISIBLE
        view.contentDescription = text ?: context.getString(R.string.bubble_content_description)

        updatePulse(appearance.pulses)
    }

    /**
     * Distinguishes a tap from a drag: the trigger follows the finger, and a release
     * after a real drag never fires the click listener.
     *
     * `performClick()` is not called from the touch handler on purpose — the click
     * listener set by [show] handles real taps, and a consumed drag must not trigger it.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachDrag(view: View, params: WindowManager.LayoutParams) {
        val slop = ViewConfiguration.get(context).scaledTouchSlop
        var downRawX = 0f
        var downRawY = 0f
        var originX = 0
        var originY = 0
        var dragging = false

        view.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    originX = params.x
                    originY = params.y
                    dragging = false
                    false
                }

                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downRawX
                    val dy = event.rawY - downRawY
                    if (!dragging && (abs(dx) > slop || abs(dy) > slop)) {
                        dragging = true
                    }
                    if (dragging) {
                        params.x = originX + dx.toInt()
                        params.y = originY + dy.toInt()
                        runCatching { windowManager?.updateViewLayout(view, params) }
                            .onFailure { Timber.w(it, "Could not move the bubble overlay") }
                    }
                    dragging
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging

                else -> false
            }
        }
    }

    private fun updatePulse(shouldPulse: Boolean) {
        if (!shouldPulse) {
            stopPulse()
            return
        }
        if (pulse?.isRunning == true) return

        val target = disc ?: return
        pulse = ObjectAnimator.ofPropertyValuesHolder(
            target,
            PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, PULSE_SCALE),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, PULSE_SCALE),
        ).apply {
            duration = PULSE_DURATION_MS
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        disc?.scaleX = 1f
        disc?.scaleY = 1f
    }

    private companion object {
        const val INITIAL_X = 24
        const val INITIAL_Y = 480
        const val OVERLAY_RING_PX = 3
        const val OVERLAY_RING_COLOR = 0x33FFFFFF
        const val PULSE_SCALE = 1.12f
        const val PULSE_DURATION_MS = 550L
    }
}
