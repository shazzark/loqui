package com.loqui.android.ui.home

import com.loqui.android.capture.CaptureState
import com.loqui.android.core.PermissionStatus
import com.loqui.android.models.ModelStorageReport
import com.loqui.android.models.SpeechModel
import com.loqui.android.models.SpeechModels
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.LlmMode

/** Everything the home screen renders, already resolved into plain values. */
data class HomeUiState(
    val asrMode: AsrMode = AsrMode.ON_DEVICE,
    val llmEnabled: Boolean = false,
    val llmMode: LlmMode = LlmMode.ON_DEVICE,
    val permissions: PermissionStatus = PermissionStatus(),
    val hasCloudKey: Boolean = false,
    val hasLlmKey: Boolean = false,
    val bubbleRunning: Boolean = false,
    val captureState: CaptureState = CaptureState.Idle,
    /** True when Android will not throttle Loqui's background work. */
    val batteryExempt: Boolean = false,
    val accessibilityDisclosureAccepted: Boolean = false,
    val model: SpeechModel = SpeechModels.onDeviceDefault,
    val storage: ModelStorageReport = ModelStorageReport(
        requiredBytes = SpeechModels.onDeviceDefault.approxBytes,
        freeBytes = 0L,
    ),
) {
    /** Cloud mode is selected but the user has not supplied a key yet. */
    val needsCloudKey: Boolean
        get() = asrMode == AsrMode.CLOUD && !hasCloudKey

    /** Cloud clean-up is switched on but the user has not supplied a key yet. */
    val needsLlmKey: Boolean
        get() = llmEnabled && llmMode == LlmMode.CLOUD && !hasLlmKey

    /** The card to show while the OS may still kill the bubble. */
    val batteryRestricted: Boolean
        get() = !batteryExempt
}
