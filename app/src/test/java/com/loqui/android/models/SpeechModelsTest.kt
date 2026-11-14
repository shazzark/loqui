package com.loqui.android.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechModelsTest {

    private val mebibyte = 1024L * 1024L

    @Test
    fun `the catalogue is not empty and ids are unique`() {
        val models = SpeechModels.all

        assertTrue(models.isNotEmpty())
        assertEquals(models.size, models.map { it.id }.toSet().size)
    }

    @Test
    fun `the default model is part of the catalogue`() {
        assertTrue(SpeechModels.onDeviceDefault in SpeechModels.all)
    }

    @Test
    fun `every model records a usable license and a plausible size`() {
        SpeechModels.all.forEach { model ->
            assertTrue("blank id", model.id.isNotBlank())
            assertTrue("blank name", model.displayName.isNotBlank())
            assertTrue(
                "${model.id}: ${model.approxBytes} bytes is not a plausible model size",
                model.approxBytes in (10 * mebibyte)..(2L * 1024 * mebibyte),
            )
            assertTrue(
                "${model.id} ships under a license that forbids commercial use",
                model.license.commercialUseAllowed,
            )
        }
    }
}
