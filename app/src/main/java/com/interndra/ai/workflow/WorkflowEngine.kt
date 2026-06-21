package com.interndra.ai.workflow

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.interndra.ai.HybridExecutionEngine
import com.interndra.ai.SafetyEngine
import com.interndra.data.local.AgentRepository
import com.interndra.data.model.*
import com.interndra.service.SmartShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * WorkflowEngine — Phase 6 execution engine.
 *
 * Executes a [Workflow] step-by-step with:
 *  - Pre-flight safety validation (all steps checked before any run)
 *  - Per-step permission verification (CALL_PHONE, SEND_SMS, etc.)
 *  - Structured result reporting via [WorkflowRunResult]
 *  - Human-readable status messages suitable for chat bubbles
 *  - Abort-on-critical-failure semantics
 *  - Optional "what I understood / what I'm doing / what happened" narration
 *    for the Terminal AI (Phase 7)
 *
 * The engine is intentionally synchronous-step (sequential). Parallel step
 * execution and conditional branching are modeled on [WorkflowStep.dependsOn]
 * and [WorkflowStep.condition] but not yet executed in parallel — the data
 * model is forward-compatible so future versions can add a DAG executor
 * without breaking existing workflows.
 */
class WorkflowEngine(
    private val context: Context,
    private val repo: AgentRepository,
    private val shell: SmartShell,
    private val safety: SafetyEngine
) {
    companion object {
        private const val TAG = "WorkflowEngine"
    }

    /**
     * Run a workflow end-to-end. Returns a structured result.
     *
     * @param narration callback invoked with human-readable status messages
     *   ("Understood: …", "Doing: …", "Result: …") so the Terminal AI can
     *   show the user what's happening at each stage (Phase 7).
     */
    suspend fun run(
        workflow: Workflow,
        session: String,
        narration: (NarrationLevel, String) -> Unit = { _, _ -> }
    ): WorkflowRunResult {
        narration(NarrationLevel.UNDERSTOOD, workflow.description)
        val startMs = System.currentTimeMillis()

        // ── 1. Pre-flight safety validation on ALL steps ──────────────────
        val commands = workflow.steps.map { it.command }
        val reports = safety.validateAll(commands)
        val blockedStep = reports.indexOfFirst { it.result == SafetyEngine.ValidationResult.BLOCKED }
        if (blockedStep >= 0) {
            val reason = reports[blockedStep].reason
            narration(NarrationLevel.BLOCKED, "Step ${blockedStep + 1} blocked: $reason")
            return WorkflowRunResult(
                workflowName = workflow.name,
                stepResults = reports.mapIndexed { i, r ->
                    ExecutionResult(
                        stepIndex = i,
                        success = false,
                        error = if (i == blockedStep) reason else "Skipped — earlier step blocked"
                    )
                },
                overallSuccess = false,
                durationMs = System.currentTimeMillis() - startMs
            )
        }

        // ── 2. Per-step permission check ──────────────────────────────────
        val permIssue = verifyStepPermissions(commands)
        if (permIssue != null) {
            narration(NarrationLevel.NEEDS_PERMISSION, permIssue)
        }

        // ── 3. Execute steps sequentially ─────────────────────────────────
        narration(NarrationLevel.DOING, "Running ${workflow.steps.size} step(s)…")
        val intent = AiIntent(
            action = workflow.name,
            reply = null,
            commands = commands
        )
        val results = mutableListOf<ExecutionResult>()

        val executor = HybridExecutionEngine(context, repo, shell, safety)
        executor.execute(session, workflow.description, intent) { result ->
            results.add(result)
            val step = workflow.steps.getOrNull(result.stepIndex)
            val label = step?.label ?: "Step ${result.stepIndex + 1}"
            if (result.success) {
                narration(NarrationLevel.DONE, "$label: ${result.output.take(120)}")
            } else {
                narration(NarrationLevel.FAILED, "$label failed: ${result.error.take(120)}")
            }
        }

        val overallSuccess = results.isNotEmpty() && results.all { it.success }
        val durationMs = System.currentTimeMillis() - startMs

        narration(
            if (overallSuccess) NarrationLevel.COMPLETE else NarrationLevel.PARTIAL,
            "Workflow '${workflow.name}' " +
                (if (overallSuccess) "completed successfully" else "finished with ${results.count { !it.success }} failed step(s)") +
                " in ${durationMs}ms."
        )

        return WorkflowRunResult(
            workflowName = workflow.name,
            stepResults = results,
            overallSuccess = overallSuccess,
            durationMs = durationMs
        )
    }

    /**
     * Returns a human-readable permission issue string, or null if all good.
     * Checks CALL_PHONE / SEND_SMS permissions for the relevant intent types.
     */
    private fun verifyStepPermissions(commands: List<ShellCommand>): String? {
        for (cmd in commands) {
            if (cmd.type != CommandType.ANDROID_INTENT) continue
            val c = cmd.command.trim()
            when {
                c.startsWith("call:", ignoreCase = true) -> {
                    if (!hasPermission(android.Manifest.permission.CALL_PHONE)) {
                        return "Call workflow needs CALL_PHONE permission — grant it in Android Settings."
                    }
                }
                c.startsWith("sms:", ignoreCase = true) -> {
                    if (!hasPermission(android.Manifest.permission.SEND_SMS)) {
                        return "SMS workflow needs SEND_SMS permission — grant it in Android Settings."
                    }
                }
                c.startsWith("openfile:", ignoreCase = true) ||
                c.startsWith("sharefile:", ignoreCase = true) -> {
                    // File access on API 32 and below needs READ_EXTERNAL_STORAGE
                    if (android.os.Build.VERSION.SDK_INT <= 32 &&
                        !hasPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE)) {
                        return "File workflow needs storage permission — grant it in Android Settings."
                    }
                }
            }
        }
        return null
    }

    private fun hasPermission(perm: String): Boolean =
        context.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED

    /** Narration levels for Phase 7 terminal-AI reporting. */
    enum class NarrationLevel {
        UNDERSTOOD,       // "I understood: …"
        DOING,            // "Doing: …"
        DONE,             // "Done: …"
        FAILED,           // "Failed: …"
        BLOCKED,          // "Blocked: …"
        NEEDS_PERMISSION, // "Needs permission: …"
        COMPLETE,         // "Workflow complete."
        PARTIAL           // "Workflow finished with partial success."
    }
}
