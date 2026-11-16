package com.loqui.android.asr

import app.cash.turbine.test
import com.loqui.android.asr.cloud.MockCloudAsrTransport
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.ProviderIds
import com.loqui.android.testing.FakeSettingsRepository
import com.loqui.android.testing.InMemorySecretStore
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class AsrCoordinatorTest {

    private val settings = FakeSettingsRepository()
    private val secretStore = InMemorySecretStore()
    private val coordinator = AsrCoordinator(
        settingsRepository = settings,
        providerFactory = DefaultAsrProviderFactory(
            secretStore,
            FakeAsrProvider(AsrMode.ON_DEVICE),
        ),
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
    fun `cloud mode with a user key transcribes through the cloud provider`() = runTest {
        settings.setAsrMode(AsrMode.CLOUD)
        secretStore.writeKey(ProviderIds.CLOUD_ASR, "user-supplied-key")

        coordinator.transcribe(silence()).test {
            val event = awaitItem()
            assertEquals(
                AsrSessionEvent.Transcript(MockCloudAsrTransport.CANNED_TRANSCRIPT, AsrMode.CLOUD),
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

    @Test
    fun `a wedged provider is cut off instead of holding the microphone`() = runTest {
        val wedged = AsrCoordinator(
            settingsRepository = settings,
            providerFactory = StallingProviderFactory,
        )

        wedged.transcribe(silence(), sessionHardLimitMs = 100).test {
            assertEquals(
                AsrSessionEvent.Failed(AsrFailure.NETWORK_TIMEOUT, AsrMode.ON_DEVICE),
                awaitItem(),
            )
            awaitComplete()
        }
    }

    private fun silence() = flowOf(AudioFrame(ByteArray(16)))

    @Test
    fun `a provider failure after a partial delivers the partial, not an error`() = runTest {
        val salvaging = coordinatorFor(PartialThenFailFactory(AsrFailure.PROVIDER_ERROR))

        salvaging.transcribe(silence()).test {
            // 7 characters of real speech is a dictation; an error toast is not.
            assertEquals(
                AsrSessionEvent.Transcript(PARTIAL, AsrMode.ON_DEVICE),
                awaitItem(),
            )
            awaitComplete()
        }
    }

    @Test
    fun `a stalled flush after a partial still delivers the partial`() = runTest {
        val salvaging = coordinatorFor(PartialThenStallFactory)

        salvaging.transcribe(silence(), sessionHardLimitMs = 100).test {
            assertEquals(
                AsrSessionEvent.Transcript(PARTIAL, AsrMode.ON_DEVICE),
                awaitItem(),
            )
            awaitComplete()
        }
    }

    @Test
    fun `no-speech is a verdict on the session and is never overridden by a stale partial`() = runTest {
        val salvaging = coordinatorFor(PartialThenFailFactory(AsrFailure.NO_SPEECH))

        salvaging.transcribe(silence()).test {
            assertEquals(
                AsrSessionEvent.Failed(AsrFailure.NO_SPEECH, AsrMode.ON_DEVICE),
                awaitItem(),
            )
            awaitComplete()
        }
    }

    private fun coordinatorFor(factory: AsrProviderFactory) = AsrCoordinator(
        settingsRepository = settings,
        providerFactory = factory,
    )

    /** Provider that never produces an event, to prove the session hard limit. */
    private object StallingProviderFactory : AsrProviderFactory {

        private val provider = object : AsrProvider {
            override val mode: AsrMode = AsrMode.ON_DEVICE

            override fun availability(): AsrAvailability = AsrAvailability.Ready

            override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
                awaitCancellation()
            }
        }

        override fun onDevice(): AsrProvider = provider

        override fun cloud(): AsrProvider = provider
    }

    /** Emits recognised text, then reports that the recogniser broke. */
    private class PartialThenFailFactory(
        private val failure: AsrFailure,
    ) : AsrProviderFactory {

        private val provider = object : AsrProvider {
            override val mode: AsrMode = AsrMode.ON_DEVICE

            override fun availability(): AsrAvailability = AsrAvailability.Ready

            override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
                emit(AsrEvent.Partial(PARTIAL))
                emit(AsrEvent.Failure(failure))
            }
        }

        override fun onDevice(): AsrProvider = provider

        override fun cloud(): AsrProvider = provider
    }

    /** Emits recognised text, then hangs: a flush that never finishes. */
    private object PartialThenStallFactory : AsrProviderFactory {

        private val provider = object : AsrProvider {
            override val mode: AsrMode = AsrMode.ON_DEVICE

            override fun availability(): AsrAvailability = AsrAvailability.Ready

            override fun transcribe(audio: Flow<AudioFrame>): Flow<AsrEvent> = flow {
                emit(AsrEvent.Partial(PARTIAL))
                awaitCancellation()
            }
        }

        override fun onDevice(): AsrProvider = provider

        override fun cloud(): AsrProvider = provider
    }

    private companion object {
        /** What a real flush leaves behind: the words, not the whole sentence. */
        const val PARTIAL = "hello wor"
    }
}
