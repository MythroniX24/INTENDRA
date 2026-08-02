package com.interndra.plugin

import com.interndra.ai.SafetyEngine
import com.interndra.security.ShellArgumentPolicy

/**
 * Safety boundary for built-in plugins when they are called directly rather
 * than through PluginManager or PluginToolDescriptor.
 */
object PluginExecutionGuard {
    private val safety = SafetyEngine()

    fun rejection(command: String, args: Map<String, String>): PluginResult? {
        ShellArgumentPolicy.firstUnsafeValue(args)?.let { key ->
            return refusal("unsafe control character in argument: $key")
        }

        // Only raw shell-fragment commands accept shell syntax. Structured
        // plugin fields are quoted by the plugin before interpolation.
        val rawShellValue = when (command) {
            "shell:exec" -> args["cmd"]
            "shell:git", "shell:npm" -> args["args"]
            else -> null
        }
        if (rawShellValue != null && ShellArgumentPolicy.containsShellSyntax(rawShellValue)) {
            return refusal("shell syntax is not allowed in autonomous plugin arguments")
        }

        val candidate = buildString {
            append(command.replace(':', ' '))
            args.values.forEach { append(' ').append(it) }
        }
        val report = safety.validateForAutonomousExecution(candidate)
        return if (report.result == SafetyEngine.ValidationResult.SAFE) null
        else refusal("SafetyEngine: ${report.reason}")
    }

    private fun refusal(reason: String) = PluginResult(
        success = false,
        output = "",
        error = "Refused: $reason"
    )
}
