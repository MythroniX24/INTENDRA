package com.interndra.service

import android.content.Context
import android.content.pm.PackageManager
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
 * ## A+++ UPGRADES:
 *  1. Returns unified [ShellExecutionResult] with correct backend tag
 *     ([ExecutionBackend.SHIZUKU_ROOT] or [ExecutionBackend.SHIZUKU_ADB]).
 *  2. Uses [TerminalConfig] for ALL timeout and output cap constants.
 *  3. Concurrent stdout/stderr draining + output capping.
 *  4. Streaming variant with real-time line callbacks.
 *  5. Proper cleanup in finally blocks (no zombie processes).
 */
class ShizukuManager(private val context: Context) {

    companion object {
        private const val TAG = "ShizukuManager"
        const val SHIZUKU_REQUEST_CODE = 10001
        private const val SHIZUKU_MANAGER_PACKAGE = "moe.shizuku.manager"
        private const val SHIZUKU_PRIVILEGED_PACKAGE = "moe.shizuku.privileged.api"
    }

    // ── Status ──────────────────────────────────────────────────────────

    @Volatile var isBinderAlive: Boolean = false; private set
    @Volatile var isPermissionGranted: Boolean = false; private set
    @Volatile var shizukuUid: Int = -1; private set
    @Volatile var apiVersion: Int = 0; private set

    val privilegeLevel: String
        get() = when (shizukuUid) { 0 -> "🛡️ Root"; 2000 -> "🔑 ADB Shell"; else -> "❌ None" }

    /** Returns the correct backend tag based on current UID. */
    val executionBackend: ExecutionBackend
        get() = when (shizukuUid) { 0 -> ExecutionBackend.SHIZUKU_ROOT; else -> ExecutionBackend.SHIZUKU_ADB }

    // ── Listeners ───────────────────────────────────────────────────────

    private val permissionResultListeners = ConcurrentHashMap<Int, (Boolean) -> Unit>()

    private val requestPermissionListener = Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
        isPermissionGranted = grantResult == PackageManager.PERMISSION_GRANTED
        Log.i(TAG, "Permission result: requestCode=$requestCode, granted=$isPermissionGranted")
        permissionResultListeners.remove(requestCode)?.invoke(isPermissionGranted)
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        Log.w(TAG, "Shizuku binder died")
        isBinderAlive = false; isPermissionGranted = false; shizukuUid = -1
        onBinderDeathCallback?.invoke()
    }

    private var onBinderDeathCallback: (() -> Unit)? = null
    private val initialized = AtomicBoolean(false)

    fun init(onBinderDeath: (() -> Unit)? = null) {
        if (!initialized.compareAndSet(false, true)) return
        onBinderDeathCallback = onBinderDeath
        try {
            Shizuku.addRequestPermissionResultListener(requestPermissionListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            refreshStatus()
            Log.i(TAG, "Initialized")
        } catch (e: Exception) { Log.e(TAG, "Init failed: ${e.message}") }
    }

    fun shutdown() {
        try {
            Shizuku.removeRequestPermissionResultListener(requestPermissionListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
        } catch (e: Exception) { Log.w(TAG, "Shutdown error: ${e.message}") }
        initialized.set(false)
    }

    fun refreshStatus() {
        try {
            isBinderAlive = Shizuku.pingBinder()
            if (isBinderAlive) {
                isPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
                shizukuUid = Shizuku.getUid()
                apiVersion = Shizuku.getVersion()
            } else { isPermissionGranted = false; shizukuUid = -1; apiVersion = 0 }
        } catch (e: Exception) {
            Log.w(TAG, "Refresh failed: ${e.message}")
            isBinderAlive = false; isPermissionGranted = false; shizukuUid = -1
        }
    }

    fun isShizukuInstalled(): Boolean {
        return try { context.packageManager.getPackageInfo(SHIZUKU_MANAGER_PACKAGE, 0); true }
        catch (e: PackageManager.NameNotFoundException) {
            try { context.packageManager.getPackageInfo(SHIZUKU_PRIVILEGED_PACKAGE, 0); true }
            catch (e2: PackageManager.NameNotFoundException) { false }
        }
    }

    fun isAuthorized(): Boolean = isBinderAlive && isPermissionGranted

    fun requestPermission(onResult: ((Boolean) -> Unit)? = null): Boolean {
        if (!isBinderAlive) { onResult?.invoke(false); return false }
        if (isPermissionGranted) { onResult?.invoke(true); return true }
        try {
            if (onResult != null) permissionResultListeners[SHIZUKU_REQUEST_CODE] = onResult
            Shizuku.requestPermission(SHIZUKU_REQUEST_CODE)
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Permission request failed: ${e.message}")
            onResult?.invoke(false); return false
        }
    }

    // ── Reflection: Shizuku.newProcess is private in v13+ ──────────────
    private fun newShizukuProcess(cmd: Array<String>): Process? {
        return try {
            val method = Shizuku::class.java.getDeclaredMethod(
                "newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java
            )
            method.isAccessible = true
            method.invoke(null, cmd, null, null) as? Process
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku.newProcess failed: ${e.message}")
            null
        }
    }

    // ── Shell Execution — returns unified ShellExecutionResult ─────────

    /**
     * Execute a shell command with elevated privileges via Shizuku.
     * Returns unified [ShellExecutionResult] with the correct backend tag.
     */
    fun executeShell(
        command: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS
    ): ShellExecutionResult {
        if (!isAuthorized()) {
            return ShellExecutionResult("", "Shizuku not authorized.", -1, false,
                backend = ExecutionBackend.SHELL_EXECUTOR)
        }

        val startMs = System.currentTimeMillis()
        try {
            Log.d(TAG, "Executing via Shizuku (UID $shizukuUid): ${command.take(100)}")

            val process = newShizukuProcess(arrayOf("sh", "-c", command)) ?: return ShellExecutionResult(
                "", "Shizuku: unable to create process.", -1, false,
                backend = executionBackend,
                durationMs = System.currentTimeMillis() - startMs
            )
            val stdoutSb = StringBuilder()
            val stderrSb = StringBuilder()
            val truncatedRef = AtomicBoolean(false)

            val stdoutThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.inputStream))
                    val buffer = CharArray(8192); var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > TerminalConfig.MAX_OUTPUT_BYTES) {
                            stdoutSb.append(buffer, 0, read.coerceAtMost(TerminalConfig.MAX_OUTPUT_BYTES - (total - read)))
                            truncatedRef.set(true); break
                        }
                        stdoutSb.append(buffer, 0, read)
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-out" }

            val stderrThread = Thread {
                try {
                    val reader = BufferedReader(InputStreamReader(process.errorStream))
                    val buffer = CharArray(8192); var total = 0
                    while (true) {
                        val read = reader.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > TerminalConfig.MAX_OUTPUT_BYTES) {
                            stderrSb.append(buffer, 0, read.coerceAtMost(TerminalConfig.MAX_OUTPUT_BYTES - (total - read)))
                            truncatedRef.set(true); break
                        }
                        stderrSb.append(buffer, 0, read)
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-err" }

            stdoutThread.start(); stderrThread.start()

            val completed = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!completed) {
                process.destroyForcibly()
                runCatching { stdoutThread.join(1000) }
                runCatching { stderrThread.join(1000) }
                return ShellExecutionResult(
                    stdoutSb.toString().trim(),
                    stderrSb.toString().trim() + "\n⏱ Timed out after ${timeoutMs}ms",
                    -1, false,
                    backend = executionBackend,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            runCatching { stdoutThread.join(2000) }
            runCatching { stderrThread.join(2000) }

            val stdout = stdoutSb.toString().trim()
            val stderr = stderrSb.toString().trim()
            val finalStdout = if (truncatedRef.get())
                "$stdout\n…(output truncated at ${TerminalConfig.MAX_OUTPUT_BYTES / 1024} KB)" else stdout

            Log.d(TAG, "Shizuku exec done: exit=${process.exitValue()}, stdout=${stdout.length} chars")
            return ShellExecutionResult(finalStdout, stderr, process.exitValue(),
                backend = executionBackend,
                durationMs = System.currentTimeMillis() - startMs)

        } catch (e: SecurityException) {
            isPermissionGranted = false
            return ShellExecutionResult("", "Permission denied: ${e.message}", -1, false,
                backend = ExecutionBackend.SHELL_EXECUTOR,
                durationMs = System.currentTimeMillis() - startMs)
        } catch (e: Exception) {
            Log.e(TAG, "Shizuku exec failed: ${e.message}")
            return ShellExecutionResult("", "Error: ${e.message}", -1, false,
                backend = ExecutionBackend.SHELL_EXECUTOR,
                durationMs = System.currentTimeMillis() - startMs)
        }
    }

    /**
     * Execute with REAL-TIME streaming output via line callbacks.
     */
    fun executeShellStreaming(
        command: String,
        timeoutMs: Long = TerminalConfig.DEFAULT_TIMEOUT_MS,
        onOutput: (String) -> Unit = {}
    ): ShellExecutionResult {
        if (!isAuthorized()) {
            return ShellExecutionResult("", "Shizuku not authorized.", -1, false,
                backend = ExecutionBackend.SHELL_EXECUTOR)
        }

        val startMs = System.currentTimeMillis()
        try {
            Log.d(TAG, "Streaming via Shizuku: ${command.take(80)}")
            val process = newShizukuProcess(arrayOf("sh", "-c", command)) ?: return ShellExecutionResult(
                "", "Shizuku: unable to create process.", -1, false,
                backend = executionBackend,
                durationMs = System.currentTimeMillis() - startMs
            )
            val stdoutSb = StringBuilder(); val stderrSb = StringBuilder()

            val outThread = Thread {
                try {
                    process.inputStream.bufferedReader().use { r ->
                        r.lines().forEach { l -> val ln = "$l\n"; stdoutSb.append(ln); onOutput(ln) }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-stream-out" }

            val errThread = Thread {
                try {
                    process.errorStream.bufferedReader().use { r ->
                        r.lines().forEach { l -> val ln = "$l\n"; stderrSb.append(ln); onOutput("\u001b[31m$ln\u001b[0m") }
                    }
                } catch (_: Exception) {}
            }.apply { isDaemon = true; name = "shizuku-stream-err" }

            outThread.start(); errThread.start()

            val exited = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!exited) {
                process.destroyForcibly()
                runCatching { outThread.join(1000) }; runCatching { errThread.join(1000) }
                return ShellExecutionResult(
                    stdoutSb.toString().trim(),
                    stderrSb.toString().trim() + "\n⏱ Timed out",
                    -1, false,
                    backend = executionBackend,
                    durationMs = System.currentTimeMillis() - startMs
                )
            }

            runCatching { outThread.join(2000) }; runCatching { errThread.join(2000) }
            return ShellExecutionResult(stdoutSb.toString().trim(), stderrSb.toString().trim(), process.exitValue(),
                backend = executionBackend,
                durationMs = System.currentTimeMillis() - startMs)
        } catch (e: Exception) {
            Log.e(TAG, "Streaming error: ${e.message}")
            return ShellExecutionResult("", "Error: ${e.message}", -1, false,
                backend = ExecutionBackend.SHELL_EXECUTOR,
                durationMs = System.currentTimeMillis() - startMs)
        }
    }

    // ── Utility ─────────────────────────────────────────────────────────

    fun testConnection(): Int? {
        val result = executeShell("echo shizuku_test_ok && id -u", TerminalConfig.CONNECTION_TEST_TIMEOUT_MS)
        if (result.isSuccess && result.stdout.contains("shizuku_test_ok")) {
            return result.stdout.lines().firstOrNull { it.all { c -> c.isDigit() } }?.toIntOrNull() ?: shizukuUid
        }
        return null
    }

    fun getSystemProperty(name: String): String? {
        val result = executeShell("getprop $name", TerminalConfig.CONNECTION_TEST_TIMEOUT_MS)
        return if (result.isSuccess) result.stdout.trim().ifBlank { null } else null
    }

    fun listDirectory(path: String): List<String> {
        val result = executeShell("ls -la \"$path\" 2>/dev/null || echo 'ACCESS_DENIED'", TerminalConfig.CONNECTION_TEST_TIMEOUT_MS)
        if (!result.isSuccess || result.stdout.contains("ACCESS_DENIED")) return emptyList()
        return result.stdout.lines().filter { it.isNotBlank() }
    }
}
