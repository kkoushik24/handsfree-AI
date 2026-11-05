package com.example.handsfree_ai.ui

import com.example.handsfree_ai.accessibility.AccessibilityElementMapper
import com.example.handsfree_ai.accessibility.LiveServiceObservationSnapshot
import com.example.handsfree_ai.accessibility.ObservedUiElement
import java.util.ArrayList
import java.util.Collections

/** Caller-facing input model. Its display strings are treated as untrusted. */
data class DiagnosticScreenElement(
    val id: String,
    val text: String = "",
    val description: String = "",
    val actions: List<String> = emptyList(),
    val bounds: List<Int> = emptyList(),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val sensitive: Boolean = false,
    val parentId: String? = null,
    val hint: String = "",
    val resourceName: String = "",
    val password: Boolean = false,
    val inputType: Int = 0
)

data class DiagnosticScreenObservation(
    val packageName: String = "",
    val observationId: String = "",
    val blockedReason: String? = null,
    val elements: List<DiagnosticScreenElement> = emptyList(),
    val screenHash: String = "",
    val truncated: Boolean = false
)

data class DiagnosticActionRecord(val action: String, val result: String)

/** Returns a defensive copy whose runtime implementation rejects MutableList writes. */
internal fun <T> immutableDiagnosticList(values: Collection<T>): List<T> =
    Collections.unmodifiableList(ArrayList(values))

/** Only sanitizers can create this type; Developer Tools accepts this type instead of raw input. */
class SanitizedDiagnosticObservation private constructor(
    val value: DiagnosticScreenObservation
) {
    companion object {
        fun fromCallerInput(input: DiagnosticScreenObservation?): SanitizedDiagnosticObservation? {
            input ?: return null
            val sensitiveParents = input.elements.filter { item ->
                isSensitiveElement(item) && !item.editable
            }.mapNotNullTo(mutableSetOf()) { it.parentId }
            val elements = input.elements.take(AccessibilityElementMapper.MAX_ELEMENTS).mapIndexed { index, item ->
                val sensitive = isSensitiveElement(item) ||
                    (item.parentId != null && item.parentId in sensitiveParents &&
                        (item.editable || AccessibilityElementMapper.isCredentialLikeValue(item.text) ||
                            AccessibilityElementMapper.isCredentialLikeValue(item.description))) ||
                    (item.editable && (item.text.isNotBlank() || item.description.isNotBlank()))
                item.copy(
                    id = safeId(item.id, sensitive, index),
                    text = "",
                    description = "",
                    actions = emptyList(),
                    bounds = item.bounds.toList(),
                    parentId = item.parentId?.takeUnless(::isSensitiveDiagnosticString),
                    sensitive = sensitive,
                    hint = "",
                    resourceName = "",
                    password = false,
                    inputType = 0
                )
            }.let(::uniqueIds)
            return create(
                input.copy(
                    packageName = input.packageName.takeUnless(::isSensitiveDiagnosticString).orEmpty(),
                    observationId = input.observationId.takeUnless(::isSensitiveDiagnosticString).orEmpty(),
                    blockedReason = null
                ),
                elements,
                input.truncated || input.elements.size > AccessibilityElementMapper.MAX_ELEMENTS
            )
        }

        /** Called only with the service's sanitized observation state, not caller-provided UI data. */
        internal fun fromLiveService(
            snapshot: LiveServiceObservationSnapshot,
            blockedReason: String? = null
        ): SanitizedDiagnosticObservation {
            val input = snapshot.observation
            val diagnosticInput = DiagnosticScreenObservation(
                packageName = input.packageName,
                observationId = snapshot.observationId,
                blockedReason = blockedReason,
                truncated = input.truncated,
                elements = input.elements.map { item ->
                    DiagnosticScreenElement(
                        id = item.id,
                        text = item.text,
                        description = item.description,
                        bounds = item.bounds,
                        clickable = item.clickable,
                        editable = item.editable,
                        sensitive = item.sensitive,
                        parentId = item.parentId
                    )
                }
            )
            val elements = diagnosticInput.elements.take(AccessibilityElementMapper.MAX_ELEMENTS).mapIndexed { index, item ->
                val sensitive = item.sensitive || AccessibilityElementMapper.isSensitiveData(
                    text = item.text,
                    description = item.description,
                    resourceName = item.id
                ) || AccessibilityElementMapper.isLikelySensitiveValue(item.text) ||
                    AccessibilityElementMapper.isLikelySensitiveValue(item.description)
                item.copy(
                    id = safeId(item.id, sensitive, index),
                    text = if (sensitive) "" else item.text,
                    description = if (sensitive) "" else item.description,
                    actions = if (sensitive) emptyList() else item.actions.filterNot(::isSensitiveDiagnosticString),
                    sensitive = sensitive,
                    bounds = item.bounds.toList()
                )
            }.let(::uniqueIds)
            return create(diagnosticInput, elements, input.truncated || diagnosticInput.elements.size > AccessibilityElementMapper.MAX_ELEMENTS)
        }

        private fun create(
            input: DiagnosticScreenObservation,
            elements: List<DiagnosticScreenElement>,
            truncated: Boolean
        ): SanitizedDiagnosticObservation {
            val safeBlockedReason = input.blockedReason?.takeUnless(::isSensitiveDiagnosticString)
            val withSafeActions = elements.map { item ->
                if (item.sensitive) item.copy(actions = emptyList())
                else item.copy(actions = item.actions.filterNot(::isSensitiveDiagnosticString))
            }
            val hashElements = withSafeActions.map { item ->
                ObservedUiElement(
                    id = item.id,
                    text = item.text,
                    description = item.description,
                    bounds = item.bounds,
                    clickable = item.clickable,
                    editable = item.editable,
                    sensitive = item.sensitive,
                    parentId = item.parentId,
                    actions = item.actions
                )
            }
            val immutableElements = immutableDiagnosticList(withSafeActions.map { item ->
                item.copy(
                    actions = immutableDiagnosticList(item.actions),
                    bounds = immutableDiagnosticList(item.bounds)
                )
            })
            val safeValue = input.copy(
                blockedReason = safeBlockedReason,
                elements = immutableElements,
                screenHash = AccessibilityElementMapper.screenHash(input.packageName, hashElements, truncated),
                truncated = truncated
            )
            return SanitizedDiagnosticObservation(safeValue)
        }

        private fun uniqueIds(elements: List<DiagnosticScreenElement>): List<DiagnosticScreenElement> {
            val used = mutableSetOf<String>()
            return elements.mapIndexed { index, element ->
                val base = element.id.ifBlank { "e_$index" }
                var id = base
                var suffix = 2
                while (!used.add(id)) id = "${base}_${suffix++}"
                element.copy(id = id)
            }
        }

        private fun safeId(id: String, sensitive: Boolean, index: Int): String =
            if (sensitive && AccessibilityElementMapper.containsSensitiveKeyword(id)) "e_redacted_$index" else id

        private fun isSensitiveElement(item: DiagnosticScreenElement): Boolean =
            AccessibilityElementMapper.isSensitiveData(
                text = item.text,
                description = item.description,
                hint = item.hint,
                resourceName = item.resourceName.ifBlank { item.id },
                password = item.password,
                inputType = item.inputType,
                explicitlySensitive = item.sensitive
            )

        private fun isSensitiveDiagnosticString(value: String): Boolean =
            AccessibilityElementMapper.containsSensitiveKeyword(value) ||
                AccessibilityElementMapper.isLikelySensitiveValue(value)
    }
}

/** All untrusted text is omitted; labels/values are retained only for service-originated snapshots. */
fun sanitizeDiagnosticObservation(observation: DiagnosticScreenObservation?): SanitizedDiagnosticObservation? =
    SanitizedDiagnosticObservation.fromCallerInput(observation)
