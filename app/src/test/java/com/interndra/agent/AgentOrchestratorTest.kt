package com.interndra.agent

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the agent state machine, bounded loop, self-correction and verification. */
class AgentOrchestratorTest {

    // ── State machine ─────────────────────────────────────────────────────

    @Test
    fun `begin enters ANALYZING and emits a thinking event`() {
        val orch = AgentOrchestrator()
        orch.begin("Fix the build")

        assertEquals(AgentState.ANALYZING, orch.state.value)
        assertTrue(orch.activities.value.isNotEmpty())
        assertTrue(orch.activities.value.first() is AgentActivity.Thinking)
        assertTrue(orch.isActive())
    }

    @Test
    fun `complete moves to COMPLETED and stops the run`() {
        val orch = AgentOrchestrator()
        orch.begin("Hi")
        orch.complete()

        assertEquals(AgentState.COMPLETED, orch.state.value)
        assertFalse(orch.isActive())
        assertEquals(AgentActivity.Completed, orch.activities.value.last())
    }

    @Test
    fun `fail moves to FAILED with an error event`() {
        val orch = AgentOrchestrator()
        orch.begin("Run tests")
        orch.fail("Tests failed")

        assertEquals(AgentState.FAILED, orch.state.value)
        assertTrue(orch.activities.value.last() is AgentActivity.Error)
        assertFalse(orch.isActive())
    }

    @Test
    fun `reset clears state and activities`() {
        val orch = AgentOrchestrator()
        orch.begin("Do work")
        orch.planning("Plan")
        orch.reset()

        assertEquals(AgentState.IDLE, orch.state.value)
        assertTrue(orch.activities.value.isEmpty())
        assertEquals(0, orch.toolCallCount)
    }

    @Test
    fun `needsConfirmation enters WAITING_FOR_CONFIRMATION`() {
        val orch = AgentOrchestrator()
        orch.begin("Delete files")
        orch.needsConfirmation("This command may delete data")

        assertEquals(AgentState.WAITING_FOR_CONFIRMATION, orch.state.value)
    }

    @Test
    fun `toolStart increments the bounded call counter`() {
        val orch = AgentOrchestrator(maxToolCallsPerRun = 5)
        orch.begin("Do work")
        repeat(3) { orch.toolStart("terminal", "cmd $it", "echo $it") }

        assertEquals(3, orch.toolCallCount)
        assertEquals(AgentState.EXECUTING, orch.state.value)
    }

    // ── Self-correction loop (Phase 7 + 8) ────────────────────────────────

    @Test
    fun `self correction retries with corrected attempt and succeeds`() = runBlocking {
        val orch = AgentOrchestrator(maxRetriesPerTool = 2)
        var firstAttempt = true
        val outcome = orch.runWithSelfCorrection(
            tool = "terminal",
            description = "Install python",
            command = "pkg install python",
            attempts = listOf(
                { AgentOrchestrator.Outcome(false, "", "permission denied") },
                {
                    if (firstAttempt) { firstAttempt = false; AgentOrchestrator.Outcome(true, "installed") }
                    else AgentOrchestrator.Outcome(false, "", "unexpected")
                }
            )
        )

        assertTrue(outcome.success)
        assertFalse(firstAttempt)  // both attempts were consumed
        // Timeline: ToolStart + Thinking(diagnosis) + ToolResult
        val activities = orch.activities.value
        assertTrue(activities.any { it is AgentActivity.ToolStart })
        assertTrue(activities.any { it is AgentActivity.Thinking })
        assertTrue(activities.last() is AgentActivity.ToolResult)
    }

    @Test
    fun `self correction gives up after bounded retries`() = runBlocking {
        val orch = AgentOrchestrator(maxRetriesPerTool = 1)
        var calls = 0
        val outcome = orch.runWithSelfCorrection(
            tool = "terminal",
            description = "Flaky command",
            command = "flaky",
            attempts = listOf(
                { calls++; AgentOrchestrator.Outcome(false, "", "boom") },
                { calls++; AgentOrchestrator.Outcome(false, "", "boom again") }
            )
        )

        assertFalse(outcome.success)
        // maxRetriesPerTool=1 → at most 2 attempts
        assertEquals(2, calls)
    }

    @Test
    fun `self correction skips retry when first attempt succeeds`() = runBlocking {
        val orch = AgentOrchestrator(maxRetriesPerTool = 2)
        var calls = 0
        val outcome = orch.runWithSelfCorrection(
            tool = "terminal",
            description = "Quick command",
            command = "pwd",
            attempts = listOf(
                { calls++; AgentOrchestrator.Outcome(true, "ok") },
                { calls++; AgentOrchestrator.Outcome(true, "ok") }
            )
        )

        assertTrue(outcome.success)
        assertEquals(1, calls)  // no unnecessary retry
        assertTrue(orch.activities.value.none { it is AgentActivity.Thinking })
    }

    @Test
    fun `self correction never exceeds maxToolCallsPerRun`() = runBlocking {
        val orch = AgentOrchestrator(maxRetriesPerTool = 10, maxToolCallsPerRun = 3)
        val outcome = orch.runWithSelfCorrection(
            tool = "terminal",
            description = "Stubborn command",
            command = "nope",
            attempts = (1..20).map {
                { AgentOrchestrator.Outcome(false, "", "fail $it") }
            }
        )

        assertFalse(outcome.success)
        assertTrue(orch.toolCallCount <= 3)
    }

    // ── Verification (Phase 9) ────────────────────────────────────────────

    @Test
    fun `successful verification keeps the run going`() = runBlocking {
        val orch = AgentOrchestrator()
        orch.begin("Build")
        val passed = orch.verify("Verifying build") { AgentOrchestrator.Outcome(true, "build ok") }

        assertTrue(passed)
        assertEquals(AgentState.VERIFYING, orch.state.value)
        assertTrue(orch.activities.value.any { it is AgentActivity.Verification && it.success })
    }

    @Test
    fun `failed verification reports failure and does not complete`() = runBlocking {
        val orch = AgentOrchestrator()
        orch.begin("Build")
        val passed = orch.verify("Verifying build") { AgentOrchestrator.Outcome(false, "", "compile error") }

        assertFalse(passed)
        assertTrue(orch.activities.value.any { it is AgentActivity.Verification && !it.success })
    }
}
