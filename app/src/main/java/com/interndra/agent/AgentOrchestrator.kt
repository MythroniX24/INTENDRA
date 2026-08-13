package com.interndra.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AgentOrchestrator — a state-driven controller for the whole agent lifecycle.
 *
 * It is intentionally UI- and ViewModel-agnostic: callers drive it by emitting
 * high-level [AgentActivity] events and state transitions; the UI observes
 * [state] and [activities] to render a live Claude-style activity timeline.
 *
 * It also provides the bounded agent-loop primitives the spec requires:
 * - [runWithSelfCorrection] — diagnose → corrected attempt → retry, bounded by
 *   [maxRetriesPerTool] (Phase 7 "maximum iterations" + Phase 8 "self-correction").
 * - [verify] — a verification step that must pass before a run is Completed
 *   (Phase 9 "verification"). The run FAILS if verification fails.
 *
 * All logic is pure (no Android dependencies) so the loop can be unit-tested.
 */
class AgentOrchestrator(
    /** Max retries per tool invocation before giving up on it. */
    val maxRetriesPerTool: Int = 2,
    /** Max tool invocations per run — hard safety bound against runaway loops. */
    val maxToolCallsPerRun: Int = 12,
    private val timeSource: () -> Long = System::currentTimeMillis
) {
    private val _state = MutableStateFlow(AgentState.IDLE)
    val state: StateFlow<AgentState> = _state.asStateFlow()

    private val _activities = MutableStateFlow<List<AgentActivity>>(emptyList())
    val activities: StateFlow<List<AgentActivity>> = _activities.asStateFlow()

    /** Tool invocations performed during the current run (bounded by [maxToolCallsPerRun]). */
    var toolCallCount: Int = 0
        private set

    /** True while the agent is actively working. */
    fun isActive(): Boolean = _state.value.isActive()

    // ── State transitions ────────────────────────────────────────────────

    /** Begin a new run: resets everything and enters ANALYZING. */
    fun begin(request: String) {
        reset()
        emit(AgentActivity.Thinking("Understanding your request"))
        setState(AgentState.ANALYZING)
    }

    /** End the current run. Drops all accumulated activities. */
    fun reset() {
        _state.value = AgentState.IDLE
        _activities.value = emptyList()
        toolCallCount = 0
    }

    fun setState(state: AgentState) {
        _state.value = state
    }

    /** Append one activity event to the live timeline. */
    fun emit(activity: AgentActivity) {
        _activities.value = _activities.value + activity
    }

    /** Mark the run as finished successfully (state → COMPLETED). */
    fun complete() {
        emit(AgentActivity.Completed)
        setState(AgentState.COMPLETED)
    }

    /** Mark the run as failed (state → FAILED) with a user-safe reason. */
    fun fail(message: String) {
        emit(AgentActivity.Error(message))
        setState(AgentState.FAILED)
    }

    /** Enter WAITING_FOR_CONFIRMATION (destructive operation needs approval). */
    fun needsConfirmation(message: String) {
        emit(AgentActivity.Error("Confirmation required: $message"))
        setState(AgentState.WAITING_FOR_CONFIRMATION)
    }

    // ── Convenience emitters ─────────────────────────────────────────────

    fun thinking(message: String) {
        setState(AgentState.THINKING)
        emit(AgentActivity.Thinking(message))
    }

    fun planning(message: String) {
        setState(AgentState.PLANNING)
        emit(AgentActivity.Planning(message))
    }

    fun searching(query: String) {
        setState(AgentState.SEARCHING)
        emit(AgentActivity.Search(query))
    }

    fun reading(message: String) {
        setState(AgentState.READING)
        emit(AgentActivity.Reading(message))
    }

    fun toolStart(tool: String, description: String, command: String = "") {
        setState(AgentState.EXECUTING)
        toolCallCount++
        emit(AgentActivity.ToolStart(tool, description, command))
    }

    fun toolResult(tool: String, success: Boolean, summary: String, durationMs: Long = 0L) {
        setState(if (success) AgentState.INSPECTING else AgentState.THINKING)
        emit(AgentActivity.ToolResult(tool, success, summary, durationMs))
    }

    /** Start the verification phase for the just-completed work. */
    fun verifyStart(message: String) {
        setState(AgentState.VERIFYING)
        emit(AgentActivity.Verification(message, success = true))
    }

    // ── Bounded agent loop primitives ────────────────────────────────────

    /**
     * Execute a tool with self-correction.
     *
     * [attempts] is an ordered list of strategies: the first is the original
     * approach, and each subsequent entry is a *corrected* variant (e.g. the
     * same command without `sudo`, or with `--yes`). On failure the next
     * strategy is tried automatically, bounded by [maxRetriesPerTool].
     * [diagnose] converts a failure into a short, user-safe explanation that
     * is shown as an activity event before retrying.
     *
     * @return the outcome of the last attempt
     */
    suspend fun runWithSelfCorrection(
        tool: String,
        description: String,
        command: String = "",
        attempts: List<suspend () -> Outcome>,
        diagnose: (String) -> String = { it.take(200) }
    ): Outcome {
        if (attempts.isEmpty()) return Outcome(false, "", "No attempts provided")
        toolStart(tool, description, command)

        // Bounded by BOTH the per-tool retry limit and the per-run hard cap.
        // toolStart already consumed one call, so the remaining budget is
        // (maxToolCallsPerRun - (toolCallCount - 1)).
        val callsBudget = (maxToolCallsPerRun - (toolCallCount - 1)).coerceAtLeast(1)
        val maxAttempts = (maxRetriesPerTool + 1)
            .coerceAtMost(attempts.size)
            .coerceAtMost(callsBudget)
        var last = attempts[0]()
        for (attempt in 1 until maxAttempts) {
            if (last.success) break
            val diagnosis = diagnose(last.error)
            thinking("Diagnosing failure: $diagnosis")
            last = attempts[attempt]()
        }
        toolResult(tool, last.success, last.summary, last.durationMs)
        return last
    }

    /**
     * Run the verification phase for a run.
     *
     * [verifyAction] performs the actual check (build, tests, file existence…).
     * If it fails, the run FAILS — never claim success without verification.
     *
     * @return true if verification passed
     */
    suspend fun verify(message: String, verifyAction: suspend () -> Outcome): Boolean {
        verifyStart(message)
        val outcome = verifyAction()
        if (outcome.success) {
            emit(AgentActivity.Verification(outcome.summary.ifBlank { message }, success = true))
            return true
        }
        emit(AgentActivity.Verification(
            outcome.error.ifBlank { outcome.summary }.ifBlank { message },
            success = false
        ))
        return false
    }

    /** Result of a single tool invocation. */
    data class Outcome(
        val success: Boolean,
        val summary: String,
        val error: String = "",
        val durationMs: Long = 0L
    )
}
