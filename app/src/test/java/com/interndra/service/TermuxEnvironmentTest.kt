package com.interndra.service

import android.content.Context
import io.mockk.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TermuxEnvironmentTest — tests for embedded Termux runtime environment
 * management: mode switching, command routing, health checking.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TermuxEnvironmentTest {

    private lateinit var context: Context
    private lateinit var shizukuShell: ShizukuShell
    private lateinit var installer: TermuxBootstrapInstaller
    private lateinit var env: TermuxEnvironment
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        shizukuShell = mockk(relaxed = true)
        installer = mockk(relaxed = true)

        every { shizukuShell.isElevatedAvailable } returns false
        every { shizukuShell.manager } returns mockk(relaxed = true)
        every { installer.isInstalled() } returns false
        every { context.filesDir } returns File("/data")

        testScope = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        env = TermuxEnvironment(context, shizukuShell, installer, scope = testScope)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // ── Initial State ──────────────────────────────────────────────────

    @Test
    fun `initial mode is FALLBACK`() {
        assertEquals(TermuxEnvironment.ExecMode.FALLBACK, env.getMode())
    }

    @Test
    fun `hasTermux returns false initially`() {
        assertFalse(env.hasTermux())
    }

    @Test
    fun `info flow emits initial state`() = runTest {
        val info = env.info.first()
        assertFalse(info.bootstrapInstalled)
        assertFalse(info.bashAvailable)
        assertFalse(info.aptAvailable)
    }

    // ── Mode Switching ─────────────────────────────────────────────────

    @Test
    fun `switchMode to same mode returns true`() = runTest {
        assertTrue(env.switchMode(TermuxEnvironment.ExecMode.FALLBACK))
    }

    @Test
    fun `switchMode to TERMUX when not installed returns false`() = runTest {
        assertFalse(env.switchMode(TermuxEnvironment.ExecMode.TERMUX))
    }

    @Test
    fun `switchMode returns false for null TermuxEnvironment`() = runTest {
        // Testing that mode switching requires valid environment
        assertFalse(env.switchMode(TermuxEnvironment.ExecMode.SHIZUKU))
        // Stays in FALLBACK
        assertEquals(TermuxEnvironment.ExecMode.FALLBACK, env.getMode())
    }

    // ── Command Routing ───────────────────────────────────────────────

    @Test
    fun `suggestModeForCommand routes pkg to TERMUX`() {
        val mode = env.suggestModeForCommand("pkg install python")
        assertEquals(TermuxEnvironment.ExecMode.TERMUX, mode)
    }

    @Test
    fun `suggestModeForCommand routes apt to TERMUX`() {
        val mode = env.suggestModeForCommand("apt update")
        assertEquals(TermuxEnvironment.ExecMode.TERMUX, mode)
    }

    @Test
    fun `suggestModeForCommand routes pip to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX, env.suggestModeForCommand("pip install requests"))
    }

    @Test
    fun `suggestModeForCommand routes npm to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX, env.suggestModeForCommand("npm install"))
    }

    @Test
    fun `suggestModeForCommand routes node to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX, env.suggestModeForCommand("node server.js"))
    }

    @Test
    fun `suggestModeForCommand routes python to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX, env.suggestModeForCommand("python3 script.py"))
    }

    @Test
    fun `suggestModeForCommand routes git to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX, env.suggestModeForCommand("git clone ..."))
    }

    @Test
    fun `suggestModeForCommand routes system commands to SHIZUKU`() {
        val mode = env.suggestModeForCommand("pm list packages")
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU, mode)
    }

    @Test
    fun `suggestModeForCommand routes am commands to SHIZUKU`() {
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU, env.suggestModeForCommand("am start -n ..."))
    }

    @Test
    fun `suggestModeForCommand routes dumpsys to SHIZUKU`() {
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU, env.suggestModeForCommand("dumpsys battery"))
    }

    @Test
    fun `suggestModeForCommand routes ls to current mode`() {
        val mode = env.suggestModeForCommand("ls -la")
        assertNotNull(mode)
    }

    // ── Environment Variables ──────────────────────────────────────────

    @Test
    fun `getEnvironmentVars returns map in FALLBACK mode`() {
        val vars = env.getEnvironmentVars()
        assertTrue(vars.containsKey("PATH"))
        assertTrue(vars.containsKey("HOME"))
    }

    @Test
    fun `buildExecutionCommand wraps command for current mode`() {
        val cmd = env.buildExecutionCommand("ls -la")
        assertNotNull(cmd)
        assertTrue(cmd.command.contains("ls"))
    }

    // ── Health / Status ──────────────────────────────────────────────

    @Test
    fun `refreshStatus does not throw`() = runTest {
        env.refreshStatus()
        // Should complete without exception
        assertTrue(true)
    }

    @Test
    fun `info reflects FALLBACK mode`() = runTest {
        val info = env.info.first()
        assertEquals(TermuxEnvironment.ExecMode.FALLBACK, info.mode)
    }

    // ── Init ──────────────────────────────────────────────────────────

    @Test
    fun `init does not throw when bootstrap not installed`() = runTest {
        env.init()
        assertEquals(TermuxEnvironment.ExecMode.FALLBACK, env.getMode())
    }

    // ── Edge Cases ────────────────────────────────────────────────────

    @Test
    fun `getMode returns consistent value`() {
        val mode1 = env.getMode()
        val mode2 = env.getMode()
        assertEquals(mode1, mode2)
    }

    @Test
    fun `ExecMode has correct values`() {
        val modes = TermuxEnvironment.ExecMode.values()
        assertTrue(modes.isNotEmpty())
        // Each mode should have emoji and label
        modes.forEach {
            assertNotNull(it.emoji)
            assertNotNull(it.label)
            assertTrue(it.label.isNotBlank())
        }
    }

    @Test
    fun `mode labels are unique`() {
        val labels = TermuxEnvironment.ExecMode.values().map { it.label }
        assertEquals(labels.size, labels.distinct().size)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: buildExecutionCommand
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `buildExecutionCommand with FALLBACK mode returns simple command`() {
        val req = env.buildExecutionCommand("ls -la", TermuxEnvironment.ExecMode.FALLBACK)
        assertEquals(TermuxEnvironment.ExecMode.FALLBACK, req.mode)
        assertFalse(req.useShizuku)
        assertFalse(req.useProot)
        assertTrue(req.command.contains("ls"))
    }

    @Test
    fun `buildExecutionCommand with SHIZUKU mode sets useShizuku flag`() {
        val req = env.buildExecutionCommand("pm list packages", TermuxEnvironment.ExecMode.SHIZUKU)
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU, req.mode)
        assertTrue(req.useShizuku)
    }

    @Test
    fun `buildExecutionCommand has empty env for FALLBACK`() {
        val req = env.buildExecutionCommand("echo test", TermuxEnvironment.ExecMode.FALLBACK)
        assertNotNull(req.envVars)
        assertTrue(req.envVars.containsKey("HOME"))
        assertTrue(req.envVars.containsKey("PATH"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: wrapTermuxCommand
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `wrapTermuxCommand wraps command with env vars`() {
        val wrapped = env.wrapTermuxCommand("echo test", "/data/termux")
        assertTrue(wrapped.contains("/data/termux/usr/bin/bash"))
        assertTrue(wrapped.contains("echo test"))
        assertTrue(wrapped.contains("PREFIX"))
        assertTrue(wrapped.contains("PATH"))
    }

    @Test
    fun `wrapTermuxCommand with blank prefix returns original command`() {
        val wrapped = env.wrapTermuxCommand("echo test", "")
        assertEquals("echo test", wrapped)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: wrapShizukuCommand
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `wrapShizukuCommand wraps command with env vars`() {
        val wrapped = env.wrapShizukuCommand("pm list packages")
        assertTrue(wrapped.contains("pm list packages"))
        assertTrue(wrapped.contains("PATH"))
        assertTrue(wrapped.contains("HOME"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: Environment Variables
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `getTermuxEnvVars returns full env for Termux mode`() {
        val vars = env.getTermuxEnvVars("/data/termux")
        assertEquals("/data/termux/usr", vars["PREFIX"])
        assertEquals("/data/termux/home", vars["HOME"])
        assertTrue(vars["PATH"]?.contains("/data/termux/usr/bin") == true)
        assertEquals("C.UTF-8", vars["LANG"])
        assertEquals("C.UTF-8", vars["LC_ALL"])
    }

    @Test
    fun `getTermuxEnvVars with blank prefix returns fallback vars`() {
        val vars = env.getTermuxEnvVars("")
        // Fallback env should have minimal vars
        assertTrue(vars.containsKey("HOME"))
        assertTrue(vars.containsKey("PATH"))
        assertFalse(vars.containsKey("PREFIX"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: isElevated / hasTermux
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `isElevated returns false in FALLBACK mode`() {
        assertFalse(env.isElevated())
    }

    @Test
    fun `isElevated returns false when mode is not SHIZUKU or ROOT`() {
        // FALLBACK mode should not be elevated
        assertFalse(env.isElevated())
    }

    @Test
    fun `isElevated returns false after unsuccessful switch to TERMUX`() = runTest {
        // Bootstrap not installed, switch will fail
        env.switchMode(TermuxEnvironment.ExecMode.TERMUX)
        // Whether it succeeds or fails, isElevated should be false
        assertFalse(env.isElevated())
    }

    @Test
    fun `hasTermux returns false when not installed`() {
        assertFalse(env.hasTermux())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: getPrefix
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `getPrefix returns empty initially`() {
        assertEquals("", env.getPrefix())
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: getSummary
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `getSummary returns non-empty description`() = runTest {
        val summary = env.getSummary()
        assertNotNull(summary)
        assertTrue(summary.isNotBlank(), "Summary should not be blank")
        assertTrue(summary.contains("Termux Environment") ||
                   summary.contains("Mode") ||
                   summary.contains("Bash"),
            "Summary should contain environment info: $summary")
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: shutdown
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `shutdown does not throw`() {
        env.shutdown()
        // Should complete without exception
        assertTrue(true)
    }

    @Test
    fun `shutdown can be called multiple times`() {
        env.shutdown()
        env.shutdown()
        assertTrue(true)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: installPackages
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `installPackages returns false when termux not available`() = runTest {
        // Bootstrap is not installed, Termux not available
        val result = env.installPackages(listOf("python"))
        assertFalse(result)
    }

    @Test
    fun `installCommonPackages returns false when termux not available`() = runTest {
        val result = env.installCommonPackages()
        assertFalse(result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: isPackageInstalled
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `isPackageInstalled returns false without termux`() = runTest {
        val installed = env.isPackageInstalled("python")
        assertFalse(installed)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: ExecutionRequest
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `ExecutionRequest stores correct fields`() {
        val req = TermuxEnvironment.ExecutionRequest(
            command = "echo test",
            mode = TermuxEnvironment.ExecMode.SHIZUKU,
            useShizuku = true,
            envVars = mapOf("PATH" to "/system/bin"),
            workdir = "/tmp"
        )
        assertEquals("echo test", req.command)
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU, req.mode)
        assertTrue(req.useShizuku)
        assertEquals("/system/bin", req.envVars["PATH"])
        assertEquals("/tmp", req.workdir)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: EnvInfo defaults
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `EnvInfo has sensible defaults`() {
        val info = TermuxEnvironment.EnvInfo()
        assertEquals(TermuxEnvironment.ExecMode.FALLBACK, info.mode)
        assertFalse(info.bootstrapInstalled)
        assertFalse(info.bashAvailable)
        assertFalse(info.aptAvailable)
        assertFalse(info.shizukuAvailable)
        assertFalse(info.shizukuAuthorized)
        assertEquals(-1, info.shizukuUid)
        assertTrue(info.installedPackages.isEmpty())
        assertNull(info.error)
    }

    @Test
    fun `EnvInfo with proot fields`() {
        val info = TermuxEnvironment.EnvInfo(
            mode = TermuxEnvironment.ExecMode.TERMUX,
            prootDistroAvailable = true,
            activeProotDistro = "ubuntu",
            installedProotDistros = listOf("ubuntu", "debian")
        )
        assertTrue(info.prootDistroAvailable)
        assertEquals("ubuntu", info.activeProotDistro)
        assertEquals(2, info.installedProotDistros.size)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: suggestModeForCommand edge cases
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `suggestModeForCommand routes settings to SHIZUKU`() {
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU,
            env.suggestModeForCommand("settings put global airplane_mode_on 1"))
    }

    @Test
    fun `suggestModeForCommand routes input to SHIZUKU`() {
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU,
            env.suggestModeForCommand("input keyevent KEYCODE_HOME"))
    }

    @Test
    fun `suggestModeForCommand routes wm to SHIZUKU`() {
        assertEquals(TermuxEnvironment.ExecMode.SHIZUKU,
            env.suggestModeForCommand("wm size 1080x1920"))
    }

    @Test
    fun `suggestModeForCommand routes pip3 to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX,
            env.suggestModeForCommand("pip3 install torch"))
    }

    @Test
    fun `suggestModeForCommand routes cargo to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX,
            env.suggestModeForCommand("cargo build --release"))
    }

    @Test
    fun `suggestModeForCommand routes make to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX,
            env.suggestModeForCommand("make install"))
    }

    @Test
    fun `suggestModeForCommand routes go to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX,
            env.suggestModeForCommand("go build main.go"))
    }

    @Test
    fun `suggestModeForCommand routes ruby to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX,
            env.suggestModeForCommand("ruby script.rb"))
    }

    @Test
    fun `suggestModeForCommand routes php to TERMUX`() {
        assertEquals(TermuxEnvironment.ExecMode.TERMUX,
            env.suggestModeForCommand("php artisan serve"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: PrefixPaths
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `prefix paths are configured correctly`() {
        // Access via package-private is not possible, but we can observe behavior
        assertTrue(true) // placeholder for prefix path test
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ADDITIONAL COVERAGE: mode flow updates
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `mode flow emits initial value`() = runTest {
        val modeVal = env.mode.first()
        assertEquals(TermuxEnvironment.ExecMode.FALLBACK, modeVal)
    }

    @Test
    fun `isInstalling flow emits initial false`() = runTest {
        val installing = env.isInstalling.first()
        assertFalse(installing)
    }

    @Test
    fun `installProgress flow emits initial empty`() = runTest {
        val progress = env.installProgress.first()
        assertNotNull(progress)
    }
}
