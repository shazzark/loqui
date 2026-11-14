package com.loqui.android.llm

import com.loqui.android.settings.LlmMode
import com.loqui.android.settings.SettingsRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber

/**
 * Text the pipeline should insert, plus why it is what it is.
 *
 * @property text the corrected text, or the raw transcript when correction was skipped.
 * @property corrected true when [text] came from the corrector rather than the recognizer.
 */
data class CorrectionOutcome(
    val text: String,
    val corrected: Boolean,
    val skipped: CorrectionSkip? = null,
)

/** Why the raw transcript was inserted unchanged. */
enum class CorrectionSkip {
    /** `llm.enabled` is off. */
    DISABLED,

    /** Cloud clean-up selected and no user key is stored. */
    MISSING_KEY,

    /** The corrector reported a provider failure. */
    UNAVAILABLE,

    /** The attempt exceeded the latency budget. */
    TIMED_OUT,

    /** The corrector answered with nothing usable, so the raw transcript was kept. */
    EMPTY_RESULT,

    /** There was nothing to correct. */
    BLANK_TRANSCRIPT,
}

/**
 * Settings-aware entry point for the optional clean-up pass, sitting between the final
 * transcript and insertion.
 *
 * **Latency is a hard rule, not a target.** Correction may add at most [budgetMs] to a
 * dictation; past that the corrector is cancelled and the raw transcript goes to the
 * field. Disabled, unconfigured, failing or slow all end the same way: the user's own
 * words, immediately. Correction is optional, dictation is not.
 */
@Singleton
class LlmCoordinator @Inject constructor(
    private val settingsRepository: SettingsRepository,
    private val providerFactory: LlmProviderFactory,
) {

    suspend fun correctIfEnabled(
        transcript: String,
        budgetMs: Long = CORRECTION_BUDGET_MS,
    ): CorrectionOutcome {
        if (transcript.isBlank()) return fallback(transcript, CorrectionSkip.BLANK_TRANSCRIPT)

        val settings = settingsRepository.settings.first()
        if (!settings.llmEnabled) return fallback(transcript, CorrectionSkip.DISABLED)

        val corrector = providerFor(settings.llmMode)
        if (corrector.availability() == LlmAvailability.MissingKey) {
            return fallback(transcript, CorrectionSkip.MISSING_KEY)
        }

        // The budget wraps the whole attempt, so no corrector implementation can
        // stretch the dictation past it. Cancellation is the enforcement mechanism.
        val result = withTimeoutOrNull(budgetMs) { corrector.correct(transcript) }

        return when (result) {
            null -> {
                Timber.w("Clean-up exceeded the %d ms budget; inserting the raw transcript", budgetMs)
                fallback(transcript, CorrectionSkip.TIMED_OUT)
            }

            CorrectionResult.TimedOut -> fallback(transcript, CorrectionSkip.TIMED_OUT)

            is CorrectionResult.Unavailable -> fallback(transcript, CorrectionSkip.UNAVAILABLE)

            is CorrectionResult.Corrected -> {
                val corrected = result.text.trim()
                if (corrected.isEmpty()) {
                    // Never let a model delete what the user said.
                    Timber.w("Clean-up answered with nothing; inserting the raw transcript")
                    fallback(transcript, CorrectionSkip.EMPTY_RESULT)
                } else {
                    CorrectionOutcome(text = corrected, corrected = true)
                }
            }
        }
    }

    private fun fallback(transcript: String, reason: CorrectionSkip): CorrectionOutcome =
        CorrectionOutcome(text = transcript, corrected = false, skipped = reason)

    private fun providerFor(mode: LlmMode): LlmCorrector = when (mode) {
        LlmMode.ON_DEVICE -> providerFactory.onDevice()
        LlmMode.CLOUD -> providerFactory.cloud()
    }

    companion object {
        /** Clean-up may add at most one second to a dictation. Phase 4 exit criterion. */
        const val CORRECTION_BUDGET_MS = 1_000L
    }
}
