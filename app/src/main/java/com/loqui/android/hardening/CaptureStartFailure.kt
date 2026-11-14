package com.loqui.android.hardening

/**
 * Why a dictation could not start. Android signals all of these as exceptions, so the
 * classification lives in one pure function instead of a `catch` block per caller.
 */
enum class CaptureStartFailure {
    /** `RECORD_AUDIO` is missing or was revoked. */
    MICROPHONE_PERMISSION_MISSING,

    /**
     * Android refused a foreground-service start while the app was in the background
     * (`ForegroundServiceStartNotAllowedException` and its older `IllegalStateException`
     * form, API 26+).
     */
    BACKGROUND_START_BLOCKED,

    /**
     * The service came up but the platform refused the `microphone` promotion, usually
     * because the while-in-use window closed before the start.
     */
    FOREGROUND_PROMOTION_REFUSED,

    /** Nothing recognisable: report it and let the user retry. */
    UNKNOWN,
}

/**
 * Maps the throwable a start attempt produced onto a user-explicable reason.
 *
 * [hasMicrophone] disambiguates the two `SecurityException` cases: without the grant the
 * platform is rejecting the microphone, with it the rejection is about the foreground
 * promotion itself (Android 14+ enforces one from the background).
 */
fun classifyCaptureStartFailure(
    throwable: Throwable,
    hasMicrophone: Boolean,
): CaptureStartFailure = when {
    throwable is SecurityException && !hasMicrophone -> CaptureStartFailure.MICROPHONE_PERMISSION_MISSING
    throwable is SecurityException -> CaptureStartFailure.FOREGROUND_PROMOTION_REFUSED
    throwable is IllegalStateException -> CaptureStartFailure.BACKGROUND_START_BLOCKED
    else -> CaptureStartFailure.UNKNOWN
}
