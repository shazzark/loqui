package com.loqui.android.hardening

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class CaptureStartFailureTest {

    @Test
    fun `a security exception without the grant blames the microphone permission`() {
        assertEquals(
            CaptureStartFailure.MICROPHONE_PERMISSION_MISSING,
            classifyCaptureStartFailure(SecurityException("RECORD_AUDIO"), hasMicrophone = false),
        )
    }

    @Test
    fun `a security exception with the grant blames the foreground promotion`() {
        assertEquals(
            CaptureStartFailure.FOREGROUND_PROMOTION_REFUSED,
            classifyCaptureStartFailure(SecurityException("microphone type"), hasMicrophone = true),
        )
    }

    @Test
    fun `a blocked background start is reported as the platform illegal state`() {
        // Android 12+ throws ForegroundServiceStartNotAllowedException, an
        // IllegalStateException; API 26-30 throw the plain form for a background start.
        assertEquals(
            CaptureStartFailure.BACKGROUND_START_BLOCKED,
            classifyCaptureStartFailure(
                IllegalStateException("Not allowed to start service Intent"),
                hasMicrophone = true,
            ),
        )
    }

    @Test
    fun `an illegal state exception is a background start even without the grant`() {
        assertEquals(
            CaptureStartFailure.BACKGROUND_START_BLOCKED,
            classifyCaptureStartFailure(IllegalStateException("blocked"), hasMicrophone = false),
        )
    }

    @Test
    fun `anything unrecognisable is unknown`() {
        assertEquals(
            CaptureStartFailure.UNKNOWN,
            classifyCaptureStartFailure(RuntimeException("boom"), hasMicrophone = true),
        )
        assertEquals(
            CaptureStartFailure.UNKNOWN,
            classifyCaptureStartFailure(java.io.IOException("io"), hasMicrophone = true),
        )
    }

    @Test
    fun `every failure has a user-facing message`() {
        CaptureStartFailure.entries.forEach { failure ->
            assertNotEquals("no message for $failure", 0, captureStartFailureMessage(failure))
        }
    }
}
