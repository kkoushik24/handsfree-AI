package com.example.handsfree_ai.accessibility

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Tracks only whether Android has bound the service; it contains no screen data. */
object AccessibilityServiceConnectionState {
    var isConnected by mutableStateOf(false)
        private set

    internal fun connected() {
        isConnected = true
    }

    internal fun disconnected() {
        isConnected = false
    }
}

/** True when Android lists this component as enabled in Accessibility settings. */
fun isHandsFreeAccessibilityEnabled(context: Context): Boolean = runCatching {
    val expected = ComponentName(context, HandsFreeAccessibilityService::class.java)
    Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ).orEmpty()
        .split(':')
        .mapNotNull(ComponentName::unflattenFromString)
        .any { it == expected }
}.getOrDefault(false)

/**
 * Lifecycle foundation only. Events are intentionally ignored; this service does not retrieve
 * window content, retain screen information, communicate over a network, or perform actions.
 */
class HandsFreeAccessibilityService : AccessibilityService() {
    override fun onServiceConnected() {
        super.onServiceConnected()
        AccessibilityServiceConnectionState.connected()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onUnbind(intent: Intent?): Boolean {
        AccessibilityServiceConnectionState.disconnected()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        AccessibilityServiceConnectionState.disconnected()
        super.onDestroy()
    }
}
