package com.interndra.ai.agents

import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * AgentPool — lightweight multi-agent coordinator.
 *
 * Agents run concurrently on a shared coroutine dispatcher.
 * Each agent is a named coroutine that processes tasks from a typed queue.
 * The pool can be used to parallelize independent sub-tasks (e.g. search +
 * file analysis + shell command can run simultaneously).
 *
 * Design is intentionally simple — no actor model, no RxJava, no additional
 * libraries. Just coroutines + StateFlow.
 */
class AgentPool(private val scope: CoroutineScope) {

    companion object {
        private const val TAG = "AgentPool"
    }

    // ── Agent task definition ─────────────────────────────────────────────
    data class AgentTask(
        val id: String,
        val agentName: String,
        val input: String,
        val priority: Int = 5,          // 1 (highest) .. 10 (lowest)
        val timeoutMs: Long = 30_000L
    )

    data class AgentResult(
        val taskId: String,
        val agentName: String,
        val output: String,
        val success: Boolean,
        val latencyMs: Long,
        val error: String = ""
    )

    // ── State ─────────────────────────────────────────────────────────────
    private val _results = MutableStateFlow<List<AgentResult>>(emptyList())
    val results: StateFlow<List<AgentResult>> = _results.asStateFlow()

    private val _runningAgents = MutableStateFlow<Set<String>>(emptySet())
    val runningAgents: StateFlow<Set<String>> = _runningAgents.asStateFlow()

    // ── Agent registry ────────────────────────────────────────────────────
    private val agentHandlers = mutableMapOf<String, suspend (String) -> String>()

    fun registerAgent(name: String, handler: suspend (String) -> String) {
        agentHandlers[name] = handler
        Log.d(TAG, "Agent registered: $name")
    }

    // ── Submit single task ────────────────────────────────────────────────
    fun submitTask(task: AgentTask): Deferred<AgentResult> = scope.async {
        val handler = agentHandlers[task.agentName]
            ?: return@async AgentResult(task.id, task.agentName, "", false, 0L,
                "No handler registered for agent: ${task.agentName}")

        _runningAgents.value = _runningAgents.value + task.agentName
        val start = System.currentTimeMillis()

        try {
            val output = withTimeout(task.timeoutMs) { handler(task.input) }
            val result = AgentResult(
                taskId     = task.id,
                agentName  = task.agentName,
                output     = output,
                success    = true,
                latencyMs  = System.currentTimeMillis() - start
            )
            _results.value = (_results.value + result).takeLast(100)
            Log.d(TAG, "${task.agentName} done in ${result.latencyMs}ms")
            result
        } catch (e: TimeoutCancellationException) {
            val r = AgentResult(task.id, task.agentName, "", false,
                System.currentTimeMillis() - start, "Timeout after ${task.timeoutMs}ms")
            _results.value = (_results.value + r).takeLast(100)
            r
        } catch (e: Exception) {
            val r = AgentResult(task.id, task.agentName, "", false,
                System.currentTimeMillis() - start, e.message ?: "Agent error")
            _results.value = (_results.value + r).takeLast(100)
            r
        } finally {
            _runningAgents.value = _runningAgents.value - task.agentName
        }
    }

    // ── Submit parallel tasks ─────────────────────────────────────────────
    suspend fun submitParallel(tasks: List<AgentTask>): List<AgentResult> {
        Log.d(TAG, "Submitting ${tasks.size} agents in parallel")
        return tasks.map { submitTask(it) }.awaitAll()
    }

    // ── Convenience: run named agents on same input ───────────────────────
    suspend fun fanOut(input: String, agentNames: List<String>): List<AgentResult> {
        val tasks = agentNames.mapIndexed { i, name ->
            AgentTask(
                id        = "${System.currentTimeMillis()}_$i",
                agentName = name,
                input     = input
            )
        }
        return submitParallel(tasks)
    }

    fun clearResults() { _results.value = emptyList() }
    fun availableAgents(): List<String> = agentHandlers.keys.toList()
}
