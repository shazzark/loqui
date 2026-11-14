package com.loqui.android.ui.onboarding

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.loqui.android.R
import com.loqui.android.ui.home.HomeUiState
import com.loqui.android.ui.privacy.AccessibilityDisclosureDialog

/**
 * Permission walkthrough. Each row explains the one job its permission does; the
 * clipboard fallback means accessibility is genuinely optional.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    uiState: HomeUiState,
    onRefreshPermissions: () -> Unit,
    onOpenOverlaySettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    onAcceptDisclosure: () -> Unit,
    onOpenPrivacyPolicy: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { onRefreshPermissions() }

    // The Play disclosure is shown the first time the user reaches for accessibility.
    var showDisclosure by rememberSaveable { mutableStateOf(false) }

    if (showDisclosure) {
        AccessibilityDisclosureDialog(
            onAccept = {
                showDisclosure = false
                onAcceptDisclosure()
                onOpenAccessibilitySettings()
            },
            onDecline = { showDisclosure = false },
        )
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        onRefreshPermissions()
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.onboarding_title)) },
                navigationIcon = {
                    TextButton(onClick = onDone) {
                        Text(stringResource(R.string.onboarding_back))
                    }
                },
            )
        },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    if (uiState.permissions.essentialsGranted) {
                        R.string.onboarding_ready
                    } else {
                        R.string.onboarding_still_missing
                    },
                ),
                style = MaterialTheme.typography.bodySmall,
                color = if (uiState.permissions.essentialsGranted) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.onboarding_required))

            PermissionRow(
                title = stringResource(R.string.permission_mic_title),
                body = stringResource(R.string.permission_mic_body),
                granted = uiState.permissions.microphone,
                actionLabel = stringResource(R.string.permission_grant),
                onAction = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            )

            PermissionRow(
                title = stringResource(R.string.permission_notifications_title),
                body = stringResource(R.string.permission_notifications_body),
                granted = uiState.permissions.notifications,
                actionLabel = stringResource(R.string.permission_grant),
                onAction = {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        onOpenNotificationSettings()
                    }
                },
            )

            PermissionRow(
                title = stringResource(R.string.permission_overlay_title),
                body = stringResource(R.string.permission_overlay_body),
                granted = uiState.permissions.overlay,
                actionLabel = stringResource(R.string.permission_open_settings),
                onAction = onOpenOverlaySettings,
            )

            Spacer(Modifier.height(20.dp))
            SectionLabel(stringResource(R.string.onboarding_optional))

            PermissionRow(
                title = stringResource(R.string.permission_accessibility_title),
                body = stringResource(R.string.permission_accessibility_body),
                hint = stringResource(R.string.permission_accessibility_steps),
                granted = uiState.permissions.accessibility,
                actionLabel = stringResource(R.string.permission_open_settings),
                onAction = {
                    if (uiState.accessibilityDisclosureAccepted) {
                        onOpenAccessibilitySettings()
                    } else {
                        showDisclosure = true
                    }
                },
            )

            Spacer(Modifier.height(24.dp))
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    stringResource(
                        if (uiState.permissions.essentialsGranted) {
                            R.string.onboarding_continue
                        } else {
                            R.string.onboarding_skip
                        },
                    ),
                )
            }
            TextButton(
                onClick = onOpenPrivacyPolicy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.privacy_open_action))
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
    )
}

@Composable
private fun PermissionRow(
    title: String,
    body: String,
    granted: Boolean,
    actionLabel: String,
    onAction: () -> Unit,
    hint: String? = null,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(text = title, style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(
                        if (granted) R.string.permission_granted else R.string.permission_missing,
                    ),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (granted) {
                        MaterialTheme.colorScheme.primary
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (hint != null && !granted) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            if (!granted) {
                TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) {
                    Text(actionLabel)
                }
            }
        }
    }
}
