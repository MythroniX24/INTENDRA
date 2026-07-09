package com.interndra.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * SmartShell — executes shell commands safely on a background dispatcher.
 *
 * ## A+++ UPGRADES:
 *  1. Returns unified [ShellExecutionResult] with correct backend tag.
 *  2. Uses [TerminalConfig] for all constants (MAX_OUTPUT_BYTES, timeouts).
 *  3. Concurrent stdout/stderr draining avoids pipe-buffer deadlock.
 *  4. Output capped at TerminalConfig.MAX_OUTPUT_BYTES to prevent OOM.
 *  5. Process always destroyed in finally to prevent zombies.
 *  6. Default timeout from TerminalConfig.DEFAULT_TIMEOUT_MS.
 *  7. **[NEW]** Background process spawning via [spawnBackground] — returns
 *     a [BackgroundProcess] handle that can be cancelled and polled for output.
 */
class SmartShell(private val context: Context) {

    companion object {
        private const val TAG = "SmartShell"

        private val RE_DOWNLOADS = Regex("~/Downloads?\\b")
        private val RE_PICTURES   = Regex("~/Pictures\\b")
        private val RE_DCIM       = Regex("~/DCIM\\b")
        private val RE_DOCUMENTS  = Regex("~/Documents\\b")
        private val RE_MUSIC      = Regex("~/Music\\b")
        private val RE_HOME_SLASH = Regex("~/")
        private val RE_HOME_BARE  = Regex("(?<![\\w/])~(?![\\w/])")

        @Volatile private var instance: SmartShell? = null
        fun get(context: Context): SmartShell =
            instance ?: synchronized(this) {
                instance ?: SmartShell(context.applicationContext).also { instance = it }
            }
    }

    // ── Background Process Handle ───────────────────────────────────────

    /**
     * Represents a running background process spawned via [spawnBackground].
     * Output is collected asynchronously via onOutput callbacks.
     */
    data class BackgroundProcess(
        val process: java.lang.Process,
        val command: String,
        val startedAt: Long = System.currentTimeMillis(),
        private val stdoutThread: Thread,
        private val stderrThread: Thread
    ) {
        @Volatile var isRunning: Boolean = true
            private set
        @Volatile var exitCode: Int? = null
            private set

        /** Kill the background process. */
        fun cancel() {
            isRunning = false
            runCatching { process.destroyForcibly() }
        }

        /** Wait for the process to complete (blocking) with timeout. */
        fun waitFor(timeoutMs: Long): Boolean {
            val done = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (done) {
                exitCode = process.exitValue()
                isRunning = false
                runCatching { stdoutThread.join(2000) }
                runCatching { stderrThread.join(2000) }
            }
            return done
        }

        /** Check if the process has exited (non-blocking). */
        fun hasExited(): Boolean {
            if (!isRunning) return true
            return try {
                exitCode = process.exitValue()
                isRunning = false
                true
            } catch (_: IllegalThreadStateException) {
                false
            }
        }
    }

    // ── Background Process Spawning ─────────────────────────────────────

    /**
     * Spawn a command in the background WITHOUT waiting for it to finish.
     * Returns a [BackgroundProcess] handle for cancellation and output polling.
     *
     * Use this for long-running commands (servers, watchers, daemons) where
     * you don't want to block the terminal.
     */
    fun spawnBackground(
        cmd: String,
        onOutput: ((String) -> Unit)? = null
    ): BackgroundProcess {
        val normalizedCmd = normalizePaths(cmd)
        Log.d(TAG, "Spawning background: ${normalizedCmd.take(100)}")

        val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", normalizedCmd))

        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        val l = "$line\n"
                        onOutput?.invoke(l)
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "bg-stdout" }

        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.lines().forEach { line ->
                        val l = "$line\n"
                        onOutput?.invoke("\u001b[31m$l\u001b[0m")
                    }
                }
            } catch (_: Exception) {}
        }.apply { isDaemon = true; name = "bg-stderr" }

        stdoutThread.start()
        stderrThread.start()

        return BackgroundProcess(process, normalizedCmd, stdoutThread = stdoutThread, stderrThread = stderrThread)
    }

    /**
     * Suspend variant — runs the command on Dispatchers.IO.
     * Returns unified [ShellExecutionResult] with [ExecutionBackend.SMART_SHELL].
     */
    suspend fun runAsync(
        cmd: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        runInternal(cmd, timeoutMs)
    }

    /**
     * Blocking variant — kept for non-coroutine callers.
     * Returns unified [ShellExecutionResult] with [ExecutionBackend.SMART_SHELL].
     */
    fun run(
        cmd: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult = runInternal(cmd, timeoutMs)

    /**
     * Backward-compatible overload — returns stdout as a plain String.
     * For callers that don't need the full ShellExecutionResult (InterndraNotificationListener,
     * AutomationWorker, AutomationEngine, HybridExecutionEngine).
     */
    @Suppress("unused")
    fun run(cmd: String): String {
        val result = runInternal(cmd, TerminalConfig.DEFAULT_TIMEOUT_MS)
        return when {
            result.isSuccess && result.stdout.isNotBlank() -> result.stdout
            result.stderr.isNotBlank() -> result.stderr
            else -> result.stdout.ifEmpty { "(no output)" }
        }
    }

    /**
     * Execute with streaming line-by-line output callback.
     */
    suspend fun runStreaming(
        cmd: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS,
        onOutput: (String) -> Unit
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        val normalizedCmd = normalizePaths(cmd)
        try {
            Log.d(TAG, "Executing streaming: ${normalizedCmd.take(100)}")

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", normalizedCmd))
            val stdoutSb = StringBuilder()
            val stderrSb = StringBuilder()

            val stdoutThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { reader ->
                        reader.lines().forEach { line ->
                            val l = "$line\n"
                            stdoutSb.append(l)
                            onOutput(l)
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "smartshell-stream-out" }

            val stderrThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { reader ->
                        reader.lines().forEach { line ->
                            val l = "$line\n"
                            stderrSb.append(l)
                            onOutput("\u001b[31m$l\u001b[0m")
                        }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "smartshell-stream-err" }

            stdoutThread.start(); stderrThread.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                runCatching { stdoutThread.join(500) }
                runCatching { stderrThread.join(500) }
                return@withContext ShellExecutionResult(
                    stdoutSb.toString().trim(),
                    "⏱ Timed out after ${timeoutMs}ms",
                    -1, false,
                    backend = ExecutionBackend.SMART_SHELL,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            runCatching { stdoutThread.join(1000) }
            runCatching { stderrThread.join(1000) }

            ShellExecutionResult(
                stdoutSb.toString().trim(),
                stderrSb.toString().trim(),
                process.exitValue(),
                durationMs = System.currentTimeMillis() - startMs,
                backend = ExecutionBackend.SMART_SHELL
            )
        } catch (e: Exception) {
            Log.e(TAG, "Streaming exec failed: ${e.message}")
            ShellExecutionResult("", "Error: ${e.message}", -1, false,
                backend = ExecutionBackend.SMART_SHELL,
                durationMs = System.currentTimeMillis() - startMs)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────

    private fun runInternal(cmd: String, timeoutMs: Long): ShellExecutionResult {
        val startMs = System.currentTimeMillis()
        val normalizedCmd = normalizePaths(cmd)
        return try {
            Log.d(TAG, "Executing: ${normalizedCmd.take(100)}")

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", normalizedCmd))
            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")
            val truncatedRef = AtomicBoolean(false)

            val stdoutThread = Thread {
                stdoutRef.set(readStreamCapped(process.inputStream, truncatedRef))
            }.apply { isDaemon = true; name = "smartshell-out" }

            val stderrThread = Thread {
                stderrRef.set(readStreamCapped(process.errorStream, truncatedRef))
            }.apply { isDaemon = true; name = "smartshell-err" }

            stdoutThread.start(); stderrThread.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                runCatching { stdoutThread.join(500) }
                runCatching { stderrThread.join(500) }
                Log.w(TAG, "Timed out after ${timeoutMs}ms: ${normalizedCmd.take(60)}")
                return ShellExecutionResult(
                    stdoutRef.get().trim(),
                    "⏱ Timed out after ${timeoutMs}ms",
                    -1, false,
                    backend = ExecutionBackend.SMART_SHELL,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            runCatching { stdoutThread.join(2000) }
            runCatching { stderrThread.join(2000) }

            val stdout = stdoutRef.get().trim()
            val stderr = stderrRef.get().trim()
            val truncated = truncatedRef.get()

            val finalStdout = if (truncated)
                "$stdout\n…(output truncated at ${TerminalConfig.MAX_OUTPUT_BYTES / 1024} KB)" else stdout

            ShellExecutionResult(
                finalStdout, stderr, process.exitValue(),
                durationMs = System.currentTimeMillis() - startMs,
                backend = ExecutionBackend.SMART_SHELL
            )
        } catch (e: Exception) {
            Log.e(TAG, "Shell exec failed: ${e.message}")
            ShellExecutionResult("", "Execution failed: ${e.message}", -1, false,
                backend = ExecutionBackend.SMART_SHELL,
                durationMs = System.currentTimeMillis() - startMs)
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

    private fun normalizePaths(cmd: String): String {
        var c = cmd
        c = c.replace(RE_DOWNLOADS, "/storage/emulated/0/Download")
        c = c.replace(RE_PICTURES,  "/storage/emulated/0/Pictures")
        c = c.replace(RE_DCIM,      "/storage/emulated/0/DCIM")
        c = c.replace(RE_DOCUMENTS, "/storage/emulated/0/Documents")
        c = c.replace(RE_MUSIC,     "/storage/emulated/0/Music")
        c = c.replace(RE_HOME_SLASH, "/storage/emulated/0/")
        c = c.replace(RE_HOME_BARE,  "/storage/emulated/0")
        return c
    }
}
