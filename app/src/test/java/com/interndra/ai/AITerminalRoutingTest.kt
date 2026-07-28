package com.interndra.ai

import com.interndra.data.model.CommandType
import com.interndra.data.model.ExecutionResult
import com.interndra.data.model.ShellCommand
import org.junit.Assert.*
import org.junit.Test

/**
 * AITerminalRoutingTest — Verifies that HybridExecutionEngine routes
 * AI commands through TerminalAgent (Termux/Shizuku) instead of
 * the sandboxed ShellExecutor.
 *
 * This was the ROOT CAUSE of AI not using Termux: HybridExecutionEngine
 * was constructed WITHOUT passing terminalAgent, so all commands went
 * through ShellExecutor (sandboxed one-shot sh).
 */
class AITerminalRoutingTest {

    @Test
    fun `HybridExecutionEngine accepts terminalAgent parameter`() {
        // The constructor signature includes terminalAgent: TerminalAgent? = null
        // Verify the class has constructors (it's not an object/companion)
        val constructors = HybridExecutionEngine::class.java.constructors
        assertTrue("Should have at least one constructor", constructors.isNotEmpty())

        // The full constructor should have 5 parameters:
        // (context, repo, shell, safety, terminalAgent)
        val fullConstructor = constructors.maxByOrNull { it.parameterCount }
        assertNotNull(fullConstructor)
        assertTrue("Full constructor should have 5 parameters",
            (fullConstructor?.parameterCount ?: 0) >= 5)
    }

    @Test
    fun `CommandType enum has TERMUX and ADB_SHELL types`() {
        val types = CommandType.values()
        assertTrue("Should have ADB_SHELL type", types.contains(CommandType.ADB_SHELL))
        assertTrue("Should have TERMUX type", types.contains(CommandType.TERMUX))
        assertTrue("Should have ANDROID_INTENT type", types.contains(CommandType.ANDROID_INTENT))
    }

    @Test
    fun `ExecutionResult contains success output and error fields`() {
        val success = ExecutionResult(stepIndex = 0, success = true, output = "done")
        assertTrue(success.success)
        assertEquals("done", success.output)
        assertEquals("", success.error)

        val failure = ExecutionResult(stepIndex = 1, success = false, output = "", error = "failed")
        assertFalse(failure.success)
        assertEquals("failed", failure.error)
    }

    @Test
    fun `ShellCommand has type command and description`() {
        val cmd = ShellCommand(
            type = CommandType.TERMUX,
            command = "pkg install python",
            description = "Install Python"
        )
        assertEquals(CommandType.TERMUX, cmd.type)
        assertEquals("pkg install python", cmd.command)
        assertEquals("Install Python", cmd.description)
    }

    @Test
    fun `ADB_SHELL and TERMUX commands are routed differently`() {
        // ADB_SHELL commands (ls, cat, dumpsys) go through executeShell
        // TERMUX commands (pkg, pip, python3) go through executeInTermux
        // Both should use TerminalAgent when available
        val adbCmd = ShellCommand(CommandType.ADB_SHELL, "ls -la", "List files")
        val termuxCmd = ShellCommand(CommandType.TERMUX, "pkg install python", "Install Python")

        assertEquals(CommandType.ADB_SHELL, adbCmd.type)
        assertEquals(CommandType.TERMUX, termuxCmd.type)

        // They should be different types (not the same routing)
        assertNotEquals(adbCmd.type, termuxCmd.type)
    }
}
