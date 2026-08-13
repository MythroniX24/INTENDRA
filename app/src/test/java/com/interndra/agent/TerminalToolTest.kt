package com.interndra.agent

import com.interndra.service.TerminalBackend
import com.interndra.service.TerminalResult
import com.interndra.service.TerminalSessionHandle
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the structured terminal tool layer (§1-§2, §13, §32). */
class TerminalToolTest {

    /** In-memory fake backend — no Android dependencies. */
    private class FakeBackend : TerminalBackend {
        var lastCommand: String = ""
        var lastWorkdir: String = ""
        var result = TerminalResult(0, "ok\n", "", 5L, true)
        var closed = false

        override val displayName: String get() = "fake"
        override fun isAvailable(): Boolean = true

        override suspend fun startSession(sessionId: String, workingDirectory: String): TerminalSessionHandle {
            lastWorkdir = workingDirectory
            return TerminalSessionHandle(sessionId, workingDirectory)
        }

        override suspend fun execute(sessionId: String, command: String, timeoutMs: Long): TerminalResult {
            lastCommand = command
            return result
        }

        override suspend fun changeWorkdir(sessionId: String, target: String): String {
            lastWorkdir = target
            return target
        }

        override suspend fun writeInput(sessionId: String, input: String): Boolean = true

        override suspend fun stopProcess(sessionId: String): Boolean = true

        override suspend fun closeSession(sessionId: String) { closed = true }
    }

    // ── Tool metadata ─────────────────────────────────────────────────────

    @Test
    fun `terminal tool surface has all 8 tools`() {
        val tool = TerminalTool(FakeBackend())
        val names = tool.tools.map { it.name }
        assertEquals(8, names.size)
        assertTrue(names.containsAll(listOf(
            "terminal.execute", "terminal.create_session", "terminal.close_session",
            "terminal.send_input", "terminal.stop", "terminal.start_background",
            "terminal.get_process", "terminal.list_processes"
        )))
    }

    @Test
    fun `execute tool declares input and output schema`() {
        val meta = TerminalTool(FakeBackend()).meta("terminal.execute")
        assertNotNull(meta)
        assertEquals("string", meta!!.inputSchema["command"])
        assertEquals("int", meta.outputSchema["exit_code"])
        assertEquals("string", meta.outputSchema["stdout"])
        assertEquals("string", meta.outputSchema["stderr"])
        assertTrue(meta.cancellable)
    }

    @Test
    fun `background tools declare process id in schema`() {
        val tool = TerminalTool(FakeBackend())
        assertNotNull(tool.meta("terminal.start_background")!!.outputSchema["process_id"])
        assertNotNull(tool.meta("terminal.get_process")!!.inputSchema["process_id"])
    }

    // ── ToolRegistry conversion ───────────────────────────────────────────

    @Test
    fun `toToolDescriptors registers one descriptor per tool`() {
        val descriptors = TerminalTool(FakeBackend()).toToolDescriptors()
        assertEquals(8, descriptors.size)
        descriptors.forEach { d ->
            assertTrue(d.name.startsWith("terminal."))
            assertTrue(d.keywords.contains(d.name))
        }
    }

    @Test
    fun `execute descriptor runs the command through the backend`() = runBlocking {
        val backend = FakeBackend()
        val tool = TerminalTool(backend)
        val descriptor = tool.toToolDescriptors().first { it.name == "terminal.execute" }

        val res = descriptor.execute(mapOf("command" to "pwd"))

        assertTrue(res.success)
        assertEquals("pwd", backend.lastCommand)
        assertTrue(res.output.contains("ok"))
    }

    @Test
    fun `execute descriptor rejects blank command`() = runBlocking {
        val tool = TerminalTool(FakeBackend())
        val descriptor = tool.toToolDescriptors().first { it.name == "terminal.execute" }

        val res = descriptor.execute(emptyMap())

        assertFalse(res.success)
        assertTrue(res.error.isNotBlank())
    }

    // ── Session + observability ───────────────────────────────────────────

    @Test
    fun `execute returns structured result with tool call id`() = runBlocking {
        val backend = FakeBackend()
        val tool = TerminalTool(backend)

        val res = tool.execute("echo hi", workingDirectory = "/workspace/project")

        assertEquals("/workspace/project", backend.lastWorkdir)
        assertEquals(0, res.exitCode)
        assertTrue(res.success)
        assertTrue(res.toolCallId.startsWith("tool-"))
        assertTrue(tool.recentCalls.any { it.id == res.toolCallId && it.success == true })
    }

    @Test
    fun `closeSession forwards to the backend`() = runBlocking {
        val backend = FakeBackend()
        val tool = TerminalTool(backend)
        tool.closeSession("test")
        assertTrue(backend.closed)
    }

    @Test
    fun `background tools are unsupported without a terminal agent`() {
        val tool = TerminalTool(FakeBackend())
        assertEquals(-1, tool.startBackground("s", "sleep 5"))
        assertTrue(tool.listProcesses().isEmpty())
        assertNull(tool.getProcess(1))
    }
}
