package com.interndra.ui.viewmodel

import android.os.Looper
import com.interndra.agent.AgentQuestion
import com.interndra.agent.AgentState
import com.interndra.agent.QuestionAnswer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.TimeUnit

/**
 * Drives the FULL pre-task question flow against the REAL [HybridAgentViewModel]
 * under Robolectric (real Application, Room SQLite, DataStore, main looper):
 *
 *   sendCommand("Build me a mobile app.")  → pauses with the platform question
 *   answerQuestion(android)                → resumes the SAME task
 *   resumed run                            → never re-asks the question
 *
 * The resumed run may trigger the autonomous web search (bounded by the
 * providers' call timeouts) and the local rule-based AI — both terminate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HybridAgentViewModelQuestionFlowTest {

    /** Run all tasks currently queued on the Robolectric main looper. */
    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Idle the main looper and poll until [cond] holds or [timeoutMs] elapses. */
    private fun await(timeoutMs: Long = 90_000L, what: String, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            idleMain()
            if (cond()) return
            Thread.sleep(25)
        }
        fail("Timed out waiting for: $what")
    }

    @Test
    fun `pre-task question flow end-to-end through the real ViewModel`() {
        val app = RuntimeEnvironment.getApplication()
        val vm = HybridAgentViewModel(app)

        // ── Phase 1: sendCommand() pauses with the platform question ──────
        vm.sendCommand("Build me a mobile app.")

        // Wait for the FULL paused state: question visible AND loading already
        // reset (the pause path clears isLoading synchronously with the card).
        await(what = "pending platform question with loading reset") {
            vm.pendingQuestion.value != null && !vm.uiState.value.isLoading
        }

        val question = vm.pendingQuestion.value
        assertNotNull("question should be pending", question)
        assertTrue(
            "Expected SingleChoice, got ${question!!::class.simpleName}",
            question is AgentQuestion.SingleChoice
        )
        assertEquals("project_platform", (question as AgentQuestion.SingleChoice).id)
        assertEquals("agent waits for the user", AgentState.WAITING_FOR_USER, vm.agentState.value)
        assertFalse("paused task is not loading", vm.uiState.value.isLoading)

        // The user message was persisted (real Room write under Robolectric).
        await(what = "user message persisted to Room") {
            vm.messages.value.any { it.content.contains("Build me a mobile app.") }
        }

        // ── Phase 2: answerQuestion() records + resumes the SAME task ─────
        vm.answerQuestion(QuestionAnswer.SingleChoiceAnswer("project_platform", "android"))

        assertNull("question cleared after answer", vm.pendingQuestion.value)
        assertEquals(AgentState.RESUMING, vm.agentState.value)

        // Fire the 250 ms resume delay, then let the resumed run reach a
        // terminal state. Room/DataStore/network work happens on real threads;
        // the main-looper continuations are drained by the poll loop. 90s
        // covers the bounded autonomous web search (providers cap calls at
        // 20-40s) + local reply. Both COMPLETED and FAILED are terminal — the
        // key guarantee is that the question was never re-asked either way.
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        await(what = "resumed task to terminate (COMPLETED or FAILED, loading reset)") {
            val state = vm.agentState.value
            (state == AgentState.COMPLETED || state == AgentState.FAILED) && !vm.uiState.value.isLoading
        }

        // ── Phase 3: the resumed run never re-asked the question ──────────
        assertNull("question must never be re-asked after answering", vm.pendingQuestion.value)
        assertNotEquals(
            "agent no longer waiting for the user",
            AgentState.WAITING_FOR_USER,
            vm.agentState.value
        )
    }
}
