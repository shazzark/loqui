package com.loqui.android.ui

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.loqui.android.ui.home.HomeScreen
import com.loqui.android.ui.home.HomeViewModel
import com.loqui.android.ui.onboarding.OnboardingScreen
import com.loqui.android.ui.privacy.PrivacyPolicyScreen

/**
 * Three destinations: home/settings, the permission walkthrough and the privacy policy.
 *
 * The walkthrough opens on launch while the essentials are missing, so a fresh install
 * asks for the microphone, the overlay and (optionally) accessibility before the user is
 * dropped into an app that cannot dictate. The policy is reachable from both screens.
 * Navigation stays local state — no library is needed without parameters or deep links.
 */
@Composable
fun LoquiApp() {
    val viewModel: HomeViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // User opened setup explicitly, and "not now" respectively.
    var setupRequested by rememberSaveable { mutableStateOf(false) }
    var setupDismissed by rememberSaveable { mutableStateOf(false) }
    var showPrivacy by rememberSaveable { mutableStateOf(false) }

    val showSetup = !showPrivacy &&
        (setupRequested || (!uiState.permissions.essentialsGranted && !setupDismissed))

    // Overlay, accessibility and the battery exemption live in system settings: re-read.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    val dismissSetup = {
        setupRequested = false
        setupDismissed = true
        viewModel.onSetupFinished()
    }

    BackHandler(enabled = showPrivacy || showSetup) {
        if (showPrivacy) {
            showPrivacy = false
        } else {
            dismissSetup()
        }
    }

    val openPrivacyPolicy = { showPrivacy = true }

    when {
        showPrivacy -> PrivacyPolicyScreen(onBack = { showPrivacy = false })

        showSetup -> OnboardingScreen(
            uiState = uiState,
            onRefreshPermissions = viewModel::refreshPermissions,
            onOpenOverlaySettings = viewModel::openOverlaySettings,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
            onAcceptDisclosure = viewModel::acceptAccessibilityDisclosure,
            onOpenPrivacyPolicy = openPrivacyPolicy,
            onOpenNotificationSettings = viewModel::openNotificationSettings,
            onDone = dismissSetup,
        )

        else -> HomeScreen(
            uiState = uiState,
            onSelectAsrMode = viewModel::selectAsrMode,
            onSelectModelQuality = viewModel::setModelQuality,
            onSaveCloudKey = viewModel::saveCloudKey,
            onRemoveCloudKey = viewModel::removeCloudKey,
            onSaveLlmKey = viewModel::saveLlmKey,
            onRemoveLlmKey = viewModel::removeLlmKey,
            onLlmEnabledChange = viewModel::setLlmEnabled,
            onLlmModeChange = viewModel::setLlmMode,
            onStartBubble = viewModel::startBubble,
            onStopBubble = viewModel::stopBubble,
            onStartCapture = viewModel::startCapture,
            onOpenSetup = { setupRequested = true },
            onOpenAppSettings = viewModel::openAppSettings,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
            onAcceptAccessibilityDisclosure = viewModel::acceptAccessibilityDisclosure,
            onRequestBatteryExemption = viewModel::requestBatteryExemption,
            onOpenBatterySettings = viewModel::openBatterySettings,
            onOpenPrivacyPolicy = openPrivacyPolicy,
            onDownloadModel = viewModel::downloadModel,
            onCancelModelDownload = viewModel::cancelModelDownload,
        )
    }
}

