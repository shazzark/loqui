package com.loqui.android.privacy

import com.loqui.android.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyTest {

    /** Topics a reviewer will look for; the screen is generated from this list. */
    private val requiredTopics = listOf(
        R.string.privacy_audio_title,
        R.string.privacy_on_device_title,
        R.string.privacy_cloud_title,
        R.string.privacy_cleanup_title,
        R.string.privacy_logging_title,
        R.string.privacy_keys_title,
        R.string.privacy_accessibility_title,
    )

    @Test
    fun `every required topic is covered`() {
        val titles = PrivacyPolicy.sections.map { it.title }

        requiredTopics.forEach { topic ->
            assertTrue("the policy dropped a required topic ($topic)", topic in titles)
        }
    }

    @Test
    fun `no section is repeated`() {
        val titles = PrivacyPolicy.sections.map { it.title }

        assertEquals(titles.size, titles.toSet().size)
    }

    @Test
    fun `every section has a title and a body`() {
        PrivacyPolicy.sections.forEach { section ->
            assertNotEquals("missing title", 0, section.title)
            assertNotEquals("missing body", 0, section.body)
        }
    }

    @Test
    fun `the policy is versioned`() {
        assertTrue(PrivacyPolicy.VERSION >= 1)
    }
}
