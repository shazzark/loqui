package com.loqui.android.asr

import app.cash.turbine.test
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.ProviderIds
import com.loqui.android.testing.FakeSettingsRepository
import com.loqui.android.testing.InMemorySecretStore
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrCoordinatorTest {

    private val settings = FakeSettingsRepository()
    private val secretStore = InMemorySecretStore()
    private val coordinator = AsrCoordinator(
        settingsRepository = settings,
        providerFactory = FakeAsrProviderFactory(secretStore),
    )

    @Test
    fun `on-device mode transcribes without any key`() = runTest {
        coordinator.transcribe(silence()).test {
            val event = awaitItem()
            assertEquals(
                AsrSessionEvent.Transcript(FakeAsrProvider.DEFAULT_TRANSCRIPT, AsrMode.ON_DEVICE),
                event,
            )
            awaitComplete()
        }
    }

    @Test
    fun `cloud mode without a key reports NeedsKey and never fabricates a transcript`() = runTest {
        settings.setAsrMode(AsrMode.CLOUD)

        coordinator.transcribe(silence()).test {
            assertEquals(AsrSessionEvent.NeedsKey, awaitItem())
            awaitComplete()
        }
    }

    @Test
    fun `cloud mode with a user key transcribes through that provider`() = runTest {
        settings.setAsrMode(AsrMode.CLOUD)
        secretStore.writeKey(ProviderIds.CLOUD_ASR, "user-supplied-key")

        coordinator.transcribe(silence()).test {
            val event = awaitItem()
            assertEquals(
                AsrSessionEvent.Transcript(FakeAsrProvider.DEFAULT_TRANSCRIPT, AsrMode.CLOUD),
                event,
            )
            awaitComplete()
        }
    }

    @Test
    fun `availability follows the selected mode`() = runTest {
        assertEquals(AsrAvailability.Ready, coordinator.availability())

        settings.setAsrMode(AsrMode.CLOUD)
        assertEquals(AsrAvailability.MissingKey, coordinator.availability())

        secretStore.writeKey(ProviderIds.CLOUD_ASR, "user-supplied-key")
        assertEquals(AsrAvailability.Ready, coordinator.availability())
    }

    private fun silence() = flowOf(AudioFrame(ByteArray(16)))
}
