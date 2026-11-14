package com.loqui.android.privacy

/**
 * Rule for the Play-required prominent disclosure before the accessibility deep-link.
 *
 * The disclosure is shown *before* the user is sent to system settings and only once: a
 * second visit to settings does not repeat it, because the user already gave the
 * affirmative consent Play asks for. Declining leaves the clipboard fallback untouched.
 */
object DisclosurePolicy {

    /** True while the disclosure still has to be shown. */
    fun needsDisclosure(accepted: Boolean): Boolean = !accepted

    /** The deep-link is only opened once the disclosure has been accepted. */
    fun mayOpenSettings(accepted: Boolean): Boolean = accepted
}
