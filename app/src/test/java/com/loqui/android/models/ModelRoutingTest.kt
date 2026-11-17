package com.loqui.android.models

import com.loqui.android.settings.ModelQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The routing rule on its own: no Android, no disk, no repository.
 *
 * Two failure modes are being pinned here. A 4 GB phone told to run the accurate model
 * hears lag rather than wrong words, which is worse to diagnose than an error; and an
 * unreadable RAM total must never *promote* a device to the larger model.
 */
class ModelRoutingTest {

    private val fourGigabytes = 4_000_000_000L
    private val sixGigabytes = 6_000_000_000L
    private val eightGigabytes = 8_000_000_000L

    @Test
    fun `auto picks the fast model on a 4 GB phone`() {
        assertEquals(SpeechModels.onDeviceFast, ModelRouting.resolve(ModelQuality.AUTO, fourGigabytes))
    }

    @Test
    fun `auto picks the accurate model from six gigabytes up`() {
        assertEquals(
            SpeechModels.onDeviceAccurate,
            ModelRouting.resolve(ModelQuality.AUTO, sixGigabytes),
        )
        assertEquals(
            SpeechModels.onDeviceAccurate,
            ModelRouting.resolve(ModelQuality.AUTO, eightGigabytes),
        )
    }

    @Test
    fun `the threshold itself counts as high memory`() {
        assertTrue(ModelRouting.isHighMemory(ModelRouting.ACCURATE_MIN_TOTAL_RAM_BYTES))
        assertFalse(ModelRouting.isHighMemory(ModelRouting.ACCURATE_MIN_TOTAL_RAM_BYTES - 1))
    }

    @Test
    fun `auto on a device that will not report its RAM keeps the fast model`() {
        // 0 is what ActivityManager reports when a vendor ROM throws; promoting that device
        // to the larger decoder is exactly the lag the router exists to prevent.
        assertFalse(ModelRouting.isHighMemory(0L))
        assertEquals(SpeechModels.onDeviceFast, ModelRouting.resolve(ModelQuality.AUTO, 0L))
    }

    @Test
    fun `an explicit choice ignores the RAM`() {
        // The user overrides the router in both directions: accurate on a phone that will
        // lag, fast on one that could run the larger model.
        assertEquals(
            SpeechModels.onDeviceAccurate,
            ModelRouting.resolve(ModelQuality.ACCURATE, fourGigabytes),
        )
        assertEquals(
            SpeechModels.onDeviceFast,
            ModelRouting.resolve(ModelQuality.FAST, eightGigabytes),
        )
    }

    @Test
    fun `every quality resolves to a model in the catalogue`() {
        ModelQuality.entries.forEach { quality ->
            listOf(0L, fourGigabytes, eightGigabytes).forEach { ram ->
                val model = ModelRouting.resolve(quality, ram)
                assertTrue("$quality on ${ram}B picked ${model.id}", model in SpeechModels.all)
            }
        }
    }
}
