package com.loqui.android.llm

import com.loqui.android.settings.LlmMode
import com.loqui.android.settings.ProviderIds
import com.loqui.android.testing.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class LlmProviderFactoryTest {

    private val secretStore = InMemorySecretStore()
    private val factory = DefaultLlmProviderFactory(secretStore)

    @Test
    fun `the on-device corrector is created once and reused across dictations`() {
        val first = factory.onDevice()

        assertSame(first, factory.onDevice())
        assertEquals(LlmMode.ON_DEVICE, first.mode)
    }

    @Test
    fun `the cloud corrector is rebuilt per session so the key is read at call time`() {
        assertNotSame(factory.cloud(), factory.cloud())
        assertEquals(LlmMode.CLOUD, factory.cloud().mode)
    }

    @Test
    fun `cloud clean-up reports the missing key state until a key is stored`() {
        assertEquals(LlmAvailability.MissingKey, factory.cloud().availability())

        secretStore.writeKey(ProviderIds.CLOUD_LLM, "user-supplied-key")

        assertEquals(LlmAvailability.Ready, factory.cloud().availability())
    }
}
