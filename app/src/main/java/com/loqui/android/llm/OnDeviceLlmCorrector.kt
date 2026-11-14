package com.loqui.android.llm

import com.loqui.android.settings.LlmMode

/**
 * Default corrector: punctuation and an initial capital, computed on the device.
 *
 * No model, no network, no key — Loqui keeps working with zero credentials, which is
 * the project's first non-negotiable. A real on-device language model replaces the body
 * behind the same [LlmCorrector] interface and inherits the coordinator's budget; the
 * pipeline does not change.
 */
class OnDeviceLlmCorrector : LlmCorrector {

    override val mode: LlmMode = LlmMode.ON_DEVICE

    override fun availability(): LlmAvailability = LlmAvailability.Ready

    override suspend fun correct(text: String): CorrectionResult =
        CorrectionResult.Corrected(PunctuationCorrection.apply(text))
}
