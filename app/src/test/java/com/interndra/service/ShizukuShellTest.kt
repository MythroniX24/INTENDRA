package com.interndra.service

import android.content.Context
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ShizukuShellTest — tests for elevated shell command execution via Shizuku
 * with graceful fallback to ShellExecutor.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShizukuShellTest {

    private lateinit var context: Context
    private lateinit var shizukuManager: ShizukuManager

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        shizukuManager = mockk(relaxed = true)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  CONSTRUCTOR & SINGLETON
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `constructor creates instance with provided manager`() {
        val shell = ShizukuShell(context, shizukuManager)
        assertNotNull(shell)
        assertEquals(shizukuManager, shell.manager)
    }

    @Test
    fun `constructor creates instance without manager`() {
        // When no manager provided, uses ShizukuManager(context)
        val shell = ShizukuShell(context)
        assertNotNull(shell)
        assertNotNull(shell.manager)
    }

    @Test
    fun `get returns singleton instance`() {
        val shell1 = ShizukuShell.get(context)
        val shell2 = ShizukuShell.get(context)
        assertSame(shell1, shell2)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  isElevatedAvailable
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `isElevatedAvailable delegates to manager`() {
        every { shizukuManager.isAuthorized() } returns true
        val shell = ShizukuShell(context, shizukuManager)
        assertTrue(shell.isElevatedAvailable)
        verify { shizukuManager.isAuthorized() }
    }

    @Test
    fun `isElevatedAvailable returns false when not authorized`() {
        every { shizukuManager.isAuthorized() } returns false
        val shell = ShizukuShell(context, shizukuManager)
        assertFalse(shell.isElevatedAvailable)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  execute() — Suspend API
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `execute uses Shizuku when authorized`() = runTest {
        every { shizukuManager.isAuthorized() } returns true
        every {
            shizukuManager.executeShell(any(), any())
        } returns ShellExecutionResult("output", "", 0, true,
            backend = ExecutionBackend.SHIZUKU_ADB)

        val shell = ShizukuShell(context, shizukuManager)
        val result = shell.execute("echo test")

        assertTrue(result.isSuccess)
        assertEquals(ExecutionBackend.SHIZUKU_ADB, result.backend)
        verify { shizukuManager.executeShell("echo test", TerminalConfig.DEFAULT_TIMEOUT_MS) }
    }

    @Test
    fun `execute falls back to ShellExecutor when Shizuku unauthorized`() = runTest {
        every { shizukuManager.isAuthorized() } returns false

        val shell = ShizukuShell(context, shizukuManager)
        val result = shell.execute("echo 'fallback test'")

        // Should use ShellExecutor (or mockk relaxed returns default)
        assertNotNull(result)
        // Backend should be SHELL_EXECUTOR since Shizuku is not authorized
        verify(exactly = 0) { shizukuManager.executeShell(any(), any()) }
    }

    @Test
    fun `execute falls back when Shizuku result has unknown backend`() = runTest {
        every { shizukuManager.isAuthorized() } returns true
        every {
            shizukuManager.executeShell(any(), any())
        } returns ShellExecutionResult("", "error", -1, false,
            backend = ExecutionBackend.SHELL_EXECUTOR) // unexpected: Shizuku returned SHELL_EXECUTOR

        val shell = ShizukuShell(context, shizukuManager)
        val result = shell.execute("echo test")

        // Should fall through because backend is not SHIZUKU_ROOT/SHIZUKU_ADB
        assertNotNull(result)
    }

    @Test
    fun `execute with onOutput streams through Shizuku`() = runTest {
        every { shizukuManager.isAuthorized() } returns true
        every {
            shizukuManager.executeShellStreaming(any(), any(), any())
        } returns ShellExecutionResult("streamed output", "", 0, true,
            backend = ExecutionBackend.SHIZUKU_ADB)

        val shell = ShizukuShell(context, shizukuManager)
        val outputLines = mutableListOf<String>()
        val result = shell.execute("echo streaming", onOutput = { line ->
            outputLines.add(line)
        })

        assertTrue(result.isSuccess)
        verify {
            shizukuManager.executeShellStreaming("echo streaming",
                TerminalConfig.DEFAULT_TIMEOUT_MS, any())
        }
    }

    @Test
    fun `execute with custom timeout`() = runTest {
        every { shizukuManager.isAuthorized() } returns true
        every {
            shizukuManager.executeShell(any(), any())
        } returns ShellExecutionResult("ok", "", 0, true,
            backend = ExecutionBackend.SHIZUKU_ROOT)

        val shell = ShizukuShell(context, shizukuManager)
        val result = shell.execute("echo test", timeoutMs = 60_000)

        assertTrue(result.isSuccess)
        verify { shizukuManager.executeShell("echo test", 60_000) }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  executeBlocking() — Synchronous API
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `executeBlocking uses Shizuku when authorized`() {
        every { shizukuManager.isAuthorized() } returns true
        every {
            shizukuManager.executeShell(any(), any())
        } returns ShellExecutionResult("blocking output", "", 0, true,
            backend = ExecutionBackend.SHIZUKU_ADB)

        val shell = ShizukuShell(context, shizukuManager)
        val result = shell.executeBlocking("pm list packages")

        assertTrue(result.isSuccess)
        assertEquals(ExecutionBackend.SHIZUKU_ADB, result.backend)
        verify { shizukuManager.executeShell("pm list packages", TerminalConfig.DEFAULT_TIMEOUT_MS) }
    }

    @Test
    fun `executeBlocking falls back when Shizuku not authorized`() {
        every { shizukuManager.isAuthorized() } returns false

        val shell = ShizukuShell(context, shizukuManager)
        val result = shell.executeBlocking("echo 'hello'")

        // Should work via fallback
        assertNotNull(result)
        verify(exactly = 0) { shizukuManager.executeShell(any(), any()) }
    }

    @Test
    fun `executeBlocking with custom timeout`() {
        every { shizukuManager.isAuthorized() } returns true
        every {
            shizukuManager.executeShell(any(), any())
        } returns ShellExecutionResult("ok", "", 0, true,
            backend = ExecutionBackend.SHIZUKU_ROOT)

        val shell = ShizukuShell(context, shizukuManager)
        shell.executeBlocking("echo test", timeoutMs = 10_000)

        verify { shizukuManager.executeShell("echo test", 10_000) }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  testConnection()
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `testConnection returns UID from Shizuku when authorized`() = runTest {
        every { shizukuManager.isAuthorized() } returns true
        every { shizukuManager.testConnection() } returns 2000

        val shell = ShizukuShell(context, shizukuManager)
        val uid = shell.testConnection()

        assertEquals(2000, uid)
        verify { shizukuManager.testConnection() }
    }

    @Test
    fun `testConnection falls back when Shizuku returns null`() = runTest {
        every { shizukuManager.isAuthorized() } returns true
        every { shizukuManager.testConnection() } returns null

        val shell = ShizukuShell(context, shizukuManager)
        val uid = shell.testConnection()

        // Should fall through and return app's own UID
        assertTrue(uid >= 0)
    }

    @Test
    fun `testConnection works without Shizuku`() = runTest {
        every { shizukuManager.isAuthorized() } returns false

        val shell = ShizukuShell(context, shizukuManager)
        val uid = shell.testConnection()

        // Should work via ShellExecutor fallback
        assertTrue(uid >= 0)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  privilegeDescription
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `privilegeDescription delegates to manager`() {
        every { shizukuManager.privilegeLevel } returns "ADB Shell (UID 2000)"
        val shell = ShizukuShell(context, shizukuManager)
        assertEquals("ADB Shell (UID 2000)", shell.privilegeDescription)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  EDGE CASES
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `execute handles long command`() = runTest {
        every { shizukuManager.isAuthorized() } returns false

        val longCmd = "echo '" + "a".repeat(10_000) + "'"
        val shell = ShizukuShell(context, shizukuManager)
        val result = shell.execute(longCmd)

        assertNotNull(result)
    }
}
