package com.interndra.agent

/**
 * AgentState — the explicit state machine of the INTENDRA agent.
 *
 * The orchestrator transitions through these states as it works through a
 * request. UI observes [AgentOrchestrator.state] to show a live status
 * indicator ("Working…", "Searching…", "Verifying…").
 *
 * States follow the lifecycle: IDLE → ANALYZING → (PLANNING | THINKING) →
 * (SEARCHING | READING | EXECUTING | WAITING_FOR_TOOL) → INSPECTING →
 * (VERIFYING) → COMPLETED | FAILED. WAITING_FOR_CONFIRMATION is entered when
 * a destructive/sensitive operation needs user approval before it can run.
 */
enum class AgentState(val label: String) {
    /** No task running. */
    IDLE("Idle"),

    /** Reading the request and deciding what it needs. */
    ANALYZING("Analyzing"),

    /** Building a plan for moderate/complex tasks. */
    PLANNING("Planning"),

    /** Reasoning without a tool (routing, intent, explanation). */
    THINKING("Thinking"),

    /** Web search in progress. */
    SEARCHING("Searching"),

    /** Reading webpages / files that were found. */
    READING("Reading"),

    /** A tool (terminal, filesystem, intent…) is running. */
    EXECUTING("Executing"),

    /** Waiting for a tool to return (long-running process). */
    WAITING_FOR_TOOL("Waiting for tool"),

    /** Examining a tool's output to decide the next action. */
    INSPECTING("Inspecting"),

    /** Confirming that completed work is actually correct. */
    VERIFYING("Verifying"),

    /** A destructive/sensitive operation needs user approval. */
    WAITING_FOR_CONFIRMATION("Needs confirmation"),

    /** Task finished successfully. */
    COMPLETED("Completed"),

    /** Task failed or was aborted. */
    FAILED("Failed");

    /** True while the agent is actively working (not idle/completed/failed). */
    fun isActive(): Boolean = when (this) {
        IDLE, COMPLETED, FAILED -> false
        else -> true
    }
}
