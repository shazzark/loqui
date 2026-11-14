package com.loqui.android.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loqui.android.capture.CaptureFeedback
import com.loqui.android.capture.CaptureState
import com.loqui.android.capture.CaptureStateHolder
import com.loqui.android.capture.DictationTap
import com.loqui.android.capture.VoiceInputService
import com.loqui.android.capture.dictationTapFor
import com.loqui.android.core.BatteryOptimizations
import com.loqui.android.core.PermissionStatus
import com.loqui.android.core.Permissions
import com.loqui.android.hardening.captureStartFailureMessage
import com.loqui.android.models.ModelStorageProbe
import com.loqui.android.models.ModelStorageReport
import com.loqui.android.models.SpeechModels
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.LlmMode
import com.loqui.android.settings.ProviderIds
import com.loqui.android.settings.SecretStore
import com.loqui.android.settings.SettingsRepository
import com.loqui.android.trigger.BubbleService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import timber.log.Timber

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settingsRepository: SettingsRepository,
    private val secretStore: SecretStore,
    private val captureStateHolder: CaptureStateHolder,
    private val captureFeedback: CaptureFeedback,
    private val modelStorageProbe: ModelStorageProbe,
) : ViewModel() {

    private val permissionState = MutableStateFlow(Permissions.status(context))
    private val batteryState = MutableStateFlow(BatteryOptimizations.isExempt(context))
    private val storedKeys = MutableStateFlow(
        StoredKeys(
            asr = secretStore.hasKey(ProviderIds.CLOUD_ASR),
            llm = secretStore.hasKey(ProviderIds.CLOUD_LLM),
        ),
    )
    private val storageReport = MutableStateFlow(currentStorageReport())
    private val bubbleRunning = MutableStateFlow(false)

    /** Permissions, battery exemption, keys and free space: all read from the system. */
    private val deviceState = combine(
        permissionState,
        batteryState,
        storedKeys,
        storageReport,
    ) { permissions, battery, keys, storage ->
        DeviceState(permissions, battery, keys, storage)
    }

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        deviceState,
        bubbleRunning,
        captureStateHolder.state,
    ) { settings, device, bubble, captureState ->
        HomeUiState(
            asrMode = settings.asrMode,
            llmEnabled = settings.llmEnabled,
            llmMode = settings.llmMode,
            permissions = device.permissions,
            hasCloudKey = device.keys.asr,
            hasLlmKey = device.keys.llm,
            bubbleRunning = bubble,
            captureState = captureState,
            batteryExempt = device.batteryExempt,
            accessibilityDisclosureAccepted = settings.accessibilityDisclosureAccepted,
            model = SpeechModels.onDeviceDefault,
            storage = device.storage,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(
            permissions = permissionState.value,
            hasCloudKey = storedKeys.value.asr,
            hasLlmKey = storedKeys.value.llm,
            batteryExempt = batteryState.value,
            storage = storageReport.value,
        ),
    )

    /**
     * Call on every resume: the user changes permissions, the battery exemption and free
     * space in system settings, so one refresh point keeps the home screen honest.
     */
    fun refreshPermissions() {
        permissionState.value = Permissions.status(context)
        batteryState.value = BatteryOptimizations.isExempt(context)
        storageReport.value = currentStorageReport()
    }

    /**
     * Called when the permission walkthrough closes. With the essentials in place the
     * floating trigger starts immediately, so the next step is simply "open any app and
     * tap the bubble".
     */
    fun onSetupFinished() {
        refreshPermissions()
        if (Permissions.status(context).essentialsGranted) {
            startBubble()
        }
    }

    fun selectAsrMode(mode: AsrMode) {
        viewModelScope.launch { settingsRepository.setAsrMode(mode) }
    }

    fun setLlmEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setLlmEnabled(enabled) }
    }

    fun setLlmMode(mode: LlmMode) {
        viewModelScope.launch { settingsRepository.setLlmMode(mode) }
    }

    /**
     * Records the Play accessibility disclosure consent so the dialog is not repeated.
     * The consent is not a permission: the user still switches the service on in settings.
     */
    fun acceptAccessibilityDisclosure() {
        viewModelScope.launch { settingsRepository.setAccessibilityDisclosureAccepted(true) }
    }

    /**
     * Asks Android to exempt Loqui from battery optimisation. Doze can otherwise kill the
     * bubble or end capture mid-sentence with the screen off.
     *
     * @return false when the device has no exemption dialog, so the screen can point the
     * user at the battery list; the settings screen has already been opened.
     */
    fun requestBatteryExemption(): Boolean {
        val requested = BatteryOptimizations.requestExemption(context)
        refreshPermissions()
        if (!requested) Timber.i("Battery exemption dialog unavailable; opened the battery list")
        return requested
    }

    fun openBatterySettings() {
        BatteryOptimizations.openSettings(context)
        refreshPermissions()
    }

    /** @return false when the input was blank, so the screen can keep the error visible. */
    fun saveCloudKey(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) return false

        secretStore.writeKey(ProviderIds.CLOUD_ASR, key)
        storedKeys.value = storedKeys.value.copy(asr = true)
        Timber.i("Cloud ASR key stored for this device")
        return true
    }

    fun removeCloudKey() {
        secretStore.deleteKey(ProviderIds.CLOUD_ASR)
        storedKeys.value = storedKeys.value.copy(asr = false)
    }

    /** @return false when the input was blank, so the screen can keep the error visible. */
    fun saveLlmKey(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) return false

        secretStore.writeKey(ProviderIds.CLOUD_LLM, key)
        storedKeys.value = storedKeys.value.copy(llm = true)
        Timber.i("Cloud clean-up key stored for this device")
        return true
    }

    fun removeLlmKey() {
        secretStore.deleteKey(ProviderIds.CLOUD_LLM)
        storedKeys.value = storedKeys.value.copy(llm = false)
    }

    fun startBubble() {
        if (!Permissions.hasOverlay(context)) {
            Permissions.openOverlaySettings(context)
            return
        }
        BubbleService.start(context)
        bubbleRunning.value = true
    }

    fun stopBubble() {
        BubbleService.stop(context)
        bubbleRunning.value = false
    }

    /** Same toggle rule as the bubble: a second tap stops the running session. */
    fun startCapture() {
        if (!Permissions.hasMicrophone(context)) return
        when (dictationTapFor(captureStateHolder.state.value)) {
            DictationTap.STOP -> VoiceInputService.cancel(context)
            DictationTap.START -> {
                // Android 12+ can refuse a foreground-service start outright; say why
                // instead of letting the tap do nothing.
                val failure = VoiceInputService.start(context)
                if (failure != null) {
                    val message = context.getString(captureStartFailureMessage(failure))
                    Timber.w("Capture start refused: %s", failure)
                    captureStateHolder.update(CaptureState.Error(message))
                    captureFeedback.report(CaptureState.Error(message))
                }
            }
        }
    }

    fun cancelCapture() {
        VoiceInputService.cancel(context)
    }

    fun openOverlaySettings() = Permissions.openOverlaySettings(context)

    fun openAccessibilitySettings() = Permissions.openAccessibilitySettings(context)

    fun openNotificationSettings() = Permissions.openNotificationSettings(context)

    fun openAppSettings() = Permissions.openAppSettings(context)

    /** Free space on the volume a model download would be extracted into. */
    private fun currentStorageReport(): ModelStorageReport = ModelStorageReport(
        requiredBytes = SpeechModels.onDeviceDefault.approxBytes,
        freeBytes = modelStorageProbe.freeBytes(),
    )

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }

    /** Which provider keys are present, collapsed so the UI combine stays readable. */
    private data class StoredKeys(val asr: Boolean, val llm: Boolean)

    /** Everything read from the device rather than from the user's settings. */
    private data class DeviceState(
        val permissions: PermissionStatus,
        val batteryExempt: Boolean,
        val keys: StoredKeys,
        val storage: ModelStorageReport,
    )
}
