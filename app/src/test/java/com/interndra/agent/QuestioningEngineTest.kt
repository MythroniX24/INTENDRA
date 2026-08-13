package com.interndra.agent

import com.interndra.agent.QuestioningEngine.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the Questioning Engine — the acceptance scenarios in §37. */
class QuestioningEngineTest {

    // ── TEST 1 — Simple task: no question ────────────────────────────────

    @Test
    fun `simple arithmetic never asks`() {
        assertNull(QuestioningEngine.needsQuestion("What is 2 + 2?"))
        assertNull(QuestioningEngine.needsQuestion("25 x 4"))
        assertNull(QuestioningEngine.needsQuestion("Calculate 15% of 300"))
    }

    @Test
    fun `casual chat and direct questions never ask`() {
        assertNull(QuestioningEngine.needsQuestion("hi"))
        assertNull(QuestioningEngine.needsQuestion("What is Kotlin?"))
        assertNull(QuestioningEngine.needsQuestion("Explain coroutines"))
        assertNull(QuestioningEngine.needsQuestion("Translate hello to spanish"))
    }

    // ── TEST 3 — Clear task: no unnecessary question ─────────────────────

    @Test
    fun `explicit run commands never ask`() {
        assertNull(QuestioningEngine.needsQuestion("Run ./gradlew test."))
        assertNull(QuestioningEngine.needsQuestion("Run the tests in my project"))
        assertNull(QuestioningEngine.needsQuestion("git status"))
        assertNull(QuestioningEngine.needsQuestion("$ ls -la"))
    }

    // ── TEST 2 — Ambiguous task: ask ─────────────────────────────────────

    @Test
    fun `project creation without platform asks for the platform`() {
        val plan = QuestioningEngine.needsQuestion("Build me a mobile app.")
        assertNotNull(plan)
        assertEquals(Decision.ASK_CHOICE, plan!!.decision)
        assertTrue(plan.question is AgentQuestion.SingleChoice)
        val q = plan.question as AgentQuestion.SingleChoice
        assertEquals("project_platform", q.id)
        assertTrue(q.options.any { it.id == "android" })
        assertTrue(q.options.any { it.id == "web" })
    }

    @Test
    fun `project creation with platform does not ask`() {
        assertNull(QuestioningEngine.needsQuestion("Create an Android app"))
        assertNull(QuestioningEngine.needsQuestion("Build a python project"))
        assertNull(QuestioningEngine.needsQuestion("Make a web app"))
    }

    // ── TEST 10 — Duplicate question guard ───────────────────────────────

    @Test
    fun `question is not re-asked once answered`() {
        val askedOnce = setOf("project_platform")
        assertNull(QuestioningEngine.needsQuestion("Build me an app", alreadyAsked = askedOnce))
        // Fresh session asks again.
        assertNotNull(QuestioningEngine.needsQuestion("Build me an app"))
    }

    // ── Priority (spec §22) ───────────────────────────────────────────────

    @Test
    fun `confirmation outranks choices`() {
        assertTrue(
            QuestioningEngine.priority(AgentQuestion.Type.CONFIRMATION) <
                QuestioningEngine.priority(AgentQuestion.Type.SINGLE_CHOICE)
        )
        assertTrue(
            QuestioningEngine.priority(AgentQuestion.Type.SINGLE_CHOICE) <
                QuestioningEngine.priority(AgentQuestion.Type.NUMBER_INPUT)
        )
    }

    // ── shouldContinue (spec §8, §25) ─────────────────────────────────────

    @Test
    fun `continues by default and when defaults exist`() {
        assertTrue(QuestioningEngine.shouldContinue(Decision.CONTINUE))
        assertTrue(QuestioningEngine.shouldContinue(Decision.ASK_CHOICE, defaultsAvailable = true))
        // Low-impact choices never block.
        assertTrue(QuestioningEngine.shouldContinue(Decision.ASK_CHOICE, defaultsAvailable = false, impactLow = true))
        // High-impact architecture choice without a default does block.
        assertTrue(!QuestioningEngine.shouldContinue(Decision.ASK_CHOICE, defaultsAvailable = false))
        // Destructive ops always require confirmation.
        assertTrue(!QuestioningEngine.shouldContinue(Decision.REQUEST_CONFIRMATION, defaultsAvailable = true))
    }

    // ── Safety bridge (spec §18) ──────────────────────────────────────────

    @Test
    fun `destructive operations require confirmation unless approved`() {
        assertEquals(
            Decision.REQUEST_CONFIRMATION,
            QuestioningEngine.confirmationDecision(isDestructive = true, alreadyApproved = false)
        )
        assertEquals(
            Decision.CONTINUE,
            QuestioningEngine.confirmationDecision(isDestructive = true, alreadyApproved = true)
        )
        assertEquals(
            Decision.CONTINUE,
            QuestioningEngine.confirmationDecision(isDestructive = false, alreadyApproved = false)
        )
    }

    // ── Multi-step sequence (spec §11, §23) ───────────────────────────────

    @Test
    fun `ai project setup asks platform then language adaptively`() {
        val engine = QuestioningEngine
        val first = engine.needsQuestion("Set up a new AI project")
        assertNotNull(first)
        assertEquals("proj_platform", (first!!.question as AgentQuestion.SingleChoice).id)

        // After platform answered, language question depends on it.
        val second = engine.nextSequenceQuestion(
            "Set up a new AI project",
            answers = mapOf(
                "proj_platform" to QuestionAnswer.SingleChoiceAnswer("proj_platform", "android")
            )
        )
        assertNotNull(second)
        val lang = second!!.question as AgentQuestion.SingleChoice
        assertEquals("proj_language", lang.id)
        assertTrue(lang.options.any { it.id == "kotlin" })
        assertTrue(lang.options.any { it.id == "java" })
        assertTrue(lang.options.first { it.id == "kotlin" }.recommended)
    }

    @Test
    fun `sequence ends after all questions answered`() {
        val engine = QuestioningEngine
        val next = engine.nextSequenceQuestion(
            "Set up a new AI project",
            answers = mapOf(
                "proj_platform" to QuestionAnswer.SingleChoiceAnswer("proj_platform", "android"),
                "proj_language" to QuestionAnswer.SingleChoiceAnswer("proj_language", "kotlin")
            ),
            alreadyAsked = setOf("proj_platform", "proj_language")
        )
        assertNull(next)
    }
}
