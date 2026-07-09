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
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * TermuxBridge — bidirectional IPC with Termux via RUN_COMMAND Intent API.
 *
 * ## A+++ UPGRADES:
 *  1. Returns unified [ShellExecutionResult] with [ExecutionBackend.TERMUX].
 *  2. Uses [TerminalConfig] for all timeout constants.
 *  3. Local streaming fallback wraps SmartShell.runStreaming().
 *  4. Receiver lifecycle properly managed with double-checked locking.
 */
class TermuxBridge(private val context: Context) {

    companion object {
        private const val TAG = "TermuxBridge"
        const val TERMUX_PACKAGE = "com.termux"
        const val RUN_COMMAND_SERVICE = "com.termux.app.RunCommandService"

        const val EXTRA_COMMAND_PATH = "com.termux.RUN_COMMAND_PATH"
        const val EXTRA_ARGUMENTS = "com.termux.RUN_COMMAND_ARGUMENTS"
        const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
        const val EXTRA_PENDING_INTENT = "com.termux.RUN_COMMAND_PENDING_INTENT"
        const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"

        const val EXTRA_RESULT_BUNDLE = "com.termux.TERMUX_SERVICE.EXTRA_PLUGIN_RESULT_BUNDLE"
        const val EXTRA_STDOUT = "STDOUT"
        const val EXTRA_STDERR = "STDERR"
        const val EXTRA_EXIT_CODE = "EXIT_CODE"
        const val EXTRA_ERR = "ERR"

        const val ACTION_RESULT = "com.interndra.TERMUX_COMMAND_RESULT"
        const val PERMISSION_RUN_COMMAND = "com.termux.permission.RUN_COMMAND"

        private val requestIdCounter = AtomicInteger(0)
    }

    // ── Active request tracking ──────────────────────────────────────────
    private val pendingRequests = ConcurrentHashMap<Int, CompletableDeferred<ShellExecutionResult>>()
    private val receiver = TermuxResultReceiver()
    private val receiverRegistered = AtomicBoolean(false)

    /** Check if Termux is installed. */
    fun isTermuxInstalled(): Boolean = isPackageInstalled(TERMUX_PACKAGE)

    /** Check RUN_COMMAND permission. */
    fun hasPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.checkSelfPermission(PERMISSION_RUN_COMMAND) == PackageManager.PERMISSION_GRANTED
        } else {
            @Suppress("DEPRECATION")
            context.checkPermission(PERMISSION_RUN_COMMAND, android.os.Process.myPid(), android.os.Process.myUid()) == PackageManager.PERMISSION_GRANTED
        }
    }

    /** Check if Termux has external apps enabled by running a quick echo test. */
    suspend fun isExternalAppsEnabled(): Boolean = withContext(Dispatchers.IO) {
        try {
            val result = executeRaw("echo", listOf("termux_bridge_test_ok"), timeoutMs = TerminalConfig.CONNECTION_TEST_TIMEOUT_MS)
            result.isSuccess && result.stdout.contains("termux_bridge_test_ok")
        } catch (e: Exception) { false }
    }

    /** Execute a command in Termux. Returns unified [ShellExecutionResult]. */
    suspend fun execute(
        command: String,
        arguments: List<String> = emptyList(),
        workdir: String? = null,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult = executeRaw(command, arguments, workdir, timeoutMs)

    /**
     * Execute a full shell command string in Termux.
     * With streaming callback: falls back to SmartShell for line-by-line output.
     * Without callback: uses Termux IPC (RUN_COMMAND Intent).
     */
    suspend fun executeShell(
        shellCommand: String,
        workdir: String? = null,
        timeoutMs: Long = TerminalConfig.AGENT_TIMEOUT_MS,
        onOutput: ((String) -> Unit)? = null
    ): ShellExecutionResult {
        if (onOutput != null) {
            // Streaming mode: use SmartShell's local execution with line callbacks
            return executeShellLocal(shellCommand, workdir, timeoutMs, onOutput)
        }
        return executeRaw(
            command = "/data/data/com.termux/files/usr/bin/sh",
            arguments = listOf("-c", shellCommand),
            workdir = workdir,
            timeoutMs = timeoutMs
        )
    }

    /** Execute shell command locally with real-time streaming via SmartShell. */
    private suspend fun executeShellLocal(
        shellCommand: String,
        workdir: String? = null,
        timeoutMs: Long = TerminalConfig.AGENT_TIMEOUT_MS,
        onOutput: (String) -> Unit
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val startMs = System.currentTimeMillis()
        try {
            val pb = ProcessBuilder("/data/data/com.termux/files/usr/bin/sh", "-c", shellCommand)
            if (workdir != null) pb.directory(java.io.File(workdir))
            pb.environment()["HOME"] = "/data/data/com.termux/files/home"
            pb.environment()["PATH"] = "/data/data/com.termux/files/usr/bin:/usr/bin:/bin"
            pb.environment()["TERM"] = "xterm-256color"

            val process = pb.start()
            val stdoutSb = StringBuilder(); val stderrSb = StringBuilder()

            val outThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        r.lines().forEach { l -> val ln = "$l\n"; stdoutSb.append(ln); onOutput(ln) }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "termux-stream-out" }

            val errThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { r ->
                        r.lines().forEach { l -> val ln = "$l\n"; stderrSb.append(ln); onOutput("\u001b[31m$ln\u001b[0m") }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "termux-stream-err" }

            outThread.start(); errThread.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                runCatching { outThread.join(1000) }; runCatching { errThread.join(1000) }
                return@withContext ShellExecutionResult(
                    stdoutSb.toString().trim(),
                    stderrSb.toString().trim() + "\n⏱ Timed out after ${timeoutMs}ms",
                    -1, false,
                    backend = ExecutionBackend.SMART_SHELL,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            runCatching { outThread.join(2000) }; runCatching { errThread.join(2000) }
            ShellExecutionResult(stdoutSb.toString().trim(), stderrSb.toString().trim(), process.exitValue(),
                backend = ExecutionBackend.SMART_SHELL,
                durationMs = System.currentTimeMillis() - startMs)
        } catch (e: Exception) {
            ShellExecutionResult("", "Error: ${e.message}", -1, false,
                backend = ExecutionBackend.SMART_SHELL,
                durationMs = System.currentTimeMillis() - startMs)
        }
    }

    /** Convenience methods — all return unified ShellExecutionResult */
    suspend fun installPackage(packageName: String) =
        executeShell("pkg install -y $packageName 2>&1", timeoutMs = TerminalConfig.INSTALL_TIMEOUT_MS)

    suspend fun updatePackages() =
        executeShell("pkg update -y && pkg upgrade -y 2>&1", timeoutMs = TerminalConfig.AGENT_TIMEOUT_MS)

    suspend fun runPython(script: String) =
        executeShell("python3 -c '$script' 2>&1", timeoutMs = TerminalConfig.DEFAULT_TIMEOUT_MS)

    suspend fun pipInstall(packages: List<String>) =
        executeShell("pip install ${packages.joinToString(" ")} 2>&1", timeoutMs = TerminalConfig.INSTALL_TIMEOUT_MS)

    suspend fun git(vararg args: String, workdir: String? = null) =
        executeRaw("/data/data/com.termux/files/usr/bin/git", args.toList(), workdir, TerminalConfig.DEFAULT_TIMEOUT_MS)

    suspend fun npm(vararg args: String, workdir: String? = null) =
        executeRaw("/data/data/com.termux/files/usr/bin/npm", args.toList(), workdir, TerminalConfig.INSTALL_TIMEOUT_MS)

    // ── Core execution ─────────────────────────────────────────────────────

    private suspend fun executeRaw(
        command: String,
        arguments: List<String> = emptyList(),
        workdir: String? = null,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        if (!isTermuxInstalled()) {
            return@withContext ShellExecutionResult("", "Termux is not installed.", -1, false,
                backend = ExecutionBackend.SMART_SHELL)
        }

        if (!receiverRegistered.get()) {
            synchronized(this) {
                if (!receiverRegistered.get()) registerReceiver()
            }
        }

        val requestId = requestIdCounter.incrementAndGet()
        val deferred = CompletableDeferred<ShellExecutionResult>()
        pendingRequests[requestId] = deferred

        try {
            val intent = Intent().apply {
                component = android.content.ComponentName(TERMUX_PACKAGE, RUN_COMMAND_SERVICE)
                putExtra(EXTRA_COMMAND_PATH, command)
                putExtra(EXTRA_ARGUMENTS, arguments.toTypedArray())
                if (workdir != null) putExtra(EXTRA_WORKDIR, workdir)
                putExtra(EXTRA_BACKGROUND, false)

                val resultIntent = Intent(ACTION_RESULT).apply {
                    putExtra("REQUEST_ID", requestId)
                    setPackage(context.packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M)
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                else PendingIntent.FLAG_UPDATE_CURRENT

                putExtra(EXTRA_PENDING_INTENT, PendingIntent.getBroadcast(context, requestId, resultIntent, flags))
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION") context.startService(intent)
            }

            val result = withTimeoutOrNull(timeoutMs) { deferred.await() }
            if (result != null) result
            else {
                deferred.complete(ShellExecutionResult("", "Timed out after ${timeoutMs}ms", -1, false, backend = ExecutionBackend.TERMUX))
                pendingRequests.remove(requestId)
                ShellExecutionResult("", "Timed out after ${timeoutMs}ms", -1, false, backend = ExecutionBackend.TERMUX)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Execute failed: ${e.message}")
            pendingRequests.remove(requestId)
            ShellExecutionResult("", "Termux error: ${e.message}", -1, false, backend = ExecutionBackend.TERMUX)
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
                    deferred.complete(ShellExecutionResult(
                        stdout, if (err != null) "$stderr\n$err" else stderr, exitCode,
                        backend = ExecutionBackend.TERMUX
                    ))
                } else {
                    deferred.complete(ShellExecutionResult(
                        intent.getStringExtra(EXTRA_STDOUT) ?: "",
                        intent.getStringExtra(EXTRA_STDERR) ?: "",
                        intent.getIntExtra(EXTRA_EXIT_CODE, -1),
                        backend = ExecutionBackend.TERMUX
                    ))
                }
            } catch (e: Exception) {
                deferred.complete(ShellExecutionResult("", "Error: ${e.message}", -1, false, backend = ExecutionBackend.TERMUX))
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
        } catch (e: Exception) { Log.e(TAG, "Register failed: ${e.message}") }
    }

    fun unregisterReceiver() {
        if (receiverRegistered.compareAndSet(true, false)) {
            try { context.unregisterReceiver(receiver) }
            catch (e: Exception) { Log.w(TAG, "Unregister error: ${e.message}") }
        }
    }

    private fun isPackageInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0); true
    } catch (e: PackageManager.NameNotFoundException) { false }
}
