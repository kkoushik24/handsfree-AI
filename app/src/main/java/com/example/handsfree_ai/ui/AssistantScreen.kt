package com.example.handsfree_ai.ui

import android.app.Activity
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.handsfree_ai.BuildConfig
import com.example.handsfree_ai.R

data class AssistantConversationItem(val text: String, val fromUser: Boolean)

data class AssistantClarification(
    val question: String,
    val appChoices: List<ClarificationAppChoice> = emptyList()
)

data class AssistantDiagnostics(
    val connectionStatus: String = "",
    val lastProposedAction: String = "",
    val actionHistory: List<DiagnosticActionRecord> = emptyList(),
    val observation: DiagnosticScreenObservation? = null
)

@Composable
fun AssistantScreen(
    modifier: Modifier = Modifier,
    isConnected: Boolean = false,
    isLoading: Boolean = false,
    connectionMessage: String? = null,
    isTaskRunning: Boolean = false,
    taskStatus: String? = null,
    conversation: List<AssistantConversationItem> = emptyList(),
    resultMessage: String? = null,
    resultIsError: Boolean = false,
    screenAccessEnabled: Boolean = false,
    modelSetupState: ModelSetupUiState = ModelSetupUiState(failureRes = R.string.assistant_model_not_connected),
    pendingClarification: AssistantClarification? = null,
    pendingConfirmation: String? = null,
    diagnostics: AssistantDiagnostics = AssistantDiagnostics(),
    onSubmitTask: ((String) -> Unit)? = null,
    onStartConversation: (() -> Unit)? = null,
    onStopTask: (() -> Unit)? = null,
    onClarificationContinue: ((String) -> Unit)? = null,
    onClarificationAppSelected: ((ClarificationAppChoice) -> Unit)? = null,
    onClarificationListenAgain: (() -> Unit)? = null,
    onClarificationStop: (() -> Unit)? = null,
    onClarificationDismiss: (() -> Unit)? = null,
    onConfirmationApprove: (() -> Unit)? = null,
    onConfirmationDecline: (() -> Unit)? = null,
    onConfirmationStop: (() -> Unit)? = null,
    onConfirmationDismiss: (() -> Unit)? = null,
    onInspectApp: ((String) -> Unit)? = null,
    modelSetupCallbacks: ModelSetupCallbacks = ModelSetupCallbacks()
) {
    val context = LocalContext.current
    var selectedTab by rememberSaveable { mutableStateOf(AssistantTab.ASSISTANT) }
    var taskInput by rememberSaveable { mutableStateOf("") }
    var settingsVisible by rememberSaveable { mutableStateOf(false) }
    var screenDisclosureVisible by rememberSaveable { mutableStateOf(false) }
    var voiceDisclosureVisible by rememberSaveable { mutableStateOf(false) }
    var modelSetupVisible by rememberSaveable { mutableStateOf(false) }
    var diagnosticsVisible by rememberSaveable { mutableStateOf(false) }
    var voiceEnabled by rememberSaveable { mutableStateOf(false) }
    var localConnectionNote by remember { mutableStateOf<String?>(null) }
    val tabStateHolder = rememberSaveableStateHolder()

    BackHandler(enabled = selectedTab != AssistantTab.ASSISTANT) {
        selectedTab = AssistantTab.ASSISTANT
    }

    val language = AppLanguage.code(context)
    val statusText = when {
        isLoading -> context.uiText(R.string.assistant_loading)
        isTaskRunning -> taskStatus ?: context.uiText(R.string.assistant_working)
        localConnectionNote != null -> localConnectionNote!!
        connectionMessage != null -> connectionMessage
        isConnected -> context.uiText(R.string.assistant_connected)
        else -> context.uiText(R.string.assistant_not_connected)
    }

    Column(modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(painterResource(R.drawable.handsfree_logo), null, Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Text(context.uiText(R.string.assistant_name), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            TextButton(onClick = { settingsVisible = true }) { Text(context.uiText(R.string.settings)) }
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { selectedTab = AssistantTab.ASSISTANT }) { Text(context.uiText(R.string.home)) }
            FilterChip(
                selected = selectedTab == AssistantTab.ASSISTANT,
                onClick = { selectedTab = AssistantTab.ASSISTANT },
                label = { Text(context.uiText(R.string.assistant)) }
            )
            FilterChip(
                selected = selectedTab == AssistantTab.APPS,
                onClick = { selectedTab = AssistantTab.APPS },
                label = { Text(context.uiText(R.string.your_apps)) }
            )
        }

        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(context.uiText(R.string.language), style = MaterialTheme.typography.labelLarge)
            FilterChip(
                selected = language == "en-IN",
                enabled = !isTaskRunning,
                onClick = { (context as? Activity)?.let { AppLanguage.select(it, "en-IN") } },
                label = { Text(context.uiText(R.string.language_english)) }
            )
            FilterChip(
                selected = language == "kn-IN",
                enabled = !isTaskRunning,
                onClick = { (context as? Activity)?.let { AppLanguage.select(it, "kn-IN") } },
                label = { Text(context.uiText(R.string.language_kannada)) }
            )
        }

        tabStateHolder.SaveableStateProvider(selectedTab.name) {
        if (selectedTab == AssistantTab.APPS) {
            AppBrowser(Modifier.weight(1f))
        } else {
            val scrollState = rememberScrollState()
            androidx.compose.runtime.LaunchedEffect(conversation, resultMessage, selectedTab) {
                if (conversation.isNotEmpty() || resultMessage != null) scrollState.animateScrollTo(scrollState.maxValue)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(scrollState)
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Surface(shape = RoundedCornerShape(40.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                    Row(Modifier.padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).background(
                            if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                            CircleShape
                        ))
                        Spacer(Modifier.width(8.dp))
                        Text(statusText, style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite })
                    }
                }

                if (conversation.isEmpty()) {
                    Text(context.uiText(R.string.hero), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.SemiBold)
                    Text(context.uiText(R.string.intro), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Card(shape = RoundedCornerShape(22.dp)) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(context.uiText(R.string.assistant_help_title), style = MaterialTheme.typography.titleMedium)
                            Text(context.uiText(R.string.assistant_help_body), style = MaterialTheme.typography.bodyMedium)
                            TextButton(onClick = { taskInput = context.uiText(R.string.food_help) }, contentPadding = PaddingValues(0.dp)) {
                                Text(context.uiText(R.string.food_help_button))
                            }
                        }
                    }
                }

                if (!screenAccessEnabled) {
                    Card(shape = RoundedCornerShape(20.dp)) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(context.uiText(R.string.screen_title), style = MaterialTheme.typography.titleMedium)
                            Text(context.uiText(R.string.screen_intro), style = MaterialTheme.typography.bodyMedium)
                            Button(onClick = { screenDisclosureVisible = true }) { Text(context.uiText(R.string.screen_setup)) }
                        }
                    }
                }

                if (!isConnected) {
                    Text(context.uiText(R.string.connection_help), style = MaterialTheme.typography.bodyMedium)
                    Text(context.uiText(R.string.assistant_not_connected_hint), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }

                conversation.forEach { message ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start) {
                        Surface(
                            Modifier.heightIn(min = 40.dp),
                            shape = RoundedCornerShape(20.dp),
                            color = if (message.fromUser) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
                        ) {
                            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(context.uiText(if (message.fromUser) R.string.you else R.string.assistant_name),
                                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                Text(message.text, style = MaterialTheme.typography.bodyLarge)
                            }
                        }
                    }
                }

                if (isLoading) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(context.uiText(R.string.assistant_loading), style = MaterialTheme.typography.bodyMedium)
                } else if (isTaskRunning) {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Text(context.uiText(R.string.working_hint), style = MaterialTheme.typography.bodyMedium)
                }
                resultMessage?.let { result ->
                    Text(
                        context.uiText(if (resultIsError) R.string.assistant_error_title else R.string.assistant_result_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = if (resultIsError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                    )
                    Text(result, style = MaterialTheme.typography.bodyMedium)
                }
                localConnectionNote?.let { note ->
                    Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            Surface(shadowElevation = 5.dp, color = MaterialTheme.colorScheme.surfaceContainerLow) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (!isTaskRunning) {
                        OutlinedTextField(
                            value = taskInput,
                            onValueChange = { taskInput = it.take(2000); localConnectionNote = null },
                            placeholder = { Text(context.uiText(R.string.instruction)) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 1,
                            maxLines = 3,
                            shape = RoundedCornerShape(18.dp)
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (isTaskRunning) {
                            Button(
                                onClick = { onStopTask?.invoke() },
                                enabled = onStopTask != null,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                            ) { Text(context.uiText(R.string.stop)) }
                        } else {
                            OutlinedButton(
                                onClick = { onStartConversation?.invoke() },
                                enabled = onStartConversation != null && !isLoading,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                            ) { Text(context.uiText(R.string.start_conversation)) }
                            Button(
                                onClick = {
                                    val task = taskInput.trim()
                                    if (task.isNotEmpty()) onSubmitTask?.invoke(task)
                                },
                                enabled = taskInput.isNotBlank() && onSubmitTask != null && isConnected && !isLoading,
                                modifier = Modifier.weight(1f).heightIn(min = 52.dp)
                            ) { Text(context.uiText(R.string.send_task)) }
                        }
                    }
                    Text(
                        context.uiText(if (onStartConversation == null) R.string.assistant_voice_not_connected else R.string.start_hint),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
        }
    }

    if (settingsVisible) SettingsDialog(
        voiceEnabled = voiceEnabled,
        onVoiceEnabledChange = { enabled ->
            if (enabled) { settingsVisible = false; voiceDisclosureVisible = true } else voiceEnabled = false
        },
        selectedLanguage = language,
        onSelectLanguage = { code -> (context as? Activity)?.let { AppLanguage.select(it, code) } },
        screenAccessEnabled = screenAccessEnabled,
        onOpenScreenSettings = { settingsVisible = false; screenDisclosureVisible = true },
        onOpenModelSetup = { settingsVisible = false; modelSetupVisible = true },
        onDismiss = { settingsVisible = false },
        isTaskRunning = isTaskRunning,
        isModelSetupEnabled = !isTaskRunning && !modelSetupState.isBusy,
        onOpenDiagnostics = if (BuildConfig.DEBUG) ({ settingsVisible = false; diagnosticsVisible = true }) else null
    )

    if (screenDisclosureVisible) ScreenAccessDisclosureDialog(
        onConfirm = {
            screenDisclosureVisible = false
            runCatching { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
                .onFailure { localConnectionNote = context.uiText(R.string.assistant_settings_unavailable) }
        },
        onDismiss = { screenDisclosureVisible = false }
    )

    if (voiceDisclosureVisible) VoiceDisclosureDialog(
        onConfirm = {
            voiceDisclosureVisible = false
            voiceEnabled = true
            localConnectionNote = context.uiText(R.string.assistant_voice_not_connected)
        },
        onDismiss = { voiceDisclosureVisible = false; voiceEnabled = false }
    )

    if (modelSetupVisible) ModelSetupDialog(
        onClose = { modelSetupVisible = false },
        uiState = modelSetupState,
        onOpenDownloadPage = modelSetupCallbacks.onOpenDownloadPage,
        onModelSelected = modelSetupCallbacks.onModelSelected,
        onRemoveModel = modelSetupCallbacks.onRemoveModel,
        onCancelOperation = modelSetupCallbacks.onCancelOperation
    )

    var clarificationDismissed by remember(pendingClarification) { mutableStateOf(false) }
    pendingClarification?.takeUnless { clarificationDismissed }?.let { pending ->
        ClarificationDialog(
            question = pending.question,
            appChoices = pending.appChoices,
            onContinue = onClarificationContinue?.let { callback ->
                { answer: String ->
                    callback(answer)
                    clarificationDismissed = true
                }
            },
            onAppSelected = onClarificationAppSelected,
            onListenAgain = onClarificationListenAgain,
            onStop = onClarificationStop?.let { callback ->
                {
                    callback()
                    clarificationDismissed = true
                }
            },
            onDismiss = {
                onClarificationDismiss?.invoke()
                clarificationDismissed = true
            }
        )
    }

    var confirmationDismissed by remember(pendingConfirmation) { mutableStateOf(false) }
    pendingConfirmation?.takeUnless { confirmationDismissed }?.let { message ->
        ConfirmationDialog(
            message = message,
            onApprove = onConfirmationApprove?.let { callback ->
                {
                    callback()
                    confirmationDismissed = true
                }
            },
            onDecline = onConfirmationDecline?.let { callback ->
                {
                    callback()
                    confirmationDismissed = true
                }
            },
            onStop = onConfirmationStop?.let { callback ->
                {
                    callback()
                    confirmationDismissed = true
                }
            },
            onDismiss = {
                onConfirmationDismiss?.invoke()
                confirmationDismissed = true
            }
        )
    }

    if (diagnosticsVisible && BuildConfig.DEBUG) DeveloperToolsDialog(
        connectionStatus = diagnostics.connectionStatus.ifBlank { context.uiText(R.string.assistant_not_connected) },
        lastProposedAction = diagnostics.lastProposedAction,
        actionHistory = diagnostics.actionHistory,
        observation = diagnostics.observation,
        onInspectApp = onInspectApp,
        onDismiss = { diagnosticsVisible = false }
    )
}

data class ModelSetupCallbacks(
    val onOpenDownloadPage: (() -> Unit)? = null,
    val onModelSelected: ((android.net.Uri) -> Unit)? = null,
    val onRemoveModel: (() -> Unit)? = null,
    val onCancelOperation: (() -> Unit)? = null
)

private enum class AssistantTab { ASSISTANT, APPS }
