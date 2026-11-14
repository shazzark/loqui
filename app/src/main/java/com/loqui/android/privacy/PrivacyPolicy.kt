package com.loqui.android.privacy

import androidx.annotation.StringRes
import com.loqui.android.R

/** One heading + body pair of the in-app policy. */
data class PolicySection(
    @StringRes val title: Int,
    @StringRes val body: Int,
)

/**
 * The privacy policy that ships inside the app.
 *
 * Kept as data so the screen and the Play listing describe the *same* handling: the
 * generated string ids are asserted in `PrivacyPolicyTest`, which fails if a topic that
 * matters for review (audio, on-device default, cloud opt-in, logging, keys,
 * accessibility) ever drops out.
 *
 * A hosted copy is still required for the Play listing before release; the in-app text is
 * the source of truth it will be generated from.
 */
object PrivacyPolicy {

    /** Bump whenever how audio or transcripts are handled changes materially. */
    const val VERSION = 1

    val sections: List<PolicySection> = listOf(
        PolicySection(R.string.privacy_audio_title, R.string.privacy_audio_body),
        PolicySection(R.string.privacy_on_device_title, R.string.privacy_on_device_body),
        PolicySection(R.string.privacy_cloud_title, R.string.privacy_cloud_body),
        PolicySection(R.string.privacy_cleanup_title, R.string.privacy_cleanup_body),
        PolicySection(R.string.privacy_logging_title, R.string.privacy_logging_body),
        PolicySection(R.string.privacy_keys_title, R.string.privacy_keys_body),
        PolicySection(R.string.privacy_accessibility_title, R.string.privacy_accessibility_body),
        PolicySection(R.string.privacy_models_title, R.string.privacy_models_body),
        PolicySection(R.string.privacy_permissions_title, R.string.privacy_permissions_body),
        PolicySection(R.string.privacy_changes_title, R.string.privacy_changes_body),
    )
}
