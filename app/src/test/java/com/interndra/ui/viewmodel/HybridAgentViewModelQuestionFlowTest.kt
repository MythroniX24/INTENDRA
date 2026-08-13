package com.interndra.ui.viewmodel

import android.os.Looper
import com.interndra.agent.AgentActivity
import com.interndra.agent.AgentQuestion
import com.interndra.agent.AgentState
import com.interndra.agent.QuestionAnswer
import com.interndra.agent.QuestionOption
import com.interndra.data.local.AgentDatabase
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
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

    /**
     * AgentDatabase holds a static singleton over a file-backed Room DB. Under
     * Robolectric each test gets a fresh Application, but the static instance
     * survives across tests in the same class — so messages persisted by one
     * test would leak into the next. Reset the singleton before every test so
     * each one starts from a truly empty database.
     */
    @Before
    fun resetDatabase() {
        // Kotlin compiles a private companion-object property's backing field
        // as a STATIC field on the outer class (not on the Companion class).
        val field = try {
            AgentDatabase::class.java.getDeclaredField("instance")
        } catch (_: NoSuchFieldException) {
            AgentDatabase.Companion::class.java.getDeclaredField("instance")
        }
        field.isAccessible = true
        field.set(null, null)
    }

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

    @Test
    fun `mid-task question pauses the agent and the answer resumes the same task`() {
        val app = RuntimeEnvironment.getApplication()
        val vm = HybridAgentViewModel(app)

        // An in-flight task (as a tool/agent would mid-execution, spec §12)
        // discovers an important ambiguity and calls askQuestion(), which
        // suspends the task until the user answers.
        val receivedAnswer = CompletableDeferred<QuestionAnswer>()
        val taskScope = CoroutineScope(Dispatchers.Main)
        taskScope.launch {
            val answer = vm.askQuestion(
                AgentQuestion.SingleChoice(
                    id = "approach",
                    question = "Which approach should I use?",
                    options = listOf(
                        QuestionOption(
                            "upgrade", "Upgrade existing renderer",
                            "Smaller change, lower risk", recommended = true
                        ),
                        QuestionOption(
                            "replace", "Replace renderer",
                            "More control, larger change"
                        )
                    )
                )
            )
            receivedAnswer.complete(answer)
            // The task continues after the answer and finishes normally.
            vm.agentOrchestrator.complete()
        }

        // ── Phase 1: the running task pauses with the question ─────────────
        await(what = "mid-task question pending") {
            vm.pendingQuestion.value != null && vm.agentState.value == AgentState.WAITING_FOR_USER
        }
        val question = vm.pendingQuestion.value
        assertTrue(
            "Expected SingleChoice, got ${question!!::class.simpleName}",
            question is AgentQuestion.SingleChoice
        )
        assertEquals("approach", (question as AgentQuestion.SingleChoice).id)
        assertTrue(
            "Waiting-for-choice activity should be shown",
            vm.agentActivity.value.any {
                it is AgentActivity.Thinking && it.message == "Waiting for your choice"
            }
        )
        // The pause does not persist anything or re-enter a new task.
        assertEquals("no messages persisted by the mid-task pause", 0, vm.messages.value.size)

        // ── Phase 2: the user answers the mid-task question ────────────────
        vm.answerQuestion(QuestionAnswer.SingleChoiceAnswer("approach", "upgrade"))

        // ── Phase 3: the SAME task resumed with the exact structured answer ─
        assertNull("question cleared after answer", vm.pendingQuestion.value)
        await(what = "suspended task resumed with the structured answer") {
            receivedAnswer.isCompleted
        }
        assertEquals(
            QuestionAnswer.SingleChoiceAnswer("approach", "upgrade"),
            receivedAnswer.getCompleted()
        )

        // The resumed task ran to completion; the agent is no longer waiting.
        await(what = "task completed after the answer") {
            vm.agentState.value == AgentState.COMPLETED
        }
        assertNotEquals(
            "agent no longer waiting for the user",
            AgentState.WAITING_FOR_USER,
            vm.agentState.value
        )
        // A mid-task answer must NEVER re-trigger the pre-task resume path
        // (no stored pre-task request → no sendCommand → no new messages).
        assertEquals(
            "mid-task answer must not re-send the request as a new message",
            0, vm.messages.value.size
        )

        taskScope.cancel()
    }
}
