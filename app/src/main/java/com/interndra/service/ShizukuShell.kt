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
class ShizukuShell(
    private val context: Context,
    private val shizukuManager: ShizukuManager? = null
) {

    companion object {
        private const val TAG = "ShizukuShell"

        @Volatile private var instance: ShizukuShell? = null
        fun get(context: Context): ShizukuShell =
            instance ?: synchronized(this) {
                instance ?: ShizukuShell(context.applicationContext).also { instance = it }
            }
    }

    /**
     * ShizukuManager instance. If provided via constructor, uses that (preferred).
     * Otherwise creates a local lazy instance (which needs separate init() call).
     */
    private val managerInstance: ShizukuManager = shizukuManager ?: ShizukuManager(context)

    /** Whether Shizuku is currently available and authorized. */
    val isElevatedAvailable: Boolean get() = managerInstance.isAuthorized()

    /** Human-readable privilege level. */
    val privilegeDescription: String get() = managerInstance.privilegeLevel

    val manager: ShizukuManager get() = managerInstance

    /**
     * Execute a shell command using the best available backend.
     * Tries Shizuku first (elevated), falls back to ShellExecutor (sandboxed).
     */
    suspend fun execute(
        command: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        Log.d(TAG, "Executing: ${command.take(100)}")

        if (managerInstance.isAuthorized()) {
            Log.d(TAG, "Using Shizuku backend (UID ${managerInstance.shizukuUid})")
            val result = if (onOutput != null) {
                managerInstance.executeShellStreaming(command, timeoutMs, onOutput)
            } else {
                managerInstance.executeShell(command, timeoutMs)
            }
            // Only use Shizuku result if it actually worked (including non-zero exit)
            if (result.backend == ExecutionBackend.SHIZUKU_ROOT ||
                result.backend == ExecutionBackend.SHIZUKU_ADB) {
                return@withContext result
            }
            Log.w(TAG, "Shizuku failed (backend=${result.backend}), falling back to ShellExecutor")
        }

        // Fall back to ShellExecutor (sandboxed)
        Log.d(TAG, "Using ShellExecutor backend (sandboxed)")
        if (onOutput != null) {
            ShellExecutor.runStreaming(command, timeoutMs, onOutput)
        } else {
            ShellExecutor.runAsync(command, timeoutMs)
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
        if (managerInstance.isAuthorized()) {
            val result = managerInstance.executeShell(command, timeoutMs)
            if (result.backend == ExecutionBackend.SHIZUKU_ROOT ||
                result.backend == ExecutionBackend.SHIZUKU_ADB) {
                return result
            }
        }
        return ShellExecutor.run(command, timeoutMs)
    }

    /**
     * Test that the shell is working by running a simple echo command.
     */
    suspend fun testConnection(): Int = withContext(Dispatchers.IO) {
        if (managerInstance.isAuthorized()) {
            val uid = managerInstance.testConnection()
            if (uid != null) return@withContext uid
        }
        val result = ShellExecutor.runAsync("echo \$USER && id -u", TerminalConfig.DEFAULT_TIMEOUT_MS)
        result.stdout.lines().firstOrNull { it.all { c -> c.isDigit() } }?.toIntOrNull() ?: Process.myUid()
    }
}
