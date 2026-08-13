package com.interndra.agent

/**
 * AgentQuestion — the structured question model for the interactive
 * Questioning / Clarification engine (spec §14).
 *
 * Questions appear as inline chat cards, never as blocking system dialogs.
 * Answers are returned structurally ([QuestionAnswer]) so the Agent never has
 * to parse UI text.
 *
 * Question types (spec §3): single choice, multi choice, confirmation,
 * yes/no, text input, number input. Every question has an ID so the engine
 * can remember it was asked and never re-ask (spec §10, §21).
 */
sealed class AgentQuestion {
    abstract val id: String
    abstract val question: String
    abstract val type: Type

    enum class Type(val label: String) {
        SINGLE_CHOICE("Single choice"),
        MULTI_CHOICE("Multi choice"),
        CONFIRMATION("Confirmation"),
        YES_NO("Yes/No"),
        TEXT_INPUT("Text input"),
        NUMBER_INPUT("Number input")
    }

    /** User picks exactly one option. [youDecide] adds a "You decide" option. */
    data class SingleChoice(
        override val id: String,
        override val question: String,
        val options: List<QuestionOption>,
        val allowCustomAnswer: Boolean = true,
        val youDecide: Boolean = true
    ) : AgentQuestion() {
        override val type: Type = Type.SINGLE_CHOICE
    }

    /** User picks zero or more options. */
    data class MultiChoice(
        override val id: String,
        override val question: String,
        val options: List<QuestionOption>
    ) : AgentQuestion() {
        override val type: Type = Type.MULTI_CHOICE
    }

    /** Explicit yes/no for a sensitive or destructive operation. */
    data class Confirmation(
        override val id: String,
        override val question: String,
        val confirmLabel: String = "Continue",
        val cancelLabel: String = "Cancel",
        val commandPreview: String = ""
    ) : AgentQuestion() {
        override val type: Type = Type.CONFIRMATION
    }

    /** Simple yes/no question. */
    data class YesNo(
        override val id: String,
        override val question: String
    ) : AgentQuestion() {
        override val type: Type = Type.YES_NO
    }

    /** Free-text input. */
    data class TextInput(
        override val id: String,
        override val question: String,
        val placeholder: String = ""
    ) : AgentQuestion() {
        override val type: Type = Type.TEXT_INPUT
    }

    /** Numeric input. */
    data class NumberInput(
        override val id: String,
        override val question: String,
        val placeholder: String = "0"
    ) : AgentQuestion() {
        override val type: Type = Type.NUMBER_INPUT
    }
}

/**
 * QuestionOption — one tappable choice (spec §5).
 * Keep descriptions short; the user must understand the choice quickly.
 */
data class QuestionOption(
    val id: String,
    val title: String,
    val description: String = "",
    /** Honest recommendation based on task requirements — never manipulative. */
    val recommended: Boolean = false,
    /** Shown when choosing this option may have side effects. */
    val warning: String = "",
    val icon: String? = null
)

/**
 * QuestionAnswer — structured answer to an [AgentQuestion] (spec §15).
 * The Agent receives these rather than raw UI text.
 */
sealed class QuestionAnswer {
    abstract val questionId: String

    data class SingleChoiceAnswer(
        override val questionId: String,
        val optionId: String
    ) : QuestionAnswer()

    data class MultiChoiceAnswer(
        override val questionId: String,
        val optionIds: List<String>
    ) : QuestionAnswer()

    data class ConfirmationAnswer(
        override val questionId: String,
        val confirmed: Boolean
    ) : QuestionAnswer()

    data class YesNoAnswer(
        override val questionId: String,
        val yes: Boolean
    ) : QuestionAnswer()

    data class TextAnswer(
        override val questionId: String,
        val value: String
    ) : QuestionAnswer()

    data class NumberAnswer(
        override val questionId: String,
        val value: Int
    ) : QuestionAnswer()

    /** The user picked the "Other" option and typed their own answer. */
    data class CustomAnswer(
        override val questionId: String,
        val value: String
    ) : QuestionAnswer()

    /** The user picked "You decide" — the agent picks a reasonable default. */
    data class YouDecide(
        override val questionId: String
    ) : QuestionAnswer()

    /** The user cancelled the task while this question was waiting. */
    data class Cancelled(
        override val questionId: String
    ) : QuestionAnswer()
}
