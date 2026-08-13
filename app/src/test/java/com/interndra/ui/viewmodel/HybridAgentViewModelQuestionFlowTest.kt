package com.interndra.ui.viewmodel

import android.content.Context
import android.os.Looper
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import com.interndra.agent.AgentQuestion
import com.interndra.agent.AgentState
import com.interndra.agent.QuestionAnswer
import kotlinx.coroutines.runBlocking
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
 * under Robolectric (real Application, Room SQLite, DataStore):
 *
 *   sendCommand("Build me a mobile app.")  → pauses with the platform question
 *   answerQuestion(android)                → resumes the SAME task
 *   resumed run                            → never re-asks the question
 *
 * The autonomous web-search toggle is pre-disabled via DataStore so the
 * resumed run stays fully offline and deterministic.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class HybridAgentViewModelQuestionFlowTest {

    /** Same DataStore file the app's ProviderSettings uses. */
    private val Context.searchDataStore by preferencesDataStore("interndra_search_prefs")
    private val WEB_SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")

    /** Run all tasks currently queued on the Robolectric main looper. */
    private fun idleMain() {
        shadowOf(Looper.getMainLooper()).idle()
    }

    /** Idle the main looper and poll until [cond] holds or [timeoutMs] elapses. */
    private fun await(timeoutMs: Long = 30_000L, what: String, cond: () -> Boolean) {
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

        // Keep the resumed run fully offline: disable autonomous web search
        // BEFORE the ViewModel first reads the (lazy) toggle.
        runBlocking { app.searchDataStore.edit { it[WEB_SEARCH_ENABLED] = false } }

        val vm = HybridAgentViewModel(app)

        // Settle the lazy web-search toggle from DataStore — otherwise the
        // first .value access returns the default (true) and the resumed run
        // would attempt real network calls.
        vm.webSearchEnabled.value
        await(what = "web search toggle settled from DataStore") { !vm.webSearchEnabled.value }

        // ── Phase 1: sendCommand() pauses with the platform question ──────
        vm.sendCommand("Build me a mobile app.")

        await(what = "pending platform question") { vm.pendingQuestion.value != null }

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

        // Fire the 250 ms resume delay, then let the resumed run complete
        // (Room + DataStore work happens on real threads, polled via idleMain).
        shadowOf(Looper.getMainLooper()).idleFor(700, TimeUnit.MILLISECONDS)
        await(what = "resumed task to complete (agent COMPLETED, loading reset)") {
            vm.agentState.value == AgentState.COMPLETED && !vm.uiState.value.isLoading
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
