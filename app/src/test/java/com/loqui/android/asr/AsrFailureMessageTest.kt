package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The regression this guards: an on-device failure used to be described with the cloud
 * sentence "The provider rejected the request. Check your key", sending users who never
 * configured a key looking for one that does not exist.
 */
class AsrFailureMessageTest {

    @Test
    fun `on-device failures never mention a key or a provider`() {
        AsrFailure.entries.forEach { reason ->
            val message = messageFor(reason, AsrMode.ON_DEVICE)

            assertNotEquals(
                "$reason must not blame a key on-device",
                AsrFailureMessage.CLOUD_KEY_MISSING,
                message,
            )
            assertNotEquals(
                "$reason must not blame a provider on-device",
                AsrFailureMessage.CLOUD_REJECTED,
                message,
            )
        }
    }

    @Test
    fun `a provider error on-device is an on-device failure`() {
        assertEquals(
            AsrFailureMessage.ON_DEVICE_FAILED,
            messageFor(AsrFailure.PROVIDER_ERROR, AsrMode.ON_DEVICE),
        )
    }

    @Test
    fun `a missing key reported on-device still does not mention a key`() {
        assertEquals(
            AsrFailureMessage.ON_DEVICE_FAILED,
            messageFor(AsrFailure.MISSING_KEY, AsrMode.ON_DEVICE),
        )
    }

    @Test
    fun `cloud failures keep their provider and key wording`() {
        assertEquals(
            AsrFailureMessage.CLOUD_REJECTED,
            messageFor(AsrFailure.PROVIDER_ERROR, AsrMode.CLOUD),
        )
        assertEquals(
            AsrFailureMessage.CLOUD_KEY_MISSING,
            messageFor(AsrFailure.MISSING_KEY, AsrMode.CLOUD),
        )
        assertEquals(
            AsrFailureMessage.NETWORK_TIMEOUT,
            messageFor(AsrFailure.NETWORK_TIMEOUT, AsrMode.CLOUD),
        )
    }

    @Test
    fun `a stalled session reads as a timeout in either mode`() {
        assertEquals(
            AsrFailureMessage.ON_DEVICE_TIMEOUT,
            messageFor(AsrFailure.NETWORK_TIMEOUT, AsrMode.ON_DEVICE),
        )
        assertEquals(
            AsrFailureMessage.NETWORK_TIMEOUT,
            messageFor(AsrFailure.NETWORK_TIMEOUT, AsrMode.CLOUD),
        )
    }

    @Test
    fun `silence and a missing model read the same in both modes`() {
        AsrMode.entries.forEach { mode ->
            assertEquals(AsrFailureMessage.NO_SPEECH, messageFor(AsrFailure.NO_SPEECH, mode))
            assertEquals(
                AsrFailureMessage.MODEL_MISSING,
                messageFor(AsrFailure.MODEL_UNAVAILABLE, mode),
            )
            assertEquals(AsrFailureMessage.CANCELLED, messageFor(AsrFailure.CANCELLED, mode))
        }
    }
}