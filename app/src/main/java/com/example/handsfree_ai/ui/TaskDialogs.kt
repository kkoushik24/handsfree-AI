package com.example.handsfree_ai.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.handsfree_ai.BuildConfig
import com.example.handsfree_ai.R
import com.example.handsfree_ai.accessibility.AccessibilityElementMapper

/** An app choice supplied by the host screen; this UI does not discover installed apps. */
data class ClarificationAppChoice(val id: String, val name: String)

@Composable
fun ClarificationDialog(
    question: String,
    appChoices: List<ClarificationAppChoice> = emptyList(),
    onContinue: ((String) -> Unit)?,
    onAppSelected: ((ClarificationAppChoice) -> Unit)?,
    onListenAgain: (() -> Unit)?,
    onStop: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var answer by remember(question, appChoices) { mutableStateOf("") }
    var selectedApp by remember(question, appChoices) { mutableStateOf<ClarificationAppChoice?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(context.uiText(R.string.clarification_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(question)
                appChoices.forEach { app ->
                    OutlinedButton(
                        onClick = {
                            selectedApp = app
                            answer = app.name
                            onAppSelected?.invoke(app)
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(if (selectedApp?.id == app.id) "✓ ${app.name}" else app.name) }
                }
                OutlinedTextField(
                    value = answer,
                    onValueChange = { answer = it; selectedApp = null },
                    label = { Text(context.uiText(R.string.your_reply)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(enabled = onListenAgain != null, onClick = { onListenAgain?.invoke() }) {
                    Text(context.uiText(R.string.listen_again))
                }
                if (onContinue == null || onListenAgain == null || onStop == null) {
                    Text(context.uiText(R.string.clarification_integration_unavailable),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(enabled = answer.isNotBlank() && onContinue != null, onClick = { onContinue?.invoke(answer.trim()) }) {
                Text(context.uiText(R.string.continue_button))
            }
        },
        dismissButton = {
            TextButton(enabled = onStop != null, onClick = { onStop?.invoke() }) { Text(context.uiText(R.string.stop)) }
        }
    )
}

@Composable
fun ConfirmationDialog(
    message: String,
    onApprove: (() -> Unit)?,
    onDecline: (() -> Unit)?,
    onStop: (() -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(context.uiText(R.string.confirmation_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(message)
                if (onApprove == null || onDecline == null || onStop == null) {
                    Text(context.uiText(R.string.confirmation_integration_unavailable),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            Button(enabled = onApprove != null, onClick = { onApprove?.invoke() }) {
                Text(context.uiText(R.string.approve))
            }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(enabled = onDecline != null, onClick = { onDecline?.invoke() }) {
                    Text(context.uiText(R.string.decline))
                }
                TextButton(enabled = onStop != null, onClick = { onStop?.invoke() }) {
                    Text(context.uiText(R.string.stop))
                }
            }
        }
    )
}

/** Debug builds only. All diagnostic values and inspection behavior come from the host. */
@Composable
fun DeveloperToolsDialog(
    connectionStatus: String,
    lastProposedAction: String,
    actionHistory: List<DiagnosticActionRecord>,
    observation: SanitizedDiagnosticObservation?,
    onInspectApp: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
    val safeObservation = remember(observation) { observation?.value }
    val safeActionHistory = immutableDiagnosticList(actionHistory)
    var appQuery by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(context.uiText(R.string.developer_tools)) },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(context.uiText(R.string.diagnostics_connection, connectionStatus.ifBlank { context.uiText(R.string.diagnostics_unavailable) }))
                Text(context.uiText(R.string.diagnostics_last_action, lastProposedAction.ifBlank { context.uiText(R.string.diagnostics_none) }))
                Text(context.uiText(R.string.diagnostics_history))
                if (safeActionHistory.isEmpty()) Text(context.uiText(R.string.diagnostics_empty))
                safeActionHistory.forEach { item ->
                    Text(context.uiText(R.string.diagnostics_history_item, item.action, item.result), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                Text(context.uiText(R.string.diagnostics_observation))
                if (safeObservation == null) {
                    Text(context.uiText(R.string.diagnostics_empty))
                } else {
                    Text(context.uiText(R.string.diagnostics_package, safeObservation.packageName.ifBlank { context.uiText(R.string.diagnostics_unavailable) }))
                    Text(context.uiText(R.string.diagnostics_observation_id, safeObservation.observationId.ifBlank { context.uiText(R.string.diagnostics_unavailable) }))
                    Text(context.uiText(R.string.diagnostics_screen_hash, safeObservation.screenHash.ifBlank { context.uiText(R.string.diagnostics_unavailable) }))
                    Text(context.uiText(R.string.diagnostics_blocked_reason, safeObservation.blockedReason?.takeIf(String::isNotBlank) ?: context.uiText(R.string.diagnostics_none)))
                    Text(context.uiText(if (safeObservation.truncated) R.string.diagnostics_observation_truncated else R.string.diagnostics_observation_complete))
                    if (safeObservation.elements.isEmpty()) Text(context.uiText(R.string.diagnostics_no_elements))
                    safeObservation.elements.forEach { element ->
                        Text(context.uiText(
                            R.string.diagnostics_element,
                            element.id,
                            element.text.ifBlank { context.uiText(R.string.diagnostics_none) },
                            element.description.ifBlank { context.uiText(R.string.diagnostics_none) },
                            element.bounds.joinToString(", ").ifBlank { context.uiText(R.string.diagnostics_none) },
                            context.uiText(if (element.clickable) R.string.diagnostics_yes else R.string.diagnostics_no),
                            context.uiText(if (element.editable) R.string.diagnostics_yes else R.string.diagnostics_no),
                            context.uiText(if (element.sensitive) R.string.diagnostics_yes else R.string.diagnostics_no),
                            element.actions.joinToString().ifBlank { context.uiText(R.string.diagnostics_none) }
                        ), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    label = { Text(context.uiText(R.string.diagnostics_inspect_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(
                    enabled = appQuery.isNotBlank() && onInspectApp != null,
                    onClick = { onInspectApp?.invoke(appQuery.trim()) }
                ) {
                    Text(context.uiText(R.string.diagnostics_inspect))
                }
                if (onInspectApp == null) {
                    Text(context.uiText(R.string.diagnostics_inspect_unavailable),
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.uiText(R.string.close)) } }
    )
}
