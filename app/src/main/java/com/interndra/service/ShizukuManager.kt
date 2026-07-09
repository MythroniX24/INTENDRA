package com.interndra.service

import android.content.Context
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import rikka.shizuku.Shizuku
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * ShizukuManager — manages Shizuku authorization, lifecycle monitoring,
 * and privileged shell command execution.
 *
 * ## What Shizuku Provides
 * Shizuku allows INTERNDRA to execute shell commands with elevated privileges:
 * - **ADB mode** (UID 2000): Shell-level access — can use `pm`, `am`, `settings`,
 *   `cmd`, `dumpsys`, access /data/local/tmp/, and read many system files.
 * - **Root mode** (UID 0): Full root access — all commands available.
 *
 * ## Why This Matters
 * Without Shizuku, INTERNDRA has two execution backends:
 * 1. **SmartShell** — Android's sandboxed `Runtime.exec()`. Cannot access
 *    /sdcard/Download/, /data/data/ other apps, or use `pm`/`am` commands.
 * 2. **TermuxBridge** — Requires Termux app. Works well but users must install
 *    Termux + enable external apps.
 *
 * With Shizuku:
 * - **No Termux required** for many commands
 * - Can access /sdcard/ directly
 * - Can use `pm install`, `pm uninstall`, `am start`, `settings put`, etc.
 * - Can read system properties and dumpsys output
 * - Can manage other apps' data (backup, restore)
 *
 * ## Architecture
 * ```
 * TerminalAgent
 *   ├── ShizukuShell (PREFERRED)  ← ShizukuManager
 *   │     Elevated process (UID 2000/0)
 *   ├── TermuxBridge (FALLBACK)
 *   │     Termux Linux environment
 *   └── SmartShell (LAST RESORT)
 *         Sandboxed app process
 * ```
 *
 * ## Usage
 * ```kotlin
 * val shizuku = ShizukuManager(context)
 * shizuku.init()  // Start monitoring binder
 *
 * if (shizuku.isShizukuInstalled() && shizuku.isAuthorized()) {
 *     val result = shizuku.executeShell("pm list packages")
 * }
 * ```
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"

        // Shizuku API request code (can be any positive integer)
        const val SHIZUKU_REQUEST_CODE = 10001

        // Default shell timeout
        private const val DEFAULT_TIMEOUT_MS = 30_000L
        private const val MAX_OUTPUT_BYTES = 512 * 1024

        // Shizuku app package name (for checking if installed)
        private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"
        private const val SHIZUKU_PRIVILEGED_PACKAGE = "moe.shizuku.privileged.api"

        private val commandIdCounter = AtomicInteger(0)
    }

    // ── Status ──────────────────────────────────────────────────────────

    /** Whether the Shizuku binder is currently alive. */
    @Volatile
    var isBinderAlive: Boolean = false
        private set

    /** Whether the app has been granted Shizuku permission. */
    @Volatile
    var isPermissionGranted: Boolean = false
        private set

    /** Shizuku server UID: 0 for root, 2000 for ADB shell. -1 if unknown. */
    @Volatile
    var shizukuUid: Int = -1
        private set

    /** Human-readable privilege level string. */
    val privilegeLevel: String
        get() = when (shizukuUid) {
            0 -> "🛡️ Root"
            2000 -> "🔑 ADB Shell"
            else -> "❌ None"
        }

    /** Shizuku API version (0 if not available). */
    @Volatile
    var apiVersion: Int = 0
        private set

    // ── Permission request listener ──────────────────────────────────────

    private val permissionResultListeners = ConcurrentHashMap<Int, (Boolean) -> Unit>()

    private val requestPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        val granted = grantResult == PackageManager.PERMISSION_GRANTED
        isPermissionGranted = granted
        Log.i(TAG, "Permission result: requestCode=$requestCode, granted=$granted")
        permissionResultListeners.remove(requestCode)?.invoke(granted)
    }

    // ── Binder death listener ────────────────────────────────────────────

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died — service may have restarted")
        isBinderAlive = false
        isPermissionGranted = false
        shizukuUid = -1
        onBinderDeathCallback?.invoke()
    }

    private var onBinderDeathCallback: (() -> Unit)? = null

    // ── Initialization flag ──────────────────────────────────────────────

    private val initialized = AtomicBoolean(false)

    /**
     * Call once to start monitoring Shizuku lifecycle.
     * Registers permission result listener and binder death listener.
     * Should be called from Application.onCreate() or ViewModel init.
     */
    fun init(onBinderDeath: (() -> Unit)? = null) {
        if (!initialized.compareAndSet(false, true)) return

        onBinderDeathCallback = onBinderDeath

        try {
            Shizuku.addRequestPermissionResultListener(requestPermissionListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            refreshStatus()
            Log.i(TAG, "ShizukuManager initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to init Shizuku: ${e.message}")
        }
    }

    /**
     * Clean up listeners. Call from ViewModel.onCleared().
     */
    fun shutdown() {
        try {
            Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (e: Exception) {
            Log.w(TAG, "Shizuku shutdown error: ${e.message}")
        }
        initialized.set(false)
    }

    /**
     * Refresh the Shizuku status (binder alive, permission, UID).
     */
    fun refreshStatus() {
        try {
            isBinderAlive = Shizuku.pingBinder()
            if (isBinderAlive) {
                isPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                shizukuUid = Shizuku.getUid()
                apiVersion = Shizuku.getVersion()
            } else {
                isPermissionGranted = false
                shizukuUid = -1
                apiVersion = 0
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh Shizuku status: ${e.message}")
            isBinderAlive = false
            isPermissionGranted = false
            shizukuUid = -1
        }
    }

    // ── Authorization ────────────────────────────────────────────────────

    /**
     * Check if the Shizuku service is installed on the device.
     */
    fun isShizukuInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0)
            true
        } catch (e: PackageManager.NameNotFoundException) {
            try {
                context.packageManager.getPackageInfo(SHIZUKU_PRIVILEGED_PACKAGE, 0)
                true
            } catch (e2: PackageManager.NameNotFoundException) {
                false
            }
        }
    }

    /**
     * Check if the app has been authorized by Shizuku.
     */
    fun isAuthorized(): Boolean = isBinderAlive && isPermissionGranted

    /**
     * Request Shizuku permission from the user.
     * The result is delivered to the registered OnRequestPermissionResultListener.
     *
     * @param onResult Callback with (granted: Boolean)
     * @return true if the request was sent successfully, false if Shizuku is not available
     */
    fun requestPermission(onResult: ((Boolean) -> Unit)? = null): Boolean {
        if (!isBinderAlive) {
            Log.w(TAG, "Cannot request permission: Shizuku binder not alive")
            onResult?.invoke(false)
            return false
        }

        if (isPermissionGranted) {
            onResult?.invoke(true)
            return true
        }

        try {
            val requestCode = SHIZUKU_REQUEST_CODE
            if (onResult != null) {
                permissionResultListeners[requestCode] = onResult
            }
            Shizuku.requestPermission(requestCode)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to request Shizuku permission: ${e.message}")
            onResult?.invoke(false)
            return false
        }
    }

    // ── Shell Execution ─────────────────────────────────────────────────

    /**
     * Data class for the result of a shell command executed via Shizuku.
     */
    data class ShizukuShellResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val isSuccess: Boolean = exitCode == 0
    )

    /**
     * Execute a shell command with elevated privileges via Shizuku.
     *
     * Uses Shizuku.newProcess() to create a process running as UID 2000 (ADB)
     * or UID 0 (root), allowing access to system APIs and file paths beyond
     * the app sandbox.
     *
     * @param command The shell command to execute
     * @param timeoutMs Maximum execution time in milliseconds
     * @return ShizukuShellResult with stdout, stderr, and exit code
     */
    fun executeShell(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShizukuShellResult {
        if (!isAuthorized()) {
            return ShizukuShellResult(
                stdout = "",
                stderr = "Shizuku not authorized. Please grant permission in Settings.",
                exitCode = -1
            )
        }

        try {
            Log.d(TAG, "Executing via Shizuku: ${command.take(100)}")

            // Use Shizuku to create an elevated process running sh -c
            val process = Shizuku.newProcess(
                arrayOf("sh", "-c", command),
                null,  // inherit environment
                null   // inherit working directory
            )

            // Drain stdout and stderr concurrently to prevent pipe-buffer deadlock
            val stdoutSb = StringBuilder()
            val stderrSb = StringBuilder()
            val truncatedRef = AtomicBoolean(false)
            val completed = AtomicBoolean(false)

            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val buffer = CharArray(8192)
                    var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_OUTPUT_BYTES) {
                            stdoutSb.append(buffer, 0, read.coerceAtMost(MAX_OUTPUT_BYTES - (total - read)))
                            truncatedRef.set(true)
                            break
                        }
                        stdoutSb.append(buffer, 0, read)
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-stdout" }

            val stderrThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    val buffer = CharArray(8192)
                    var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_OUTPUT_BYTES) {
                            stderrSb.append(buffer, 0, read.coerceAtMost(MAX_OUTPUT_BYTES - (total - read)))
                            truncatedRef.set(true)
                            break
                        }
                        stderrSb.append(buffer, 0, read)
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-stderr" }

            stdoutThread.start()
            stderrThread.start()

            // Wait for process completion with timeout
            completed.set(process.waitFor(timeoutMs, TimeUnit.MILLISECONDS))

            if (!completed.get()) {
                process.destroyForcibly()
                runCatching { stdoutThread.join(1000) }
                runCatching { stderrThread.join(1000) }
                Log.w(TAG, "Shizuku command timed out after ${timeoutMs}ms")
                return ShizukuShellResult(
                    stdoutSb.toString().trim(),
                    stderrSb.toString().trim() + "\n⏱ Command timed out after ${timeoutMs}ms",
                    -1
                )
            }

            runCatching { stdoutThread.join(2000) }
            runCatching { stderrThread.join(2000) }

            val stdout = stdoutSb.toString().trim()
            val stderr = stderrSb.toString().trim()
            val exitCode = process.exitValue()

            val truncated = truncatedRef.get()
            val finalStdout = if (truncated) "$stdout\n…(output truncated at ${MAX_OUTPUT_BYTES / 1024} KB)" else stdout

            Log.d(TAG, "Shizuku exec done: exit=$exitCode, stdout=${stdout.length} chars")
            ShizukuShellResult(finalStdout, stderr, exitCode)

        } catch (e: SecurityException) {
            Log.e(TAG, "Shizuku permission denied: ${e.message}")
            isPermissionGranted = false
            ShizukuShellResult("", "Shizuku permission denied: ${e.message}", -1)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku execution failed: ${e.message}")
            ShizukuShellResult("", "Shizuku error: ${e.message}", -1)
        }
    }

    /**
     * Execute a shell command WITH streaming output callback.
     * Each line of stdout/stderr is delivered to onOutput as it arrives.
     */
    fun executeShellStreaming(
        command: String,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS,
        onOutput: (String) -> Unit = {}
    ): ShizukuShellResult {
        if (!isAuthorized()) {
            return ShizukuShellResult(
                stdout = "",
                stderr = "Shizuku not authorized.",
                exitCode = -1
            )
        }

        try {
            Log.d(TAG, "Executing streaming via Shizuku: ${command.take(80)}")
            val process = Shizuku.newProcess(arrayOf("sh", "-c", command), null, null)

            val stdoutSb = StringBuilder()
            val stderrSb = StringBuilder()
            val completed = AtomicBoolean(false)

            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lineWithNewline = "$line\n"
                        stdoutSb.append(lineWithNewline)
                        onOutput(lineWithNewline)
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-stream-stdout" }

            val stderrThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    var line: String?
                    while (reader.readLine().also { line = it } != null) {
                        val lineWithNewline = "$line\n"
                        stderrSb.append(lineWithNewline)
                        onOutput("\u001b[31m$lineWithNewline\u001b[0m") // red for stderr
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-stream-stderr" }

            stdoutThread.start()
            stderrThread.start()

            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                runCatching { stdoutThread.join(1000) }
                runCatching { stderrThread.join(1000) }
                return ShizukuShellResult(
                    stdoutSb.toString().trim(),
                    stderrSb.toString().trim() + "\n⏱ Command timed out",
                    -1
                )
            }

            runCatching { stdoutThread.join(2000) }
            runCatching { stderrThread.join(2000) }

            return ShizukuShellResult(
                stdoutSb.toString().trim(),
                stderrSb.toString().trim(),
                process.exitValue()
            )
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku streaming error: ${e.message}")
            return ShizukuShellResult("", "Error: ${e.message}", -1)
        }
    }

    // ── Utility Commands ────────────────────────────────────────────────

    /**
     * Test that Shizuku is working by running a simple echo command.
     * Returns the Shizuku UID on success, null on failure.
     */
    fun testConnection(): Int? {
        val result = executeShell("echo shizuku_test_ok && id -u")
        if (result.isSuccess && result.stdout.contains("shizuku_test_ok")) {
            // Extract UID from output — second line from echo + id
            val lines = result.stdout.lines()
            val uidLine = lines.firstOrNull { it.all { c -> c.isDigit() } }
            return uidLine?.toIntOrNull() ?: shizukuUid
        }
        return null
    }

    /**
     * Get system property value using Shizuku (supports properties
     * not readable from app sandbox).
     */
    fun getSystemProperty(name: String): String? {
        val result = executeShell("getprop $name", timeoutMs = 5000)
        return if (result.isSuccess) result.stdout.trim().ifBlank { null } else null
    }

    /**
     * List contents of a directory that may not be accessible from the
     * app sandbox (e.g., /data/local/tmp/, /cache/, etc.).
     */
    fun listDirectory(path: String): List<String> {
        val result = executeShell("ls -la \"$path\" 2>/dev/null || echo 'ACCESS_DENIED'", timeoutMs = 5000)
        if (!result.isSuccess || result.stdout.contains("ACCESS_DENIED")) return emptyList()
        return result.stdout.lines().filter { it.isNotBlank() }
    }
}
