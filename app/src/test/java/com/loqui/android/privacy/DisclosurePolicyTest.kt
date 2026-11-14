package com.loqui.android.privacy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DisclosurePolicyTest {

    @Test
    fun `the disclosure is required until it is accepted`() {
        assertTrue(DisclosurePolicy.needsDisclosure(accepted = false))
        assertFalse(DisclosurePolicy.needsDisclosure(accepted = true))
    }

    @Test
    fun `system settings are only opened after consent`() {
        assertFalse(DisclosurePolicy.mayOpenSettings(accepted = false))
        assertTrue(DisclosurePolicy.mayOpenSettings(accepted = true))
    }

    @Test
    fun `asking again does not count as consent`() {
        // Re-rendering the screen must not look like an accepted disclosure.
        repeat(3) {
            assertTrue(DisclosurePolicy.needsDisclosure(accepted = false))
            assertFalse(DisclosurePolicy.mayOpenSettings(accepted = false))
        }
    }
}
