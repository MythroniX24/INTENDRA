package com.interndra.service

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * PRootDistroManager — Manages full Linux distributions via proot-distro.
 *
 * ## What is proot-distro?
 * proot-distro is a Termux package that provides a complete user-space Linux
 * distribution (Ubuntu, Debian, Arch, Fedora, Alpine, etc.) using PRoot
 * (user-mode chroot). It does NOT require root access.
 *
 * ## How it works
 * 1. Install `proot-distro` via `pkg install proot-distro` in Termux
 * 2. Install a distro: `proot-distro install ubuntu`
 * 3. Run commands: `proot-distro run ubuntu -- <command>`
 * 4. Login: `proot-distro login ubuntu`
 *
 * ## Supported Distros
 * - Ubuntu (24.04 LTS / 22.04 LTS)
 * - Debian (bookworm/bullseye)
 * - Arch Linux
 * - Fedora
 * - Alpine Linux
 * - Void Linux
 * - Kali Nethunter
 * - And more...
 *
 * ## Key Benefits
 * - Full `apt install <package>` — install ANY Linux package
 * - Python3, nodejs, git, docker, nginx, postgres — all work
 * - Normal linux filesystem (/etc, /var, /usr, /home)
 * - Separate from Termux environment — no package conflicts
 * - Each distro is isolated in its own directory
 *
 * ## Usage in App
 * The AI can:
 * - Check which distros are available
 * - Install a distro (e.g., "install ubuntu in termux")
 * - Run commands inside a specific distro
 * - Install packages inside a distro (apt, pip, npm)
 */
class ProotDistroManager(
    private val context: Context,
    private val termuxEnvironment: TermuxEnvironment,
    private val shizukuShell: ShizukuShell
) {
    companion object {
        private const val TAG = "ProotDistro"
        private const val PROOT_DISTRO_SCRIPT = "proot-distro"
        private const val PROOT_BINARY = "proot"

        /** Timeout for proot-distro operations. */
        private const val DISTRO_TIMEOUT_MS = 120_000L
        private const val DISTRO_INSTALL_TIMEOUT_MS = 600_000L // 10 min for install
    }

    /** Information about a proot-distro distribution. */
    data class DistroInfo(
        val name: String,                     // e.g., "ubuntu"
        val displayName: String,              // e.g., "Ubuntu 24.04 LTS"
        val isInstalled: Boolean = false,
        val installPath: String = "",
        val version: String = "",
        val error: String? = null
    )

    /** Overall proot-distro state. */
    data class ProotDistroState(
        val isAvailable: Boolean = false,           // proot-distro script exists
        val prootBinaryAvailable: Boolean = false,  // proot binary exists
        val prootDistroVersion: String = "",
        val installedDistros: List<DistroInfo> = emptyList(),
        val availableDistros: List<DistroInfo> = emptyList(), // all distros (including not installed)
        val activeDistro: String = "",               // currently active distro
        val error: String? = null
    )

    @Volatile private var cachedState: ProotDistroState? = null
    @Volatile private var lastRefreshMs: Long = 0L
    private val cacheTtlMs = 30_000L // 30 seconds cache

    /**
     * Get the current proot-distro state.
     * Uses cached state within cache TTL; refreshes otherwise.
     */
    suspend fun getState(): ProotDistroState {
        val now = System.currentTimeMillis()
        if (cachedState != null && now - lastRefreshMs < cacheTtlMs) {
            return cachedState!!
        }
        return refreshState()
    }

    /**
     * Refresh proot-distro state from the actual environment.
     */
    suspend fun refreshState(): ProotDistroState = withContext(Dispatchers.IO) {
        try {
            val prefix = termuxEnvironment.getPrefix()
            if (prefix.isBlank()) {
                cachedState = ProotDistroState(error = "Termux environment not available")
                lastRefreshMs = System.currentTimeMillis()
                return@withContext cachedState!!
            }

            val prootDistroScript = "$prefix/usr/bin/$PROOT_DISTRO_SCRIPT"
            val prootBinary = findProotBinary()

            // Check if proot-distro is installed as a package
            val isAvailable = File(prootDistroScript).exists() ||
                checkProotDistroExists()

            val prootBinAvailable = prootBinary != null

            if (!isAvailable) {
                cachedState = ProotDistroState(
                    isAvailable = false,
                    prootBinaryAvailable = prootBinAvailable,
                    error = "proot-distro not installed. Run 'pkg install proot-distro' in Termux"
                )
                lastRefreshMs = System.currentTimeMillis()
                return@withContext cachedState!!
            }

            // Get proot-distro version
            val version = getProotDistroVersion()

            // List available distros
            val available = listAvailableDistros(prefix)
            val installed = available.filter { it.isInstalled }

            cachedState = ProotDistroState(
                isAvailable = true,
                prootBinaryAvailable = prootBinAvailable,
                prootDistroVersion = version,
                installedDistros = installed,
                availableDistros = available,
                activeDistro = installed.firstOrNull()?.name ?: ""
            )
            lastRefreshMs = System.currentTimeMillis()
            cachedState!!
        } catch (e: Exception) {
            Log.w(TAG, "Failed to refresh proot-distro state: ${e.message}")
            ProotDistroState(error = "Error: ${e.message}").also { cachedState = it }
        }
    }

    /**
     * Install proot-distro via pkg in the Termux environment.
     */
    suspend fun installProotDistro(): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Installing proot-distro...")
        val prefix = termuxEnvironment.getPrefix()
        if (prefix.isBlank()) return@withContext false

        val cmd = "pkg install -y proot-distro 2>&1"
        val execReq = termuxEnvironment.buildExecutionCommand(cmd)
        val result = if (execReq.useShizuku) {
            shizukuShell.executeBlocking(execReq.command, DISTRO_TIMEOUT_MS)
        } else {
            ShellExecutor.runAsync(execReq.command, DISTRO_TIMEOUT_MS)
        }

        val success = result.isSuccess && !result.stderr.contains("failed", ignoreCase = true)
        if (success) {
            Log.i(TAG, "proot-distro installed successfully")
            refreshState()
        } else {
            Log.w(TAG, "proot-distro install failed: ${result.stderr.take(200)}")
        }
        success
    }

    /**
     * Install a full Linux distribution (e.g., "ubuntu", "debian", "archlinux").
     *
     * @param distroName The distro name (e.g., "ubuntu", "debian", "archlinux")
     * @param progressCallback Optional progress callback
     * @return true if installation succeeded
     */
    suspend fun installDistro(
        distroName: String,
        progressCallback: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Installing distro: $distroName")
        val prefix = termuxEnvironment.getPrefix()
        if (prefix.isBlank()) {
            progressCallback?.invoke("❌ Termux environment not available")
            return@withContext false
        }

        // Step 1: Ensure proot-distro is installed
        if (!checkProotDistroExists()) {
            progressCallback?.invoke("📦 Installing proot-distro...")
            if (!installProotDistro()) {
                progressCallback?.invoke("❌ Failed to install proot-distro")
                return@withContext false
            }
        }

        // Step 2: Install the distro
        progressCallback?.invoke("🐧 Installing $distroName (this may take 1-5 minutes)...")
        val installCmd = "proot-distro install $distroName 2>&1"
        val execReq = termuxEnvironment.buildExecutionCommand(installCmd)
        val result = if (execReq.useShizuku) {
            shizukuShell.executeBlocking(execReq.command, DISTRO_INSTALL_TIMEOUT_MS)
        } else {
            ShellExecutor.runAsync(execReq.command, DISTRO_INSTALL_TIMEOUT_MS)
        }

        val success = result.isSuccess || result.stdout.contains("already installed", ignoreCase = true)
        if (success) {
            progressCallback?.invoke("✅ $distroName installed!")
            refreshState()
        } else {
            progressCallback?.invoke("❌ Install failed: ${result.stderr.take(200)}")
        }
        success
    }

    /**
     * Remove an installed distribution.
     */
    suspend fun removeDistro(distroName: String): Boolean = withContext(Dispatchers.IO) {
        Log.i(TAG, "Removing distro: $distroName")
        val prefix = termuxEnvironment.getPrefix()
        if (prefix.isBlank()) return@withContext false

        val cmd = "proot-distro remove $distroName 2>&1"
        val execReq = termuxEnvironment.buildExecutionCommand(cmd)
        val result = if (execReq.useShizuku) {
            shizukuShell.executeBlocking(execReq.command, DISTRO_TIMEOUT_MS)
        } else {
            ShellExecutor.runAsync(execReq.command, DISTRO_TIMEOUT_MS)
        }
        val success = result.isSuccess
        if (success) {
            Log.i(TAG, "Distro $distroName removed")
            refreshState()
        }
        success
    }

    /**
     * Run a command inside a specific distribution.
     *
     * Example: runInDistro("ubuntu", "apt update && apt install -y python3")
     */
    suspend fun runInDistro(
        distroName: String,
        command: String,
        timeoutMs: Long = DISTRO_TIMEOUT_MS
    ): ShellExecutionResult = withContext(Dispatchers.IO) {
        val wrappedCmd = "proot-distro login $distroName -- $command 2>&1"
        val execReq = termuxEnvironment.buildExecutionCommand(wrappedCmd)
        if (execReq.useShizuku) {
            shizukuShell.executeBlocking(execReq.command, timeoutMs)
        } else {
            ShellExecutor.runAsync(execReq.command, timeoutMs)
        }
    }

    /**
     * Login to a distro (interactive bash shell).
     * Returns the command string — caller should execute it via runInDistro or directly.
     */
    fun loginDistro(distroName: String): String {
        return "proot-distro login $distroName"
    }

    /**
     * Install packages inside a specific distro using its native package manager.
     */
    suspend fun installPackagesInDistro(
        distroName: String,
        packages: List<String>,
        progressCallback: ((String) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        progressCallback?.invoke("📦 Installing ${packages.joinToString(", ")} in $distroName...")
        val packageList = packages.joinToString(" ")
        // Use distro's package manager (assumes apt-based for ubuntu/debian, adjust as needed)
        val cmd = "apt-get update -qq 2>&1 && apt-get install -y $packageList 2>&1"
        val result = runInDistro(distroName, cmd, DISTRO_INSTALL_TIMEOUT_MS)
        val success = result.isSuccess
        if (success) {
            progressCallback?.invoke("✅ Installed: ${packages.joinToString(", ")} in $distroName")
        } else {
            progressCallback?.invoke("❌ Package install failed: ${result.stderr.take(200)}")
        }
        success
    }

    /**
     * Check if a specific distro is installed.
     */
    suspend fun isDistroInstalled(distroName: String): Boolean {
        val state = getState()
        return state.installedDistros.any { it.name == distroName }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  INTERNAL HELPERS
    // ══════════════════════════════════════════════════════════════════════

    private suspend fun checkProotDistroExists(): Boolean {
        val prefix = termuxEnvironment.getPrefix()
        if (prefix.isBlank()) return false
        val scriptPath = "$prefix/usr/bin/$PROOT_DISTRO_SCRIPT"
        if (File(scriptPath).exists()) return true

        // Check via shell
        val result = if (shizukuShell.isElevatedAvailable) {
            shizukuShell.executeBlocking(
                "test -f '$scriptPath' && echo 'yes' || echo 'no'", 5_000
            )
        } else {
            ShellExecutor.runAsync("test -f '$scriptPath' && echo 'yes' || echo 'no'", 5_000)
        }
        return result.isSuccess && result.stdout.trim() == "yes"
    }

    private fun findProotBinary(): String? {
        // Check multiple locations for the proot binary
        val prefixes = listOf(
            File(context.filesDir, "proot"),
            File(termuxEnvironment.getPrefix(), "usr/bin")
        )
        for (dir in prefixes) {
            val proot = File(dir, "proot")
            if (proot.exists() && proot.canExecute()) return proot.absolutePath
        }
        // Check in PATH via `command -v proot`
        return null
    }

    private suspend fun getProotDistroVersion(): String {
        val cmd = "$PROOT_DISTRO_SCRIPT --version 2>&1 || echo 'unknown'"
        val execReq = termuxEnvironment.buildExecutionCommand(cmd)
        val result = if (execReq.useShizuku) {
            shizukuShell.executeBlocking(execReq.command, 10_000)
        } else {
            ShellExecutor.runAsync(execReq.command, 10_000)
        }
        return result.stdout.lines().firstOrNull { it.isNotBlank() }?.trim() ?: "unknown"
    }

    /**
     * List all available distros and check which ones are installed.
     */
    private suspend fun listAvailableDistros(prefix: String): List<DistroInfo> {
        try {
            // Get the list of known distros from proot-distro
            val listCmd = "proot-distro list 2>&1"
            val execReq = termuxEnvironment.buildExecutionCommand(listCmd)
            val result = if (execReq.useShizuku) {
                shizukuShell.executeBlocking(execReq.command, 15_000)
            } else {
                ShellExecutor.runAsync(execReq.command, 15_000)
            }

            if (!result.isSuccess && !result.stdout.contains("Available")) {
                return emptyList()
            }

            // Parse proot-distro list output
            // Typical output format:
            //   Available distributions:
            //   - alpine (Alpine Linux)
            //   - archlinux (Arch Linux)
            //   - debian (Debian Bookworm)
            //   - fedora (Fedora 39)
            //   - ubuntu (Ubuntu 24.04 LTS)
            //
            // Installed ones show a marker or appear in a "Installed:" section
            
            val lines = result.stdout.lines()
            val distros = mutableListOf<DistroInfo>()
            var inListSection = false
            
            for (line in lines) {
                val trimmed = line.trim()
                
                // Check for section headers
                if (trimmed.contains("Available", ignoreCase = true) ||
                    trimmed.contains("distributions", ignoreCase = true)) {
                    inListSection = true
                    continue
                }
                if (trimmed.contains("Installed", ignoreCase = true) ||
                    trimmed.contains("Notes", ignoreCase = true)) {
                    inListSection = false
                    continue
                }
                if (!inListSection && !trimmed.startsWith("- ")) continue
                
                // Parse "- name (Display Name)" format
                val match = Regex("""[-*]\s*(\w[\w.-]*)\s*\(([^)]+)\)""").find(trimmed)
                if (match != null) {
                    val name = match.groupValues[1].trim().lowercase()
                    val displayName = match.groupValues[2].trim()
                    
                    // Check if installed by looking for directory in proot-distro's install path
                    val installPath = "$prefix/var/lib/proot-distro/installed-rootfs/$name"
                    val isInstalled = File(installPath).exists() ||
                        checkDistroInstalledViaShell(name)
                    
                    distros.add(DistroInfo(
                        name = name,
                        displayName = displayName,
                        isInstalled = isInstalled,
                        installPath = installPath
                    ))
                }
            }

            return distros
        } catch (e: Exception) {
            Log.w(TAG, "Failed to list distros: ${e.message}")
            return emptyList()
        }
    }

    private suspend fun checkDistroInstalledViaShell(distroName: String): Boolean {
        val checkCmd = "test -d \$(proot-distro path $distroName 2>/dev/null) && echo 'yes' || echo 'no'"
        val execReq = termuxEnvironment.buildExecutionCommand(checkCmd)
        val result = if (execReq.useShizuku) {
            shizukuShell.executeBlocking(execReq.command, 10_000)
        } else {
            ShellExecutor.runAsync(execReq.command, 10_000)
        }
        return result.isSuccess && result.stdout.trim() == "yes"
    }

    /**
     * Get a human-readable summary of the proot-distro state.
     * Used by the AI system prompt to tell the AI about available distros.
     */
    suspend fun getSummary(): String = withContext(Dispatchers.IO) {
        val state = getState()
        buildString {
            if (!state.isAvailable) {
                appendLine("❌ proot-distro not installed")
                appendLine("- Install with: `pkg install proot-distro` in Termux")
                return@buildString
            }
            appendLine("📦 **PRoot Distros**")
            if (state.installedDistros.isNotEmpty()) {
                appendLine("✅ Installed distros:")
                state.installedDistros.forEach { distro ->
                    appendLine("  - ${distro.displayName} (`${distro.name}`)")
                }
                appendLine("")
                appendLine("  Use commands inside a distro with: `proot-distro login <name> -- <command>`")
                appendLine("  Install packages: `apt install <package>` (inside the distro)")
            } else {
                appendLine("- No distros installed yet.")
                appendLine("- Available: ${state.availableDistros.take(8).joinToString(", ") { it.displayName }}...")
                appendLine("- Install: `proot-distro install <name>`")
            }
            if (state.availableDistros.isNotEmpty()) {
                appendLine("- Available to install: ${state.availableDistros.take(12).joinToString(", ") { it.displayName }}")
            }
        }
    }
}
