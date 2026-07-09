package com.interndra.service

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * ShizukuShell — executes shell commands with elevated privileges via Shizuku.
 *
 * ## Execution Priority
 * ShizukuShell automatically selects the best available backend:
 * 1. **Shizuku** (elevated) — UID 2000 or 0, can access system APIs
 * 2. **SmartShell** (fallback) — Sandboxed app process via Runtime.exec()
 *
 * This allows TerminalAgent to use Shizuku when available, and transparently
 * fall back to the sandboxed SmartShell when Shizuku is not authorized.
 *
 * ## What Shizuku Unlocks
 * Commands that fail in the sandbox but work with Shizuku:
 * - `pm install`, `pm uninstall`, `pm list packages`
 * - `am start`, `am force-stop`, `am broadcast`
 * - `settings put global`, `settings put secure`
 * - `cmd wifi`, `cmd battery`, `cmd netpolicy`
 * - `dumpsys` (full output, not filtered)
 * - Access to /sdcard/ files (already accessible, but with Shizuku more reliable)
 * - `screencap /sdcard/screenshot.png`
 * - `input tap`, `input swipe`, `input text`
 * - Running scripts from /data/local/tmp/
 *
 * ## Architecture
 * ```
 * ShizukuShell
 *   ├── isShizukuAvailable() && isShizukuAuthorized()
 *   │     → Shizuku.newProcess("sh -c <cmd>")  ← ELEVATED
 *   └── else
 *         → SmartShell.run(cmd)                  ← SANDBOXED
 * ```
 */
class ShizukuShell(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuShell"
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_BYTES = 512 * 1024

        /** Singleton instance — ShizukuShell is safe to share. */
        @Volatile private var instance: ShizukuShell? = null
        fun get(context: Context): ShizukuShell =
            instance ?: synchronized(this) {
                instance ?: ShizukuShell(context.applicationContext).also { instance = it }
            }
    }

    // SmartShell as the sandboxed fallback
    private val smartShell = SmartShell.get(context)
    private val shizukuManager by lazy { ShizukuManager(context) }

    /** Whether Shizuku is currently available and authorized for elevated execution. */
    val isElevatedAvailable: Boolean
        get() = shizukuManager.isAuthorized()

    /** Human-readable description of the current privilege level. */
    val privilegeDescription: String
        get() = when {
            shizukuManager.isAuthorized() -> shizukuManager.privilegeLevel
            else -> "📦 Sandboxed"
        }

    /** The underlying ShizukuManager instance (for authorization UI). */
    val manager: ShizukuManager get() = shizukuManager

    /**
     * Execute a shell command using the best available backend.
     *
     * @param command Shell command string to execute
     * @param timeoutMs Timeout in milliseconds
     * @param onOutput Optional streaming callback for each line of output
     * @return ShellResult with stdout, stderr, exit code
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null
    ): ShellResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing: ${command.take(100)}")

        // Try Shizuku first if available
        if (shizukuManager.isAuthorized()) {
            Log.d(TAG, "Using Shizuku backend (UID ${shizukuManager.shizukuUid})")
            val result = if (onOutput != null) {
                val shizukuResult = shizukuManager.executeShellStreaming(command, timeoutMs, onOutput)
                shizukuResult.toShellResult()
            } else {
                shizukuManager.executeShell(command, timeoutMs).toShellResult()
            }
            if (result.isSuccess || result.exitCode != -1) {
                return@withContext result
            }
            Log.w(TAG, "Shizuku returned exit=${result.exitCode}, falling back to SmartShell")
        }

        // Fall back to SmartShell
        Log.d(TAG, "Using SmartShell backend (sandboxed)")
        val output = smartShell.runAsync(command, timeoutSec = TimeUnit.MILLISECONDS.toSeconds(timeoutMs))
        ShellResult(
            stdout = output,
            stderr = "",
            exitCode = if (output.startsWith("⏱") || output.startsWith("Execution failed")) 1 else 0
        )
    }

    /**
     * Execute a command and return the result synchronously.
     * For use from non-coroutine contexts (e.g., notification listener).
     */
    fun executeBlocking(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult {
        Log.d(TAG, "Executing blocking: ${command.take(100)}")
        if (shizukuManager.isAuthorized()) {
            val result = shizukuManager.executeShell(command, timeoutMs).toShellResult()
            if (result.isSuccess || result.exitCode != -1) return result
        }
        val output = smartShell.run(command, TimeUnit.MILLISECONDS.toSeconds(timeoutMs))
        return ShellResult(
            stdout = output,
            stderr = "",
            exitCode = if (output.startsWith("⏱") || output.startsWith("Execution failed")) 1 else 0
        )
    }

    /**
     * Test that the shell is working by running a simple echo command.
     * Returns the effective UID on success.
     */
    suspend fun testConnection(): Int = withContext(Dispatchers.IO) {
        if (shizukuManager.isAuthorized()) {
            val uid = shizukuManager.testConnection()
            if (uid != null) return@withContext uid
        }
        // Fallback: run whoami via SmartShell
        val result = smartShell.run("echo \$USER && id -u")
        val lines = result.lines()
        lines.firstOrNull { it.all { c -> c.isDigit() } }?.toIntOrNull() ?: Process.myUid()
    }

    // ── Model Mapper ───────────────────────────────────────────────────

    private fun ShizukuManager.ShizukuShellResult.toShellResult() = ShellResult(
        stdout = this.stdout,
        stderr = this.stderr,
        exitCode = this.exitCode
    )

    data class ShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val isSuccess: Boolean = exitCode == 0
    )
}
