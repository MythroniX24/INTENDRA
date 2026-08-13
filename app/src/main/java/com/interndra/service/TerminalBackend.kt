package com.interndra.service

import com.interndra.agent.TerminalAgent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * TerminalBackend — the abstraction the AI Agent and terminal UI talk to.
 *
 * The embedded Linux runtime is intentionally replaceable: today INTENDRA
 * ships with an embedded Termux/proot Linux environment, but tomorrow the
 * backend could be a full virtual machine or a cloud sandbox — the Agent and
 * Chat UI never change, they only speak to this interface.
 *
 * Implementations:
 * - [EmbeddedLinuxBackend] — persistent sessions over the embedded Termux /
 *   proot runtime (via [TerminalAgent]).
 * - [AndroidShellBackend] — sandboxed one-shot `sh -c` execution (no Termux,
 *   no persistent state) — a safe fallback when the Linux env is unavailable.
 */
interface TerminalBackend {

    /** Human-readable backend name shown in the UI / status bar. */
    val displayName: String

    /** Whether this backend can execute commands right now. */
    fun isAvailable(): Boolean

    /**
     * Start (or reuse) a persistent session. Working directory is resolved
     * inside the backend's own filesystem namespace.
     */
    suspend fun startSession(sessionId: String, workingDirectory: String): TerminalSessionHandle

    /**
     * Execute a command in the given session. Returns a structured result —
     * the Agent never receives raw Android process APIs.
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long = TerminalConfig.AGENT_TIMEOUT_MS
    ): TerminalResult

    /** Write raw input (stdin) to an interactive process, where supported. */
    suspend fun writeInput(sessionId: String, input: String): Boolean

    /** Stop the currently running process in the session (Ctrl+C semantics). */
    suspend fun stopProcess(sessionId: String): Boolean

    /** Close a session and release all its resources. */
    suspend fun closeSession(sessionId: String)
}

/** Identity of a started terminal session. */
data class TerminalSessionHandle(
    val id: String,
    val workdir: String
)

/** Structured result of one command execution. */
data class TerminalResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val durationMs: Long,
    val success: Boolean
)

/**
 * EmbeddedLinuxBackend — the primary backend. Routes every call through
 * [TerminalAgent], which uses the embedded Termux/proot Linux runtime with
 * persistent sessions (working directories, env vars and shell state survive
 * across commands).
 */
class EmbeddedLinuxBackend(
    private val terminalAgent: TerminalAgent
) : TerminalBackend {

    override val displayName: String get() = "🐧 Embedded Linux"

    override fun isAvailable(): Boolean = true

    override suspend fun startSession(sessionId: String, workingDirectory: String): TerminalSessionHandle =
        withContext(Dispatchers.IO) {
            terminalAgent.createSession(sessionId, workingDirectory)
            TerminalSessionHandle(sessionId, terminalAgent.getWorkdir(sessionId))
        }

    override suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long
    ): TerminalResult = withContext(Dispatchers.IO) {
        val result = terminalAgent.execute(sessionId, command, timeoutMs)
        TerminalResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = result.durationMs,
            success = result.isSuccess
        )
    }

    override suspend fun writeInput(sessionId: String, input: String): Boolean =
        withContext(Dispatchers.IO) {
            // Ctrl+C / Ctrl+D get dedicated handling; anything else is sent raw.
            when (input) {
                "\u0003" -> terminalAgent.sendControlChar(sessionId, '\u0003')
                "\u0004" -> terminalAgent.sendControlChar(sessionId, '\u0004')
                "\u001A" -> terminalAgent.sendControlChar(sessionId, '\u001A')
                "\u000C" -> terminalAgent.sendControlChar(sessionId, '\u000C')
                else -> input.toCharArray().fold(true) { acc, ch ->
                    terminalAgent.sendControlChar(sessionId, ch) && acc
                }
            }
        }

    override suspend fun stopProcess(sessionId: String): Boolean =
        withContext(Dispatchers.IO) {
            terminalAgent.sendControlChar(sessionId, '\u0003')
        }

    override suspend fun closeSession(sessionId: String) =
        withContext(Dispatchers.IO) {
            terminalAgent.removeSession(sessionId)
        }
}

/**
 * AndroidShellBackend — safe sandboxed fallback. Executes one-shot commands
 * via [ShellExecutor] (app-private shell, no persistent state). Used when the
 * embedded Linux environment is not yet installed.
 */
class AndroidShellBackend : TerminalBackend {

    override val displayName: String get() = "⚙️ Android Shell"

    override fun isAvailable(): Boolean = true

    override suspend fun startSession(sessionId: String, workingDirectory: String): TerminalSessionHandle =
        TerminalSessionHandle(sessionId, workingDirectory)

    override suspend fun execute(
        sessionId: String,
        command: String,
        timeoutMs: Long
    ): TerminalResult = withContext(Dispatchers.IO) {
        val result = ShellExecutor.runAsync(command, timeoutMs)
        TerminalResult(
            exitCode = result.exitCode,
            stdout = result.stdout,
            stderr = result.stderr,
            durationMs = result.durationMs,
            success = result.isSuccess
        )
    }

    override suspend fun writeInput(sessionId: String, input: String): Boolean = false

    override suspend fun stopProcess(sessionId: String): Boolean = false

    override suspend fun closeSession(sessionId: String) { /* one-shot backend — nothing to close */ }
}
