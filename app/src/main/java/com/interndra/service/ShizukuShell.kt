package com.interndra.service

import android.content.Context
import android.os.Process
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ShizukuShell — executes shell commands with elevated privileges via Shizuku.
 *
 * ## A+++ UPGRADES:
 *  1. Returns unified [ShellExecutionResult] — no more ShizukuShell.ShellResult conversion.
 *  2. Clean fallback chain: Shizuku (elevated) → SmartShell (sandboxed).
 *  3. Uses [TerminalConfig] for all timeout defaults.
 *  4. Proper backend tags propagated through results.
 *
 * ## Execution Priority
 * 1. **Shizuku** (elevated) — UID 2000 or 0, can access system APIs
 * 2. **SmartShell** (fallback) — Sandboxed app process via Runtime.exec()
 */
class ShizukuShell(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuShell"

        @Volatile private var instance: ShizukuShell? = null
        fun get(context: Context): ShizukuShell =
            instance ?: synchronized(this) {
                instance ?: ShizukuShell(context.applicationContext).also { instance = it }
            }
    }

    private val smartShell = SmartShell.get(context)
    private val shizukuManager by lazy { ShizukuManager(context) }

    /** Whether Shizuku is currently available and authorized. */
    val isElevatedAvailable: Boolean get() = shizukuManager.isAuthorized()

    /** Human-readable privilege level. */
    val privilegeDescription: String get() = shizukuManager.privilegeLevel

    val manager: ShizukuManager get() = shizukuManager

    /**
     * Execute a shell command using the best available backend.
     * Tries Shizuku first (elevated), falls back to SmartShell (sandboxed).
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing: ${command.take(100)}")

        if (shizukuManager.isAuthorized()) {
            Log.d(TAG, "Using Shizuku backend (UID ${shizukuManager.shizukuUid})")
            val result = if (onOutput != null) {
                shizukuManager.executeShellStreaming(command, timeoutMs, onOutput)
            } else {
                shizukuManager.executeShell(command, timeoutMs)
            }
            // Only use Shizuku result if it actually worked (including non-zero exit)
            if (result.backend == ExecutionBackend.SHIZUKU_ROOT ||
                result.backend == ExecutionBackend.SHIZUKU_ADB) {
                return@withContext result
            }
            Log.w(TAG, "Shizuku failed (backend=${result.backend}), falling back to SmartShell")
        }

        // Fall back to SmartShell
        Log.d(TAG, "Using SmartShell backend (sandboxed)")
        if (onOutput != null) {
            smartShell.runStreaming(command, timeoutMs, onOutput)
        } else {
            smartShell.runAsync(command, timeoutMs)
        }
    }

    /**
     * Execute synchronously for non-coroutine contexts.
     */
    fun executeBlocking(
        command: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult {
        Log.d(TAG, "Executing blocking: ${command.take(100)}")
        if (shizukuManager.isAuthorized()) {
            val result = shizukuManager.executeShell(command, timeoutMs)
            if (result.backend == ExecutionBackend.SHIZUKU_ROOT ||
                result.backend == ExecutionBackend.SHIZUKU_ADB) {
                return result
            }
        }
        return smartShell.run(command, timeoutMs)
    }

    /**
     * Test that the shell is working by running a simple echo command.
     */
    suspend fun testConnection(): Int = withContext(Dispatchers.IO) {
        if (shizukuManager.isAuthorized()) {
            val uid = shizukuManager.testConnection()
            if (uid != null) return@withContext uid
        }
        val result: ShellExecutionResult = smartShell.runAsync("echo \$USER && id -u", TerminalConfig.DEFAULT_TIMEOUT_MS)
        result.stdout.lines().firstOrNull { it.all { c -> c.isDigit() } }?.toIntOrNull() ?: Process.myUid()
    }
}
