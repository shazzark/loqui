package com.loqui.android.llm

import com.loqui.android.settings.LlmMode
import com.loqui.android.settings.LoquiSettings
import com.loqui.android.settings.ProviderIds
import com.loqui.android.testing.FakeSettingsRepository
import com.loqui.android.testing.InMemorySecretStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LlmCoordinatorTest {

    private val settings = FakeSettingsRepository(LoquiSettings(llmEnabled = true))
    private val secretStore = InMemorySecretStore()

    private val coordinator = LlmCoordinator(
        settingsRepository = settings,
        providerFactory = DefaultLlmProviderFactory(secretStore),
    )

    @Test
    fun `switched off inserts the raw transcript and never builds a corrector`() = runTest {
        settings.setLlmEnabled(false)
        val factory = FixedCorrectorFactory(ConstCorrector("unused"))

        val outcome = LlmCoordinator(settings, factory).correctIfEnabled(RAW)

        assertEquals(RAW, outcome.text)
        assertFalse(outcome.corrected)
        assertEquals(CorrectionSkip.DISABLED, outcome.skipped)
        assertEquals(0, factory.selections)
    }

    @Test
    fun `on-device clean-up fixes punctuation without a key`() = runTest {
        val outcome = coordinator.correctIfEnabled("hello from loqui")

        assertEquals("Hello from loqui.", outcome.text)
        assertTrue(outcome.corrected)
        assertEquals(null, outcome.skipped)
    }

    @Test
    fun `cloud clean-up without a key falls back to the raw transcript`() = runTest {
        settings.setLlmMode(LlmMode.CLOUD)

        val outcome = coordinator.correctIfEnabled(RAW)

        assertEquals(RAW, outcome.text)
        assertFalse(outcome.corrected)
        assertEquals(CorrectionSkip.MISSING_KEY, outcome.skipped)
    }

    @Test
    fun `cloud clean-up with a user key uses the model answer`() = runTest {
        settings.setLlmMode(LlmMode.CLOUD)
        secretStore.writeKey(ProviderIds.CLOUD_LLM, "user-supplied-key")

        val outcome = coordinator.correctIfEnabled("this is a loqui test transcript")

        assertEquals("This is a loqui test transcript.", outcome.text)
        assertTrue(outcome.corrected)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun `a slow corrector is cut off at the budget and the raw text is inserted`() = runTest {
        val slow = LlmCoordinator(
            settingsRepository = settings,
            providerFactory = FixedCorrectorFactory(SlowCorrector(delayMs = 30_000)),
        )

        val outcome = slow.correctIfEnabled(RAW)

        assertEquals(RAW, outcome.text)
        assertFalse(outcome.corrected)
        assertEquals(CorrectionSkip.TIMED_OUT, outcome.skipped)
        // The hard guarantee: the pass added the budget, not the corrector's own time.
        assertEquals(LlmCoordinator.CORRECTION_BUDGET_MS, testScheduler.currentTime)
    }

    @Test
    fun `the correction budget is one second`() {
        assertEquals(1_000L, LlmCoordinator.CORRECTION_BUDGET_MS)
    }

    @Test
    fun `a corrector failure falls back to the raw transcript`() = runTest {
        val failing = LlmCoordinator(
            settingsRepository = settings,
            providerFactory = FixedCorrectorFactory(UnavailableCorrector),
        )

        val outcome = failing.correctIfEnabled(RAW)

        assertEquals(RAW, outcome.text)
        assertFalse(outcome.corrected)
        assertEquals(CorrectionSkip.UNAVAILABLE, outcome.skipped)
    }

    @Test
    fun `an empty model answer never deletes what the user said`() = runTest {
        val empty = LlmCoordinator(
            settingsRepository = settings,
            providerFactory = FixedCorrectorFactory(ConstCorrector("   ")),
        )

        val outcome = empty.correctIfEnabled(RAW)

        assertEquals(RAW, outcome.text)
        assertFalse(outcome.corrected)
        assertEquals(CorrectionSkip.EMPTY_RESULT, outcome.skipped)
    }

    @Test
    fun `a blank transcript is never sent to a model`() = runTest {
        val outcome = coordinator.correctIfEnabled("  ")

        assertEquals("  ", outcome.text)
        assertEquals(CorrectionSkip.BLANK_TRANSCRIPT, outcome.skipped)
    }

    private companion object {
        const val RAW = "this is a loqui test transcript"
    }
}

/** Factory that always hands back the same corrector, so tests can script behaviour. */
private class FixedCorrectorFactory(private val corrector: LlmCorrector) : LlmProviderFactory {

    var selections: Int = 0
        private set

    override fun onDevice(): LlmCorrector {
        selections++
        return corrector
    }

    override fun cloud(): LlmCorrector {
        selections++
        return corrector
    }
}

/** A corrector that is never fast enough; proves the coordinator's budget. */
private class SlowCorrector(private val delayMs: Long) : LlmCorrector {

    override val mode: LlmMode = LlmMode.ON_DEVICE

    override fun availability(): LlmAvailability = LlmAvailability.Ready

    override suspend fun correct(text: String): CorrectionResult {
        delay(delayMs)
        return CorrectionResult.Corrected(text)
    }
}

/** A corrector whose provider rejected the request. */
private object UnavailableCorrector : LlmCorrector {

    override val mode: LlmMode = LlmMode.CLOUD

    override fun availability(): LlmAvailability = LlmAvailability.Ready

    override suspend fun correct(text: String): CorrectionResult =
        CorrectionResult.Unavailable(LlmFailure.PROVIDER_ERROR)
}

/** A corrector with a fixed answer, used for the "empty answer" rule. */
private class ConstCorrector(private val answer: String) : LlmCorrector {

    override val mode: LlmMode = LlmMode.ON_DEVICE

    override fun availability(): LlmAvailability = LlmAvailability.Ready

    override suspend fun correct(text: String): CorrectionResult =
        CorrectionResult.Corrected(answer)
}

