package com.interndra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the AgentQuestion model and structured answers (§14, §15). */
class AgentQuestionTest {

    @Test
    fun `single choice question carries options and flags`() {
        val q = AgentQuestion.SingleChoice(
            id = "platform",
            question = "Which platform?",
            options = listOf(
                QuestionOption("android", "Android", "Kotlin", recommended = true),
                QuestionOption("web", "Web", "Browser")
            )
        )
        assertEquals(AgentQuestion.Type.SINGLE_CHOICE, q.type)
        assertEquals(2, q.options.size)
        assertTrue(q.options.first { it.id == "android" }.recommended)
        assertTrue(q.youDecide)
        assertTrue(q.allowCustomAnswer)
    }

    @Test
    fun `confirmation question carries labels and preview`() {
        val q = AgentQuestion.Confirmation(
            id = "del_env",
            question = "Delete the Linux environment?",
            confirmLabel = "Delete",
            cancelLabel = "Keep",
            commandPreview = "rm -rf termux"
        )
        assertEquals(AgentQuestion.Type.CONFIRMATION, q.type)
        assertEquals("Delete", q.confirmLabel)
        assertEquals("rm -rf termux", q.commandPreview)
    }

    @Test
    fun `option warnings are carried for destructive choices`() {
        val opt = QuestionOption("reset", "Reset", "Factory reset", warning = "Erases all data")
        assertEquals("Erases all data", opt.warning)
    }

    @Test
    fun `single choice answer maps to the option id`() {
        val a = QuestionAnswer.SingleChoiceAnswer("platform", "android")
        assertEquals("platform", a.questionId)
        assertEquals("android", a.optionId)
    }

    @Test
    fun `custom and you-decide answers are structured`() {
        val custom = QuestionAnswer.CustomAnswer("name", "INTENDRA")
        assertEquals("INTENDRA", custom.value)

        val youDecide = QuestionAnswer.YouDecide("platform")
        assertEquals("platform", youDecide.questionId)
    }

    @Test
    fun `confirmation answer records explicit consent`() {
        assertTrue(QuestionAnswer.ConfirmationAnswer("del_env", true).confirmed)
        assertTrue(!QuestionAnswer.ConfirmationAnswer("del_env", false).confirmed)
    }

    @Test
    fun `all question types have distinct ids and types`() {
        val questions = listOf<AgentQuestion>(
            AgentQuestion.SingleChoice("a", "q", listOf(QuestionOption("x", "X"))),
            AgentQuestion.MultiChoice("b", "q", listOf(QuestionOption("x", "X"))),
            AgentQuestion.Confirmation("c", "q"),
            AgentQuestion.YesNo("d", "q"),
            AgentQuestion.TextInput("e", "q"),
            AgentQuestion.NumberInput("f", "q")
        )
        assertEquals(6, questions.map { it.id }.toSet().size)
        assertEquals(6, questions.map { it.type }.toSet().size)
    }
}
