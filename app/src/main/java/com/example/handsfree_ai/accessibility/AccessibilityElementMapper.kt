package com.example.handsfree_ai.accessibility

import java.security.MessageDigest

data class AccessibilityNodeCandidate(
    val path: String,
    val parentPath: String? = null,
    val text: String = "",
    val description: String = "",
    val hint: String = "",
    val resourceName: String = "",
    val bounds: List<Int> = emptyList(),
    val clickable: Boolean = false,
    val editable: Boolean = false,
    val password: Boolean = false,
    val inputType: Int = 0,
    /** Set by the service when sensitive metadata was redacted before retaining this candidate. */
    val sensitive: Boolean = false
)

data class ObservedUiElement(
    val id: String,
    val text: String,
    val description: String,
    val bounds: List<Int>,
    val clickable: Boolean,
    val editable: Boolean,
    val sensitive: Boolean,
    /** Path within this observation; IDs are observation-scoped and may change if siblings reorder. */
    val parentId: String? = null,
    val actions: List<String> = emptyList()
)

enum class ScreenObservationStatus { OBSERVED, EMPTY, WINDOW_UNAVAILABLE, FAILED }

data class AccessibilityScreenObservation(
    val packageName: String,
    val screenHash: String,
    val elements: List<ObservedUiElement>,
    val status: ScreenObservationStatus,
    val truncated: Boolean = false
)

/** Pure mapper so redaction, element caps, IDs, and hashes can be unit tested without Android. */
object AccessibilityElementMapper {
    const val MAX_ELEMENTS = 40

    private val sensitiveKeyword = Regex(
        "(?i)\\b(?:password|passcode|passphrase|o\\s*t\\s*p|c\\s*v\\s*v(?:\\s*\\d)?|c\\s*v\\s*c(?:\\s*\\d)?|" +
            "security\\s+code|one\\s*time(?:\\s+(?:password|code))?|verification\\s+code|" +
            "payment(?:\\s+(?:card|method|details|information|address))?|" +
            "credit\\s+card|debit\\s+card|card\\s*(?:number|holder|expiry|expiration)|" +
            "name\\s+on\\s+card|billing(?:\\s+(?:address|zip|postal|postcode))?|" +
            "account\\s+number|bank\\s+account|routing\\s+number|iban|sort\\s+code|" +
            "exp(?:iration|iry)?\\s+date|valid\\s+(?:thru|through))\\b"
    )
    private val sensitivePinKeyword = Regex("(?i)(?:^pin$|\\b(?:enter|your|security|card)\\s+pin\\b|\\bpin\\s+(?:code|number)\\b)")
    private val credentialLikeValue = Regex("^\\s*(?:\\d[\\s-]*){3,19}\\s*$")
    private val otpLikeValue = Regex("^\\d(?:[ -]?\\d){5,7}$")
    private val paymentCardValue = Regex("^\\d(?:[ -]?\\d){12,18}$")

    fun isCredentialLikeValue(value: String): Boolean = credentialLikeValue.matches(value)

    /** Detects standalone 6–8 digit codes and 13–19 digit payment-card shaped values. */
    fun isLikelySensitiveValue(value: String): Boolean =
        otpLikeValue.matches(value.trim()) || paymentCardValue.matches(value.trim())

    /** Android InputType masks are constants so this mapper stays usable in local JVM tests. */
    fun hasPasswordInputType(inputType: Int): Boolean {
        val inputClass = inputType and TYPE_MASK_CLASS
        val variation = inputType and TYPE_MASK_VARIATION
        return (inputClass == TYPE_CLASS_TEXT && variation in TEXT_PASSWORD_VARIATIONS) ||
            (inputClass == TYPE_CLASS_NUMBER && variation == NUMBER_VARIATION_PASSWORD)
    }

    fun isExplicitlySensitive(candidate: AccessibilityNodeCandidate): Boolean =
        isSensitiveData(
            text = candidate.text,
            description = candidate.description,
            hint = candidate.hint,
            resourceName = candidate.resourceName,
            password = candidate.password,
            inputType = candidate.inputType,
            explicitlySensitive = candidate.sensitive
        )

    fun containsSensitiveKeyword(value: String): Boolean {
        val normalized = value.replace(Regex("[^A-Za-z0-9]+"), " ").trim()
        return sensitiveKeyword.containsMatchIn(normalized) || sensitivePinKeyword.containsMatchIn(normalized)
    }

    fun isSensitiveData(
        text: String = "",
        description: String = "",
        hint: String = "",
        resourceName: String = "",
        password: Boolean = false,
        inputType: Int = 0,
        explicitlySensitive: Boolean = false
    ): Boolean = explicitlySensitive || password || hasPasswordInputType(inputType) ||
        listOf(text, description, hint, resourceName).any { containsSensitiveKeyword(it) || isLikelySensitiveValue(it) }

    fun map(
        packageName: String,
        candidates: List<AccessibilityNodeCandidate>,
        sourceTruncated: Boolean = false
    ): AccessibilityScreenObservation {
        val explicitSensitive = candidates.filter(::isExplicitlySensitive)
        val sensitivePaths = explicitSensitive.mapTo(mutableSetOf()) { it.path }
        val sensitiveLabelParents = explicitSensitive
            .filterNot { it.editable }
            .mapNotNullTo(mutableSetOf()) { it.parentPath }

        val usedIds = mutableSetOf<String>()
        val elements = candidates.mapNotNull { candidate ->
            val belowSensitiveNode = sensitivePaths.any { path ->
                candidate.path.startsWith("$path/")
            }
            val sensitiveSibling = candidate.parentPath in sensitiveLabelParents &&
                (candidate.editable || credentialLikeValue.matches(candidate.text))
            val isSensitive = isExplicitlySensitive(candidate) || belowSensitiveNode || sensitiveSibling
            val text = if (isSensitive) "" else candidate.text
            val description = if (isSensitive) "" else candidate.description
            if (!isSensitive && text.isBlank() && description.isBlank() && !candidate.clickable && !candidate.editable) {
                null
            } else {
                val pathId = candidate.path.split('/').filter(String::isNotBlank).joinToString("_")
                    .ifBlank { "root" }
                val baseId = "e_$pathId"
                var id = baseId
                var suffix = 2
                while (!usedIds.add(id)) id = "${baseId}_${suffix++}"
                ObservedUiElement(
                    id = id,
                    text = text,
                    description = description,
                    bounds = candidate.bounds.toList(),
                    clickable = candidate.clickable,
                    editable = candidate.editable,
                    sensitive = isSensitive,
                    parentId = candidate.parentPath?.split('/')?.filter(String::isNotBlank)?.joinToString("_")?.let { "e_$it" }
                )
            }
        }.take(MAX_ELEMENTS)
        val truncated = sourceTruncated || candidates.size > MAX_ELEMENTS

        return AccessibilityScreenObservation(
            packageName = packageName,
            screenHash = screenHash(packageName, elements, truncated),
            elements = elements,
            status = if (elements.isEmpty()) ScreenObservationStatus.EMPTY else ScreenObservationStatus.OBSERVED,
            truncated = truncated
        )
    }

    fun screenHash(packageName: String, elements: List<ObservedUiElement>, truncated: Boolean = false): String {
        val canonical = StringBuilder()
        appendField(canonical, packageName)
        appendField(canonical, if (truncated) "truncated" else "complete")
        elements.forEach { element ->
            appendField(canonical, element.id)
            appendField(canonical, element.text)
            appendField(canonical, element.description)
            appendField(canonical, element.bounds.joinToString(","))
            appendField(canonical, if (element.clickable) "1" else "0")
            appendField(canonical, if (element.editable) "1" else "0")
            appendField(canonical, if (element.sensitive) "1" else "0")
            appendField(canonical, element.parentId.orEmpty())
            appendField(canonical, element.actions.size.toString())
            element.actions.forEach { appendField(canonical, it) }
        }
        val bytes = MessageDigest.getInstance("SHA-256").digest(canonical.toString().toByteArray(Charsets.UTF_8))
        val hex = "0123456789abcdef"
        return buildString(bytes.size * 2) {
            bytes.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(hex[value ushr 4])
                append(hex[value and 0x0f])
            }
        }
    }

    private fun appendField(target: StringBuilder, value: String) {
        target.append(value.length).append(':').append(value).append(';')
    }

    private const val TYPE_MASK_CLASS = 0x0000000f
    private const val TYPE_MASK_VARIATION = 0x00000ff0
    private const val TYPE_CLASS_TEXT = 0x00000001
    private const val TYPE_CLASS_NUMBER = 0x00000002
    private const val TEXT_VARIATION_PASSWORD = 0x00000080
    private const val TEXT_VARIATION_VISIBLE_PASSWORD = 0x00000090
    private const val TEXT_VARIATION_WEB_PASSWORD = 0x000000e0
    private const val NUMBER_VARIATION_PASSWORD = 0x00000010
    private val TEXT_PASSWORD_VARIATIONS = setOf(
        TEXT_VARIATION_PASSWORD,
        TEXT_VARIATION_VISIBLE_PASSWORD,
        TEXT_VARIATION_WEB_PASSWORD
    )
}
