package com.interndra.services

/** Explicit outcome for an automation rule execution. */
data class AutomationExecutionResult(
    val success: Boolean,
    val output: String = "",
    val error: String = "",
    val refused: Boolean = false,
    /** Human-readable refusal source, e.g. "SafetyEngine" or "IntentUriPolicy". */
    val refusalSource: String = "SafetyEngine"
) {
    fun legacyMessage(): String = when {
        success -> output
        refused -> "Refused by $refusalSource: ${error.ifBlank { "automation refused" }}"
        error.isNotBlank() -> "Error: $error"
        else -> "Error: automation failed"
    }
}
