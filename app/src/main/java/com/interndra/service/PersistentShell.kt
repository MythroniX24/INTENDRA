package com.interndra.service

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * PersistentShell — a REAL, long-lived shell process with piped stdin/stdout.
 *
 * ## RELIABILITY REWRITE (v3):
 *  1. redirectErrorStream(true) — stderr merged into stdout, one reader thread
 *  2. No blocking startup test — shell starts fast, first command validates it
 *  3. Fixed PS1 (no double-escaping)
 *  4. Per-command output routing (only streams to the command that's running)
 *  5. Proper exit marker parsing (handles split reads)
 *  6. Auto-restart on shell death
 *  7. Shizuku-elevated shell support
 *
 * ## How it works
 * Spawns ONE `/system/bin/sh` process and keeps it alive. Commands are written
 * to stdin, output is read from stdout. An exit-code marker (`echo EXIT_MARKER:$?`)
 * is injected after each command to detect completion.
 */
class PersistentShell(
    private val shellPath: String = DEFAULT_SHELL,
    private val initialWorkdir: String = DEFAULT_WORKDIR,
    private val envVars: Map<String, String> = DEFAULT_ENV,
    private val shizukuProvider: (() -> Process?)? = null
) {
    companion object {
        private const val TAG = "PersistentShell"

        const val DEFAULT_SHELL = "/system/bin/sh"
        const val DEFAULT_WORKDIR = "/"

        val DEFAULT_ENV = mapOf(
            "HOME" to "/",
            "PATH" to "/system/bin:/system/xbin:/sbin:/vendor/bin:/data/local/tmp:/su/bin:/su/xbin:/data/data/com.termux/files/usr/bin:/usr/bin:/bin",
            "TERM" to "xterm-256color",
            "PS1" to "$ ",
            "PWD" to "/",
            "SHELL" to "/system/bin/sh",
            "COLUMNS" to "120",
            "LINES" to "40",
            "LC_ALL" to "C.UTF-8"
        )

        private const val EXIT_MARKER_PREFIX = "__INTENDRA_EXIT__"
        private const val COMMAND_TIMEOUT_MS = 300_000L
        private val markerCounter = AtomicLong(0)
    }

    // ── Process state ──────────────────────────────────────────────────
    @Volatile private var process: Process? = null
    @Volatile private var stdinWriter: BufferedWriter? = null
    @Volatile private var stdoutReader: BufferedReader? = null
    @Volatile private var isRunning = false
    private val startLock = Any()

    // ── Pending command tracking ────────────────────────────────────────
    private val pendingCommands = ConcurrentHashMap<Long, PendingCommand>()
    private val shellOutput = StringBuilder()

    // Track which command ID is currently the "active" one receiving streaming output
    @Volatile private var activePendingId: Long = -1L

    data class PendingCommand(
        val id: Long,
        val command: String,
        val deferred: CompletableDeferred<ShellExecutionResult>,
        val onOutput: ((String) -> Unit)? = null,
        val startedAt: Long = System.currentTimeMillis()
    )

    // ── State queries ───────────────────────────────────────────────────
    val isAlive: Boolean get() = isRunning && try { process?.isAlive == true } catch (_: Exception) { false }
    val pid: Int? get() {
        val p = process ?: return null
        return try {
            val method = p.javaClass.getMethod("pid")
            method.invoke(p) as? Int
        } catch (_: Exception) { null }
    }

    val backendDescription: String
        get() = if (shizukuProvider != null) "🛡️ Shizuku PTY Shell" else "⚙️ Persistent Shell"

    suspend fun getWorkdir(): String = withContext(Dispatchers.IO) {
        val result = executeInternal("pwd", timeoutMs = 3000)
        result.stdout.trim().ifEmpty { initialWorkdir }
    }

    suspend fun changeWorkdir(target: String): String = withContext(Dispatchers.IO) {
        val result = executeInternal("cd \"$target\" 2>&1 && pwd", timeoutMs = 3000)
        result.stdout.trim().ifEmpty { initialWorkdir }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Start the persistent shell process. Returns quickly — validates
     * the shell is alive but doesn't block waiting for a response.
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        synchronized(startLock) {
            if (isAlive) {
                Log.d(TAG, "Shell already running (PID ${pid})")
                return@withContext true
            }

            Log.i(TAG, "Starting persistent shell: $shellPath")
            try {
                val proc = shizukuProvider?.invoke()

                val theProcess = if (proc != null) {
                    Log.d(TAG, "Using Shizuku-elevated shell process")
                    process = proc
                    proc
                } else {
                    Log.d(TAG, "Using sandboxed shell (Runtime.exec)")
                    // Use Runtime.exec for wider Android compatibility
                    val pb = ProcessBuilder(shellPath)
                    pb.directory(File(initialWorkdir))
                    pb.environment().putAll(envVars)
                    pb.redirectErrorStream(true) // Merge stderr → stdout for single reader
                    val p = pb.start()
                    process = p
                    p
                }

                stdinWriter = theProcess.outputStream.bufferedWriter()
                stdoutReader = theProcess.inputStream.bufferedReader()
                isRunning = true

                // ── Start stdout reader thread (handles merged stdout+stderr) ──
                Thread({
                    try {
                        val reader = stdoutReader!!
                        val buf = CharArray(8192)
                        while (isRunning) {
                            val read = reader.read(buf)
                            if (read < 0) break
                            val text = String(buf, 0, read)
                            processOutput(text)
                        }
                    } catch (e: IOException) {
                        if (isRunning) Log.w(TAG, "stdout reader ended: ${e.message}")
                    } finally {
                        // Shell process died — mark as not running
                        if (isRunning) {
                            Log.w(TAG, "Shell process exited unexpectedly")
                            isRunning = false
                            // Fail all pending commands
                            pendingCommands.values.forEach { cmd ->
                                cmd.deferred.complete(
                                    ShellExecutionResult("", "Shell terminated", -1, false,
                                        backend = ExecutionBackend.SHELL_EXECUTOR))
                            }
                            pendingCommands.clear()
                        }
                    }
                }, "pshell-stdout").apply { isDaemon = true }.start()

                // Brief wait to see if shell started successfully
                Thread.sleep(200)
                val alive = try { theProcess.isAlive } catch (_: Exception) { false }

                if (!alive) {
                    Log.e(TAG, "Shell process died immediately after start")
                    destroy()
                    return@withContext false
                }

                // Set initial working directory
                if (initialWorkdir != "/") {
                    try {
                        stdinWriter?.write("cd \"$initialWorkdir\" 2>/dev/null\n")
                        stdinWriter?.flush()
                    } catch (_: Exception) {}
                }

                Log.i(TAG, "✅ Persistent shell started (PID ${pid}, workdir=$initialWorkdir, alive=$alive)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start shell: ${e.message}", e)
                destroy()
                false
            }
        }
    }

    /**
     * Execute a command in the persistent shell.
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = COMMAND_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val trimmed = command.trim()
        if (trimmed.isEmpty()) {
            return@withContext ShellExecutionResult("", "", 0, true,
                backend = ExecutionBackend.SHELL_EXECUTOR)
        }

        if (!isAlive) {
            Log.w(TAG, "Shell not running, attempting restart...")
            val restarted = start()
            if (!restarted) {
                return@withContext ShellExecutionResult("", "Shell is not running", -1, false,
                    backend = ExecutionBackend.SHELL_EXECUTOR)
            }
        }

        executeInternal(trimmed, timeoutMs, onOutput)
    }

    private fun executeInternal(
        command: String,
        timeoutMs: Long,
        onOutput: ((String) -> Unit)? = null
    ): ShellExecutionResult {
        val startMs = System.currentTimeMillis()
        val id = markerCounter.incrementAndGet()
        val exitMarker = "${EXIT_MARKER_PREFIX}${id}"
        val deferred = CompletableDeferred<ShellExecutionResult>()

        val pending = PendingCommand(
            id = id,
            command = command,
            deferred = deferred,
            onOutput = onOutput,
            startedAt = startMs
        )

        // Set as the active command for streaming output
        activePendingId = id
        pendingCommands[id] = pending

        try {
            // Clear accumulated output between commands
            synchronized(shellOutput) {
                if (shellOutput.isNotEmpty()) {
                    // Flush any remaining output to the callback before clearing
                    val leftover = shellOutput.toString()
                    shellOutput.setLength(0)
                    onOutput?.invoke(leftover)
                }
            }

            // ── Write command + exit marker to shell stdin ──
            val writer = stdinWriter ?: run {
                pendingCommands.remove(id)
                activePendingId = -1L
                return ShellExecutionResult("", "Shell stdin not available", -1, false,
                    backend = ExecutionBackend.SHELL_EXECUTOR,
                    durationMs = System.currentTimeMillis() - startMs)
            }

            synchronized(writer) {
                writer.write("$command\n")
                writer.write("echo ${exitMarker}:\$?\n")
                writer.flush()
            }

            // ── Wait for the exit marker in stdout ──
            val deadline = startMs + timeoutMs
            var result: ShellExecutionResult? = null

            while (result == null && System.currentTimeMillis() < deadline) {
                synchronized(shellOutput) {
                    val idx = shellOutput.indexOf(exitMarker)
                    if (idx >= 0) {
                        // Extract output before the marker
                        val output = shellOutput.substring(0, idx)

                        // Parse exit code: "EXIT_MARKER:0\n" or similar
                        val afterMarker = shellOutput.substring(idx)
                        val newlineIdx = afterMarker.indexOf('\n')
                        val markerLine = if (newlineIdx >= 0) afterMarker.substring(0, newlineIdx) else afterMarker
                        val exitCodeStr = markerLine.substringAfterLast(':')
                        val exitCode = exitCodeStr.toIntOrNull() ?: 0

                        // Remove processed content from buffer
                        val endIdx = if (newlineIdx >= 0) idx + newlineIdx + 1 else shellOutput.length
                        shellOutput.delete(0, endIdx.coerceAtMost(shellOutput.length))

                        result = ShellExecutionResult(
                            stdout = output.trim(),
                            stderr = "", // stderr is merged into stdout via redirectErrorStream
                            exitCode = exitCode,
                            isSuccess = exitCode == 0,
                            backend = if (shizukuProvider != null) ExecutionBackend.SHIZUKU_ADB
                                      else ExecutionBackend.SHELL_EXECUTOR,
                            durationMs = System.currentTimeMillis() - startMs
                        )
                    }
                }
                if (result == null) Thread.sleep(10)
            }

            pendingCommands.remove(id)
            activePendingId = -1L

            if (result == null) {
                return ShellExecutionResult(
                    "", "Command timed out after ${timeoutMs}ms", -1, false,
                    backend = ExecutionBackend.SHELL_EXECUTOR,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            return result!!
        } catch (e: Exception) {
            Log.e(TAG, "Execute failed: ${e.message}")
            pendingCommands.remove(id)
            activePendingId = -1L
            return ShellExecutionResult("", "Error: ${e.message}", -1, false,
                backend = ExecutionBackend.SHELL_EXECUTOR,
                durationMs = System.currentTimeMillis() - startMs)
        }
    }

    /**
     * Process stdout output. Appends to the shared buffer AND streams to
     * the currently active command's callback ONLY.
     */
    private fun processOutput(text: String) {
        synchronized(shellOutput) {
            shellOutput.append(text)
        }

        // Only stream to the ACTIVE pending command, not ALL commands
        val activeId = activePendingId
        if (activeId >= 0) {
            pendingCommands[activeId]?.onOutput?.invoke(text)
        }
    }

    /** Get all accumulated shell output since last flush. */
    fun flushOutput(): String = synchronized(shellOutput) {
        val text = shellOutput.toString()
        shellOutput.setLength(0)
        text
    }

    /**
     * Write raw text directly to the shell's stdin without appending a newline
     * or an exit marker. Useful for sending control characters (e.g. Ctrl+C).
     * Returns true if the write succeeded.
     */
    fun sendRaw(text: String): Boolean {
        val writer = stdinWriter ?: return false
        return try {
            synchronized(writer) {
                writer.write(text)
                writer.flush()
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "sendRaw failed: ${e.message}")
            false
        }
    }

    /** Destroy the shell process and clean up resources. */
    fun destroy() {
        synchronized(startLock) {
            if (!isRunning) return
            isRunning = false

            Log.i(TAG, "Destroying persistent shell (PID ${pid})")

            // Fail all pending commands
            pendingCommands.values.forEach { cmd ->
                cmd.deferred.complete(ShellExecutionResult("", "Shell destroyed", -1, false,
                    backend = ExecutionBackend.SHELL_EXECUTOR))
            }
            pendingCommands.clear()
            activePendingId = -1L

            // Write exit to stdin
            try { stdinWriter?.write("exit\n"); stdinWriter?.flush() } catch (_: Exception) {}

            // Close streams
            try { stdinWriter?.close() } catch (_: Exception) {}
            try { stdoutReader?.close() } catch (_: Exception) {}

            // Destroy process
            try { process?.destroy() } catch (_: Exception) {}
            try { Thread.sleep(200); process?.destroyForcibly() } catch (_: Exception) {}

            stdinWriter = null
            stdoutReader = null
            process = null

            synchronized(shellOutput) { shellOutput.setLength(0) }
        }
    }

    /** Restart the shell (destroy + start). */
    suspend fun restart(): Boolean = withContext(Dispatchers.IO) {
        destroy()
        start()
    }
}
