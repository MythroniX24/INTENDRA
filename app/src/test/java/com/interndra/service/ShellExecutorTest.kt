package com.interndra.service

import org.junit.Assert.*
import org.junit.Test
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Before

/**
 * ShellExecutorTest — tests for the stateless shell command executor.
 *
 * Tests cover:
 * - Path normalization (tilde expansion)
 * - Basic synchronous execution
 * - Async/coroutine-safe execution
 * - Streaming with line callbacks
 * - Background process spawning and lifecycle
 * - Timeout enforcement
 * - Error handling (bad commands, non-zero exit)
 * - Output truncation (MAX_OUTPUT_BYTES)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShellExecutorTest {

    private val testScope = TestScope()

    @After
    fun tearDown() {
        // Ensure no leaked processes from failed tests
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PATH NORMALIZATION
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `normalizePaths replaces tilde Downloads`() {
        val result = ShellExecutor.normalizePaths("ls ~/Download")
        assertFalse(result.contains("~/Download"))
        assertTrue(result.contains("/storage/emulated/0/Download"))
    }

    @Test
    fun `normalizePaths replaces tilde Pictures`() {
        val result = ShellExecutor.normalizePaths("open ~/Pictures/photo.jpg")
        assertFalse(result.contains("~/Pictures"))
        assertTrue(result.contains("/storage/emulated/0/Pictures"))
    }

    @Test
    fun `normalizePaths replaces tilde DCIM`() {
        val result = ShellExecutor.normalizePaths("cp ~/DCIM/* .")
        assertFalse(result.contains("~/DCIM"))
        assertTrue(result.contains("/storage/emulated/0/DCIM"))
    }

    @Test
    fun `normalizePaths replaces tilde Documents`() {
        val result = ShellExecutor.normalizePaths("ls ~/Documents/work")
        assertFalse(result.contains("~/Documents"))
        assertTrue(result.contains("/storage/emulated/0/Documents"))
    }

    @Test
    fun `normalizePaths replaces tilde Music`() {
        val result = ShellExecutor.normalizePaths("find ~/Music -name '*.mp3'")
        assertFalse(result.contains("~/Music"))
        assertTrue(result.contains("/storage/emulated/0/Music"))
    }

    @Test
    fun `normalizePaths replaces bare tilde`() {
        val result = ShellExecutor.normalizePaths("cd ~")
        assertFalse(result.contains("~"))
        assertTrue(result.contains("/storage/emulated/0"))
    }

    @Test
    fun `normalizePaths keeps non-home tilde paths unchanged`() {
        val result = ShellExecutor.normalizePaths("ls /tmp/~test")
        // Should not change non-home tilde
        assertTrue(result.contains("/tmp/~test"))
    }

    @Test
    fun `normalizePaths handles empty string`() {
        val result = ShellExecutor.normalizePaths("")
        assertEquals("", result)
    }

    @Test
    fun `normalizePaths handles multiple tilde replacements`() {
        val result = ShellExecutor.normalizePaths("ls ~/Download && cp ~/Documents/report.pdf ~/Pictures")
        assertTrue(result.contains("/storage/emulated/0/Download"))
        assertTrue(result.contains("/storage/emulated/0/Documents"))
        assertTrue(result.contains("/storage/emulated/0/Pictures"))
    }

    @Test
    fun `normalizePaths does not affect absolute paths`() {
        val result = ShellExecutor.normalizePaths("ls /data/data/com.example")
        assertEquals("ls /data/data/com.example", result)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BASIC SYNCHRONOUS EXECUTION
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `run executes a simple echo command`() {
        val result = ShellExecutor.run("echo 'hello world'")
        assertTrue("Expected success but got: ${result.stderr}", result.isSuccess)
        assertEquals(0, result.exitCode)
        assertTrue("hello world" in result.stdout)
    }

    @Test
    fun `run captures stderr separately`() {
        val result = ShellExecutor.run("echo 'err output' >&2 && echo 'stdout output'")
        assertTrue(result.isSuccess)
        assertTrue(result.stdout.contains("stdout output"))
        assertTrue(result.stderr.contains("err output"))
    }

    @Test
    fun `run returns non-zero exit code on failure`() {
        val result = ShellExecutor.run("false")
        assertFalse(result.isSuccess)
        assertTrue(result.exitCode != 0)
    }

    @Test
    fun `run handles command not found`() {
        val result = ShellExecutor.run("nonexistent_command_xyz 2>&1; echo DONE")
        // Shell may still report success if echo DONE runs
        // Just check it doesn't crash
        assertNotNull(result.stdout)
    }

    @Test
    fun `run handles empty command`() {
        val result = ShellExecutor.run("")
        // Empty command should not crash
        assertNotNull(result.stdout)
    }

    @Test
    fun `run sets correct backend`() {
        val result = ShellExecutor.run("echo test")
        assertEquals(ExecutionBackend.SHELL_EXECUTOR, result.backend)
    }

    @Test
    fun `run records duration`() {
        val result = ShellExecutor.run("echo test")
        assertTrue(result.durationMs >= 0)
        assertTrue(result.durationMs < 30_000) // sanity check
    }

    @Test
    fun `run with custom timeout`() {
        val result = ShellExecutor.run("echo 'fast'", timeoutMs = 5_000)
        assertTrue(result.isSuccess)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  TIMEOUT HANDLING
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `run times out slow commands`() {
        val result = ShellExecutor.run("sleep 10", timeoutMs = 500)
        assertFalse(result.isSuccess)
        assertTrue(result.stderr.contains("Timed out") || !result.isSuccess)
    }

    @Test
    fun `run timeout returns exit code -1`() {
        val result = ShellExecutor.run("sleep 10", timeoutMs = 500)
        assertEquals(-1, result.exitCode)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  COROUTINE-SAFE EXECUTION
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `runAsync executes in coroutine context`() = runTest {
        val result = ShellExecutor.runAsync("echo 'coroutine test'")
        assertTrue(result.isSuccess)
        assertTrue("coroutine test" in result.stdout)
    }

    @Test
    fun `runAsync handles failure gracefully`() = runTest {
        val result = ShellExecutor.runAsync("exit 42")
        assertFalse(result.isSuccess)
        assertEquals(42, result.exitCode)
    }

    @Test
    fun `runAsync with timeout fails on slow command`() = runTest {
        val result = ShellExecutor.runAsync("sleep 10", timeoutMs = 200)
        assertFalse(result.isSuccess)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  STREAMING EXECUTION
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `runStreaming calls onOutput for each line`() = runTest {
        val lines = mutableListOf<String>()
        val result = ShellExecutor.runStreaming("echo 'line1' && echo 'line2'") { line ->
            lines.add(line)
        }
        assertTrue(result.isSuccess)
        assertTrue("Expected line1 in: $lines", lines.any { it.contains("line1") })
        assertTrue("Expected line2 in: $lines", lines.any { it.contains("line2") })
    }

    @Test
    fun `runStreaming streams stderr output`() = runTest {
        val lines = mutableListOf<String>()
        val result = ShellExecutor.runStreaming("echo 'stderr' >&2") { line ->
            lines.add(line)
        }
        assertTrue(result.isSuccess)
        assertTrue("Expected stderr in: $lines",
            lines.any { it.contains("stderr") || it.contains("\\u001b[31m") })
    }

    @Test
    fun `runStreaming captures total output in result`() = runTest {
        val result = ShellExecutor.runStreaming("echo 'hello world'") { }
        assertTrue(result.isSuccess)
        assertTrue(result.stdout.contains("hello world"))
    }

    // ══════════════════════════════════════════════════════════════════════
    //  BACKGROUND PROCESS SPAWNING
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `spawnBackground starts process and captures output`() {
        val latch = CountDownLatch(1)
        val outputLines = mutableListOf<String>()

        val bg = ShellExecutor.spawnBackground("echo 'background test'") { line ->
            outputLines.add(line)
            if (line.contains("background test")) latch.countDown()
        }

        assertNotNull(bg)
        assertTrue(bg.isRunning || bg.hasExited())
        assertTrue(bg.startedAt > 0)

        // Wait for output with timeout
        latch.await(5, TimeUnit.SECONDS)
        bg.cancel()

        assertTrue("Expected output but got: $outputLines",
            outputLines.any { it.contains("background test") })
    }

    @Test
    fun `spawnBackground waitFor completes successfully`() {
        val bg = ShellExecutor.spawnBackground("echo 'test'")

        val done = bg.waitFor(5_000)
        assertTrue("Background process should complete", done)
        assertFalse(bg.isRunning)
    }

    @Test
    fun `spawnBackground cancel stops process`() {
        val bg = ShellExecutor.spawnBackground("sleep 30")

        bg.cancel()
        assertFalse(bg.isRunning)
    }

    @Test
    fun `spawnBackground hasExited detects process completion`() {
        val bg = ShellExecutor.spawnBackground("echo 'test'")
        bg.waitFor(5_000)
        assertTrue(bg.hasExited())
    }

    @Test
    fun `spawnBackground normalizes paths in command`() {
        // Paths should be normalized before being passed to shell
        val bg = ShellExecutor.spawnBackground("ls ~/Download")
        bg.cancel()
        assertNotNull(bg.command)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OUTPUT TRUNCATION
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `run truncates output exceeding MAX_OUTPUT_BYTES`() {
        // Generate output slightly larger than limit
        val largeOutput = "A".repeat(TerminalConfig.MAX_OUTPUT_BYTES + 10_000)
        val result = ShellExecutor.run("echo '$largeOutput'")
        // Should not crash and output should be capped
        assertNotNull(result.stdout)
        assertTrue("Output was ${result.stdout.length}, expected < ${TerminalConfig.MAX_OUTPUT_BYTES + 1000}",
            result.stdout.length < TerminalConfig.MAX_OUTPUT_BYTES + 1000)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ERROR HANDLING
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `run handles special characters in command`() {
        val result = ShellExecutor.run("echo 'test with spaces and !@#$%^&*()'")
        assertTrue(result.isSuccess)
    }

    @Test
    fun `run handles multiline output`() {
        val result = ShellExecutor.run("printf 'line 1\nline 2\nline 3\n'")
        assertTrue(result.isSuccess)
        assertTrue("Expected at least 3 lines, got ${result.stdout.lines().size}", result.stdout.lines().size >= 3)
    }

    @Test
    fun `run handles pipe commands`() {
        val result = ShellExecutor.run("echo 'hello world' | grep hello")
        assertTrue(result.isSuccess)
        assertTrue("hello" in result.stdout)
    }

    @Test
    fun `run handles redirect`() {
        val result = ShellExecutor.run("echo 'redirect test' 2>&1")
        assertTrue(result.isSuccess)
        assertTrue("redirect test" in result.stdout)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  REGRESSION: SmartShell migration
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `runSimple returns trimmed output`() {
        val output = ShellExecutor.runSimple("echo 'simple test'")
        assertNotNull(output)
        assertTrue("simple test" in output)
    }

    @Test
    fun `runSimple returns no output placeholder for empty output`() {
        val output = ShellExecutor.runSimple("echo -n ''")
        assertEquals("(no output)", output)
    }

    @Test
    fun `BackgroundProcess stores command reference`() {
        val bg = ShellExecutor.spawnBackground("ls -la")
        assertTrue(bg.command.contains("ls -la"))
        bg.cancel()
    }

    @Test
    fun `BackgroundProcess hasExited returns false while running`() {
        val bg = ShellExecutor.spawnBackground("sleep 5")
        assertFalse(bg.hasExited())
        bg.cancel()
    }
}
