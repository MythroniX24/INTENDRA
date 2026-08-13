package com.interndra.agent

/**
 * PreTaskQuestionFlow — owns the stateful pre-task questioning flow that the
 * ViewModel drives (spec §1, §13, §21).
 *
 * The flow mirrors exactly what `HybridAgentViewModel.sendCommand` →
 * `answerQuestion` → resume does:
 *
 *   1. `decide(request)`     — ask the Questioning Engine whether this request
 *                              needs user input. If yes, the task pauses
 *                              (WAITING_FOR_USER) with the question pending and
 *                              the original request stored for resume.
 *   2. `onAnswered(answer)`  — record the structured answer, clear the pending
 *                              question, and return the stored request so the
 *                              SAME task can resume.
 *   3. `decide(resume)`      — run again after the answer: the history guard
 *                              prevents the same question from ever re-asking.
 *   4. `contextBlock()`      — collected answers injected into the resumed
 *                              task's prompt so the model sees user choices.
 *   5. `cancel()`            — safe cancel: the pending question is recorded so
 *                              it is not re-asked, and no task resumes.
 *
 * Extracted from the ViewModel so the full end-to-end flow is pure, deterministic
 * and unit-testable without the Android framework.
 */
class PreTaskQuestionFlow(
    private val engine: QuestioningEngine = QuestioningEngine
) {

    /** The question currently waiting for the user (null when not paused). */
    @Volatile var pendingQuestion: AgentQuestion? = null
        private set

    /** The original request stored while a pre-task question waits (spec §6). */
    @Volatile var pendingResumeInput: String? = null
        private set

    /** Question IDs already asked in this session — never re-asked (§10, §21). */
    private val history = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    /** Structured answers collected so far — injected into the task context. */
    private val answers = java.util.concurrent.ConcurrentHashMap<String, QuestionAnswer>()

    /**
     * Mirrors the ViewModel's processCommand decision block: consult the
     * Questioning Engine; when a question is required, pause the task
     * (WAITING_FOR_USER) by storing the question + the request to resume.
     * Returns the question to show, or null to continue automatically.
     */
    fun decide(request: String): AgentQuestion? {
        val plan = engine.needsQuestion(request, alreadyAsked = history, answers = answers)
        if (plan != null) {
            pendingResumeInput = request
            pendingQuestion = plan.question
            return plan.question
        }
        return null
    }

    /**
     * Mirrors the ViewModel's answerQuestion callback: record the structured
     * answer, clear the pending question, and return the stored request that
     * should be re-run so the SAME task resumes with the answer in context.
     * Returns null when there was no paused pre-task request (e.g. mid-task).
     */
    fun onAnswered(answer: QuestionAnswer): String? {
        history.add(answer.questionId)
        answers[answer.questionId] = answer
        pendingQuestion = null
        val resume = pendingResumeInput
        pendingResumeInput = null
        return resume
    }

    /**
     * Mirrors the ViewModel's cancelQuestion callback: safe cancel of a waiting
     * task. The pending question is recorded so it is not re-asked this session,
     * and no resume happens.
     */
    fun cancel() {
        pendingQuestion?.let { history.add(it.id) }
        pendingQuestion = null
        pendingResumeInput = null
    }

    /** True when [questionId] has already been answered (or cancelled). */
    fun isAnswered(questionId: String): Boolean = questionId in history

    /**
     * Mirrors the ViewModel's questionContext(): formats the collected answers
     * as prompt context for the resumed task. Empty when nothing was collected.
     */
    fun contextBlock(): String {
        if (answers.isEmpty()) return ""
        return buildString {
            append("\n\n── User preferences ──\n")
            answers.values.forEach { a ->
                when (a) {
                    is QuestionAnswer.SingleChoiceAnswer -> appendLine("- ${a.questionId}: ${a.optionId}")
                    is QuestionAnswer.MultiChoiceAnswer -> appendLine("- ${a.questionId}: ${a.optionIds.joinToString(", ")}")
                    is QuestionAnswer.TextAnswer -> appendLine("- ${a.questionId}: ${a.value}")
                    is QuestionAnswer.CustomAnswer -> appendLine("- ${a.questionId}: ${a.value}")
                    is QuestionAnswer.YesNoAnswer -> appendLine("- ${a.questionId}: ${if (a.yes) "yes" else "no"}")
                    is QuestionAnswer.NumberAnswer -> appendLine("- ${a.questionId}: ${a.value}")
                    is QuestionAnswer.YouDecide -> appendLine("- ${a.questionId}: agent decides")
                    else -> { /* confirmation / cancelled — no context */ }
                }
            }
        }.trimEnd()
    }
}
