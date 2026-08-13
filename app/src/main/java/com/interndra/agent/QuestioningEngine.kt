package com.interndra.agent

/**
 * QuestioningEngine — decides WHEN the Agent needs user input (spec §2, §8, §9).
 *
 * The default is to CONTINUE: the Agent prefers reasonable defaults whenever
 * the choice is low-impact, reversible, or inferable. It only asks when the
 * request is genuinely ambiguous, the decision is architecturally significant,
 * an operation is destructive, or required information is missing.
 *
 * Pure and deterministic so the decision logic is fully unit-testable.
 */
object QuestioningEngine {

    /** Possible decisions for a request or context (spec §2). */
    enum class Decision(val label: String) {
        /** Continue automatically — no user input needed. */
        CONTINUE("Continue"),
        /** The request is genuinely ambiguous. */
        ASK_CLARIFICATION("Clarification"),
        /** Multiple approaches with meaningfully different outcomes. */
        ASK_CHOICE("Choice"),
        /** A destructive/sensitive operation needs explicit approval. */
        REQUEST_CONFIRMATION("Confirmation"),
        /** The operation needs a permission the user must grant. */
        REQUEST_PERMISSION("Permission")
    }

    /** A plan describing one question to ask. */
    data class QuestionPlan(
        val question: AgentQuestion,
        val decision: Decision,
        /** Lower = ask first (spec §22). */
        val priority: Int
    )

    // ── Priority (spec §22) ───────────────────────────────────────────────
    // 1. Safety-critical   2. Task-blocking   3. Architecture
    // 4. User preferences  5. Optimization    6. Optional details

    fun priority(type: AgentQuestion.Type): Int = when (type) {
        AgentQuestion.Type.CONFIRMATION -> 1
        AgentQuestion.Type.YES_NO -> 2
        AgentQuestion.Type.SINGLE_CHOICE -> 3
        AgentQuestion.Type.MULTI_CHOICE -> 4
        AgentQuestion.Type.TEXT_INPUT -> 5
        AgentQuestion.Type.NUMBER_INPUT -> 6
    }

    /** History guard — never re-ask a question that was already answered. */
    fun alreadyAsked(questionId: String, history: Set<String>): Boolean =
        questionId in history

    // ── Main entry: does this request need a question? ────────────────────

    /**
     * Decide whether the incoming request needs a clarifying question.
     *
     * Conservative by design: most requests CONTINUE. Only clearly ambiguous
     * requests (no platform for a new app, missing critical info) produce a
     * question, and only if it hasn't already been answered.
     */
    fun needsQuestion(
        request: String,
        alreadyAsked: Set<String> = emptySet(),
        answers: Map<String, QuestionAnswer> = emptyMap()
    ): QuestionPlan? {
        val text = request.trim()
        if (text.isEmpty()) return null

        // Clear / explicit requests never ask (spec §8).
        if (isClearRequest(text)) return null

        // New project / app with no platform specified (spec Test 2).
        // Already answered the platform question? Then it's clear — continue.
        if (isProjectCreation(text) && !hasPlatform(text) && !alreadyAsked.contains("project_platform")) {
            return QuestionPlan(
                question = AgentQuestion.SingleChoice(
                    id = "project_platform",
                    question = "What kind of project should I create?",
                    options = listOf(
                        QuestionOption("android", "Android app", "Kotlin + Jetpack Compose", recommended = true),
                        QuestionOption("ios", "iOS app", "Swift / SwiftUI"),
                        QuestionOption("web", "Web app", "React / HTML / CSS"),
                        QuestionOption("python", "Python project", "Scripts, tooling, backend"),
                        QuestionOption("cli", "CLI tool", "Terminal-based utility")
                    )
                ),
                decision = Decision.ASK_CHOICE,
                priority = priority(AgentQuestion.Type.SINGLE_CHOICE)
            )
        }

        // "Set up a new AI project" — multi-step flow (spec §11).
        if (isAiProjectSetup(text)) {
            return nextSequenceQuestion(text, answers, alreadyAsked)
        }

        return null
    }

    /**
     * Multi-step question sequence (spec §11, §23): returns the NEXT
     * unanswered question for a flow, based on previously collected answers.
     */
    fun nextSequenceQuestion(
        request: String,
        answers: Map<String, QuestionAnswer>,
        alreadyAsked: Set<String> = emptySet()
    ): QuestionPlan? {
        val text = request.lowercase()

        // Step 1: platform.
        if (!alreadyAsked.contains("proj_platform") && answers["proj_platform"] == null) {
            return QuestionPlan(
                question = AgentQuestion.SingleChoice(
                    id = "proj_platform",
                    question = "Which platform is the project for?",
                    options = listOf(
                        QuestionOption("android", "Android", "Kotlin, runs on phones"),
                        QuestionOption("web", "Web", "Browser-based"),
                        QuestionOption("python", "Python", "Backend / tooling"),
                        QuestionOption("cli", "CLI", "Terminal tool")
                    )
                ),
                decision = Decision.ASK_CHOICE,
                priority = priority(AgentQuestion.Type.SINGLE_CHOICE)
            )
        }

        // Step 2: language depends on platform (adaptive, spec §10).
        if (answers["proj_platform"] != null && !alreadyAsked.contains("proj_language")) {
            val platform = (answers["proj_platform"] as? QuestionAnswer.SingleChoiceAnswer)?.optionId
            val options = when (platform) {
                "android" -> listOf(
                    QuestionOption("kotlin", "Kotlin", "Modern, recommended by Google", recommended = true),
                    QuestionOption("java", "Java", "Classic Android language")
                )
                "web" -> listOf(
                    QuestionOption("typescript", "TypeScript", "Typed JavaScript", recommended = true),
                    QuestionOption("javascript", "JavaScript", "Plain JS")
                )
                "python" -> listOf(QuestionOption("python", "Python", "Default for Python projects", recommended = true))
                else -> listOf(
                    QuestionOption("python", "Python", recommended = true),
                    QuestionOption("rust", "Rust"),
                    QuestionOption("go", "Go")
                )
            }
            return QuestionPlan(
                question = AgentQuestion.SingleChoice(
                    id = "proj_language",
                    question = "Which language should the project use?",
                    options = options
                ),
                decision = Decision.ASK_CHOICE,
                priority = priority(AgentQuestion.Type.SINGLE_CHOICE)
            )
        }

        return null
    }

    /**
     * Decide whether the agent may continue on its own.
     * [defaultsAvailable] — a reasonable default exists → continue.
     * [impactLow] — the choice is low-impact or reversible → continue.
     */
    fun shouldContinue(
        decision: Decision,
        defaultsAvailable: Boolean = true,
        impactLow: Boolean = false
    ): Boolean {
        if (decision == Decision.CONTINUE) return true
        if (impactLow) return true
        if (decision == Decision.ASK_CHOICE && defaultsAvailable) return true
        return false
    }

    /**
     * Bridge to the safety layer (spec §18): map whether a destructive
     * operation needs explicit confirmation before execution.
     */
    fun confirmationDecision(isDestructive: Boolean, alreadyApproved: Boolean): Decision =
        when {
            alreadyApproved -> Decision.CONTINUE
            isDestructive -> Decision.REQUEST_CONFIRMATION
            else -> Decision.CONTINUE
        }

    // ── Heuristics ────────────────────────────────────────────────────────

    /** Requests that never need a clarifying question. */
    private fun isClearRequest(text: String): Boolean {
        val lower = text.lowercase()
        // Simple arithmetic (spec §8, Test 1).
        if (Regex("\\d+\\s*[+\\-*/%x×]\\s*\\d+").containsMatchIn(lower)) return true
        // Explicit run/execute commands (spec Test 3).
        if (lower.startsWith("run ") || lower.startsWith("execute ")
            || lower.startsWith("$ ") || lower.contains("./gradlew")
            || lower.startsWith("git ")) return true
        // Direct questions / explanations — the model answers them.
        val directPrefixes = listOf(
            "what is", "what are", "who is", "why", "how do i", "how does",
            "explain", "translate", "summarize", "calculate", "define",
            "hi", "hello", "hey", "thanks", "thank you"
        )
        if (directPrefixes.any { lower.startsWith(it) }) return true
        // Simple, single-token requests.
        if (lower.split(Regex("\\s+")).size <= 3) return true
        return false
    }

    /** "Create/build/make a new project/app" patterns. */
    private fun isProjectCreation(text: String): Boolean {
        val lower = text.lowercase()
        val createPatterns = listOf(
            "create a new project", "create an app", "create a project",
            "create me an app", "create me a project",
            "build an app", "build a mobile app", "build an application",
            "build me an app", "build me a mobile app", "build me an application",
            "make an app", "make a mobile app", "make me an app", "make me a mobile app",
            "start a new project", "start a new app", "create a new app"
        )
        return createPatterns.any { lower.contains(it) }
    }

    /** Platform hints that make the request unambiguous. */
    private fun hasPlatform(text: String): Boolean {
        val lower = text.lowercase()
        val platforms = listOf(
            "android", "ios", "iphone", "web", "website", "python", "kotlin",
            "java", "flutter", "react", "node", "javascript", "typescript",
            "cli", "terminal", "desktop", "windows", "linux", "macos"
        )
        return platforms.any { lower.contains(it) }
    }

    /** "Set up a new AI project" — the multi-step flow trigger. */
    private fun isAiProjectSetup(text: String): Boolean {
        val lower = text.lowercase()
        return (lower.contains("ai project") || lower.contains("new project"))
            && (lower.contains("set up") || lower.contains("setup") || lower.contains("create"))
            && !isClearRequest(text)
    }
}
