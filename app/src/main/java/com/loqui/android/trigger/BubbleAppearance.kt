package com.loqui.android.trigger

import androidx.annotation.ColorInt
import androidx.annotation.StringRes
import com.loqui.android.R
import com.loqui.android.capture.CaptureState

/**
 * How the floating trigger paints one [CaptureState]. Plain data so the mapping is
 * unit-testable on the JVM: no `View`, no resources resolved here, no Android runtime.
 */
data class BubbleAppearance(
    val visual: BubbleVisual,
    @ColorInt val accent: Int,
    val label: BubbleLabel,
    val showsProgress: Boolean,
    val pulses: Boolean,
)

/** The distinct looks the bubble can wear. */
enum class BubbleVisual {
    IDLE,
    LISTENING,
    PROCESSING,
    INSERTED,
    COPIED,
    NEEDS_KEY,
    ERROR,
}

/** Text under the bubble; `None` keeps the overlay as a bare disc. */
sealed interface BubbleLabel {
    data object None : BubbleLabel

    data class Res(@StringRes val id: Int) : BubbleLabel

    /** Free-form text, used for provider and permission errors. */
    data class Text(val value: String) : BubbleLabel
}

object BubbleAppearances {

    /** Same purple as the app icon, so the idle trigger reads as Loqui. */
    const val IDLE_ACCENT = 0xFF6D4AFF.toInt()
    const val LISTENING_ACCENT = 0xFFE5484D.toInt()
    const val PROCESSING_ACCENT = 0xFFF59E0B.toInt()
    const val INSERTED_ACCENT = 0xFF16A34A.toInt()
    const val COPIED_ACCENT = 0xFF2563EB.toInt()
    const val NEEDS_KEY_ACCENT = 0xFFB45309.toInt()
    const val ERROR_ACCENT = 0xFF991B1B.toInt()

    fun forState(state: CaptureState): BubbleAppearance = when (state) {
        CaptureState.Idle -> BubbleAppearance(
            visual = BubbleVisual.IDLE,
            accent = IDLE_ACCENT,
            label = BubbleLabel.None,
            showsProgress = false,
            pulses = false,
        )

        CaptureState.Listening -> BubbleAppearance(
            visual = BubbleVisual.LISTENING,
            accent = LISTENING_ACCENT,
            label = BubbleLabel.Res(R.string.bubble_state_listening),
            showsProgress = false,
            pulses = true,
        )

        CaptureState.Processing -> BubbleAppearance(
            visual = BubbleVisual.PROCESSING,
            accent = PROCESSING_ACCENT,
            label = BubbleLabel.Res(R.string.bubble_state_processing),
            showsProgress = true,
            pulses = false,
        )

        is CaptureState.Inserted -> BubbleAppearance(
            visual = BubbleVisual.INSERTED,
            accent = INSERTED_ACCENT,
            label = BubbleLabel.Res(R.string.bubble_state_inserted),
            showsProgress = false,
            pulses = false,
        )

        is CaptureState.Copied -> BubbleAppearance(
            visual = BubbleVisual.COPIED,
            accent = COPIED_ACCENT,
            label = BubbleLabel.Res(R.string.bubble_state_copied),
            showsProgress = false,
            pulses = false,
        )

        CaptureState.NeedsKey -> BubbleAppearance(
            visual = BubbleVisual.NEEDS_KEY,
            accent = NEEDS_KEY_ACCENT,
            label = BubbleLabel.Res(R.string.bubble_state_needs_key),
            showsProgress = false,
            pulses = false,
        )

        is CaptureState.Error -> BubbleAppearance(
            visual = BubbleVisual.ERROR,
            accent = ERROR_ACCENT,
            label = BubbleLabel.Text(state.message),
            showsProgress = false,
            pulses = false,
        )
    }
}
