package com.interndra.service

import android.content.Context
import android.os.Build
import android.util.Log
import com.interndra.util.DeviceArchitecture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * LinuxEnvironmentManager — first-class lifecycle control for the embedded
 * Linux environment (Section 6 "First-Run Setup" + Section 28 "Rootfs Update /
 * Repair" of the Embedded Linux spec).
 *
 * Wraps [TermuxBootstrapInstaller] (download/extract the rootfs),
 * [TermuxEnvironment] (runtime mode + health) and [ProotDistroManager]
 * (full Linux distros) behind a single status/action API used by the
 * Settings → Linux Environment screen and the AI Agent.
 *
 * All heavy work runs on [Dispatchers.IO]; the Android UI is never blocked.
 */
class LinuxEnvironmentManager(
    private val context: Context,
    private val installer: TermuxBootstrapInstaller,
    private val termuxEnvironment: TermuxEnvironment,
    private val prootDistroManager: ProotDistroManager,
    private val shizukuShell: ShizukuShell
) {
    companion object {
        private const val TAG = "LinuxEnvManager"
    }

    /** What the manager is currently doing (drives the UI spinner/progress). */
    enum class Phase(val label: String) {
        IDLE("Idle"),
        CHECKING("Checking environment"),
        INSTALLING("Installing"),
        REPAIRING("Repairing"),
        RESETTING("Resetting"),
        REMOVING("Removing"),
        REINSTALLING("Reinstalling"),
        ERROR("Error")
    }

    /** Full snapshot of the embedded Linux environment for the UI. */
    data class EnvironmentState(
        val phase: Phase = Phase.IDLE,
        val installed: Boolean = false,
        val archSupported: Boolean = true,
        val archLabel: String = "",
        val termuxArch: String = "",
        val modeLabel: String = "",
        val storageUsedBytes: Long = 0L,
        val installedPackages: List<String> = emptyList(),
        val prootDistros: List<String> = emptyList(),
        val progress: String = "",
        val error: String? = null
    ) {
        val storageLabel: String get() = formatBytes(storageUsedBytes)
        val packageCount: Int get() = installedPackages.size
    }

    private val _state = MutableStateFlow(EnvironmentState())
    val state: StateFlow<EnvironmentState> = _state.asStateFlow()

    private val operationMutex = Mutex()

    // ── Public actions (all async, all safe to call from the UI thread) ──

    /**
     * Check the environment: detect architecture, verify installation,
     * measure storage and collect installed packages / proot distros.
     */
    suspend fun check(): EnvironmentState = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.value = _state.value.copy(phase = Phase.CHECKING, error = null)
            try {
                val detection = DeviceArchitecture.detect(Build.SUPPORTED_ABIS.toList())
                if (!detection.supported) {
                    _state.value = EnvironmentState(
                        phase = Phase.IDLE,
                        installed = false,
                        archSupported = false,
                        archLabel = detection.label,
                        error = "Unsupported device architecture: ${detection.label}"
                    )
                    return@withLock _state.value
                }

                runCatching { termuxEnvironment.refreshStatus() }
                val envInfo = termuxEnvironment.info.value

                val installed = installer.isInstalled()
                val storage = if (installed) measureStorage() else 0L

                val prootState = runCatching { prootDistroManager.refreshState() }.getOrNull()
                val distros = prootState?.installedDistros?.mapNotNull { it.name } ?: emptyList()

                _state.value = EnvironmentState(
                    phase = Phase.IDLE,
                    installed = installed,
                    archSupported = true,
                    archLabel = detection.label,
                    termuxArch = detection.termuxArch.orEmpty(),
                    modeLabel = if (installed) envInfo.mode.label else "Not installed",
                    storageUsedBytes = storage,
                    installedPackages = envInfo.installedPackages,
                    prootDistros = distros,
                    progress = if (installed) "✓ Linux environment ready" else "Not installed yet",
                    error = envInfo.error
                )
                _state.value
            } catch (e: Exception) {
                Log.e(TAG, "check failed: ${e.message}")
                _state.value = _state.value.copy(phase = Phase.ERROR, error = e.message)
                _state.value
            }
        }
    }

    /**
     * Repair the environment: if the rootfs is missing or corrupted the
     * bootstrap is re-extracted; otherwise just verifies health.
     */
    suspend fun repair(onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.value = _state.value.copy(phase = Phase.REPAIRING, error = null)
            try {
                if (installer.isInstalled()) {
                    runCatching { termuxEnvironment.refreshStatus() }
                    val healthy = termuxEnvironment.hasTermux() || termuxEnvironment.getMode() == TermuxEnvironment.ExecMode.SHIZUKU
                    if (healthy) {
                        onProgress?.invoke("✓ Environment is healthy — no repair needed")
                        _state.value = _state.value.copy(phase = Phase.IDLE, progress = "✓ Environment healthy")
                        return@withLock true
                    }
                }
                // Rootfs missing/corrupt → reinstall it (keeps proot distros).
                onProgress?.invoke("🔄 Re-extracting Linux filesystem…")
                val result = installer.install(progressCallback = { msg ->
                    onProgress?.invoke(msg)
                    _state.value = _state.value.copy(progress = msg)
                }, setupPackages = false)
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    installed = result.success,
                    progress = if (result.success) "✓ Linux environment repaired" else "Repair failed",
                    error = if (result.success) null else result.error
                )
                result.success
            } catch (e: Exception) {
                _state.value = _state.value.copy(phase = Phase.ERROR, error = e.message)
                false
            }
        }
    }

    /** Reset the environment: remove the rootfs, then reinstall it fresh. */
    suspend fun reset(onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.value = _state.value.copy(phase = Phase.RESETTING, error = null)
            try {
                onProgress?.invoke("🧹 Removing Linux filesystem…")
                installer.uninstall(progressCallback = onProgress)
                onProgress?.invoke("📦 Installing fresh Linux filesystem…")
                val result = installer.install(progressCallback = { msg ->
                    onProgress?.invoke(msg)
                    _state.value = _state.value.copy(progress = msg)
                }, setupPackages = false)
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    installed = result.success,
                    progress = if (result.success) "✓ Linux environment reset" else "Reset failed",
                    error = if (result.success) null else result.error
                )
                result.success
            } catch (e: Exception) {
                _state.value = _state.value.copy(phase = Phase.ERROR, error = e.message)
                false
            }
        }
    }

    /** Remove the environment completely (rootfs + markers). */
    suspend fun remove(onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.value = _state.value.copy(phase = Phase.REMOVING, error = null)
            try {
                onProgress?.invoke("🧹 Removing Linux environment…")
                installer.uninstall(progressCallback = onProgress)
                runCatching { termuxEnvironment.refreshStatus() }
                _state.value = EnvironmentState(
                    phase = Phase.IDLE,
                    installed = false,
                    progress = "Environment removed",
                    archLabel = _state.value.archLabel
                )
                true
            } catch (e: Exception) {
                _state.value = _state.value.copy(phase = Phase.ERROR, error = e.message)
                false
            }
        }
    }

    /** Reinstall: remove everything, then install a fresh environment. */
    suspend fun reinstall(onProgress: ((String) -> Unit)? = null): Boolean = withContext(Dispatchers.IO) {
        operationMutex.withLock {
            _state.value = _state.value.copy(phase = Phase.REINSTALLING, error = null)
            try {
                onProgress?.invoke("🧹 Removing old environment…")
                installer.uninstall(progressCallback = onProgress)
                onProgress?.invoke("📦 Installing fresh Linux environment…")
                val result = installer.install(progressCallback = { msg ->
                    onProgress?.invoke(msg)
                    _state.value = _state.value.copy(progress = msg)
                }, setupPackages = false)
                _state.value = _state.value.copy(
                    phase = Phase.IDLE,
                    installed = result.success,
                    progress = if (result.success) "✓ Linux environment ready" else "Installation failed",
                    error = if (result.success) null else result.error
                )
                result.success
            } catch (e: Exception) {
                _state.value = _state.value.copy(phase = Phase.ERROR, error = e.message)
                false
            }
        }
    }

    // ── Storage measurement ────────────────────────────────────────────

    /**
     * Measure the on-disk size of the Linux rootfs. Shizuku installs live in
     * /data/local/tmp (measured via `du`), proot installs live in the app's
     * private dir (measured locally).
     */
    private suspend fun measureStorage(): Long = withContext(Dispatchers.IO) {
        if (installer.isShizukuInstalled()) {
            val result = shizukuShell.executeBlocking(
                "du -sb ${TermuxBootstrapInstaller.SHIZUKU_PREFIX} 2>/dev/null | cut -f1",
                TerminalConfig.RECOVERY_TIMEOUT_MS
            )
            if (result.isSuccess) {
                result.stdout.trim().toLongOrNull()?.let { return@withContext it }
            }
            0L
        } else {
            val prefix = File(context.filesDir, "termux")
            if (prefix.exists()) dirSize(prefix) else 0L
        }
    }

    private fun dirSize(dir: File): Long {
        if (!dir.exists()) return 0L
        return try {
            dir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }
        } catch (e: Exception) {
            Log.w(TAG, "dirSize failed: ${e.message}")
            0L
        }
    }

    // ── Pure helpers (unit-testable) ─────────────────────────────────────

    companion object Utils {
        /** Human-readable byte formatting: 12345 -> "12.1 KB". */
        fun formatBytes(bytes: Long): String {
            if (bytes < 1024) return "$bytes B"
            val units = arrayOf("KB", "MB", "GB", "TB")
            var value = bytes.toDouble() / 1024.0
            var unit = 0
            while (value >= 1024 && unit < units.size - 1) {
                value /= 1024.0
                unit++
            }
            return String.format(java.util.Locale.US, "%.1f %s", value, units[unit])
        }
    }
}
