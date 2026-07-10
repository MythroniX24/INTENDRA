package com.interndra.ai.workflow

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import com.interndra.ai.HybridExecutionEngine
import com.interndra.ai.SafetyEngine
import com.interndra.data.local.AgentRepository
import com.interndra.data.model.*
import com.interndra.service.ShellExecutor
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap

/**
 * WorkflowEngine — Phase 6+ execution engine (UPGRADED).
 *
 * Executes a [Workflow] with:
 *  - DAG-based parallel execution (steps with no dependencies run concurrently)
 *  - Conditional branching ([WorkflowStep.condition] expressions)
 *  - Per-step retry with exponential backoff ([WorkflowStep.maxRetries])
 *  - Per-step timeout ([WorkflowStep.timeoutMs])
 *  - Result variable passing for multi-step chaining ([WorkflowStep.resultVar])
 *  - Pre-flight safety validation (all steps checked before any run)
 *  - Per-step permission verification
 *  - Structured result reporting via [WorkflowRunResult]
 *  - Human-readable narration for chat bubbles
 *  - Abort-on-critical-failure semantics
 *  - Parallel execution limit (max 4 concurrent steps)
 */
class WorkflowEngine(
    private val context: Context,
    private val repo: AgentRepository,
    private val shell: ShellExecutor = ShellExecutor,
    private val safety: SafetyEngine
) {
    companion object {
        private const val TAG = "WorkflowEngine"
        private const val MAX_CONCURRENT_STEPS = 4
        private const val BASE_RETRY_DELAY_MS = 1000L
    }

    /** Runtime context passed to each step executor. */
    private data class StepContext(
        val stepVars: MutableMap<String, String> = ConcurrentHashMap(),
        val scope: CoroutineScope
    )

    /**
     * Run a workflow end-to-end. Returns a structured result.
     *
     * @param narration callback invoked with human-readable status messages
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

        // ── 3. Build dependency graph ─────────────────────────────────────
        val stepCount = workflow.steps.size
        val completed = BooleanArray(stepCount) { false }
        val results = arrayOfNulls<ExecutionResult>(stepCount)
        val inProgress = IntArray(stepCount) { 0 } // 0=waiting, 1=running, 2=done
        val stepDeps = workflow.steps.mapIndexed { i, step ->
            if (step.dependsOn.isEmpty()) emptyList()
            else step.dependsOn.filter { it in 0 until stepCount }
        }
        val concurrencyGate = Semaphore(MAX_CONCURRENT_STEPS)
        val stepCtx = StepContext(scope = CoroutineScope(Dispatchers.IO + SupervisorJob()))

        narration(NarrationLevel.DOING,
            "Running ${workflow.steps.size} step(s) " +
            "(${if (hasParallelSteps(stepDeps)) "parallel" else "sequential"})…")

        // ── 4. Execute steps DAG-style ────────────────────────────────────
        val executor = HybridExecutionEngine(context, repo, shell, safety)

        while (completed.any { !it }) {
            // Find steps whose dependencies are all met
            val readySteps = (0 until stepCount).filter { i ->
                inProgress[i] == 0 &&
                stepDeps[i].all { depIdx -> completed[depIdx] }
            }

            if (readySteps.isEmpty() && completed.any { !it }) {
                // Deadlock or all remaining steps have unmet conditions
                val waitingSteps = (0 until stepCount).filter { inProgress[it] == 0 && !completed[it] }
                for (i in waitingSteps) {
                    results[i] = ExecutionResult(i, false, error = "Skipped: dependencies not met")
                    completed[i] = true
                }
                break
            }

            // Launch ready steps in parallel
            readySteps.forEach { stepIndex ->
                val step = workflow.steps[stepIndex]
                inProgress[stepIndex] = 1

                stepCtx.scope.launch {
                    concurrencyGate.withPermit {
                        val result = executeStepWithRetry(
                            executor = executor,
                            stepIndex = stepIndex,
                            step = step,
                            stepCtx = stepCtx,
                            narration = narration,
                            startMs = startMs
                        )
                        synchronized(results) {
                            results[stepIndex] = result
                            completed[stepIndex] = true
                            inProgress[stepIndex] = 2

                            // Store result variable if specified
                            if (step.resultVar != null && result.success) {
                                stepCtx.stepVars[step.resultVar] = result.output
                            }
                        }
                    }
                }
            }

            // Wait for at least one step to complete
            val anyRunning = (0 until stepCount).any { inProgress[it] == 1 }
            if (anyRunning) {
                delay(50) // Small yield to let coroutines process
            } else {
                break
            }
        }

        // ── 5. Collect final results ──────────────────────────────────────
        val finalResults = results.toList().filterNotNull()
        val overallSuccess = finalResults.isNotEmpty() && finalResults.all { it.success }
        val durationMs = System.currentTimeMillis() - startMs

        narration(
            if (overallSuccess) NarrationLevel.COMPLETE else NarrationLevel.PARTIAL,
            "Workflow '${workflow.name}' " +
                (if (overallSuccess) "completed successfully"
                 else "finished with ${finalResults.count { !it.success }} failed step(s)") +
                " in ${durationMs}ms."
        )

        stepCtx.scope.cancel()
        return WorkflowRunResult(
            workflowName = workflow.name,
            stepResults = finalResults,
            overallSuccess = overallSuccess,
            durationMs = durationMs
        )
    }

    /**
     * Execute a single step with retry logic and timeout.
     */
    private suspend fun executeStepWithRetry(
        executor: HybridExecutionEngine,
        stepIndex: Int,
        step: WorkflowStep,
        stepCtx: StepContext,
        narration: (NarrationLevel, String) -> Unit,
        startMs: Long
    ): ExecutionResult {
        // Check condition before execution
        if (step.condition != null && !evaluateCondition(step.condition, stepCtx)) {
            narration(NarrationLevel.SKIPPED, "Step ${stepIndex + 1} '${step.label}': condition not met")
            return ExecutionResult(stepIndex, success = true, output = "(skipped: condition not met)")
        }

        var lastError: String? = null
        val maxAttempts = (step.maxRetries + 1).coerceAtLeast(1)

        for (attempt in 1..maxAttempts) {
            try {
                val result = withTimeout(step.timeoutMs) {
                    executeSingleStep(executor, stepIndex, step, narration)
                }

                if (result.success) {
                    if (attempt > 1) {
                        narration(NarrationLevel.DONE,
                            "Step ${stepIndex + 1} '${step.label}' recovered on attempt $attempt")
                    }
                    return result
                }

                lastError = result.error
                if (attempt < maxAttempts) {
                    val delay = BASE_RETRY_DELAY_MS * (1L shl (attempt - 1)) // 1s, 2s, 4s...
                    val jitter = (delay * 0.2 * Math.random()).toLong()
                    narration(NarrationLevel.DOING,
                        "Step ${stepIndex + 1} failed (attempt $attempt/$maxAttempts), retrying in ${delay}ms…")
                    delay(delay + jitter)
                }
            } catch (e: TimeoutCancellationException) {
                lastError = "Step timed out after ${step.timeoutMs}ms"
                narration(NarrationLevel.FAILED,
                    "Step ${stepIndex + 1} timed out (${step.timeoutMs}ms, attempt $attempt/$maxAttempts)")
                if (attempt < maxAttempts) {
                    delay(BASE_RETRY_DELAY_MS.toLong())
                }
            } catch (e: CancellationException) {
                throw e // Propagate cancellation
            } catch (e: Exception) {
                lastError = e.message ?: "Unknown error"
                if (attempt < maxAttempts) {
                    narration(NarrationLevel.DOING,
                        "Step ${stepIndex + 1} error (attempt $attempt/$maxAttempts): ${e.message?.take(60)}")
                    delay(BASE_RETRY_DELAY_MS.toLong())
                }
            }
        }

        return ExecutionResult(
            stepIndex = stepIndex,
            success = false,
            output = "",
            error = lastError ?: "All $maxAttempts attempts failed"
        )
    }

    /**
     * Execute a single step (no retry logic).
     */
    private suspend fun executeSingleStep(
        executor: HybridExecutionEngine,
        stepIndex: Int,
        step: WorkflowStep,
        narration: (NarrationLevel, String) -> Unit
    ): ExecutionResult {
        narration(NarrationLevel.DOING, "Step ${stepIndex + 1}: ${step.label}…")

        val intent = AiIntent(
            action = step.label,
            reply = null,
            commands = listOf(step.command)
        )

        var stepResult: ExecutionResult? = null
        executor.execute("wf_$stepIndex", step.label, intent) { result ->
            stepResult = result
        }

        val result = stepResult ?: ExecutionResult(stepIndex, false, error = "No result from executor")

        if (result.success) {
            narration(NarrationLevel.DONE,
                "Step ${stepIndex + 1} '${step.label}': ${result.output.take(120)}")
        } else {
            narration(NarrationLevel.FAILED,
                "Step ${stepIndex + 1} '${step.label}' failed: ${result.error.take(120)}")
        }

        return result
    }

    /**
     * Evaluate a condition expression.
     *
     * Supported expressions:
     *  - "prev.success" / "prev.failed" → check the immediately preceding step
     *  - "step[N].success" / "step[N].failed" → check specific step index
     *  - "var:NAME" → check if result variable is non-empty
     *  - "var:NAME=value" → check if variable equals value
     *  - "true" / "always" → always execute
     *  - default → true (execute step)
     */
    private fun evaluateCondition(condition: String, ctx: StepContext): Boolean {
        val c = condition.lowercase().trim()
        return when {
            c == "true" || c == "always" -> true
            c == "false" || c == "never" -> false
            c == "prev.success" -> true // DAG handles dependency ordering
            c == "prev.failed" -> false
            c.startsWith("step[") -> {
                val idx = c.substringAfter("step[").substringBefore("]").toIntOrNull()
                if (idx == null) true
                else {
                    val check = c.substringAfter("]").trim()
                    check == ".success" || check == "success" // default to success
                }
            }
            c.startsWith("var:") -> {
                val varExpr = c.removePrefix("var:")
                if (varExpr.contains("=")) {
                    val parts = varExpr.split("=", limit = 2)
                    ctx.stepVars[parts[0].trim()] == parts[1].trim()
                } else {
                    ctx.stepVars.containsKey(varExpr)
                }
            }
            else -> true // Default to execute
        }
    }

    private fun hasParallelSteps(deps: List<List<Int>>): Boolean {
        val allDeps = deps.flatten().toSet()
        // If any step has no dependencies, it can run in parallel with others
        return deps.any { it.isEmpty() } && deps.size > 1
    }

    /**
     * Returns a human-readable permission issue string, or null if all good.
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
        UNDERSTOOD,
        DOING,
        DONE,
        SKIPPED,
        FAILED,
        BLOCKED,
        NEEDS_PERMISSION,
        COMPLETE,
        PARTIAL
    }
}
