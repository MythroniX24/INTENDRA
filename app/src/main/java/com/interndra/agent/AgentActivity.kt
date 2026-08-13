package com.interndra.agent

/**
 * AgentActivity — a user-safe, high-level event emitted while the agent works.
 *
 * IMPORTANT: these events never expose private chain-of-thought. They are
 * concise, human-readable summaries of what the agent is doing ("Running
 * tests…", "Found 8 sources"), which tool it used, whether it succeeded, and
 * how long it took.
 *
 * The full list for the current run is exposed via
 * [AgentOrchestrator.activities] and rendered as a Claude-style activity
 * timeline in the chat.
 */
sealed class AgentActivity {
    abstract val timestampMs: Long

    /** Short reasoning summary — not raw chain-of-thought. */
    data class Thinking(
        val message: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** High-level plan summary for moderate/complex tasks. */
    data class Planning(
        val message: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** A tool invocation started. [command] is the preview shown to the user. */
    data class ToolStart(
        val tool: String,
        val description: String,
        val command: String = "",
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** A tool invocation finished. [summary] is a short result (never raw dumps). */
    data class ToolResult(
        val tool: String,
        val success: Boolean,
        val summary: String,
        val durationMs: Long = 0L,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** Autonomous web search performed with the given query. */
    data class Search(
        val query: String,
        val message: String = "Searching the web",
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** Reading a webpage / file that was found. */
    data class Reading(
        val message: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** A verification step (build passed, tests passed, file exists…). */
    data class Verification(
        val message: String,
        val success: Boolean = true,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** A non-fatal error that was diagnosed (may lead to self-correction). */
    data class Error(
        val message: String,
        override val timestampMs: Long = System.currentTimeMillis()
    ) : AgentActivity()

    /** The whole run finished successfully. */
    data object Completed : AgentActivity() {
        override val timestampMs: Long = 0L
    }
}
