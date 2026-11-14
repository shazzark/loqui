package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import com.loqui.android.testing.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AsrProviderFactoryTest {

    private val factory = DefaultAsrProviderFactory(InMemorySecretStore())

    @Test
    fun `the on-device provider is created once and reused across dictations`() {
        val first = factory.onDevice()

        assertSame(first, factory.onDevice())
        assertEquals(AsrMode.ON_DEVICE, first.mode)
    }

    @Test
    fun `the cloud provider is rebuilt per session so the key is read at call time`() {
        assertNotSame(factory.cloud(), factory.cloud())
        assertEquals(AsrMode.CLOUD, factory.cloud().mode)
    }
}
