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
 * Phase 1 keeps navigation as local state — no navigation library is needed until
 * there are parameters or deep links to pass around.
 */
@Composable
fun LoquiApp() {
    val viewModel: HomeViewModel = viewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    var showSetup by rememberSaveable { mutableStateOf(false) }

    // Overlay and accessibility live in system settings, so re-read on return.
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.refreshPermissions()
    }

    BackHandler(enabled = showSetup) {
        showSetup = false
    }

    if (showSetup) {
        OnboardingScreen(
            uiState = uiState,
            onRefreshPermissions = viewModel::refreshPermissions,
            onOpenOverlaySettings = viewModel::openOverlaySettings,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
            onOpenNotificationSettings = viewModel::openNotificationSettings,
            onDone = { showSetup = false },
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
            onOpenSetup = { showSetup = true },
            onOpenAppSettings = viewModel::openAppSettings,
            onOpenAccessibilitySettings = viewModel::openAccessibilitySettings,
        )
    }
}
