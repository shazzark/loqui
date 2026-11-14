package com.loqui.android.ui.home

import androidx.annotation.StringRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.loqui.android.R
import com.loqui.android.capture.CaptureState
import com.loqui.android.settings.AsrMode
import com.loqui.android.settings.LlmMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    uiState: HomeUiState,
    onSelectAsrMode: (AsrMode) -> Unit,
    onSaveCloudKey: (String) -> Boolean,
    onRemoveCloudKey: () -> Unit,
    onSaveLlmKey: (String) -> Boolean,
    onRemoveLlmKey: () -> Unit,
    onLlmEnabledChange: (Boolean) -> Unit,
    onLlmModeChange: (LlmMode) -> Unit,
    onStartBubble: () -> Unit,
    onStopBubble: () -> Unit,
    onStartCapture: () -> Unit,
    onOpenSetup: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onOpenAccessibilitySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                text = stringResource(R.string.home_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            SetupCard(uiState = uiState, onOpenSetup = onOpenSetup)
            StatusSection(
                uiState = uiState,
                onStartCapture = onStartCapture,
                onStartBubble = onStartBubble,
                onStopBubble = onStopBubble,
            )
            AsrSection(
                uiState = uiState,
                onSelectAsrMode = onSelectAsrMode,
                onSaveCloudKey = onSaveCloudKey,
                onRemoveCloudKey = onRemoveCloudKey,
            )
            LlmSection(
                uiState = uiState,
                onLlmEnabledChange = onLlmEnabledChange,
                onLlmModeChange = onLlmModeChange,
                onSaveLlmKey = onSaveLlmKey,
                onRemoveLlmKey = onRemoveLlmKey,
            )
            InsertionSection(
                uiState = uiState,
                onOpenAccessibilitySettings = onOpenAccessibilitySettings,
                onOpenAppSettings = onOpenAppSettings,
            )
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun SetupCard(uiState: HomeUiState, onOpenSetup: () -> Unit) {
    val complete = uiState.permissions.essentialsGranted
    TextBlock(
        title = stringResource(R.string.home_setup_action),
        body = stringResource(
            if (complete) R.string.home_setup_complete else R.string.home_setup_incomplete,
        ),
        action = {
            TextButton(onClick = onOpenSetup) {
                Text(stringResource(R.string.home_setup_action))
            }
        },
    )
}

@Composable
private fun StatusSection(
    uiState: HomeUiState,
    onStartCapture: () -> Unit,
    onStartBubble: () -> Unit,
    onStopBubble: () -> Unit,
) {
    Section(stringResource(R.string.section_status)) {
        Text(
            text = captureStateText(uiState.captureState),
            style = MaterialTheme.typography.bodyLarge,
        )
        if (uiState.captureState == CaptureState.NeedsKey) {
            Text(
                text = stringResource(R.string.cloud_key_missing_body),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 6.dp),
            )
        }

        Row(
            modifier = Modifier.padding(top = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Button(
                onClick = onStartCapture,
                enabled = uiState.permissions.microphone,
            ) {
                Text(stringResource(R.string.home_dictate_now))
            }
            Button(onClick = if (uiState.bubbleRunning) onStopBubble else onStartBubble) {
                Text(
                    stringResource(
                        if (uiState.bubbleRunning) {
                            R.string.home_stop_bubble
                        } else {
                            R.string.home_start_bubble
                        },
                    ),
                )
            }
        }
    }
}

@Composable
private fun AsrSection(
    uiState: HomeUiState,
    onSelectAsrMode: (AsrMode) -> Unit,
    onSaveCloudKey: (String) -> Boolean,
    onRemoveCloudKey: () -> Unit,
) {
    Section(stringResource(R.string.section_asr)) {
        SelectableRow(
            title = stringResource(R.string.asr_mode_on_device),
            description = stringResource(R.string.asr_mode_on_device_description),
            selected = uiState.asrMode == AsrMode.ON_DEVICE,
            onClick = { onSelectAsrMode(AsrMode.ON_DEVICE) },
        )
        SelectableRow(
            title = stringResource(R.string.asr_mode_cloud),
            description = stringResource(R.string.asr_mode_cloud_description),
            selected = uiState.asrMode == AsrMode.CLOUD,
            onClick = { onSelectAsrMode(AsrMode.CLOUD) },
        )

        if (uiState.asrMode == AsrMode.CLOUD) {
            Spacer(Modifier.height(12.dp))
            CloudKeyPanel(
                missingTitle = R.string.cloud_key_missing_title,
                missingBody = R.string.cloud_key_missing_body,
                presentText = R.string.cloud_key_present,
                hasKey = uiState.hasCloudKey,
                onSaveKey = onSaveCloudKey,
                onRemoveKey = onRemoveCloudKey,
            )
        }
    }
}

/**
 * First-class "add your key" state: a cloud mode is never silently unavailable, the
 * user always sees why it needs their key and how to provide it.
 *
 * Shared by cloud recognition and cloud clean-up; only the explanatory strings differ.
 */
@Composable
private fun CloudKeyPanel(
    @StringRes missingTitle: Int,
    @StringRes missingBody: Int,
    @StringRes presentText: Int,
    hasKey: Boolean,
    onSaveKey: (String) -> Boolean,
    onRemoveKey: () -> Unit,
) {
    var keyInput by rememberSaveable { mutableStateOf("") }
    var showEmptyError by remember { mutableStateOf(false) }

    Panel {
        if (hasKey) {
            Text(
                text = stringResource(presentText),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(onClick = onRemoveKey) {
                Text(stringResource(R.string.cloud_key_remove))
            }
        } else {
            Text(
                text = stringResource(missingTitle),
                style = MaterialTheme.typography.titleSmall,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                text = stringResource(missingBody),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = keyInput,
                onValueChange = {
                    keyInput = it
                    showEmptyError = false
                },
                label = { Text(stringResource(R.string.cloud_key_label)) },
                placeholder = { Text(stringResource(R.string.cloud_key_placeholder)) },
                singleLine = true,
                isError = showEmptyError,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
            )
            if (showEmptyError) {
                Text(
                    text = stringResource(R.string.cloud_key_empty_error),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val saved = onSaveKey(keyInput)
                    showEmptyError = !saved
                    if (saved) keyInput = ""
                },
            ) {
                Text(stringResource(R.string.cloud_key_save))
            }
        }

        Text(
            text = stringResource(R.string.cloud_key_storage_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 12.dp),
        )
    }
}

@Composable
private fun LlmSection(
    uiState: HomeUiState,
    onLlmEnabledChange: (Boolean) -> Unit,
    onLlmModeChange: (LlmMode) -> Unit,
    onSaveLlmKey: (String) -> Boolean,
    onRemoveLlmKey: () -> Unit,
) {
    Section(stringResource(R.string.section_llm)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.llm_toggle_title),
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = stringResource(R.string.llm_toggle_description),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = uiState.llmEnabled,
                onCheckedChange = onLlmEnabledChange,
            )
        }

        if (uiState.llmEnabled) {
            Spacer(Modifier.height(8.dp))
            SelectableRow(
                title = stringResource(R.string.llm_mode_on_device),
                description = stringResource(R.string.llm_mode_on_device_description),
                selected = uiState.llmMode == LlmMode.ON_DEVICE,
                onClick = { onLlmModeChange(LlmMode.ON_DEVICE) },
            )
            SelectableRow(
                title = stringResource(R.string.llm_mode_cloud),
                description = stringResource(R.string.llm_mode_cloud_description),
                selected = uiState.llmMode == LlmMode.CLOUD,
                onClick = { onLlmModeChange(LlmMode.CLOUD) },
            )

            if (uiState.llmMode == LlmMode.CLOUD) {
                Spacer(Modifier.height(12.dp))
                CloudKeyPanel(
                    missingTitle = R.string.llm_key_missing_title,
                    missingBody = R.string.llm_key_missing_body,
                    presentText = R.string.llm_key_present,
                    hasKey = uiState.hasLlmKey,
                    onSaveKey = onSaveLlmKey,
                    onRemoveKey = onRemoveLlmKey,
                )
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.llm_budget_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun InsertionSection(
    uiState: HomeUiState,
    onOpenAccessibilitySettings: () -> Unit,
    onOpenAppSettings: () -> Unit,
) {
    Section(stringResource(R.string.section_insertion)) {
        Text(
            text = stringResource(R.string.insertion_accessibility_title),
            style = MaterialTheme.typography.bodyLarge,
        )
        Text(
            text = stringResource(
                if (uiState.permissions.accessibility) {
                    R.string.insertion_accessibility_granted
                } else {
                    R.string.insertion_accessibility_denied
                },
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            modifier = Modifier.padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TextButton(onClick = onOpenAccessibilitySettings) {
                Text(stringResource(R.string.permission_open_settings))
            }
            TextButton(onClick = onOpenAppSettings) {
                Text(stringResource(R.string.permission_grant))
            }
        }
        Text(
            text = stringResource(R.string.insertion_clipboard_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun SelectableRow(
    title: String,
    description: String?,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(modifier = Modifier.padding(start = 4.dp)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            if (description != null) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.padding(top = 24.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
}

@Composable
private fun Panel(content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
    ) {
        Column(modifier = Modifier.padding(16.dp), content = content)
    }
}

@Composable
private fun TextBlock(
    title: String,
    body: String,
    action: @Composable () -> Unit,
) {
    Panel {
        Text(text = title, style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(4.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        action()
    }
}

@Composable
private fun captureStateText(state: CaptureState): String = when (state) {
    CaptureState.Idle -> stringResource(R.string.state_idle)
    CaptureState.Listening -> stringResource(R.string.state_listening)
    CaptureState.Processing -> stringResource(R.string.state_processing)
    is CaptureState.Inserted -> stringResource(R.string.state_inserted)
    is CaptureState.Copied -> stringResource(R.string.state_copied)
    CaptureState.NeedsKey -> stringResource(R.string.state_needs_key)
    is CaptureState.Error -> state.message
}
