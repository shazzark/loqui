package com.loqui.android.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.loqui.android.capture.CaptureStateHolder
import com.loqui.android.capture.VoiceInputService
import com.loqui.android.core.Permissions
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
) : ViewModel() {

    private val permissionState = MutableStateFlow(Permissions.status(context))
    private val cloudKeyPresent = MutableStateFlow(secretStore.hasKey(ProviderIds.CLOUD_ASR))
    private val bubbleRunning = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        permissionState,
        cloudKeyPresent,
        bubbleRunning,
        captureStateHolder.state,
    ) { settings, permissions, hasKey, bubble, captureState ->
        HomeUiState(
            asrMode = settings.asrMode,
            llmEnabled = settings.llmEnabled,
            llmMode = settings.llmMode,
            permissions = permissions,
            hasCloudKey = hasKey,
            bubbleRunning = bubble,
            captureState = captureState,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        initialValue = HomeUiState(permissions = permissionState.value),
    )

    /** Call on every resume: the user changes these in system settings. */
    fun refreshPermissions() {
        permissionState.value = Permissions.status(context)
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

    /** @return false when the input was blank, so the screen can keep the error visible. */
    fun saveCloudKey(rawKey: String): Boolean {
        val key = rawKey.trim()
        if (key.isEmpty()) return false

        secretStore.writeKey(ProviderIds.CLOUD_ASR, key)
        cloudKeyPresent.value = true
        Timber.i("Cloud ASR key stored for this device")
        return true
    }

    fun removeCloudKey() {
        secretStore.deleteKey(ProviderIds.CLOUD_ASR)
        cloudKeyPresent.value = false
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

    fun startCapture() {
        if (!Permissions.hasMicrophone(context)) return
        VoiceInputService.start(context)
    }

    fun cancelCapture() {
        VoiceInputService.cancel(context)
    }

    fun openOverlaySettings() = Permissions.openOverlaySettings(context)

    fun openAccessibilitySettings() = Permissions.openAccessibilitySettings(context)

    fun openNotificationSettings() = Permissions.openNotificationSettings(context)

    fun openAppSettings() = Permissions.openAppSettings(context)

    private companion object {
        const val STOP_TIMEOUT_MS = 5_000L
    }
}
