package com.interndra.agent

import java.util.concurrent.atomic.AtomicLong

/**
 * AgentObservability — internal diagnostics for agent tool calls (spec §32).
 *
 * Every tool invocation gets a unique [newId] (tool call ID), and the
 * [ToolCallTracker] records the lifecycle (started, duration, exit code,
 * success) so the UI and logs can answer: what tool ran, when, how long,
 * and did it succeed. Never logs secrets — only IDs, tool names, commands
 * (user-initiated) and exit codes.
 */
object AgentObservability {

    private val counter = AtomicLong(0)

    /** Generate a unique ID: `tool-<epochMs>-<seq>`. */
    fun newId(prefix: String = "tool"): String =
        "$prefix-${System.currentTimeMillis()}-${counter.incrementAndGet()}"
}

/**
 * In-memory tracker of recent tool calls. Bounded — old entries are dropped
 * so memory never grows unbounded on long conversations.
 */
class ToolCallTracker(
    private val maxEntries: Int = 50,
    private val timeSource: () -> Long = System::currentTimeMillis
) {

    data class ToolCall(
        val id: String,
        val tool: String,
        val command: String,
        val sessionId: String,
        val startedAtMs: Long,
        var durationMs: Long = 0L,
        var exitCode: Int? = null,
        var success: Boolean? = null
    )

    private val calls = ArrayDeque<ToolCall>()

    /** All tracked calls, newest first. */
    val recentCalls: List<ToolCall> get() = calls.toList().asReversed()

    /** Start a call and return its ID. */
    fun start(tool: String, command: String, sessionId: String): String {
        val call = ToolCall(
            id = AgentObservability.newId("tool"),
            tool = tool,
            command = command.take(200),
            sessionId = sessionId,
            startedAtMs = timeSource()
        )
        calls.addLast(call)
        while (calls.size > maxEntries) calls.removeFirst()
        return call.id
    }

    /** Mark a call finished with its result. */
    fun finish(
        id: String,
        exitCode: Int,
        success: Boolean
    ) {
        val call = calls.lastOrNull { it.id == id } ?: return
        call.durationMs = timeSource() - call.startedAtMs
        call.exitCode = exitCode
        call.success = success
    }

    /** Get a call by ID, or null. */
    fun get(id: String): ToolCall? = calls.lastOrNull { it.id == id }

    /** Clear all tracking (e.g. on agent reset). */
    fun clear() = calls.clear()
}
