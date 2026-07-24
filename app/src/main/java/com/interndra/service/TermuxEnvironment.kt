package com.interndra.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * TermuxEnvironment — Manages the embedded Termux environment lifecycle
 * and provides the correct shell execution context based on available
 * privileges (Shizuku ADB vs proot fallback).
 *
 * ## Architecture
 * This class sits BETWEEN the app's AI/terminal layer and the actual shell
 * execution backends. It provides:
 *
 * 1. **Termux mode (default)** — Full Linux environment with bash, apt,
 *    python, git, node, pip. Uses the embedded bootstrap.
 * 2. **Shizuku mode (elevated)** — ADB shell (UID 2000) for system-level
 *    commands (pm install, settings put, etc.). Switches automatically
 *    when the AI detects a system-level command.
 * 3. **Hybrid mode** — Both available, AI chooses based on command.
 *
 * ## Environment Variables
 * When running in Termux mode, these env vars are set:
 * - PREFIX=<bootstrap_path>/usr
 * - HOME=<bootstrap_path>/home
 * - PATH=$PREFIX/bin:/system/bin:/system/xbin
 * - LD_PRELOAD=$PREFIX/lib/libtermux-exec.so
 * - TERM=xterm-256color
 *
 * ## Mode Switching
 * The AI can switch between modes:
 * - "switch mode to termux" → use embedded Termux
 * - "switch mode to shizuku" → use Shizuku ADB shell
 * - "run as root" → use Shizuku root mode
 */
class TermuxEnvironment(
    private val context: Context,
    private val shizukuShell: ShizukuShell,
    private val installer: TermuxBootstrapInstaller? = null,
    private val scope: CoroutineScope? = null
) {
    companion object {
        private const val TAG = "TermuxEnv"

        // Default shell to use inside the Termux environment
        private const val TERMUX_SHELL = "bash"

        // Health check interval
        private const val HEALTH_CHECK_INTERVAL_MS = 30_000L  // 30 seconds

        /** The path where Termux bootstrap is installed when Shizuku is available. */
        const val TERMUX_PREFIX = TermuxBootstrapInstaller.SHIZUKU_PREFIX

        /** The app-internal path for proot fallback. */
        const val PROOT_PREFIX = "termux"
    }

    // ── Execution Mode ──────────────────────────────────────────────────

    /** Available execution modes. */
    enum class ExecMode(val label: String, val emoji: String) {
        TERMUX("Termux", "🐧"),      // Embedded Termux (default)
        SHIZUKU("Shizuku ADB", "🔑"), // Shizuku ADB shell (elevated)
        ROOT("Root Shell", "🛡️"),    // Shizuku root (UID 0)
        FALLBACK("Fallback", "⚙️")    // Sandboxed ShellExecutor
    }

    /** Information about the current environment state. */
    data class EnvInfo(
        val mode: ExecMode = ExecMode.TERMUX,
        val bootstrapInstalled: Boolean = false,
        val bootstrapPrefix: String = "",
        val shizukuAvailable: Boolean = false,
        val shizukuAuthorized: Boolean = false,
        val shizukuUid: Int = -1,
        val aptAvailable: Boolean = false,
        val bashAvailable: Boolean = false,
        val installedPackages: List<String> = emptyList(),
        val error: String? = null
    )

    // ── State ───────────────────────────────────────────────────────────

    private val _mode = MutableStateFlow(ExecMode.FALLBACK)
    val mode: StateFlow<ExecMode> = _mode.asStateFlow()

    private val _info = MutableStateFlow(EnvInfo())
    val info: StateFlow<EnvInfo> = _info.asStateFlow()

    private val _isInstalling = MutableStateFlow(false)
    val isInstalling: StateFlow<Boolean> = _isInstalling.asStateFlow()

    private val _installProgress = MutableStateFlow("")
    val installProgress: StateFlow<String> = _installProgress.asStateFlow()

    private val internalScope: CoroutineScope =
        scope ?: CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var healthCheckJob: Job? = null
    private val installMutex = Mutex()

    // Cached prefix paths
    @Volatile private var prefixPath: String = ""
    @Volatile private var modePrev: ExecMode = ExecMode.FALLBACK

    // Flag: is the environment in the process of switching modes?
    private val switching = AtomicBoolean(false)

    /** Bootstrap install directories (determined at init). */
    data class PrefixPaths(
        val shizuku: String = TermuxBootstrapInstaller.SHIZUKU_PREFIX,
        val proot: String
    )

    private val prefixes: PrefixPaths

    /** Store the persistent shell integration for mode switching. */
    @Volatile private var activeTermuxShell: PersistentShell? = null
    @Volatile private var activeProotProcess: Process? = null

    init {
        prefixes = PrefixPaths(
            proot = File(context.filesDir, "termux").absolutePath
        )
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PUBLIC API
    // ══════════════════════════════════════════════════════════════════════

    /** Initialize the environment — detect what's available. */
    suspend fun init() {
        Log.i(TAG, "Initializing TermuxEnvironment...")
        refreshStatus()
        startHealthCheck()
    }

    /** Refresh the environment status. */
    suspend fun refreshStatus() {
        withContext(Dispatchers.IO) {
            val shizukuAvail = try { shizukuShell.isElevatedAvailable } catch (_: Exception) { false }
            val shizukuAuth = try { shizukuShell.manager.isAuthorized() } catch (_: Exception) { false }
            val shizukuUid = try { shizukuShell.manager.shizukuUid } catch (_: Exception) { -1 }

            val bootstrapInstalled = installer?.isInstalled() ?: isInstalledCheap()

            val activePrefix = when {
                bootstrapInstalled && installer?.isShizukuInstalled() == true -> prefixes.shizuku
                bootstrapInstalled && (installer?.isProotInstalled() ?: isProotInstalledCheap()) -> prefixes.proot
                else -> ""
            }

            val bashAvail = if (activePrefix.isNotBlank()) {
                checkBashExists(activePrefix)
            } else false

            val aptAvail = if (bashAvail) {
                checkAptExists(activePrefix)
            } else false

            // Determine best mode — Termux is default (user's requirement)
            // AI can switch to Shizuku for system-level commands
            val bestMode = when {
                bashAvail -> ExecMode.TERMUX           // 🐧 Default: embedded Termux
                shizukuAvail && shizukuAuth -> ExecMode.SHIZUKU // 🔑 Fallback: Shizuku ADB
                shizukuAvail && shizukuAuth && shizukuUid == 0 -> ExecMode.ROOT // 🛡️ Root
                else -> ExecMode.FALLBACK              // ⚙️ Sandboxed
            }

            // Auto-detect installed packages (only on-demand, not cached)
            // Full dpkg listing is too expensive for every 30s health check
            val installedPkgs = if (bashAvail) {
                runCatching { detectInstalledPackages(activePrefix, quick = true) }
                    .getOrDefault(emptyList())
            } else emptyList()

            prefixPath = activePrefix
            _mode.value = bestMode
            _info.value = EnvInfo(
                mode = bestMode,
                bootstrapInstalled = bootstrapInstalled,
                bootstrapPrefix = activePrefix,
                shizukuAvailable = shizukuAvail,
                shizukuAuthorized = shizukuAuth,
                shizukuUid = shizukuUid,
                aptAvailable = aptAvail,
                bashAvailable = bashAvail,
                installedPackages = installedPkgs
            )

            Log.i(TAG, "Status: mode=$bestMode, prefix=$activePrefix, " +
                  "bash=$bashAvail, apt=$aptAvail, pkgs=${installedPkgs.size}")
        }
    }

    /** Get current execution mode. */
    fun getMode(): ExecMode = _mode.value

    /** Check if we're in an elevated mode (Shizuku/Root). */
    fun isElevated(): Boolean = _mode.value in listOf(ExecMode.SHIZUKU, ExecMode.ROOT)

    /** Check if we have a full Termux environment. */
    fun hasTermux(): Boolean = _info.value.bashAvailable && _info.value.bootstrapInstalled

    /** Get the current bootstrap prefix path. */
    fun getPrefix(): String = prefixPath

    /** Get the env vars for the current mode. */
    fun getEnvironmentVars(): Map<String, String> {
        return when (_mode.value) {
            ExecMode.TERMUX -> getTermuxEnvVars(prefixPath)
            ExecMode.SHIZUKU, ExecMode.ROOT -> getShizukuEnvVars()
            ExecMode.FALLBACK -> getFallbackEnvVars()
        }
    }

    /** Get environment variables for Termux mode. */
    fun getTermuxEnvVars(prefix: String = prefixPath): Map<String, String> {
        if (prefix.isBlank()) return getFallbackEnvVars()
        return mapOf(
            "PREFIX" to "$prefix/usr",
            "HOME" to "$prefix/home",
            "PATH" to "$prefix/usr/bin:$prefix/usr/bin/applets:/system/bin:/system/xbin:/sbin:/vendor/bin",
            "LD_PRELOAD" to "$prefix/usr/lib/libtermux-exec.so",
            "TERM" to "xterm-256color",
            "SHELL" to "$prefix/usr/bin/bash",
            "TMPDIR" to "$prefix/usr/tmp",
            "LANG" to "C.UTF-8",
            "LC_ALL" to "C.UTF-8",
            "TERMUX_APP_PACKAGE" to context.packageName,
            "TERMUX_VERSION" to "0.118.0",
            "TERMUX_APK_RELEASE" to "F-Droid",
            "TERMUX_MAIN_PACKAGE_FORMAT" to "apt",
            "INTERNDRA_TERMUX" to "1"
        )
    }

    /** Get environment variables for Shizuku mode. */
    private fun getShizukuEnvVars(): Map<String, String> {
        return mapOf(
            "HOME" to "/",
            "PATH" to "/system/bin:/system/xbin:/sbin:/vendor/bin:/data/local/tmp",
            "TERM" to "xterm-256color",
            "SHELL" to "/system/bin/sh",
            "COLUMNS" to "120",
            "LINES" to "40"
        )
    }

    /** Get environment variables for fallback (sandboxed) mode. */
    private fun getFallbackEnvVars(): Map<String, String> {
        return mapOf(
            "HOME" to context.filesDir.absolutePath,
            "PATH" to "/system/bin",
            "TERM" to "xterm-256color",
            "SHELL" to "/system/bin/sh"
        )
    }

    /**
     * Build a command line that runs the given command inside the Termux
     * environment, handling env vars correctly.
     */
    fun wrapTermuxCommand(command: String, prefix: String = prefixPath): String {
        if (prefix.isBlank()) return command
        val env = getTermuxEnvVars(prefix)
        val envStr = env.entries.joinToString(" ") { (k, v) ->
            "${k}='${v.replace("'", "'\\''")}'"
        }
        return "env $envStr $prefix/usr/bin/bash -l -c '${command.replace("'", "'\\''")}' 2>&1"
    }

    /**
     * Build the command for Shizuku execution mode.
     */
    fun wrapShizukuCommand(command: String): String {
        val env = getShizukuEnvVars()
        val envStr = env.entries.joinToString(" ") { (k, v) ->
            "${k}='${v.replace("'", "'\\''")}'"
        }
        return "env $envStr $command"
    }

    // ══════════════════════════════════════════════════════════════════════
    //  MODE SWITCHING
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Switch the execution mode manually.
     * Used by the AI when the user says "switch to Shizuku" or a command
     * requires elevated privileges.
     */
    suspend fun switchMode(targetMode: ExecMode): Boolean {
        if (targetMode == _mode.value) return true
        if (switching.getAndSet(true)) {
            Log.w(TAG, "Already switching modes")
            return false
        }

        try {
            Log.i(TAG, "Switching mode: ${_mode.value} → $targetMode")
            _installProgress.value = "Switching to ${targetMode.label}..."

            when (targetMode) {
                ExecMode.TERMUX -> {
                    if (!installer?.isInstalled() ?: false) {
                        // Auto-install if needed
                        val result = installer?.install { progress ->
                            _installProgress.value = progress
                        }
                        if (result?.success != true) {
                            _installProgress.value = "Failed to install Termux: ${result?.error}"
                            return false
                        }
                    }
                    prefixPath = when {
                        installer?.isShizukuInstalled() == true -> prefixes.shizuku
                        else -> prefixes.proot
                    }
                }
                ExecMode.SHIZUKU -> {
                    if (!shizukuShell.isElevatedAvailable) {
                        _installProgress.value = "Shizuku not available"
                        return false
                    }
                }
                ExecMode.ROOT -> {
                    if (shizukuShell.manager.shizukuUid != 0) {
                        _installProgress.value = "Root access not available"
                        return false
                    }
                }
                ExecMode.FALLBACK -> {
                    // Always available
                }
            }

            _mode.value = targetMode
            _installProgress.value = "Mode: ${targetMode.emoji} ${targetMode.label}"
            refreshStatus()
            return true

        } finally {
            switching.set(false)
        }
    }

    /**
     * Smart mode selection: chooses the best mode for a given command.
     * - System commands (pm, settings, am, dumpsys, input) → Shizuku mode
     * - Package management (pkg, apt, pip, npm) → Termux mode
     * - Development (git, python, node, make) → Termux mode
     * - File operations (cp, mv, ls, find) → current mode
     */
    fun suggestModeForCommand(command: String): ExecMode {
        val lower = command.lowercase().trim()

        // Shizuku-required commands
        val systemCommands = listOf(
            "pm ", "settings ", "am ", "dumpsys ", "input ", "wm ", "svc ",
            "content ", "media ", "cmd ", "service ", "appops ",
            "monkey ", "uiautomator ", "dpm ", "installd "
        )
        for (sysCmd in systemCommands) {
            if (lower.startsWith(sysCmd)) return ExecMode.SHIZUKU
        }

        // Termux-required commands
        if (lower.startsWith("pkg ") || lower.startsWith("apt ") ||
            lower.startsWith("dpkg ") || lower.startsWith("pip ") ||
            lower.startsWith("pip3 ") || lower.startsWith("npm ") ||
            lower.startsWith("npx ") || lower.startsWith("yarn ") ||
            lower.startsWith("python") || lower.startsWith("python3") ||
            lower.startsWith("node ") || lower.startsWith("git ") ||
            lower.startsWith("make ") || lower.startsWith("gcc ") ||
            lower.startsWith("g++ ") || lower.startsWith("rustc ") ||
            lower.startsWith("cargo ") || lower.startsWith("go ") ||
            lower.startsWith("julia ") || lower.startsWith("perl ") ||
            lower.startsWith("ruby ") || lower.startsWith("php ") ||
            lower.startsWith("gem ") || lower.startsWith("bundle ") ||
            lower.startsWith("bun ") || lower.startsWith("deno ") ||
            lower.startsWith("pandoc ")) {
            return ExecMode.TERMUX
        }

        // Return current mode for everything else
        return _mode.value
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EXECUTION HELPERS
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Build a shell command that runs in the correct environment.
     * This is the MAIN method used by TerminalAgent and HybridExecutionEngine.
     */
    fun buildExecutionCommand(
        command: String,
        preferredMode: ExecMode? = null
    ): ExecutionRequest {
        val actualMode = preferredMode ?: suggestModeForCommand(command)

        return when (actualMode) {
            ExecMode.TERMUX -> {
                if (prefixPath.isNotBlank() && 
                    (installer?.isShizukuInstalled() == true || installer?.isProotInstalled() == true)) {
                    ExecutionRequest(
                        command = wrapTermuxCommand(command, prefixPath),
                        mode = ExecMode.TERMUX,
                        useShizuku = installer?.isShizukuInstalled() == true && shizukuShell.isElevatedAvailable,
                        useProot = !shizukuShell.isElevatedAvailable && prefixPath.startsWith(context.filesDir.absolutePath),
                        envVars = getTermuxEnvVars(prefixPath),
                        workdir = "$prefixPath/home"
                    )
                } else {
                    // Fallback to Shizuku or basic shell
                    if (shizukuShell.isElevatedAvailable) {
                        ExecutionRequest(
                            command = command,
                            mode = ExecMode.SHIZUKU,
                            useShizuku = true,
                            envVars = getShizukuEnvVars()
                        )
                    } else {
                        ExecutionRequest(
                            command = command,
                            mode = ExecMode.FALLBACK,
                            envVars = getFallbackEnvVars()
                        )
                    }
                }
            }
            ExecMode.SHIZUKU, ExecMode.ROOT -> {
                ExecutionRequest(
                    command = command,
                    mode = actualMode,
                    useShizuku = true,
                    envVars = getShizukuEnvVars()
                )
            }
            ExecMode.FALLBACK -> {
                ExecutionRequest(
                    command = command,
                    mode = ExecMode.FALLBACK,
                    envVars = getFallbackEnvVars()
                )
            }
        }
    }

    /** Execution request data class. */
    data class ExecutionRequest(
        val command: String,
        val mode: ExecMode,
        val useShizuku: Boolean = false,
        val useProot: Boolean = false,
        val envVars: Map<String, String> = emptyMap(),
        val workdir: String? = null
    )

    // ══════════════════════════════════════════════════════════════════════
    //  PACKAGE MANAGEMENT
    // ══════════════════════════════════════════════════════════════════════

    /** Install packages via apt/pkg. */
    suspend fun installPackages(
        packages: List<String>,
        progress: ((String) -> Unit)? = null
    ): Boolean = installMutex.withLock {
        if (!hasTermux()) {
            // Try to install Termux first
            val result = installer?.install { p -> progress?.invoke(p) }
            if (result?.success != true) {
                progress?.invoke("❌ Cannot install packages: Termux not available")
                return@withLock false
            }
        }

        progress?.invoke("📦 Installing: ${packages.joinToString(", ")}...")

        val installCmd = packages.joinToString(" ") { pkg ->
            "pkg install -y '$pkg' 2>&1 || apt-get install -y '$pkg' 2>&1"
        }
        val termuxCmd = buildExecutionCommand("$installCmd && echo 'DONE'")
        val result = if (termuxCmd.useShizuku) {
            shizukuShell.executeBlocking(termuxCmd.command, 300_000)
        } else {
            ShellExecutor.runAsync(termuxCmd.command, 300_000)
        }

        if (result.isSuccess) {
            progress?.invoke("✅ Installed: ${packages.joinToString(", ")}")
            refreshStatus()
            true
        } else {
            progress?.invoke("❌ Install failed: ${result.stderr.take(200)}")
            false
        }
    }

    /** Convenience: install common dev packages. */
    suspend fun installCommonPackages(progress: ((String) -> Unit)? = null): Boolean {
        return installPackages(listOf(
            "python", "git", "nodejs"
        ), progress)
    }

    /** Check if a specific package is installed. */
    suspend fun isPackageInstalled(packageName: String): Boolean {
        if (!hasTermux()) return false
        val prefix = prefixPath
        val cmd = buildExecutionCommand("dpkg -l '$packageName' 2>/dev/null | grep -q '^ii' && echo 'yes' || echo 'no'")
        val result = if (cmd.useShizuku) {
            shizukuShell.executeBlocking(cmd.command, 15_000)
        } else {
            ShellExecutor.runAsync(cmd.command, 15_000)
        }
        return result.isSuccess && result.stdout.trim() == "yes"
    }

    /** List installed packages. When quick=true, only checks for well-known packages. */
    private suspend fun detectInstalledPackages(prefix: String, quick: Boolean = false): List<String> {
        if (!File(prefix, "usr/var/lib/dpkg/status").exists() &&
            !File(prefix, "usr/var/lib/dpkg/status").exists()) {
            return emptyList()
        }
        return try {
            if (quick) {
                // Quick check: just look for well-known binaries instead of full dpkg listing
                val knownBinaries = listOf("python3", "git", "node", "npm")
                val result = mutableListOf<String>()
                for (bin in knownBinaries) {
                    val checkCmd = buildExecutionCommand("command -v '$bin' 2>/dev/null && echo 'yes' || echo 'no'")
                    val checkResult = if (checkCmd.useShizuku) {
                        shizukuShell.executeBlocking(checkCmd.command, 5_000)
                    } else {
                        ShellExecutor.runAsync(checkCmd.command, 5_000)
                    }
                    if (checkResult.isSuccess && checkResult.stdout.trim() == "yes") {
                        result.add(bin)
                    }
                }
                result.sorted()
            } else {
                // Full dpkg listing (slower, for manual refresh)
                val cmd = buildExecutionCommand("dpkg -l 2>/dev/null | grep '^ii' | awk '{print \$2}' | head -50")
                val result = if (cmd.useShizuku) {
                    shizukuShell.executeBlocking(cmd.command, 15_000)
                } else {
                    ShellExecutor.runAsync(cmd.command, 15_000)
                }
                if (result.isSuccess) {
                    result.stdout.lines().filter { it.isNotBlank() }.sorted()
                } else emptyList()
            }
        } catch (_: Exception) { emptyList() }
    }

    /** Full package listing (slower, called on demand). */
    suspend fun listAllPackages(): List<String> {
        return detectInstalledPackages(prefixPath, quick = false)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  HEALTH CHECKS
    // ══════════════════════════════════════════════════════════════════════

    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = internalScope.launch {
            while (true) {
                delay(HEALTH_CHECK_INTERVAL_MS)
                try {
                    refreshStatus()
                } catch (e: Exception) {
                    Log.w(TAG, "Health check error: ${e.message}")
                }
            }
        }
    }

    private fun checkBashExists(prefix: String): Boolean {
        return try {
            // For Shizuku-installed
            val result = shizukuShell.executeBlocking(
                "test -f '$prefix/usr/bin/bash' && echo 'yes' || echo 'no'",
                5_000
            )
            if (result.isSuccess && result.stdout.trim() == "yes") return true

            // For proot-installed
            File(prefix, "usr/bin/bash").exists()
        } catch (_: Exception) { false }
    }

    private fun checkAptExists(prefix: String): Boolean {
        return try {
            val result = shizukuShell.executeBlocking(
                "test -f '$prefix/usr/bin/apt-get' && echo 'yes' || echo 'no'",
                5_000
            )
            if (result.isSuccess && result.stdout.trim() == "yes") return true
            File(prefix, "usr/bin/apt-get").exists()
        } catch (_: Exception) { false }
    }

    private fun isInstalledCheap(): Boolean {
        return try {
            val result = shizukuShell.executeBlocking(
                "test -d '${prefixes.shizuku}/usr/bin' && echo 'yes' || echo 'no'",
                5_000
            )
            result.isSuccess && result.stdout.trim() == "yes"
        } catch (_: Exception) {
            File(prefixes.proot, "usr/bin").exists()
        }
    }

    private fun isProotInstalledCheap(): Boolean {
        return File(prefixes.proot, "usr/bin/bash").exists()
    }

    // ══════════════════════════════════════════════════════════════════════
    //  SHUTDOWN
    // ══════════════════════════════════════════════════════════════════════

    /** Shutdown the environment manager. */
    fun shutdown() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        activeTermuxShell = null
        activeProotProcess?.destroy()
        activeProotProcess = null
        Log.i(TAG, "TermuxEnvironment shut down")
    }

    /** Get a human-readable summary of the current environment. */
    fun getSummary(): String {
        val info = _info.value
        return buildString {
            appendLine("🐧 **Termux Environment**")
            appendLine("- Mode: ${info.mode.emoji} ${info.mode.label}")
            if (info.bootstrapPrefix.isNotBlank()) {
                appendLine("- Prefix: `${info.bootstrapPrefix}`")
            }
            appendLine("- Bash: ${if (info.bashAvailable) "✅" else "❌"}")
            appendLine("- APT/pkg: ${if (info.aptAvailable) "✅" else "❌"}")
            if (info.shizukuAvailable) {
                appendLine("- Shizuku: ${if (info.shizukuAuthorized) "✅ UID ${info.shizukuUid}" else "❌ Not authorized"}")
            }
            if (info.installedPackages.isNotEmpty()) {
                appendLine("- Packages (${info.installedPackages.size}): `${info.installedPackages.take(10).joinToString("`, `")}`${if (info.installedPackages.size > 10) "…" else ""}")
            }
        }
    }
}
