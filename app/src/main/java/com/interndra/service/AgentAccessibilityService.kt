package com.interndra.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.util.Log
import android.view.accessibility.AccessibilityEvent

/**
 * AgentAccessibilityService
 *
 * FIX: isEnabled() always returned false (placeholder).
 * Now checks via system service whether this service is actually running.
 *
 * Note: The service must still be enabled by the user in Android Settings →
 * Accessibility. This class detects that state correctly.
 */
class AgentAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "AgentA11yService"
        private const val SERVICE_CLASS = "com.interndra.service.AgentAccessibilityService"

        // FIX: real enabled check via AccessibilityManager
        fun isEnabled(context: Context? = null): Boolean {
            // Fallback value if no context — conservative false
            val ctx = context ?: return _cachedEnabled
            return try {
                val am = ctx.getSystemService(Context.ACCESSIBILITY_SERVICE) as android.view.accessibility.AccessibilityManager
                val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
                enabledServices.any { info ->
                    info.resolveInfo?.serviceInfo?.name?.contains("AgentAccessibilityService", ignoreCase = true) == true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not check accessibility state: ${e.message}")
                false
            }
        }

        // Cache last known state so callers without context can still read it
        @Volatile private var _cachedEnabled = false
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        _cachedEnabled = true
        Log.i(TAG, "AccessibilityService connected")

        serviceInfo = serviceInfo?.apply {
            eventTypes    = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED
            feedbackType  = AccessibilityServiceInfo.FEEDBACK_GENERIC
            flags         = AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS
            notificationTimeout = 100
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Placeholder — future: implement UI automation triggers here
        // Do NOT log event.text as it may contain private message content
    }

    override fun onInterrupt() {
        _cachedEnabled = false
        Log.d(TAG, "AccessibilityService interrupted")
    }

    override fun onDestroy() {
        super.onDestroy()
        _cachedEnabled = false
        Log.d(TAG, "AccessibilityService destroyed")
    }
}
