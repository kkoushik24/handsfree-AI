package com.example.handsfree_ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Process-local, sanitized observation state. No raw node values are stored here. */
object AccessibilityServiceConnectionState {
    var isConnected by mutableStateOf(false)
        private set
    var latestObservation by mutableStateOf<AccessibilityScreenObservation?>(null)
        private set
    private var nextObservationId = 0L

    internal fun connected() { isConnected = true }
    internal fun disconnected() {
        isConnected = false
        latestObservation = null
    }
    internal fun publish(observation: AccessibilityScreenObservation) {
        nextObservationId += 1
        latestObservation = observation
    }
    internal fun clearObservation() { latestObservation = null }
    fun currentObservationId(): String = nextObservationId.toString()
}

/** Opaque read-only snapshot created only from the service state's private-set observation. */
class LiveServiceObservationSnapshot private constructor(
    val observation: AccessibilityScreenObservation,
    val observationId: String
) {
    companion object {
        internal fun current(): LiveServiceObservationSnapshot? {
            val observation = AccessibilityServiceConnectionState.latestObservation ?: return null
            return LiveServiceObservationSnapshot(
                observation,
                AccessibilityServiceConnectionState.currentObservationId()
            )
        }
    }
}

/** True when Android lists this component as enabled in Accessibility settings. */
fun isHandsFreeAccessibilityEnabled(context: Context): Boolean = runCatching {
    val expected = ComponentName(context, HandsFreeAccessibilityService::class.java)
    Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        .orEmpty().split(':').mapNotNull(ComponentName::unflattenFromString).any { it == expected }
}.getOrDefault(false)

/** Observes sanitized UI structure only. It never performs node actions or reads editable values. */
class HandsFreeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceConnectionState.connected()
        observeActiveWindow()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> observeActiveWindow()
        }
    }

    private fun observeActiveWindow() {
        val root = runCatching { rootInActiveWindow }.getOrNull()
        if (root == null) {
            val unavailable = AccessibilityScreenObservation(
                packageName = "", screenHash = AccessibilityElementMapper.screenHash("", emptyList()),
                elements = emptyList(), status = ScreenObservationStatus.WINDOW_UNAVAILABLE
            )
            AccessibilityServiceConnectionState.publish(unavailable)
            return
        }

        val candidates = mutableListOf<AccessibilityNodeCandidate>()
        val stack = ArrayDeque<PendingNode>()
        stack.addLast(PendingNode(root, "", null, 0))
        var visited = 0
        var packageName = ""
        var traversalTruncated = false
        try {
            while (stack.isNotEmpty() && visited < MAX_NODES && candidates.size < AccessibilityElementMapper.MAX_ELEMENTS * 4) {
                val pending = stack.removeLast()
                val node = pending.node
                visited++
                try {
                    if (packageName.isBlank()) packageName = runCatching { node.packageName?.toString().orEmpty() }.getOrDefault("")
                    val candidate = readCandidate(node, pending.path, pending.parentPath)
                    if (candidate != null) candidates += candidate
                    val childCount = runCatching { node.childCount }.getOrElse {
                        traversalTruncated = true
                        0
                    }
                    if (omitsChildrenAtTraversalBounds(childCount, pending.depth, MAX_CHILDREN, MAX_DEPTH)) {
                        traversalTruncated = true
                    }
                    if (pending.depth < MAX_DEPTH) {
                        val count = childCount.coerceAtMost(MAX_CHILDREN)
                        for (index in count - 1 downTo 0) {
                            val child = runCatching { node.getChild(index) }.getOrNull()
                            if (child == null) {
                                traversalTruncated = true
                                continue
                            }
                            stack.addLast(PendingNode(child, "${pending.path}/$index", pending.path.ifEmpty { null }, pending.depth + 1))
                        }
                    }
                } catch (_: RuntimeException) {
                    // A node can become inaccessible while its window is changing.
                } finally {
                    runCatching { node.recycle() }
                }
            }
            traversalTruncated = traversalTruncated || stack.isNotEmpty() || visited >= MAX_NODES || candidates.size >= MAX_CANDIDATES
        } finally {
            while (stack.isNotEmpty()) runCatching { stack.removeLast().node.recycle() }
        }

        val mapped = runCatching {
            AccessibilityElementMapper.map(packageName, candidates, sourceTruncated = traversalTruncated)
        }.getOrElse {
            AccessibilityScreenObservation(packageName, AccessibilityElementMapper.screenHash(packageName, emptyList()), emptyList(), ScreenObservationStatus.FAILED)
        }
        AccessibilityServiceConnectionState.publish(mapped)
    }

    /** Editable node text is never accessed. Values detected as sensitive are discarded immediately. */
    private fun readCandidate(node: AccessibilityNodeInfo, path: String, parentPath: String?): AccessibilityNodeCandidate? {
        val editable = runCatching { node.isEditable }.getOrDefault(false)
        val password = runCatching { node.isPassword }.getOrDefault(false)
        val inputType = runCatching { node.inputType }.getOrDefault(0)
        val descriptionRaw = runCatching { node.contentDescription?.toString().orEmpty() }.getOrDefault("")
        val hintRaw = runCatching { node.hintText?.toString().orEmpty() }.getOrDefault("")
        val resource = runCatching { node.viewIdResourceName.orEmpty() }.getOrDefault("")
        val sensitiveMetadata = password || AccessibilityElementMapper.hasPasswordInputType(inputType) ||
            AccessibilityElementMapper.containsSensitiveKeyword(descriptionRaw) ||
            AccessibilityElementMapper.containsSensitiveKeyword(hintRaw) ||
            AccessibilityElementMapper.containsSensitiveKeyword(resource)
        val textRaw = if (!editable && !password) runCatching { node.text?.toString().orEmpty() }.getOrDefault("") else ""
        val sensitive = sensitiveMetadata || AccessibilityElementMapper.containsSensitiveKeyword(textRaw)
        val bounds = Rect()
        runCatching { node.getBoundsInScreen(bounds) }
        val clickable = runCatching { node.isClickable }.getOrDefault(false)
        val useful = textRaw.isNotBlank() || descriptionRaw.isNotBlank() || hintRaw.isNotBlank() || clickable || editable || sensitive
        if (!useful) return null
        return AccessibilityNodeCandidate(
            path = path, parentPath = parentPath,
            text = if (sensitive) "" else if (editable) hintRaw else textRaw,
            description = if (sensitive || editable) "" else descriptionRaw,
            hint = if (sensitive) "" else hintRaw,
            resourceName = if (sensitive) "" else resource,
            bounds = listOf(bounds.left, bounds.top, bounds.right, bounds.bottom),
            clickable = clickable, editable = editable, password = password,
            inputType = inputType, sensitive = sensitive
        )
    }

    override fun onInterrupt() {
        AccessibilityServiceConnectionState.clearObservation()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityServiceConnectionState.disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityServiceConnectionState.disconnected()
        super.onDestroy()
    }

    private data class PendingNode(val node: AccessibilityNodeInfo, val path: String, val parentPath: String?, val depth: Int)

    private companion object {
        const val MAX_NODES = 500
        const val MAX_DEPTH = 40
        const val MAX_CHILDREN = 100
        const val MAX_CANDIDATES = AccessibilityElementMapper.MAX_ELEMENTS * 4
    }
}
