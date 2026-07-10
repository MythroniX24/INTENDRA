package com.interndra.service

/**
 * TerminalConfig — centralizes all terminal execution parameters.
 *
 * ## Why this exists
 * Previously each backend (SmartShell, ShizukuShell, ShizukuManager, TermuxBridge)
 * had its own hardcoded defaults that sometimes conflicted (30s vs 180s vs 300s).
 * This single config ensures consistent behavior across ALL execution layers.
 */
object TerminalConfig {

    // ── Output limits ───────────────────────────────────────────────────

    /** Maximum bytes of stdout+stderr to buffer before truncating. */
    const val MAX_OUTPUT_BYTES = 512 * 1024  // 512 KB

    // ── Timeouts (milliseconds) ─────────────────────────────────────────

    /** Default timeout for user-initiated shell commands. */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    /** Timeout for package installation commands. */
    const val INSTALL_TIMEOUT_MS = 120_000L

    /** Timeout for AI agent commands (longer, the AI may run multi-step). */
    const val AGENT_TIMEOUT_MS = 300_000L

    /** Timeout for recovery commands (short — should be quick). */
    const val RECOVERY_TIMEOUT_MS = 15_000L

    /** Timeout for connection tests / pings. */
    const val CONNECTION_TEST_TIMEOUT_MS = 5_000L

    // ── Session limits ──────────────────────────────────────────────────

    /** Max history entries per session before trimming oldest. */
    const val MAX_HISTORY_ENTRIES = 200

    /** Max output lines retained per session for UI display. */
    const val MAX_OUTPUT_LINES = 1000

    /** Max line length for a single command in history (prevents OOM). */
    const val MAX_STDOUT_SNIPPET_CHARS = 2000

    /** Max stderr snippet length in history. */
    const val MAX_STDERR_SNIPPET_CHARS = 1000

    // ── Auto-completion ─────────────────────────────────────────────────

    /** Max auto-completion suggestions to return. */
    const val MAX_COMPLETIONS = 10

    // ── Auto-save ───────────────────────────────────────────────────────

    /** Delay before auto-saving sessions to disk (debounce). */
    const val AUTO_SAVE_DELAY_MS = 5_000L
}

/**
 * Unified shell execution result — the single data class returned by ALL
 * execution backends (SmartShell, ShizukuManager, TermuxBridge, ShizukuShell).
 *
 * Previously there were 3 different result types with slightly different fields:
 * - TermuxBridge.TermuxResult (stdout, stderr, exitCode, isSuccess)
 * - ShizukuManager.ShizukuShellResult (stdout, stderr, exitCode, isSuccess)
 * - ShizukuShell.ShellResult (stdout, stderr, exitCode, isSuccess)
 *
 * Now ALL backends return ShellExecutionResult, eliminating fragile manual
 * conversion code in TerminalAgent.
 */
data class ShellExecutionResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int,
    val isSuccess: Boolean = exitCode == 0,
    /** Which backend actually executed the command. */
    val backend: ExecutionBackend = ExecutionBackend.UNKNOWN,
    /** Duration in milliseconds from command start to finish. */
    val durationMs: Long = 0L
)

/**
 * Identifies which execution backend was used for a command.
 * Useful for debugging and for the "backend indicator" in terminal output.
 */
enum class ExecutionBackend(val displayName: String) {
    SHIZUKU_ROOT("🛡️ Shizuku Root"),
    SHIZUKU_ADB("🔑 Shizuku ADB"),
    TERMUX("📦 Termux"),
    SHELL_EXECUTOR("⚙️ ShellExecutor"),
    UNKNOWN("❓ Unknown")
}
