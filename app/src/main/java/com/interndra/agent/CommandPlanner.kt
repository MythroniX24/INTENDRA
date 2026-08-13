package com.interndra.agent

import com.interndra.service.TerminalConfig

/**
 * CommandPlanner — pure, unit-testable planning helpers for terminal tools.
 *
 * The Agent must not run commands blindly (spec §4, §11, §24):
 * - [inferTimeout] picks an appropriate timeout for the command class
 *   (short: ls/pwd/grep → fast; medium: pkg/pip/npm install → slower;
 *   long: gradle/tests/builds → longest). Enforces a global ceiling.
 * - [truncateOutput] caps stdout/stderr so huge logs never destroy the
 *   model's context window; it always preserves the *tail* (where errors
 *   and failures appear).
 * - [extractErrorSummary] pulls the important error lines from stderr so
 *   the Agent can diagnose without seeing the full dump.
 */
object CommandPlanner {

    // Timeout classes (all enforced against TerminalConfig.AGENT_TIMEOUT_MS).
    const val SHORT_TIMEOUT_MS = 15_000L
    const val MEDIUM_TIMEOUT_MS = TerminalConfig.INSTALL_TIMEOUT_MS   // 120s
    const val LONG_TIMEOUT_MS = TerminalConfig.AGENT_TIMEOUT_MS      // 300s

    /** Commands that finish in milliseconds (listing, status, tiny pipes). */
    private val SHORT_PREFIXES = listOf(
        "ls", "pwd", "whoami", "echo", "date", "uname", "id", "env", "true", "false",
        "cat ", "head ", "tail ", "grep ", "wc ", "which ", "type ",
        "git status", "git diff", "git branch", "git log ", "git remote",
        "git config", "history"
    )

    /** Commands that download/install and need a generous window. */
    private val MEDIUM_PREFIXES = listOf(
        "pkg install", "pkg update", "pkg upgrade", "apt install", "apt update",
        "apt-get install", "pip install", "pip3 install", "npm install", "yarn add",
        "git clone", "curl ", "wget ", "python -m pip", "python3 -m pip"
    )

    /** Commands that compile/test/build — the slowest class. */
    private val LONG_PREFIXES = listOf(
        "gradle", "./gradlew", "gradlew", "mvn", "make", "cargo build", "cargo test",
        "npm run build", "npm test", "npm run test", "yarn build", "yarn test",
        "python train", "pytest", "go build", "go test", "flutter build", "flutter test",
        "compile", "test", "build"
    )

    /**
     * Infer an appropriate timeout for a command.
     *
     * @param command the raw command line
     * @param defaultMs fallback when nothing matches
     * @return a timeout between [SHORT_TIMEOUT_MS] and [LONG_TIMEOUT_MS]
     */
    fun inferTimeout(command: String, defaultMs: Long = TerminalConfig.AGENT_TIMEOUT_MS): Long {
        val trimmed = command.trim().lowercase()
        if (trimmed.isBlank()) return defaultMs

        // Long class wins first (build/test commands are the slowest).
        if (LONG_PREFIXES.any { trimmed.startsWith(it) || trimmed.contains(" $it ") }) return LONG_TIMEOUT_MS
        if (MEDIUM_PREFIXES.any { trimmed.startsWith(it) }) return MEDIUM_TIMEOUT_MS
        if (SHORT_PREFIXES.any { trimmed.startsWith(it) }) return SHORT_TIMEOUT_MS

        // Multi-part / compound commands get the medium window.
        if (trimmed.contains(" && ") || trimmed.contains(" ; ") || trimmed.contains(" | ")) {
            return MEDIUM_TIMEOUT_MS
        }
        return defaultMs
    }

    /** Result of [truncateOutput] — truncated text plus truncation flags. */
    data class TruncatedOutput(
        val stdout: String,
        val stderr: String,
        val stdoutTruncated: Boolean,
        val stderrTruncated: Boolean
    )

    /**
     * Cap stdout/stderr sizes, always keeping the END of the stream (that's
     * where errors, stack traces and test failures appear).
     */
    fun truncateOutput(
        stdout: String,
        stderr: String,
        maxStdoutBytes: Int = TerminalConfig.MAX_OUTPUT_BYTES / 2,
        maxStderrBytes: Int = TerminalConfig.MAX_OUTPUT_BYTES / 4
    ): TruncatedOutput {
        val sOut = truncateKeepTail(stdout, maxStdoutBytes)
        val sErr = truncateKeepTail(stderr, maxStderrBytes)
        return TruncatedOutput(
            stdout = sOut.first,
            stderr = sErr.first,
            stdoutTruncated = sOut.second,
            stderrTruncated = sErr.second
        )
    }

    /**
     * Extract the most useful error lines from stderr for diagnosis.
     * Prefers lines containing common error markers; falls back to the tail.
     */
    fun extractErrorSummary(stderr: String, maxLines: Int = 8): String {
        if (stderr.isBlank()) return ""
        val lines = stderr.lines()
        val markers = listOf(
            "error", "failed", "failure", "exception", "unresolved", "cannot",
            "could not", "not found", "permission denied", "fatal", "e: ",
            "warning: unable", "no such"
        )
        val hits = lines.filter { line ->
            val l = line.lowercase()
            markers.any { l.contains(it) } && line.isNotBlank()
        }
        val picked = if (hits.size >= 2) hits.take(maxLines) else lines.takeLast(maxLines)
        return picked.joinToString("\n").take(2000)
    }

    // ── internals ────────────────────────────────────────────────────────

    private fun truncateKeepTail(text: String, maxBytes: Int): Pair<String, Boolean> {
        if (text.isEmpty()) return "" to false
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size <= maxBytes) return text to false
        val cut = text.takeLast(maxBytes)
        return cut to true
    }
}
