package com.interndra.services

import android.content.Context
import android.util.Log
import androidx.work.*
import com.interndra.data.model.AutomationRule
import com.interndra.data.model.AutomationTriggerType
import com.interndra.service.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * AutomationEngine — schedules, triggers, and executes automation rules.
 *
 * UPGRADE over original AutomationWorker (runBlocking removed):
 * - All shell execution is via suspend functions on IO dispatcher
 * - WorkManager CoroutineWorker is used (not Worker) for non-blocking doWork
 * - Trigger matching runs in InterndraNotificationListener and calls this engine
 * - All rules are stored in Room via AgentDao (persistent across restarts)
 */
class AutomationEngine(private val context: Context) {

    companion object {
        private const val TAG = "AutomationEngine"
        const val WORK_TAG   = "intentra_automation"

        // ── In-memory trigger map ─────────────────────────────────────────
        // Populated from Room on app start. Notification listener queries this.
        val activeTriggers = mutableMapOf<String, AutomationRule>()

        fun addTrigger(rule: AutomationRule) {
            activeTriggers[rule.triggerCondition] = rule
            Log.d(TAG, "Trigger registered: ${rule.triggerCondition}")
        }

        fun removeTrigger(triggerCondition: String) {
            activeTriggers.remove(triggerCondition)
        }

        fun matchTrigger(condition: String): AutomationRule? =
            activeTriggers.entries.firstOrNull {
                condition.contains(it.key, ignoreCase = true)
            }?.value
    }

    private val _activeRules = MutableStateFlow<List<AutomationRule>>(emptyList())
    val activeRules: StateFlow<List<AutomationRule>> = _activeRules.asStateFlow()

    // ── Schedule a delayed rule ────────────────────────────────────────────
    fun scheduleDelayed(rule: AutomationRule) {
        if (rule.delayMinutes <= 0) return

        val data = workDataOf(
            "RULE_ID"     to rule.id,
            "TYPE"        to rule.commandType,
            "COMMAND"     to rule.command,
            "DESCRIPTION" to rule.description
        )

        val request = OneTimeWorkRequestBuilder<AutomationWorker>()
            .setInitialDelay(rule.delayMinutes.toLong(), TimeUnit.MINUTES)
            .setInputData(data)
            .addTag(WORK_TAG)
            .addTag("rule_${rule.id}")
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()

        WorkManager.getInstance(context).enqueue(request)
        Log.d(TAG, "Scheduled rule ${rule.id} with ${rule.delayMinutes}m delay")
    }

    // ── Execute immediately ────────────────────────────────────────────────
    /**
     * Structured execution API. Callers that need to distinguish refusal,
     * failure, and success should use this instead of parsing display text.
     */
    suspend fun executeRule(rule: AutomationRule): AutomationExecutionResult =
        withContext(Dispatchers.IO) {
            // This method may be called from a background/automation context,
            // so there is no confirmation UI. Use the same pure preflight as
            // WorkManager to keep both execution paths consistent.
            val preflight = AutomationPreflight.validate(rule)
            if (!preflight.success) {
                Log.w(TAG, "Rule ${rule.id} refused: ${preflight.refusalSource}: ${preflight.error}")
                return@withContext preflight
            }
            try {
                when (rule.commandType) {
                    "ADB_SHELL" -> {
                        val r = ShellExecutor.runAsync(rule.command)
                        if (r.isSuccess) {
                            AutomationExecutionResult(
                                success = true,
                                output = r.stdout.ifEmpty { "(completed)" }
                            )
                        } else {
                            AutomationExecutionResult(
                                success = false,
                                output = r.stdout,
                                error = r.stderr.ifEmpty { "exit code ${r.exitCode}" }
                            )
                        }
                    }
                    "ANDROID_INTENT" -> {
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                            .setData(android.net.Uri.parse(rule.command))
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        context.startActivity(intent)
                        AutomationExecutionResult(success = true, output = "Intent dispatched")
                    }
                    else -> AutomationExecutionResult(
                        success = false,
                        error = "Unknown command type: ${rule.commandType}"
                    )
                }.also { result ->
                    if (result.success) {
                        Log.d(TAG, "Rule ${rule.id} executed: ${result.output.take(100)}")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Rule ${rule.id} failed: ${e.message}")
                AutomationExecutionResult(success = false, error = e.message ?: "automation failed")
            }
        }

    /** Backward-compatible display-text API used by existing callers. */
    suspend fun executeNow(rule: AutomationRule): String =
        executeRule(rule).legacyMessage()

    // ── Cancel scheduled rules ────────────────────────────────────────────
    fun cancelRule(ruleId: Long) {
        WorkManager.getInstance(context).cancelAllWorkByTag("rule_$ruleId")
        Log.d(TAG, "Cancelled scheduled rule: $ruleId")
    }

    fun cancelAll() {
        WorkManager.getInstance(context).cancelAllWorkByTag(WORK_TAG)
        activeTriggers.clear()
        Log.d(TAG, "All automation rules cancelled")
    }

    // ── Status ────────────────────────────────────────────────────────────
    fun updateRuleList(rules: List<AutomationRule>) {
        _activeRules.value = rules
        activeTriggers.clear()
        rules.filter { it.isEnabled && it.triggerCondition.isNotBlank() }
             .forEach { addTrigger(it) }
    }
}
