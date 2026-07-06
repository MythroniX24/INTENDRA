package com.interndra.service

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TermuxBridge — bidirectional IPC with Termux via RUN_COMMAND Intent API.
 *
 * Sends shell commands to Termux's full Linux environment and receives
 * stdout/stderr/exit code back via a local BroadcastReceiver.
 *
 * ## Architecture
 * ```
 * INTERNDRA ──Intent(RUN_COMMARD)──> Termux (RunCommandService)
 * Termux    ──Broadcast(RESULT)───> TermuxBridge.Receiver
 * ```
 *
 * ## Prerequisites
 * 1. Termux app must be installed (com.termux)
 * 2. `allow-external-apps=true` in ~/.termux/termux.properties
 * 3. Permission `com.termux.permission.RUN_COMMAND` declared in AndroidManifest
 *
 * ## Usage
 * ```kotlin
 * val bridge = TermuxBridge(context)
 * if (bridge.isTermuxInstalled()) {
 *     val result = bridge.execute("pkg install python")
 *     println("Exit: ${result.exitCode}, Out: ${result.stdout}")
 * }
 * ```
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

        // Intent extras (from Termux API)
        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

        // Result extras
        const val EXTRA_RESULT_BUNDLE = "com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE"
        const val EXTRA_STDOUT = "STDOUT"
        const val EXTRA_STDERR = "STDERR"
        const val EXTRA_EXIT_CODE = "EXIT_CODE"
        const val EXTRA_ERR = "ERR"

        const val ACTION_RESULT = "com.interndra.TERMUX_COMMAND_RESULT"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        private val requestIdCounter = AtomicInteger(0)
    }

    data class TermuxResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val isSuccess: Boolean = exitCode == 0
    )

    // ── Active request tracking ──────────────────────────────────────────
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<TermuxResult>>()
    private val receiver = TermuxResultReceiver()

    private val receiverRegistered = java.util.concurrent.atomic.AtomicBoolean(false)

    /**
     * Check if Termux is installed on the device.
     */
    fun isTermuxInstalled(): Boolean = isPackageInstalled(TERMUX_PACKAGE)

    /**
     * Check if the app has the RUN_COMMAND permission.
     */
    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        } else {
            // On older Android, custom permissions are granted at install time if declared
            @Suppress("DEPRECATION")
            context.checkPermission(PERMISSION_RUN_COMMAND, android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Check if Termux has external apps enabled.
     * We detect this by trying a simple echo command — if it fails, we assume
     * allow-external-apps is not set.
     */
    suspend fun isExternalAppsEnabled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeRaw("echo", listOf("termux_bridge_test_ok"), timeoutMs = 5000)
            result.isSuccess && result.stdout.contains("termux_bridge_test_ok")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Execute a command in Termux and return the result.
     *
     * @param command The command to execute (e.g., "pkg", "python", "git")
     * @param arguments List of arguments for the command
     * @param workdir Optional working directory (defaults to Termux home ~)
     * @param timeoutMs Timeout in milliseconds (default 30s)
     * @return TermuxResult with stdout, stderr, exit code
     */
    suspend fun execute(
        command: String,
        arguments: List<String> = emptyList(),
        workdir: String? = null,
        timeoutMs: Long = 30_000L
    ): TermuxResult = executeRaw(command, arguments, workdir, timeoutMs)

    /**
     * Execute a full shell command string in Termux.
     * The command is passed as a single argument to `sh -c`.
     *
     * @param shellCommand Complete shell command string (e.g., "pkg install python && python --version")
     * @param workdir Optional working directory
     * @param timeoutMs Timeout in milliseconds
     */
    suspend fun executeShell(
        shellCommand: String,
        workdir: String? = null,
        timeoutMs: Long = 60_000L
    ): TermuxResult = executeRaw(
        command = "/data/data/com.termux/files/usr/bin/sh",
        arguments = listOf("-c", shellCommand),
        workdir = workdir,
        timeoutMs = timeoutMs
    )

    /**
     * Install a package via pkg (Termux's package manager).
     */
    suspend fun installPackage(packageName: String): TermuxResult =
        executeShell("pkg install -y $packageName 2>&1", timeoutMs = 120_000L)

    /**
     * Update all packages.
     */
    suspend fun updatePackages(): TermuxResult =
        executeShell("pkg update -y && pkg upgrade -y 2>&1", timeoutMs = 180_000L)

    /**
     * Run a Python script or command.
     */
    suspend fun runPython(script: String): TermuxResult =
        executeShell("python3 -c '$script' 2>&1", timeoutMs = 60_000L)

    /**
     * Install Python packages via pip.
     */
    suspend fun pipInstall(packages: List<String>): TermuxResult =
        executeShell("pip install ${packages.joinToString(" ")} 2>&1", timeoutMs = 120_000L)

    /**
     * Run a git command.
     */
    suspend fun git(vararg args: String, workdir: String? = null): TermuxResult =
        executeRaw(
            command = "/data/data/com.termux/files/usr/bin/git",
            arguments = args.toList(),
            workdir = workdir,
            timeoutMs = 60_000L
        )

    /**
     * Run an npm command.
     */
    suspend fun npm(vararg args: String, workdir: String? = null): TermuxResult =
        executeRaw(
            command = "/data/data/com.termux/files/usr/bin/npm",
            arguments = args.toList(),
            workdir = workdir,
            timeoutMs = 120_000L
        )

    /**
     * Get Termux's home directory path.
     */
    suspend fun getHomeDir(): String = withContext(Dispatchers.IO) {
        try {
            val result = executeShell("echo \$HOME", timeoutMs = 5000)
            if (result.isSuccess) result.stdout.trim()
            else "/data/data/com.termux/files/home"
        } catch (e: Exception) {
            "/data/data/com.termux/files/home"
        }
    }

    // ── Core execution ─────────────────────────────────────────────────────

    private suspend fun executeRaw(
        command: String,
        arguments: List<String> = emptyList(),
        workdir: String? = null,
        timeoutMs: Long = 30_000L
    ): TermuxResult = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled()) {
            return@withContext TermuxResult(
                stdout = "",
                stderr = "Termux is not installed. Install Termux from F-Droid or GitHub.",
                exitCode = -1
            )
        }

        if (!receiverRegistered.get()) {
            synchronized(this) {
                if (!receiverRegistered.get()) {
                    registerReceiver()
                }
            }
        }

        val requestId = requestIdCounter.incrementAndGet()
        val deferred = CompletableDeferred<TermuxResult>()
        pendingRequests[requestId] = deferred

        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, command)
                putExtra(EXTRA_ARGUMENTS, arguments.toTypedArray())
                if (workdir != null) putExtra(EXTRA_WORKDIR, workdir)
                putExtra(EXTRA_BACKGROUND, false)

                // PendingIntent for result delivery
                val resultIntent = Intent(ACTION_RESULT).apply {
                    putExtra("REQUEST_ID", requestId)
                    setPackage(context.packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT

                val pendingIntent = PendingIntent.getBroadcast(
                    context, requestId, resultIntent, flags
                )
                putExtra(EXTRA_PENDING_INTENT, pendingIntent)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }

            // Wait for result with timeout
            val result = kotlinx.coroutines.withTimeoutOrNull(timeoutMs) {
                deferred.await()
            }

            if (result != null) {
                result
            } else {
                deferred.complete(TermuxResult("", "Command timed out after ${timeoutMs}ms", -1))
                pendingRequests.remove(requestId)
                TermuxResult("", "Command timed out after ${timeoutMs}ms", -1)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to execute command in Termux: ${e.message}")
            pendingRequests.remove(requestId)
            TermuxResult("", "Termux error: ${e.message}", -1)
        }
    }

    // ── Receiver ────────────────────────────────────────────────────────────

    inner class TermuxResultReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val requestId = intent.getIntExtra("REQUEST_ID", -1)
            if (requestId < 0) return

            val deferred = pendingRequests.remove(requestId) ?: return

            try {
                val bundle = intent.getBundleExtra(EXTRA_RESULT_BUNDLE)
                if (bundle != null) {
                    val stdout = bundle.getString(EXTRA_STDOUT, "")
                    val stderr = bundle.getString(EXTRA_STDERR, "")
                    val exitCode = bundle.getInt(EXTRA_EXIT_CODE, -1)
                    val err = bundle.getString(EXTRA_ERR)
                    val combinedStderr = if (err != null) "$stderr\n$err" else stderr

                    deferred.complete(TermuxResult(
                        stdout = stdout,
                        stderr = combinedStderr,
                        exitCode = exitCode
                    ))
                } else {
                    // No bundle — maybe older API version
                    val stdout = intent.getStringExtra(EXTRA_STDOUT) ?: ""
                    val stderr = intent.getStringExtra(EXTRA_STDERR) ?: ""
                    val exitCode = intent.getIntExtra(EXTRA_EXIT_CODE, -1)
                    deferred.complete(TermuxResult(stdout, stderr, exitCode))
                }
            } catch (e: Exception) {
                deferred.complete(TermuxResult("", "Error reading Termux result: ${e.message}", -1))
            }
        }
    }

    private fun registerReceiver() {
        try {
            val filter = IntentFilter(ACTION_RESULT)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            receiverRegistered.set(true)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register result receiver: ${e.message}")
        }
    }

    fun unregisterReceiver() {
        if (receiverRegistered.compareAndSet(true, false)) {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Error unregistering receiver: ${e.message}")
            }
        }
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }


}
