package com.interndra.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ShellExecutor — stateless, coroutine-safe shell command execution.
 *
 * REPLACES SmartShell (now deleted). Key improvements:
 *  1. **No singleton** — pure utility functions, no instance state
 *  2. **Coroutine-first** — all execution is via suspend functions
 *  3. **Proper timeout enforcement** — process.destroyForcibly() on timeout
 *  4. **Streaming support** with line callbacks
 *  5. **Background process spawning** for long-running commands
 *
 * Usage:
 * ```kotlin
 * val result = ShellExecutor.runAsync("ls -la")
 * ShellExecutor.runStreaming("ping 8.8.8.8") { line -> println(line) }
 * val bg = ShellExecutor.spawnBackground("node server.js") { line -> log(line) }
 * ```
 */
object ShellExecutor {

    private const val TAG = "ShellExecutor"

    // ── Path normalization (moved from SmartShell) ─────────────────────

    private val RE_DOWNLOADS = Regex("~/Downloads?\\b")
    private val RE_PICTURES = Regex("~/Pictures\\b")
    private val RE_DCIM = Regex("~/DCIM\\b")
    private val RE_DOCUMENTS = Regex("~/Documents\\b")
    private val RE_MUSIC = Regex("~/Music\\b")
    private val RE_HOME_SLASH = Regex("~/")
    private val RE_HOME_BARE = Regex("(?<![\\w/])~(?![\\w/])")

    /**
     * Normalize ~/ paths to /storage/emulated/0/ for Android.
     */
    fun normalizePaths(cmd: String): String {
        var c = cmd
        c = c.replace(RE_DOWNLOADS, "/storage/emulated/0/Download")
        c = c.replace(RE_PICTURES, "/storage/emulated/0/Pictures")
        c = c.replace(RE_DCIM, "/storage/emulated/0/DCIM")
        c = c.replace(RE_DOCUMENTS, "/storage/emulated/0/Documents")
        c = c.replace(RE_MUSIC, "/storage/emulated/0/Music")
        c = c.replace(RE_HOME_SLASH, "/storage/emulated/0/")
        c = c.replace(RE_HOME_BARE, "/storage/emulated/0")
        return c
    }

    // ── Synchronous execution (for non-coroutine callers) ──────────────

    /**
     * Execute a shell command synchronously. Returns [ShellExecutionResult].
     * Non-background threads: use [runAsync] instead.
     */
    fun run(
        cmd: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult = executeInternal(cmd, timeoutMs)

    /**
     * Execute and return plain stdout (for backward compatibility with
     * AutomationWorker, NotificationListener, etc.).
     * Returns "(no output)" if stdout is blank, stderr if only stderr present.
     */
    @Suppress("unused")
    fun runSimple(cmd: String): String {
        val result = executeInternal(cmd, TerminalConfig.DEFAULT_TIMEOUT_MS)
        return when {
            result.isSuccess && result.stdout.isNotBlank() -> result.stdout
            result.stderr.isNotBlank() -> result.stderr
            else -> result.stdout.ifEmpty { "(no output)" }
        }
    }

    // ── Suspend execution (coroutine-safe) ────────────────────────────

    /**
     * Execute a command on [Dispatchers.IO]. Returns [ShellExecutionResult].
     */
    suspend fun runAsync(
        cmd: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        executeInternal(cmd, timeoutMs)
    }

    /**
     * Execute with line-by-line streaming callback. Suspend-friendly.
     */
    suspend fun runStreaming(
        cmd: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS,
        onOutput: (String) -> Unit
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        executeStreamingInternal(cmd, timeoutMs, onOutput)
    }

    // ── Background process spawning ───────────────────────────────────

    /**
     * Represents a running background process spawned via [spawnBackground].
     */
    class BackgroundProcess(
        val process: java.lang.Process,
        val command: String,
        val startedAt: Long = System.currentTimeMillis(),
        private val stdoutThread: Thread,
        private val stderrThread: Thread
    ) {
        @Volatile var isRunning: Boolean = true; private set
        @Volatile var exitCode: Int? = null; private set

        fun cancel() { isRunning = false; runCatching { process.destroyForcibly() } }

        fun waitFor(timeoutMs: Long): Boolean {
            val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (done) {
                exitCode = process.exitValue(); isRunning = false
                runCatching { stdoutThread.join(2000) }; runCatching { stderrThread.join(2000) }
            }
            return done
        }

        fun hasExited(): Boolean {
            if (!isRunning) return true
            return try { exitCode = process.exitValue(); isRunning = false; true }
            catch (_: IllegalThreadStateException) { false }
        }
    }

    /**
     * Spawn a command in the background WITHOUT waiting. Returns a [BackgroundProcess].
     * Use for long-running commands (servers, watchers, daemons).
     */
    fun spawnBackground(
        cmd: String,
        onOutput: ((String) -> Unit)? = null
    ): BackgroundProcess {
        val normalizedCmd = normalizePaths(cmd)
        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", normalizedCmd))

        val stdoutThread = Thread {
            try { process.inputStream.bufferedReader().use { reader ->
                reader.lines().forEach { line -> onOutput?.invoke("$line\n") }
            } } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "shell-exec-out" }

        val stderrThread = Thread {
            try { process.errorStream.bufferedReader().use { reader ->
                reader.lines().forEach { line -> onOutput?.invoke("\u001b[31m$line\n\u001b[0m") }
            } } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "shell-exec-err" }

        stdoutThread.start(); stderrThread.start()
        return BackgroundProcess(process, normalizedCmd, stdoutThread = stdoutThread, stderrThread = stderrThread)
    }

    // ── Internal execution ────────────────────────────────────────────

    private fun executeInternal(cmd: String, timeoutMs: Long): ShellExecutionResult {
        val startMs = System.currentTimeMillis()
        val normalizedCmd = normalizePaths(cmd)
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", normalizedCmd))
            val stdoutRef = java.util.concurrent.atomic.AtomicReference("")
            val stderrRef = java.util.concurrent.atomic.AtomicReference("")
            val truncatedRef = AtomicBoolean(false)

            val stdoutThread = Thread {
                stdoutRef.set(readStreamCapped(process.inputStream, truncatedRef))
            }.apply { isDaemon = true; name = "shell-exec-out" }

            val stderrThread = Thread {
                stderrRef.set(readStreamCapped(process.errorStream, truncatedRef))
            }.apply { isDaemon = true; name = "shell-exec-err" }

            stdoutThread.start(); stderrThread.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                runCatching { stdoutThread.join(500) }; runCatching { stderrThread.join(500) }
                return ShellExecutionResult(
                    stdoutRef.get().trim(), "⏱ Timed out after ${timeoutMs}ms",
                    -1, false, backend = ExecutionBackend.SMART_SHELL,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            runCatching { stdoutThread.join(2000) }; runCatching { stderrThread.join(2000) }
            val stdout = stdoutRef.get().trim(); val stderr = stderrRef.get().trim()
            val finalStdout = if (truncatedRef.get())
                "$stdout\n…(output truncated at ${TerminalConfig.MAX_OUTPUT_BYTES / 1024} KB)" else stdout

            ShellExecutionResult(finalStdout, stderr, process.exitValue(),
                durationMs = System.currentTimeMillis() - startMs, backend = ExecutionBackend.SMART_SHELL)
        } catch (e: Exception) {
            Log.e(TAG, "Shell exec failed: ${e.message}")
            ShellExecutionResult("", "Execution failed: ${e.message}", -1, false,
                backend = ExecutionBackend.SMART_SHELL, durationMs = System.currentTimeMillis() - startMs)
        }
    }

    private fun executeStreamingInternal(
        cmd: String, timeoutMs: Long, onOutput: (String) -> Unit
    ): ShellExecutionResult {
        val startMs = System.currentTimeMillis()
        val normalizedCmd = normalizePaths(cmd)
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", normalizedCmd))
            val stdoutSb = StringBuilder(); val stderrSb = StringBuilder()

            val stdoutThread = Thread {
                try { process.inputStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        val l = "$line\n"; stdoutSb.append(l); onOutput(l)
                    }
                } } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shell-exec-stream-out" }

            val stderrThread = Thread {
                try { process.errorStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        val l = "$line\n"; stderrSb.append(l); onOutput("\u001b[31m$l\u001b[0m")
                    }
                } } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shell-exec-stream-err" }

            stdoutThread.start(); stderrThread.start()
            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)

            if (!completed) {
                process.destroyForcibly()
                runCatching { stdoutThread.join(1000) }; runCatching { stderrThread.join(1000) }
                return ShellExecutionResult(stdoutSb.toString().trim(),
                    "⏱ Timed out after ${timeoutMs}ms", -1, false,
                    backend = ExecutionBackend.SMART_SHELL, durationMs = System.currentTimeMillis() - startMs)
            }

            runCatching { stdoutThread.join(2000) }; runCatching { stderrThread.join(2000) }
            ShellExecutionResult(stdoutSb.toString().trim(), stderrSb.toString().trim(),
                process.exitValue(), durationMs = System.currentTimeMillis() - startMs,
                backend = ExecutionBackend.SMART_SHELL)
        } catch (e: Exception) {
            ShellExecutionResult("", "Error: ${e.message}", -1, false,
                backend = ExecutionBackend.SMART_SHELL, durationMs = System.currentTimeMillis() - startMs)
        }
    }

    private fun readStreamCapped(stream: java.io.InputStream, truncatedRef: AtomicBoolean): String {
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(stream))
        val buffer = CharArray(8192)
        var total = 0
        try {
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                total += read
                if (total > TerminalConfig.MAX_OUTPUT_BYTES) {
                    sb.append(buffer, 0, read.coerceAtMost(TerminalConfig.MAX_OUTPUT_BYTES - (total - read)))
                    truncatedRef.set(true)
                    break
                }
                sb.append(buffer, 0, read)
            }
        } catch (_: Exception) {}
        return sb.toString()
    }
}
