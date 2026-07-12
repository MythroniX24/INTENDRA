package com.interndra.service

import android.util.Log
import kotlinx.coroutines.*
import java.io.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * PersistentShell — a REAL, long-lived shell process with piped stdin/stdout.
 *
 * Unlike the old approach (Runtime.exec("sh -c command") per command), this:
 *  1. Spawns ONE shell process and keeps it alive
 *  2. Writes commands to stdin, reads output from stdout
 *  3. cd, env vars, aliases, and shell state ALL persist between commands
 *  4. Uses unique exit-code markers to detect command completion
 *  5. Supports Shizuku-elevated shells (UID 0/2000) for true root access
 *
 * ## Architecture
 * ```
 * PersistentShell
 *   ├── Process (sh) ← running continuously
 *   ├── stdin writer thread ← writes commands + exit markers
 *   ├── stdout reader thread ← reads output, detects completion
 *   └── stderr reader thread ← captures errors
 * ```
 *
 * ## Execution Modes
 * - **Shizuku mode**: Shell runs with elevated privileges (root/ADB UID)
 * - **Sandbox mode**: Shell runs as the app's UID (limited Android permissions)
 */
class PersistentShell(
    private val shellPath: String = DEFAULT_SHELL,
    private val initialWorkdir: String = DEFAULT_WORKDIR,
    private val envVars: Map<String, String> = DEFAULT_ENV,
    private val shizukuProvider: (() -> Process?)? = null  // for Shizuku-elevated shells
) {
    companion object {
        private const val TAG = "PersistentShell"

        /** Default shell binary. Shizuku mode can use /system/bin/sh. */
        const val DEFAULT_SHELL = "/system/bin/sh"

        /** Default working directory. Root (/) for Shizuku, /sdcard for sandbox. */
        const val DEFAULT_WORKDIR = "/"

        /** Default environment variables passed to the shell. */
        val DEFAULT_ENV = mapOf(
            "HOME" to "/",
            "PATH" to "/system/bin:/system/xbin:/sbin:/vendor/bin:/data/local/tmp:/su/bin:/su/xbin:/data/data/com.termux/files/usr/bin:/usr/bin:/bin",
            "TERM" to "xterm-256color",
            "PS1" to "\\$ ",
            "PWD" to "/",
            "SHELL" to "/system/bin/sh",
            "COLUMNS" to "120",
            "LINES" to "40",
            "LC_ALL" to "C.UTF-8"
        )

        /** Marker prefix for detecting command exit. Injected after every command. */
        private const val EXIT_MARKER_PREFIX = "__INTENDRA_EXIT__"

        /** Max time to wait for a command to complete (5 minutes). */
        private const val COMMAND_TIMEOUT_MS = 300_000L

        /** Max time to wait for shell startup. */
        private const val STARTUP_TIMEOUT_MS = 10_000L

        /** Max length of accumulated output before auto-flushing to callback. */
        private const val FLUSH_THRESHOLD_CHARS = 4096

        private val markerCounter = AtomicLong(0)
    }

    // ── Process state ──────────────────────────────────────────────────
    @Volatile private var process: Process? = null
    @Volatile private var stdinWriter: BufferedWriter? = null
    @Volatile private var stdoutReader: BufferedReader? = null
    @Volatile private var stderrReader: BufferedReader? = null
    @Volatile private var isRunning = false
    private val startLock = Any()

    // ── Pending command tracking ────────────────────────────────────────
    private val pendingCommands = ConcurrentHashMap<Long, PendingCommand>()
    private val shellOutput = StringBuilder() // accumulates all output

    data class PendingCommand(
        val id: Long,
        val command: String,
        val deferred: CompletableDeferred<ShellExecutionResult>,
        val onOutput: ((String) -> Unit)? = null,
        val startedAt: Long = System.currentTimeMillis()
    )

    // ── State queries ───────────────────────────────────────────────────
    val isAlive: Boolean get() = isRunning && try { process?.isAlive == true } catch (_: Exception) { false }
    val pid: Int? get() = try {
        // Process.pid() available API 26+; fallback for older versions
        val p = process ?: return@try null
        val method = p.javaClass.getMethod("pid")
        (method.invoke(p) as? Int)
    } catch (_: Exception) { null }

    /** Human-readable description of the execution backend. */
    val backendDescription: String
        get() = if (shizukuProvider != null) "🛡️ Shizuku PTY Shell" else "⚙️ Persistent Shell"

    /** Current working directory (queried from the shell). */
    suspend fun getWorkdir(): String = withContext(Dispatchers.IO) {
        val result = executeInternal("pwd", timeoutMs = 3000)
        result.stdout.trim().ifEmpty { initialWorkdir }
    }

    /** Change working directory (writes cd to the persistent shell). */
    suspend fun changeWorkdir(target: String): String = withContext(Dispatchers.IO) {
        val result = executeInternal("cd \"$target\" 2>/dev/null && pwd", timeoutMs = 3000)
        result.stdout.trim().ifEmpty { initialWorkdir }
    }

    // ── Lifecycle ───────────────────────────────────────────────────────

    /**
     * Start the persistent shell process.
     * Must be called before [execute].
     */
    suspend fun start(): Boolean = withContext(Dispatchers.IO) {
        synchronized(startLock) {
            if (isAlive) {
                Log.d(TAG, "Shell already running (PID ${pid})")
                return@withContext true
            }

            Log.i(TAG, "Starting persistent shell: $shellPath")
            try {
                val proc = if (shizukuProvider != null) {
                    Log.d(TAG, "Attempting Shizuku-elevated shell...")
                    shizukuProvider.invoke()
                } else {
                    Log.d(TAG, "Using sandboxed shell (Runtime.exec)")
                    null
                }

                val pb = if (proc != null) {
                    // Shizuku provided the process — use it directly
                    process = proc
                    proc
                } else {
                    // Fall back to Runtime.exec
                    val builder = ProcessBuilder(shellPath, "-i")
                    builder.directory(File(initialWorkdir))
                    builder.environment().putAll(envVars)
                    builder.redirectErrorStream(false)
                    val p = builder.start()
                    process = p
                    p
                }

                stdinWriter = process!!.outputStream.bufferedWriter()
                stdoutReader = process!!.inputStream.bufferedReader()
                stderrReader = process!!.errorStream.bufferedReader()

                isRunning = true

                // ── Start stdout reader thread ──
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
                        if (isRunning) Log.w(TAG, "stdout reader: ${e.message}")
                    }
                }, "pshell-stdout").apply { isDaemon = true }.start()

                // ── Start stderr reader thread ──
                Thread({
                    try {
                        val reader = stderrReader!!
                        val buf = CharArray(8192)
                        while (isRunning) {
                            val read = reader.read(buf)
                            if (read < 0) break
                            val text = String(buf, 0, read)
                            processStderr(text)
                        }
                    } catch (e: IOException) {
                        if (isRunning) Log.w(TAG, "stderr reader: ${e.message}")
                    }
                }, "pshell-stderr").apply { isDaemon = true }.start()

                // ── Wait for shell to be ready (write a no-op and wait for response) ──
                val ready = testShellReady()
                if (!ready) {
                    Log.e(TAG, "Shell failed to respond within ${STARTUP_TIMEOUT_MS}ms")
                    destroy()
                    return@withContext false
                }

                // ── Set initial working directory ──
                if (initialWorkdir != "/") {
                    stdinWriter?.write("cd \"$initialWorkdir\" 2>/dev/null\n")
                    stdinWriter?.flush()
                }

                Log.i(TAG, "✅ Persistent shell started (PID ${pid}, workdir=$initialWorkdir)")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Failed to start shell: ${e.message}", e)
                destroy()
                false
            }
        }
    }

    /** Test if the shell is ready by writing a quick echo and checking response. */
    private fun testShellReady(): Boolean {
        return try {
            val testMarker = "${EXIT_MARKER_PREFIX}STARTUP_${markerCounter.incrementAndGet()}"
            stdinWriter?.write("echo $testMarker\n")
            stdinWriter?.flush()

            val deadline = System.currentTimeMillis() + STARTUP_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                synchronized(shellOutput) {
                    if (shellOutput.contains(testMarker)) {
                        // Clear the marker from the output buffer
                        val idx = shellOutput.indexOf(testMarker)
                        shellOutput.delete(idx, idx + testMarker.length + 1) // +1 for newline
                        return true
                    }
                }
                Thread.sleep(50)
            }
            false
        } catch (e: Exception) {
            Log.e(TAG, "Shell ready test failed: ${e.message}")
            false
        }
    }

    /**
     * Execute a command in the persistent shell.
     * Writes the command + exit-code marker to stdin, waits for the marker in stdout.
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

        pendingCommands[id] = pending

        try {
            // ── Write command + exit marker to shell stdin ──
            synchronized(stdinWriter!!) {
                stdinWriter?.write("$command\n")
                stdinWriter?.write("echo $exitMarker:\$?\n")
                stdinWriter?.flush()
            }

            // ── Wait for the exit marker in stdout ──
            val deadline = startMs + timeoutMs
            var result: ShellExecutionResult? = null

            while (result == null && System.currentTimeMillis() < deadline) {
                synchronized(shellOutput) {
                    val idx = shellOutput.indexOf(exitMarker)
                    if (idx >= 0) {
                        // Found the marker — extract exit code
                        val markerLine = shellOutput.substring(idx)
                        val newlineIdx = markerLine.indexOf('\n')
                        val fullMarker = if (newlineIdx >= 0) markerLine.substring(0, newlineIdx) else markerLine

                        // Parse: __INTENDRA_EXIT__<id>:<exit_code>
                        val exitCodeStr = fullMarker.substringAfterLast(':')
                        val exitCode = exitCodeStr.toIntOrNull() ?: 0

                        // Collect all output BEFORE the marker
                        val output = shellOutput.substring(0, idx)

                        // Remove processed output from buffer
                        val endIdx = idx + (newlineIdx.coerceAtLeast(fullMarker.length)) + 1
                        shellOutput.delete(0, endIdx.coerceAtMost(shellOutput.length))

                        result = ShellExecutionResult(
                            stdout = output.trim(),
                            stderr = "",
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

            if (result == null) {
                // Timeout — command took too long
                pendingCommands.remove(id)
                return ShellExecutionResult(
                    "", "Command timed out after ${timeoutMs}ms", -1, false,
                    backend = ExecutionBackend.SHELL_EXECUTOR,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            pendingCommands.remove(id)
            val finalResult = result
            return finalResult
        } catch (e: Exception) {
            Log.e(TAG, "Execute failed: ${e.message}")
            pendingCommands.remove(id)
            return ShellExecutionResult("", "Error: ${e.message}", -1, false,
                backend = ExecutionBackend.SHELL_EXECUTOR,
                durationMs = System.currentTimeMillis() - startMs)
        }
    }

    /** Process stdout output, stream to pending command callbacks, detect exit markers. */
    private fun processOutput(text: String) {
        synchronized(shellOutput) {
            shellOutput.append(text)
        }

        // Stream output to all pending command callbacks
        pendingCommands.values.forEach { cmd ->
            try { cmd.onOutput?.invoke(text) } catch (_: Exception) {}
        }
    }

    /** Process stderr output, stream to pending command callbacks. */
    private fun processStderr(text: String) {
        // Prefix stderr with ANSI red and stream
        pendingCommands.values.forEach { cmd ->
            try { cmd.onOutput?.invoke("\u001b[31m$text\u001b[0m") } catch (_: Exception) {}
        }
    }

    /** Flush any remaining output to the callback. */
    @Volatile private var globalOutputCallback: ((String) -> Unit)? = null

    fun setGlobalOutputCallback(callback: ((String) -> Unit)?) {
        globalOutputCallback = callback
    }

    /** Get all accumulated shell output since last flush. */
    fun flushOutput(): String = synchronized(shellOutput) {
        val text = shellOutput.toString()
        shellOutput.setLength(0)
        text
    }

    /**
     * Destroy the shell process and clean up resources.
     */
    fun destroy() {
        synchronized(startLock) {
            if (!isRunning) return
            isRunning = false

            Log.i(TAG, "Destroying persistent shell (PID ${pid})")

            // Cancel all pending commands
            pendingCommands.values.forEach { cmd ->
                cmd.deferred.complete(ShellExecutionResult("", "Shell destroyed", -1, false,
                    backend = ExecutionBackend.SHELL_EXECUTOR))
            }
            pendingCommands.clear()

            // Write exit to stdin
            try { stdinWriter?.write("exit\n"); stdinWriter?.flush() } catch (_: Exception) {}

            // Close streams
            try { stdinWriter?.close() } catch (_: Exception) {}
            try { stdoutReader?.close() } catch (_: Exception) {}
            try { stderrReader?.close() } catch (_: Exception) {}

            // Destroy process
            try { process?.destroy() } catch (_: Exception) {}
            try { Thread.sleep(200); process?.destroyForcibly() } catch (_: Exception) {}

            stdinWriter = null
            stdoutReader = null
            stderrReader = null
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
