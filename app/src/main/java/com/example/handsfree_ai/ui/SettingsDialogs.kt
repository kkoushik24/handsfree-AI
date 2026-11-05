package com.example.handsfree_ai.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.handsfree_ai.BuildConfig
import com.example.handsfree_ai.R

/**
 * Personalization / Settings Dialog.
 *
 * Displays voice preference toggle, language selection (English/Kannada),
 * screen-access status, links to screen settings and model setup, and optional
 * developer tools in debug builds.
 */
@Composable
fun SettingsDialog(
    voiceEnabled: Boolean,
    onVoiceEnabledChange: (Boolean) -> Unit,
    selectedLanguage: String,
    onSelectLanguage: (String) -> Unit,
    screenAccessEnabled: Boolean,
    onOpenScreenSettings: () -> Unit,
    onOpenModelSetup: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    isTaskRunning: Boolean = false,
    isModelSetupEnabled: Boolean = true,
    showDebugTools: Boolean = BuildConfig.DEBUG,
    onOpenDiagnostics: (() -> Unit)? = null
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(context.uiText(R.string.personalize)) },
        text = {
            Column(
                modifier = modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(context.uiText(R.string.voice_conversation), Modifier.weight(1f))
                    Switch(
                        checked = voiceEnabled,
                        onCheckedChange = onVoiceEnabledChange
                    )
                }
                Text(context.uiText(R.string.voice_help), style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = selectedLanguage == "en-IN",
                        enabled = !isTaskRunning,
                        onClick = { onSelectLanguage("en-IN") },
                        label = { Text("English") }
                    )
                    FilterChip(
                        selected = selectedLanguage == "kn-IN",
                        enabled = !isTaskRunning,
                        onClick = { onSelectLanguage("kn-IN") },
                        label = { Text("ಕನ್ನಡ") }
                    )
                }
                if (isTaskRunning) {
                    Text(context.uiText(R.string.language_locked), style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    if (screenAccessEnabled) context.uiText(R.string.screen_on)
                    else context.uiText(R.string.screen_needed)
                )
                TextButton(onClick = onOpenScreenSettings) {
                    Text(context.uiText(R.string.screen_settings))
                }
                TextButton(
                    enabled = isModelSetupEnabled,
                    onClick = onOpenModelSetup
                ) {
                    Text(context.uiText(R.string.model_setup))
                }
                if (showDebugTools && onOpenDiagnostics != null) {
                    TextButton(onClick = onOpenDiagnostics) {
                        Text(context.uiText(R.string.developer_tools))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(context.uiText(R.string.done))
            }
        }
    )
}

/**
 * Screen Access Disclosure Dialog.
 *
 * Informs the user about screen access boundaries (reading visible controls to assist with
 * tasks started by the user, processing screen information on the connected computer, and allowing
 * access to be stopped at any time) before navigating to Android Accessibility Settings.
 */
@Composable
fun ScreenAccessDisclosureDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(context.uiText(R.string.allow_screen)) },
        text = { Text(context.uiText(R.string.screen_disclosure)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(context.uiText(R.string.open_settings))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.uiText(R.string.not_now))
            }
        }
    )
}

/**
 * Voice Disclosure Dialog.
 *
 * Informs the user about microphone availability during conversation mode, sending speech to the
 * speech service, pausing listening during spoken replies, stopping microphone access, and manual payment
 * boundaries before prompting the runtime audio recording permission.
 */
@Composable
fun VoiceDisclosureDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(context.uiText(R.string.voice_title)) },
        text = { Text(context.uiText(R.string.voice_disclosure)) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(context.uiText(R.string.enable_voice))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(context.uiText(R.string.use_typing))
            }
        }
    )
}
