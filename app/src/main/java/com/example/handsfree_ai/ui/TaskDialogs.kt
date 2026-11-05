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

/** An app choice supplied by the host screen; this UI does not discover installed apps. */
data class ClarificationAppChoice(val id: String, val name: String)

/** Screen details supplied by the host for developer diagnostics. */
data class DiagnosticScreenElement(
    val id: String,
    val text: String = "",
    val description: String = "",
    val actions: List<String> = emptyList()
)

data class DiagnosticScreenObservation(
    val packageName: String = "",
    val observationId: String = "",
    val blockedReason: String? = null,
    val elements: List<DiagnosticScreenElement> = emptyList()
)

data class DiagnosticActionRecord(val action: String, val result: String)

@Composable
fun ClarificationDialog(
    question: String,
    appChoices: List<ClarificationAppChoice> = emptyList(),
    onContinue: (String) -> Unit,
    onAppSelected: (ClarificationAppChoice) -> Unit,
    onListenAgain: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var answer by remember(question) { mutableStateOf("") }
    var selectedApp by remember(question) { mutableStateOf<ClarificationAppChoice?>(null) }
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
                            onAppSelected(app)
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
                TextButton(onClick = onListenAgain) { Text(context.uiText(R.string.listen_again)) }
            }
        },
        confirmButton = {
            TextButton(enabled = answer.isNotBlank(), onClick = { onContinue(answer.trim()) }) {
                Text(context.uiText(R.string.continue_button))
            }
        },
        dismissButton = { TextButton(onClick = onStop) { Text(context.uiText(R.string.stop)) } }
    )
}

@Composable
fun ConfirmationDialog(
    message: String,
    onApprove: () -> Unit,
    onDecline: () -> Unit,
    onStop: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(context.uiText(R.string.confirmation_title)) },
        text = { Text(message) },
        confirmButton = {
            Button(onClick = onApprove) { Text(context.uiText(R.string.approve)) }
        },
        dismissButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                TextButton(onClick = onDecline) { Text(context.uiText(R.string.decline)) }
                TextButton(onClick = onStop) { Text(context.uiText(R.string.stop)) }
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
    observation: DiagnosticScreenObservation?,
    onInspectApp: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!BuildConfig.DEBUG) return
    val context = LocalContext.current
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
                if (actionHistory.isEmpty()) Text(context.uiText(R.string.diagnostics_empty))
                actionHistory.forEach { item ->
                    Text(context.uiText(R.string.diagnostics_history_item, item.action, item.result), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                Text(context.uiText(R.string.diagnostics_observation))
                if (observation == null) {
                    Text(context.uiText(R.string.diagnostics_empty))
                } else {
                    Text(context.uiText(R.string.diagnostics_package, observation.packageName.ifBlank { context.uiText(R.string.diagnostics_unavailable) }))
                    Text(context.uiText(R.string.diagnostics_observation_id, observation.observationId.ifBlank { context.uiText(R.string.diagnostics_unavailable) }))
                    Text(context.uiText(R.string.diagnostics_blocked_reason, observation.blockedReason?.takeIf(String::isNotBlank) ?: context.uiText(R.string.diagnostics_none)))
                    if (observation.elements.isEmpty()) Text(context.uiText(R.string.diagnostics_no_elements))
                    observation.elements.forEach { element ->
                        Text(context.uiText(R.string.diagnostics_element, element.id, element.text.ifBlank { context.uiText(R.string.diagnostics_none) }, element.description.ifBlank { context.uiText(R.string.diagnostics_none) }, element.actions.joinToString().ifBlank { context.uiText(R.string.diagnostics_none) }), style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
                OutlinedTextField(
                    value = appQuery,
                    onValueChange = { appQuery = it },
                    label = { Text(context.uiText(R.string.diagnostics_inspect_label)) },
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(enabled = appQuery.isNotBlank(), onClick = { onInspectApp(appQuery.trim()) }) {
                    Text(context.uiText(R.string.diagnostics_inspect))
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(context.uiText(R.string.close)) } }
    )
}
