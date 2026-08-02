package com.interndra.services

import com.interndra.ai.SafetyEngine
import com.interndra.data.model.AutomationRule
import com.interndra.security.IntentUriPolicy

/** Pure, side-effect-free preflight validation for autonomous automation. */
object AutomationPreflight {
    private val safety = SafetyEngine()

    fun validate(rule: AutomationRule): AutomationExecutionResult {
        val safetyReport = safety.validateForAutonomousExecution(rule.command)
        if (safetyReport.result != SafetyEngine.ValidationResult.SAFE) {
            return AutomationExecutionResult(
                success = false,
                error = safetyReport.reason,
                refused = true,
                refusalSource = "SafetyEngine"
            )
        }

        if (rule.commandType == "ANDROID_INTENT" && !IntentUriPolicy.isAllowed(rule.command)) {
            return AutomationExecutionResult(
                success = false,
                error = "unsupported or malformed intent URI scheme",
                refused = true,
                refusalSource = "IntentUriPolicy"
            )
        }

        if (rule.commandType != "ADB_SHELL" && rule.commandType != "ANDROID_INTENT") {
            return AutomationExecutionResult(
                success = false,
                error = "Unknown command type: ${rule.commandType}"
            )
        }

        return AutomationExecutionResult(success = true)
    }
}
