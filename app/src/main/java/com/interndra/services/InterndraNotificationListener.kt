package com.interndra.services

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log
import com.interndra.ai.SafetyEngine
import com.interndra.service.SmartShell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * InterndraNotificationListener
 *
 * SECURITY FIX (Phase 10): Trigger commands are now re-validated by SafetyEngine
 *   before execution. Previously, a clever bypass that slipped past initial
 *   validation would run unchecked when a notification fired. Now every trigger
 *   fires only after a fresh safety check.
 *
 * COMPILE FIX: SafetyEngine.validate() returns a Report (which has no .safe
 *   field). Switched to safety.verdict() which returns a Verdict(safe, reason).
 *
 * BUG FIX 1: original used mutableMapOf() in companion — not thread-safe.
 *   Replaced with ConcurrentHashMap.
 *
 * BUG FIX 2: original logged full notification text (sender name + message body)
 *   via Log.d — privacy leak. Now logs only package name and trigger key, never
 *   message content.
 *
 * BUG FIX 3: created a scoped coroutine with SupervisorJob so cancelled jobs
 *   don't cascade and cancel the listener's scope.
 */
class InterndraNotificationListener : NotificationListenerService() {

    companion object {
        private const val TAG = "InterndraNL"

        private val triggers = ConcurrentHashMap<String, String>()

        fun addTrigger(condition: String, command: String) {
            triggers[condition.lowercase()] = command
            Log.d(TAG, "Trigger registered: ${condition.lowercase()}")
        }

        fun removeTrigger(condition: String) {
            triggers.remove(condition.lowercase())
        }

        fun getTriggerCount(): Int = triggers.size

        fun clearAllTriggers() {
            triggers.clear()
            Log.d(TAG, "All triggers cleared")
        }

        fun activeTriggerKeys(): List<String> = triggers.keys.toList()
    }

    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val safety = SafetyEngine()

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val packageName = sbn.packageName ?: return
        val extras = sbn.notification?.extras ?: return
        val sender = extras.getString("android.title") ?: return

        Log.d(TAG, "Notification from package: $packageName — checking triggers")

        if (packageName.contains("whatsapp", ignoreCase = true)) {
            val triggerKey = "on_whatsapp_message:${sender.lowercase()}"
            val matchedCommand = triggers[triggerKey]
            if (matchedCommand != null) {
                Log.d(TAG, "Trigger matched for key: $triggerKey — validating before execution")
                serviceScope.launch {
                    try {
                        val verdict = safety.verdict(matchedCommand)
                        if (!verdict.safe) {
                            Log.w(TAG, "Trigger blocked by SafetyEngine: ${verdict.reason}")
                            triggers.remove(triggerKey)
                            return@launch
                        }
                        val shell = SmartShell(applicationContext)
                        shell.run(matchedCommand)
                        triggers.remove(triggerKey)
                    } catch (e: Exception) {
                        Log.e(TAG, "Trigger execution failed: ${e.message}")
                    }
                }
            }
        }
    }

    fun onInterrupt() {
        Log.d(TAG, "NotificationListener interrupted")
    }
}
