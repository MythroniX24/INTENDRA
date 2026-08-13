package com.interndra.agent

/**
 * ComplexityClassifier — decides how much planning/effort a request deserves.
 *
 * The agent must NOT deeply plan every request (that wastes tokens, latency
 * and battery). Requests are classified into three buckets:
 *
 * - [SIMPLE]:   brief analysis → answer. No visible planning, no tools.
 *               (arithmetic, greetings, factual Q, translation, short chat)
 * - [MODERATE]: short plan → maybe one tool → answer.
 *               (explain code, compare libraries, find a bug's cause)
 * - [COMPLEX]:  full plan → execute → inspect → iterate → verify → answer.
 *               (fix my project, build a feature, research, multi-file work)
 *
 * Pure and deterministic so it can be unit-tested without Android.
 */
object ComplexityClassifier {

    enum class Complexity(val label: String) {
        SIMPLE("Simple"),
        MODERATE("Moderate"),
        COMPLEX("Complex")
    }

    // Multi-word phrases / verbs that imply significant work.
    private val COMPLEX_PHRASES = listOf(
        "fix the build", "fix my project", "build a", "build an", "create a",
        "create an", "implement", "refactor", "migrate", "upgrade", "research",
        "investigate", "debug", "troubleshoot", "set up", "configure",
        "install and", "write a script", "write a program", "develop",
        "design a", "add a feature", "add feature", "integrate", "automate",
        "make a", "make an", "optimize", "optimise", "analyze the project",
        "analyze project", "why is", "why does", "what is wrong", "what went wrong",
        "compare and", "difference between", "how do i", "how to build",
        "help me build", "help me fix", "test the",
        "make sure", "ensure that", "check if", "verify that", "then verify",
        "step by step", "multi", "multiple files", "change the code", "update the code"
    )

    // Single-word markers for complex work.
    private val COMPLEX_WORDS = setOf(
        "fix", "build", "create", "implement", "refactor", "migrate",
        "research", "debug", "investigate", "develop", "integrate", "automate",
        "optimize", "optimise", "upgrade", "install", "configure", "deploy",
        "verify", "troubleshoot"
    )

    // Moderate work: single tool, explanation, or a runnable command.
    private val MODERATE_PHRASES = listOf(
        "explain", "explain this", "compare", "analyze", "summarize", "summarise",
        "run this", "run the", "show me", "find the cause", "how does this",
        "what caused", "what causes", "is there a way",
        "tell me about", "find", "search for", "look up", "convert", "calculate"
    )

    /**
     * Classify the user request.
     *
     * @param input the raw (trimmed) user message
     * @return the [Complexity] bucket that decides how deeply the agent plans
     */
    fun classify(input: String): Complexity {
        val text = input.trim()
        if (text.isEmpty()) return Complexity.SIMPLE

        val lower = text.lowercase()

        // ── COMPLEX signals first (they override everything) ─────────────
        if (COMPLEX_PHRASES.any { lower.contains(it) }) return Complexity.COMPLEX

        // Multiple sentences + action verbs ⇒ likely multi-step work.
        val sentenceCount = lower.count { it == '.' || it == '?' || it == '!' }
        if (sentenceCount >= 2) {
            val words = lower.split(Regex("\\s+"))
            if (words.any { it in COMPLEX_WORDS }) return Complexity.COMPLEX
        }

        // Long requests with several steps ("then", "and then", "after that").
        if (lower.contains(" and then ") || lower.contains(" then ")
            || lower.contains(" after that ") || lower.contains(" first ")
        ) {
            if (lower.split(Regex("\\s+")).size > 12) return Complexity.COMPLEX
        }

        // Single strong action verb in a substantial request.
        if (lower.split(Regex("\\s+")).size >= 5 && COMPLEX_WORDS.any { lower.contains(it) }) {
            return Complexity.COMPLEX
        }

        // ── MODERATE signals ─────────────────────────────────────────────
        if (MODERATE_PHRASES.any { lower.contains(it) }) return Complexity.MODERATE

        // ── Default: SIMPLE (short chat, arithmetic, factual, casual) ────
        return Complexity.SIMPLE
    }
}
