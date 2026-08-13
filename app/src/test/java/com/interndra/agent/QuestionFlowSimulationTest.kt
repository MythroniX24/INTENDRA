package com.interndra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * QuestionFlowSimulationTest — drives the FULL pre-task question flow
 * end-to-end, exactly as `HybridAgentViewModel` does:
 *
 *   sendCommand(request)         → flow.decide(request)
 *   (task pauses, WAITING_FOR_USER, question card shown)
 *   answerQuestion(answer)       → flow.onAnswered(answer) → resume input
 *   resumed sendCommand(request) → flow.decide(request)    → never re-asks
 *   prompt build                 → flow.contextBlock()     → answer injected
 *   cancelQuestion()             → flow.cancel()           → safe stop
 *
 * This is the same code path the ViewModel delegates to (the ViewModel's
 * question-flow state lives in [PreTaskQuestionFlow]).
 */
class QuestionFlowSimulationTest {

    // ── Acceptance Test 2 (spec §37): ambiguous task ─────────────────────

    @Test
    fun `ambiguous request pauses with question and stores resume input`() {
        val flow = PreTaskQuestionFlow()

        // sendCommand("Build me a mobile app.")
        val question = flow.decide("Build me a mobile app.")

        assertNotNull(question)
        assertTrue(question is AgentQuestion.SingleChoice)
        val single = question as AgentQuestion.SingleChoice
        assertEquals("project_platform", single.id)
        assertTrue(single.options.any { it.id == "android" })
        assertTrue(single.options.any { it.id == "web" })

        // Paused: question pending + original request stored for resume.
        assertEquals(question, flow.pendingQuestion)
        assertEquals("Build me a mobile app.", flow.pendingResumeInput)
        assertFalse(flow.isAnswered("project_platform"))
    }

    // ── Acceptance Test 6 (spec §37): answer → resume SAME task ──────────

    @Test
    fun `answering resumes the same task and never re-asks the question`() {
        val flow = PreTaskQuestionFlow()
        val request = "Build me a mobile app."

        // Phase 1 — pause.
        assertNotNull(flow.decide(request))

        // Phase 2 — user taps "Android".
        val resumeInput = flow.onAnswered(
            QuestionAnswer.SingleChoiceAnswer("project_platform", "android")
        )

        // Phase 3 — the stored request is returned for re-running.
        assertEquals(request, resumeInput)
        assertNull(flow.pendingQuestion)
        assertNull(flow.pendingResumeInput)
        assertTrue(flow.isAnswered("project_platform"))

        // Phase 4 — resumed sendCommand() runs the SAME request again.
        // History guard: the platform question is NOT re-asked.
        assertNull(flow.decide(resumeInput!!))
        assertNull(flow.pendingQuestion)
        assertNull(flow.pendingResumeInput)
    }

    // ── Context injection: the answer reaches the resumed task ───────────

    @Test
    fun `collected answer is injected into the resumed task context`() {
        val flow = PreTaskQuestionFlow()
        assertNotNull(flow.decide("Build me a mobile app."))
        flow.onAnswered(QuestionAnswer.SingleChoiceAnswer("project_platform", "android"))

        val context = flow.contextBlock()
        assertTrue("Answer should appear in context: '$context'", context.contains("android"))
        assertTrue(context.contains("project_platform"))
    }

    // ── Acceptance Test 1 + 3: clear requests never pause ────────────────

    @Test
    fun `clear and explicit requests never pause the flow`() {
        val flow = PreTaskQuestionFlow()

        assertNull(flow.decide("What is 2 + 2?"))
        assertNull(flow.decide("What is Kotlin?"))
        assertNull(flow.decide("Run the tests in my project"))
        assertNull(flow.decide("./gradlew test"))
        assertNull(flow.decide("git status"))

        assertNull(flow.pendingQuestion)
        assertNull(flow.pendingResumeInput)
    }

    // ── Acceptance Test 10: duplicate question guard ─────────────────────

    @Test
    fun `already answered question is never asked again in the session`() {
        val flow = PreTaskQuestionFlow()
        assertNotNull(flow.decide("Build me a mobile app."))
        flow.onAnswered(QuestionAnswer.SingleChoiceAnswer("project_platform", "web"))

        // Even a fresh, unrelated ambiguous request does not re-ask the
        // platform question (history guard), and the new request itself is
        // still answered with the collected preference in context.
        assertNull(flow.decide("Build me a mobile app."))
        assertTrue(flow.contextBlock().contains("web"))
    }

    // ── Acceptance Test 9: cancel → safe stop, no re-ask ─────────────────

    @Test
    fun `cancelling a paused task stops it and does not re-ask the question`() {
        val flow = PreTaskQuestionFlow()
        assertNotNull(flow.decide("Build me a mobile app."))

        flow.cancel()

        assertNull(flow.pendingQuestion)
        assertNull(flow.pendingResumeInput)
        // ViewModel behaviour: a cancelled question is recorded so it is not
        // re-asked this session (spec §21, §31).
        assertTrue(flow.isAnswered("project_platform"))
        assertNull(flow.decide("Build me a mobile app."))
    }

    // ── Multi-step sequence (spec §11, §23): adaptive questions ──────────

    @Test
    fun `multi-step sequence asks platform then language and ends`() {
        val flow = PreTaskQuestionFlow()
        val request = "Set up a new AI project"

        // Step 1 — platform.
        val platform = flow.decide(request)
        assertNotNull(platform)
        assertEquals("proj_platform", platform!!.id)
        flow.onAnswered(QuestionAnswer.SingleChoiceAnswer("proj_platform", "android"))

        // Step 2 — language depends on the platform choice (Android → Kotlin).
        val language = flow.decide(request)
        assertNotNull(language)
        assertEquals("proj_language", language!!.id)
        val langOptions = (language as AgentQuestion.SingleChoice).options
        assertTrue(langOptions.any { it.id == "kotlin" })
        assertTrue(langOptions.any { it.id == "java" })
        flow.onAnswered(QuestionAnswer.SingleChoiceAnswer("proj_language", "kotlin"))

        // Step 3 — sequence complete: nothing left to ask.
        assertNull(flow.decide(request))
        assertNull(flow.pendingQuestion)

        // Both answers reach the resumed task's context.
        val context = flow.contextBlock()
        assertTrue(context.contains("android"))
        assertTrue(context.contains("kotlin"))
    }

    // ── You decide (spec §24): agent picks, flow continues ───────────────

    @Test
    fun `you-decide answer flows into context and resumes without re-asking`() {
        val flow = PreTaskQuestionFlow()
        assertNotNull(flow.decide("Build me a mobile app."))

        val resume = flow.onAnswered(QuestionAnswer.YouDecide("project_platform"))

        assertEquals("Build me a mobile app.", resume)
        assertNull(flow.decide(resume!!))
        assertTrue(flow.contextBlock().contains("agent decides"))
    }

    // ── Custom answer (spec §16) ─────────────────────────────────────────

    @Test
    fun `custom answer resumes and is injected into context`() {
        val flow = PreTaskQuestionFlow()
        assertNotNull(flow.decide("Build me a mobile app."))

        val resume = flow.onAnswered(QuestionAnswer.CustomAnswer("project_platform", "Flutter"))

        assertEquals("Build me a mobile app.", resume)
        assertNull(flow.decide(resume!!))
        assertTrue(flow.contextBlock().contains("Flutter"))
    }
}
