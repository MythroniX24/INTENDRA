package com.interndra.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for command planning: timeout inference + output truncation (§11, §24). */
class CommandPlannerTest {

    // ── Timeout inference ────────────────────────────────────────────────

    @Test
    fun `short commands get the short timeout`() {
        assertEquals(CommandPlanner.SHORT_TIMEOUT_MS, CommandPlanner.inferTimeout("ls -la"))
        assertEquals(CommandPlanner.SHORT_TIMEOUT_MS, CommandPlanner.inferTimeout("pwd"))
        assertEquals(CommandPlanner.SHORT_TIMEOUT_MS, CommandPlanner.inferTimeout("grep foo file.txt"))
        assertEquals(CommandPlanner.SHORT_TIMEOUT_MS, CommandPlanner.inferTimeout("git status"))
        assertEquals(CommandPlanner.SHORT_TIMEOUT_MS, CommandPlanner.inferTimeout("echo hello world"))
        assertEquals(CommandPlanner.SHORT_TIMEOUT_MS, CommandPlanner.inferTimeout("cat build.gradle.kts"))
    }

    @Test
    fun `install and download commands get the medium timeout`() {
        assertEquals(CommandPlanner.MEDIUM_TIMEOUT_MS, CommandPlanner.inferTimeout("pkg install python"))
        assertEquals(CommandPlanner.MEDIUM_TIMEOUT_MS, CommandPlanner.inferTimeout("apt install nodejs"))
        assertEquals(CommandPlanner.MEDIUM_TIMEOUT_MS, CommandPlanner.inferTimeout("pip install requests"))
        assertEquals(CommandPlanner.MEDIUM_TIMEOUT_MS, CommandPlanner.inferTimeout("npm install"))
        assertEquals(CommandPlanner.MEDIUM_TIMEOUT_MS, CommandPlanner.inferTimeout("git clone https://github.com/x/y.git"))
        assertEquals(CommandPlanner.MEDIUM_TIMEOUT_MS, CommandPlanner.inferTimeout("curl -O https://example.com/file.zip"))
    }

    @Test
    fun `build and test commands get the long timeout`() {
        assertEquals(CommandPlanner.LONG_TIMEOUT_MS, CommandPlanner.inferTimeout("./gradlew build"))
        assertEquals(CommandPlanner.LONG_TIMEOUT_MS, CommandPlanner.inferTimeout("gradle test"))
        assertEquals(CommandPlanner.LONG_TIMEOUT_MS, CommandPlanner.inferTimeout("npm run build"))
        assertEquals(CommandPlanner.LONG_TIMEOUT_MS, CommandPlanner.inferTimeout("npm test"))
        assertEquals(CommandPlanner.LONG_TIMEOUT_MS, CommandPlanner.inferTimeout("pytest tests/"))
        assertEquals(CommandPlanner.LONG_TIMEOUT_MS, CommandPlanner.inferTimeout("./gradlew assembleDebug"))
    }

    @Test
    fun `compound commands get the medium window`() {
        assertEquals(
            CommandPlanner.MEDIUM_TIMEOUT_MS,
            CommandPlanner.inferTimeout("cd project && ls")
        )
    }

    @Test
    fun `unknown and blank commands fall back to the default`() {
        val def = 42_000L
        assertEquals(def, CommandPlanner.inferTimeout("some_unknown_tool --flag", defaultMs = def))
        assertEquals(def, CommandPlanner.inferTimeout("", defaultMs = def))
        assertEquals(def, CommandPlanner.inferTimeout("   ", defaultMs = def))
    }

    @Test
    fun `timeout never exceeds the long ceiling`() {
        val worst = CommandPlanner.inferTimeout("./gradlew clean build && ./gradlew test")
        assertTrue(worst <= CommandPlanner.LONG_TIMEOUT_MS)
    }

    // ── Output truncation ────────────────────────────────────────────────

    @Test
    fun `small output passes through untouched`() {
        val out = CommandPlanner.truncateOutput("hello\nworld", "warn: nothing", 100, 100)
        assertEquals("hello\nworld", out.stdout)
        assertEquals("warn: nothing", out.stderr)
        assertFalse(out.stdoutTruncated)
        assertFalse(out.stderrTruncated)
    }

    @Test
    fun `large output keeps the tail and flags truncation`() {
        val big = (1..10_000).joinToString("\n") { "line $it" }
        val out = CommandPlanner.truncateOutput(big, "", maxStdoutBytes = 500)
        assertTrue(out.stdoutTruncated)
        assertTrue(out.stdout.length < big.length)
        // The END (where errors appear) is preserved.
        assertTrue(out.stdout.endsWith("line 10000"))
    }

    @Test
    fun `empty output never flags truncation`() {
        val out = CommandPlanner.truncateOutput("", "")
        assertFalse(out.stdoutTruncated)
        assertFalse(out.stderrTruncated)
    }

    // ── Error extraction ─────────────────────────────────────────────────

    @Test
    fun `extractErrorSummary picks marker lines from stderr`() {
        val stderr = """
            warning: something minor
            ERROR: Could not resolve dependency
            Some random info line
            FATAL: build aborted
        """.trimIndent()
        val summary = CommandPlanner.extractErrorSummary(stderr)
        assertTrue(summary.contains("Could not resolve dependency"))
        assertTrue(summary.contains("build aborted"))
        assertFalse(summary.contains("random info"))
    }

    @Test
    fun `extractErrorSummary falls back to the tail when no markers`() {
        val stderr = (1..20).joinToString("\n") { "line $it" }
        val summary = CommandPlanner.extractErrorSummary(stderr)
        assertTrue(summary.contains("line 20"))
    }

    @Test
    fun `extractErrorSummary returns empty for blank stderr`() {
        assertEquals("", CommandPlanner.extractErrorSummary(""))
    }
}
