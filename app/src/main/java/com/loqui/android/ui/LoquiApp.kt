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

/**
 * Two destinations: the home/settings screen and the permission walkthrough.
 *
 * The walkthrough opens on launch while the essentials are missing, so a fresh
 * install asks for the microphone, the overlay and (optionally) accessibility before
 * the user is dropped into an app that cannot dictate. Phase 1 kept navigation as
 * local state — no navigation library is needed without parameters or deep links.
 */
@Composable
fun LoquiApp() {
    val viewModel: HomeViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // User opened setup explicitly, and "not now" respectively.
    var setupRequested by rememberSaveable { mutableStateOf(false) }
    var setupDismissed by rememberSaveable { mutableStateOf(false) }

    val showSetup = setupRequested ||
        (!uiState.permissions.essentialsGranted && !setupDismissed)

    // Overlay and accessibility live in system settings, so re-read on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    val dismissSetup = {
        setupRequested = false
        setupDismissed = true
        viewModel.onSetupFinished()
    }

    BackHandler(enabled = showSetup) {
        dismissSetup()
    }

    if (showSetup) {
        OnboardingScreen(
            uiState = uiState,
            onRefreshPermissions = viewModel::refreshPermissions,
            onOpenOverlaySettings = viewModel::openOverlaySettings,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
            onOpenNotificationSettings = viewModel::openNotificationSettings,
            onDone = dismissSetup,
        )
    } else {
        HomeScreen(
            uiState = uiState,
            onSelectAsrMode = viewModel::selectAsrMode,
            onSaveCloudKey = viewModel::saveCloudKey,
            onRemoveCloudKey = viewModel::removeCloudKey,
            onLlmEnabledChange = viewModel::setLlmEnabled,
            onLlmModeChange = viewModel::setLlmMode,
            onStartBubble = viewModel::startBubble,
            onStopBubble = viewModel::stopBubble,
            onStartCapture = viewModel::startCapture,
            onOpenSetup = { setupRequested = true },
            onOpenAppSettings = viewModel::openAppSettings,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
        )
    }
}
