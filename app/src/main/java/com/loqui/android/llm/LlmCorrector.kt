package com.loqui.android.llm

import com.loqui.android.settings.LlmMode

/** Why a correction was not used. An outcome, never an exception. */
enum class LlmFailure {
    /** Cloud clean-up selected and the user has not supplied a key yet. */
    MISSING_KEY,

    /** The provider rejected the request, or returned nothing usable. */
    PROVIDER_ERROR,
}

/** Whether a corrector can run right now, resolved without throwing. */
sealed interface LlmAvailability {
    data object Ready : LlmAvailability

    /** Cloud clean-up selected but no user key is stored: the "add your key" state. */
    data object MissingKey : LlmAvailability
}

/** Outcome of one correction attempt, in the terms the pipeline can act on. */
sealed interface CorrectionResult {
    /** The corrector produced the text that should be inserted instead of the transcript. */
    data class Corrected(val text: String) : CorrectionResult

    /**
     * No correction available (no key, provider rejection, ...). The caller inserts the
     * raw transcript: clean-up is optional, dictation is not.
     */
    data class Unavailable(val reason: LlmFailure) : CorrectionResult

    /** The attempt did not finish inside the latency budget: insert the raw transcript. */
    data object TimedOut : CorrectionResult
}

/**
 * Optional clean-up pass between recognition and insertion.
 *
 * Contract:
 *
 * 1. [correct] must be cancellable — the pipeline enforces its budget by cancelling.
 * 2. It never throws for expected conditions (no key, provider rejection): those are
 *    [CorrectionResult.Unavailable]. A thrown exception is a bug, not a signal.
 * 3. It never decides the transcript is "empty" — the caller validates the answer and
 *    falls back to the raw text, because inserting nothing would silently drop what
 *    the user said.
 * 4. It must not block the caller's dispatcher: do CPU/IO work on an injected
 *    dispatcher and suspend, so the pipeline's timer can actually cancel it.
 */
interface LlmCorrector {
    val mode: LlmMode

    fun availability(): LlmAvailability

    suspend fun correct(text: String): CorrectionResult
}
