package com.example.handsfree_ai.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.example.handsfree_ai.R

/**
 * Public URL for downloading the Gemma model artifact.
 */
const val GEMMA_MODEL_SOURCE =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/blob/b3ca0d2f076785a8f4b2219ddbd2bdb99954eae1/gemma-4-E2B-it.litertlm"

/**
 * UI state representation for [ModelSetupDialog].
 */
data class ModelSetupUiState(
    val isBusy: Boolean = false,
    val isImporting: Boolean = false,
    val isVerified: Boolean = false,
    val hasSavedModel: Boolean = false,
    val progress: Float = 0f,
    @get:StringRes val failureRes: Int? = null
)

/**
 * Standalone Model Setup Dialog and Model Removal Confirmation Dialog.
 *
 * Model setup is independent of agent actions and never initiates audio capture.
 * Provides explicit state and callbacks for verification status, document selection,
 * download page navigation, removal, and cancellation.
 */
@Composable
fun ModelSetupDialog(
    onClose: () -> Unit,
    uiState: ModelSetupUiState = ModelSetupUiState(),
    onOpenDownloadPage: (() -> Unit)? = null,
    onModelSelected: ((Uri) -> Unit)? = null,
    onRemoveModel: (() -> Unit)? = null,
    onCancelOperation: (() -> Unit)? = null
) {
    val context = LocalContext.current
    var confirmDelete by remember { mutableStateOf(false) }
    var localFailureRes by remember { mutableStateOf<Int?>(null) }

    val effectiveFailureRes = uiState.failureRes ?: localFailureRes

    val handleDismissOrFinish: () -> Unit = {
        if (uiState.isBusy) {
            onCancelOperation?.invoke()
        }
        onClose()
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            localFailureRes = null
            onModelSelected?.invoke(uri)
        }
    }

    val handleOpenDownloadPage: () -> Unit = onOpenDownloadPage ?: {
        runCatching {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(GEMMA_MODEL_SOURCE)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            localFailureRes = null
        }.onFailure {
            localFailureRes = R.string.model_browser_failed
        }
    }

    AlertDialog(
        onDismissRequest = handleDismissOrFinish,
        title = { Text(context.uiText(R.string.model_setup)) },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(context.uiText(R.string.model_setup_description))
                Text(context.uiText(R.string.model_size))
                Text(
                    context.uiText(
                        effectiveFailureRes ?: when {
                            uiState.isImporting -> R.string.model_importing
                            uiState.isBusy -> R.string.model_checking
                            uiState.isVerified -> R.string.model_verified
                            else -> R.string.model_missing
                        }
                    )
                )

                if (uiState.isImporting) {
                    val constrainedProgress = uiState.progress.coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { constrainedProgress },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (uiState.isBusy) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                OutlinedButton(
                    enabled = !uiState.isBusy,
                    onClick = handleOpenDownloadPage,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.uiText(R.string.model_source))
                }

                Button(
                    enabled = !uiState.isBusy && onModelSelected != null,
                    onClick = { picker.launch(arrayOf("*/*")) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(context.uiText(R.string.model_import))
                }

                if (uiState.hasSavedModel) {
                    TextButton(
                        enabled = !uiState.isBusy && onRemoveModel != null,
                        onClick = { confirmDelete = true }
                    ) {
                        Text(context.uiText(R.string.model_remove))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = handleDismissOrFinish) {
                Text(context.uiText(if (uiState.isBusy) R.string.stop else R.string.done))
            }
        }
    )

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(context.uiText(R.string.model_remove)) },
            text = { Text(context.uiText(R.string.model_remove_confirm)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onRemoveModel?.invoke()
                }) {
                    Text(context.uiText(R.string.model_remove))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) {
                    Text(context.uiText(R.string.not_now))
                }
            }
        )
    }
}
