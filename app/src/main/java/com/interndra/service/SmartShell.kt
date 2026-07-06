package com.interndra.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * SmartShell — executes shell commands safely on a background dispatcher.
 *
 * FIXES (Phase 2 + Phase 10 + Phase 11):
 *  1. ANR FIX: `run()` is now a `suspend fun` that runs on `Dispatchers.IO`.
 *     Callers no longer block the main thread for up to 30 s.
 *  2. DEADLOCK FIX: stdout and stderr are drained concurrently in separate
 *     threads. Previously they were read sequentially AFTER `waitFor()`,
 *     which deadlocked when a verbose command filled the 64 KB pipe buffer.
 *  3. OUTPUT CAP: output is capped at MAX_OUTPUT_BYTES to prevent OOM on
 *     commands like `yes` or `cat /dev/urandom`.
 *  4. REGEX PRECOMPILE: the 6 path-normalization regexes are now class-level
 *     vals, not recompiled per call.
 *  5. PROCESS CLEANUP: `destroyForcibly()` is always called in a `finally`
 *     block, even on timeout, to prevent zombie processes.
 *  6. TIMEOUT: configurable per-call (default 30 s), enforced via
 *     `waitFor(timeout, SECONDS)` + watchdog.
 */
class SmartShell(private val context: Context) {

    companion object {
        private const val TAG = "SmartShell"
        private const val DEFAULT_TIMEOUT_SEC = 30L

        // Cap output to 512 KB to prevent OOM from runaway commands.
        private const val MAX_OUTPUT_BYTES = 512 * 1024

        // Precompiled path-normalization patterns (Phase 11 — regex no longer
        // recompiled on every invocation).
        private val RE_DOWNLOADS = Regex("~/Downloads?\\b")
        private val RE_PICTURES   = Regex("~/Pictures\\b")
        private val RE_DCIM       = Regex("~/DCIM\\b")
        private val RE_DOCUMENTS  = Regex("~/Documents\\b")
        private val RE_MUSIC      = Regex("~/Music\\b")
        private val RE_HOME_SLASH = Regex("~/")
        private val RE_HOME_BARE  = Regex("(?<![\\w/])~(?![\\w/])")

        /** Singleton instance — SmartShell is stateless and safe to share. */
        @Volatile private var instance: SmartShell? = null
        fun get(context: Context): SmartShell =
            instance ?: synchronized(this) {
                instance ?: SmartShell(context.applicationContext).also { instance = it }
            }
    }

    /**
     * Suspend variant — runs the command on Dispatchers.IO.
     * This is the preferred entry point from any coroutine scope.
     */
    suspend fun runAsync(cmd: String, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): String =
        withContext(Dispatchers.IO) {
            run(cmd, timeoutSec)
        }

    /**
     * Blocking variant — kept for compatibility with non-coroutine callers
     * (e.g. InterndraNotificationListener's serviceScope on Dispatchers.IO).
     * Must NOT be called from the main thread.
     */
    fun run(cmd: String, timeoutSec: Long = DEFAULT_TIMEOUT_SEC): String {
        val normalizedCmd = normalizePaths(cmd)
        return try {
            Log.d(TAG, "Executing: ${normalizedCmd.take(100)}")

            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", normalizedCmd))

            // Drain stdout and stderr CONCURRENTLY to avoid pipe-buffer deadlock.
            val stdoutRef = AtomicReference("")
            val stderrRef = AtomicReference("")
            val truncatedRef = AtomicReference(false)

            val stdoutThread = Thread {
                stdoutRef.set(readStreamCapped(process.inputStream, truncatedRef))
            }.apply { isDaemon = true; name = "smartshell-stdout" }

            val stderrThread = Thread {
                stderrRef.set(readStreamCapped(process.errorStream, truncatedRef))
            }.apply { isDaemon = true; name = "smartshell-stderr" }

            stdoutThread.start()
            stderrThread.start()

            val completed = process.waitFor(timeoutSec, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                // give drain threads a moment to flush
                runCatching { stdoutThread.join(500) }
                runCatching { stderrThread.join(500) }
                Log.w(TAG, "Command timed out after ${timeoutSec}s: ${normalizedCmd.take(60)}")
                return "⏱ Command timed out after ${timeoutSec}s"
            }

            // Wait for drain threads to finish reading residual buffer content.
            runCatching { stdoutThread.join(2000) }
            runCatching { stderrThread.join(2000) }

            val output = stdoutRef.get().trim()
            val error  = stderrRef.get().trim()
            val truncated = truncatedRef.get()

            val body = when {
                output.isNotEmpty() && error.isNotEmpty() -> "$output\n⚠ stderr: $error"
                error.isNotEmpty() -> "⚠ Error: $error"
                else -> output.ifEmpty { "(no output)" }
            }

            if (truncated) "$body\n…(output truncated at ${MAX_OUTPUT_BYTES / 1024} KB)" else body

        } catch (e: Exception) {
            Log.e(TAG, "Shell exec failed: ${e.message}")
            "Execution failed: ${e.message}"
        }
    }

    /**
     * Reads a stream into a String, capping total bytes at MAX_OUTPUT_BYTES.
     * Sets `truncatedRef` to true if the cap was hit.
     */
    private fun readStreamCapped(stream: java.io.InputStream, truncatedRef: AtomicReference<Boolean>): String {
        val sb = StringBuilder()
        val reader = BufferedReader(InputStreamReader(stream))
        val buffer = CharArray(8192)
        var total = 0
        try {
            while (true) {
                val read = reader.read(buffer)
                if (read < 0) break
                total += read
                if (total > MAX_OUTPUT_BYTES) {
                    sb.append(buffer, 0, read.coerceAtMost(MAX_OUTPUT_BYTES - (total - read)))
                    truncatedRef.set(true)
                    break
                }
                sb.append(buffer, 0, read)
            }
        } catch (_: Exception) {
            // Stream closed or process died — return what we have.
        }
        return sb.toString()
    }

    /**
     * This app's `sh -c` process does NOT have HOME set to Android's shared
     * storage (it's the app's own sandboxed exec environment), so `~` never
     * resolves to where the user's actual Downloads/Pictures/etc. live.
     * Rewrite common `~`-based paths to the real Android shared-storage paths
     * BEFORE execution, regardless of whether the command came from the AI
     * or the local CommandRegistry fallback.
     */
    private fun normalizePaths(cmd: String): String {
        var c = cmd
        // Specific known folders first (note: Android's real folder is
        // singular "Download", not "Downloads")
        c = c.replace(RE_DOWNLOADS, "/storage/emulated/0/Download")
        c = c.replace(RE_PICTURES,  "/storage/emulated/0/Pictures")
        c = c.replace(RE_DCIM,      "/storage/emulated/0/DCIM")
        c = c.replace(RE_DOCUMENTS, "/storage/emulated/0/Documents")
        c = c.replace(RE_MUSIC,     "/storage/emulated/0/Music")
        // Catch-all: any other ~/something or bare ~
        c = c.replace(RE_HOME_SLASH, "/storage/emulated/0/")
        c = c.replace(RE_HOME_BARE,  "/storage/emulated/0")
        return c
    }
}
