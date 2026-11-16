package com.loqui.android.asr

import com.loqui.android.settings.AsrMode
import com.loqui.android.testing.InMemorySecretStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test

class AsrProviderFactoryTest {

    private val onDevice = FakeAsrProvider(AsrMode.ON_DEVICE)
    private val factory = DefaultAsrProviderFactory(InMemorySecretStore(), onDevice)

    @Test
    fun `the app-scoped on-device provider is returned for every dictation`() {
        assertSame(onDevice, factory.onDevice())
        assertSame(onDevice, factory.onDevice())
        assertEquals(AsrMode.ON_DEVICE, factory.onDevice().mode)
    }

    @Test
    fun `the cloud provider is rebuilt per session so the key is read at call time`() {
        assertNotSame(factory.cloud(), factory.cloud())
        assertEquals(AsrMode.CLOUD, factory.cloud().mode)
    }
}
