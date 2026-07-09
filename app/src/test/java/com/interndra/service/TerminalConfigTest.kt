package com.interndra.service

import org.junit.Assert.*
import org.junit.Test

/**
 * TerminalConfigTest — verifies all centralized constants are consistent
 * and that no configuration conflicts exist between the 3 execution backends.
 */
class TerminalConfigTest {

    @Test
    fun `MAX_OUTPUT_BYTES is reasonable`() {
        // 512KB is enough for most command output without causing OOM
        assertEquals(512 * 1024, TerminalConfig.MAX_OUTPUT_BYTES)
        assertTrue(TerminalConfig.MAX_OUTPUT_BYTES > 100_000)
        assertTrue(TerminalConfig.MAX_OUTPUT_BYTES < 10_000_000)
    }

    @Test
    fun `timeout constants are ordered correctly`() {
        // Connection test should be fastest, agent longest
        assertTrue(TerminalConfig.CONNECTION_TEST_TIMEOUT_MS < TerminalConfig.DEFAULT_TIMEOUT_MS)
        assertTrue(TerminalConfig.RECOVERY_TIMEOUT_MS < TerminalConfig.DEFAULT_TIMEOUT_MS)
        assertTrue(TerminalConfig.DEFAULT_TIMEOUT_MS < TerminalConfig.INSTALL_TIMEOUT_MS)
        assertTrue(TerminalConfig.INSTALL_TIMEOUT_MS < TerminalConfig.AGENT_TIMEOUT_MS)
    }

    @Test
    fun `timeout constants are positive`() {
        assertTrue(TerminalConfig.DEFAULT_TIMEOUT_MS > 0)
        assertTrue(TerminalConfig.INSTALL_TIMEOUT_MS > 0)
        assertTrue(TerminalConfig.AGENT_TIMEOUT_MS > 0)
        assertTrue(TerminalConfig.RECOVERY_TIMEOUT_MS > 0)
        assertTrue(TerminalConfig.CONNECTION_TEST_TIMEOUT_MS > 0)
    }

    @Test
    fun `history limits are reasonable`() {
        assertTrue(TerminalConfig.MAX_HISTORY_ENTRIES >= 50)
        assertTrue(TerminalConfig.MAX_HISTORY_ENTRIES <= 1000)
        assertTrue(TerminalConfig.MAX_OUTPUT_LINES >= 100)
        assertTrue(TerminalConfig.MAX_OUTPUT_LINES <= 5000)
    }

    @Test
    fun `MAX_COMPLETIONS is reasonable`() {
        assertTrue(TerminalConfig.MAX_COMPLETIONS in 5..50)
    }

    @Test
    fun `AUTO_SAVE_DELAY is reasonable`() {
        assertTrue(TerminalConfig.AUTO_SAVE_DELAY_MS in 1000..30000)
    }

    @Test
    fun `MAX_STDOUT_SNIPPET_CHARS is larger than MAX_STDERR_SNIPPET_CHARS`() {
        assertTrue(TerminalConfig.MAX_STDOUT_SNIPPET_CHARS > TerminalConfig.MAX_STDERR_SNIPPET_CHARS)
    }

    // ── ShellExecutionResult ───────────────────────────────────────────

    @Test
    fun `ShellExecutionResult isSuccess defaults to exitCode 0`() {
        val success = ShellExecutionResult("out", "", 0)
        assertTrue(success.isSuccess)

        val failure = ShellExecutionResult("out", "err", 1)
        assertFalse(failure.isSuccess)
    }

    @Test
    fun `ShellExecutionResult can explicitly set isSuccess`() {
        val result = ShellExecutionResult("", "timeout", -1, false, backend = ExecutionBackend.SMART_SHELL)
        assertFalse(result.isSuccess)
        assertEquals(ExecutionBackend.SMART_SHELL, result.backend)
    }

    @Test
    fun `ShellExecutionResult duration defaults to zero`() {
        val result = ShellExecutionResult("out", "", 0)
        assertEquals(0L, result.durationMs)
    }

    @Test
    fun `all ExecutionBackend values have display names`() {
        for (backend in ExecutionBackend.entries) {
            assertTrue(backend.displayName.isNotBlank())
        }
    }

    @Test
    fun `ExecutionBackend correctly identifies elevated backends`() {
        // SHIZUKU_ROOT and SHIZUKU_ADB are elevated
        assertTrue(ExecutionBackend.SHIZUKU_ROOT.displayName.contains("Shizuku"))
        assertTrue(ExecutionBackend.SHIZUKU_ADB.displayName.contains("Shizuku"))
        assertFalse(ExecutionBackend.SMART_SHELL.displayName.contains("Shizuku"))
    }
}
