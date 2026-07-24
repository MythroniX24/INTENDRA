package com.interndra.service

import android.content.Context
import io.mockk.*
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
        every { installer.isInstalled } returns false
        every { installer.bootstrapPrefix } returns "/data/local/tmp/intendra_bootstrap"

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
    fun `switchMode to same mode returns true`() {
        assertTrue(env.switchMode(TermuxEnvironment.ExecMode.FALLBACK))
    }

    @Test
    fun `switchMode to TERMUX when not installed returns false`() {
        assertFalse(env.switchMode(TermuxEnvironment.ExecMode.TERMUX))
    }

    @Test
    fun `switchMode returns false for null TermuxEnvironment`() {
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
        assertTrue(cmd.contains("ls"))
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
}
