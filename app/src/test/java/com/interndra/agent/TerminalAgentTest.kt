package com.interndra.agent

import android.content.Context
import com.interndra.service.*
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * TerminalAgentTest — comprehensive session management, alias, background job,
 * and execution flow tests with mocked backends.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TerminalAgentTest {

    private lateinit var context: Context
    private lateinit var shizukuShell: ShizukuShell
    private lateinit var agent: TerminalAgent
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        shizukuShell = mockk(relaxed = true)

        // Default: Shizuku NOT elevated
        every { shizukuShell.isElevatedAvailable } returns false
        every { shizukuShell.manager } returns mockk(relaxed = true)

        // Mock filesDir for persistence tests — use temp dir
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "interndra-test")
        tempDir.mkdirs()
        every { context.filesDir } returns tempDir

        testScope = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        agent = TerminalAgent(context, shizukuShell, scope = testScope)
    }

    @After
    fun tearDown() {
        agent.shutdown()
        testScope.cancel()
    }

    // ── Session Management ──────────────────────────────────────────────

    @Test
    fun `createSession returns new session`() {
        val session = agent.createSession("test")
        assertEquals("test", session.name)
        assertEquals(TerminalAgent.DEFAULT_WORKDIR, session.workdir)
    }

    @Test
    fun `createSession with custom workdir`() {
        val session = agent.createSession("custom", "/tmp")
        assertEquals("custom", session.name)
        assertEquals("/tmp", session.workdir)
    }

    @Test
    fun `createSession returns existing session unchanged`() {
        val s1 = agent.createSession("dup")
        val s2 = agent.createSession("dup")
        assertSame(s1, s2)
    }

    @Test
    fun `getDefaultSession always returns default`() {
        val s = agent.getDefaultSession()
        assertEquals("default", s.name)
    }

    @Test
    fun `getSessionNames returns all session names`() {
        agent.getDefaultSession()
        agent.createSession("a")
        agent.createSession("b")
        val names = agent.getSessionNames()
        assertTrue(names.contains("a"))
        assertTrue(names.contains("b"))
        assertTrue(names.contains("default"))
    }

    @Test
    fun `removeSession deletes session`() {
        agent.createSession("temp")
        assertTrue(agent.getSessionNames().contains("temp"))
        agent.removeSession("temp")
        assertFalse(agent.getSessionNames().contains("temp"))
    }

    @Test
    fun `renameSession changes name`() {
        agent.createSession("old")
        val result = agent.renameSession("old", "new")
        assertTrue(result)
        assertFalse(agent.getSessionNames().contains("old"))
        assertTrue(agent.getSessionNames().contains("new"))
    }

    @Test
    fun `renameSession returns false for nonexistent session`() {
        val result = agent.renameSession("nope", "still-nope")
        assertFalse(result)
    }

    @Test
    fun `getAllSessions returns all sessions`() {
        agent.getDefaultSession()
        agent.createSession("s1")
        agent.createSession("s2")
        assertEquals(3, agent.getAllSessions().size) // default + s1 + s2
    }

    @Test
    fun `clearHistory clears output and history`() {
        agent.getDefaultSession()
        agent.clearHistory("default")
        val lines = agent.getOutputLines("default")
        assertTrue(lines.isEmpty())
    }

    // ── Workdir Management ──────────────────────────────────────────────

    @Test
    fun `getWorkdir returns default for new session`() {
        val dir = agent.getWorkdir("default")
        assertEquals(TerminalAgent.DEFAULT_WORKDIR, dir)
    }

    @Test
    fun `changeWorkdir updates path`() = runTest {
        agent.changeWorkdir("default", "/tmp/test")
        val dir = agent.getWorkdir("default")
        assertTrue(dir.contains("/tmp/test"))
    }

    // ── Alias Management ────────────────────────────────────────────────

    @Test
    fun `setAlias stores expansion`() = runTest {
        agent.setAlias("default", "gs", "git status")
        val aliases = agent.getAliases("default")
        assertEquals("git status", aliases["gs"])
    }

    @Test
    fun `removeAlias deletes alias`() = runTest {
        agent.setAlias("default", "gs", "git status")
        agent.removeAlias("default", "gs")
        val aliases = agent.getAliases("default")
        assertNull(aliases["gs"])
    }

    @Test
    fun `getAliases returns empty for new session`() {
        val aliases = agent.getAliases("default")
        assertTrue(aliases.isEmpty())
    }

    @Test
    fun `multiple aliases for same session`() = runTest {
        agent.setAlias("default", "gs", "git status")
        agent.setAlias("default", "gl", "git log")
        val aliases = agent.getAliases("default")
        assertEquals(2, aliases.size)
    }

    // ── Environment Variables ───────────────────────────────────────────

    @Test
    fun `setEnv stores variable`() = runTest {
        agent.setEnv("default", "MY_VAR", "hello")
        assertEquals("hello", agent.getEnv("default", "MY_VAR"))
    }

    @Test
    fun `getEnv returns null for unset variable`() {
        assertNull(agent.getEnv("default", "NONEXISTENT"))
    }

    // ── Execution Backend Detection ─────────────────────────────────────

    @Test
    fun `isElevated returns true when Shizuku is available`() {
        every { shizukuShell.isElevatedAvailable } returns true
        assertTrue(agent.isElevated)
    }

    @Test
    fun `isElevated returns false when Shizuku not authorized`() {
        assertFalse(agent.isElevated)
    }

    // ── Background Jobs ────────────────────────────────────────────────

    @Test
    fun `executeBackground returns SessionJob immediately`() = runTest {
        val job = agent.executeBackground("default", "sleep 10")
        assertNotNull(job)
        assertTrue(job.id > 0)
        assertEquals("sleep 10", job.command)
        assertEquals("default", job.sessionName)
        assertEquals(TerminalAgent.BackgroundJobStatus.RUNNING, job.status)
    }

    @Test
    fun `listJobs returns background jobs`() = runTest {
        agent.executeBackground("default", "cmd1")
        agent.executeBackground("default", "cmd2")
        val jobs = agent.listJobs()
        assertTrue(jobs.size >= 2)
    }

    @Test
    fun `listJobsForSession filters by session`() = runTest {
        agent.createSession("other")
        agent.executeBackground("default", "cmd1")
        agent.executeBackground("other", "cmd2")
        val defaultJobs = agent.listJobsForSession("default")
        val otherJobs = agent.listJobsForSession("other")
        assertTrue(defaultJobs.all { it.sessionName == "default" })
        assertTrue(otherJobs.all { it.sessionName == "other" })
    }

    @Test
    fun `getJob returns job by id`() = runTest {
        val job = agent.executeBackground("default", "test-cmd")
        val found = agent.getJob(job.id)
        assertNotNull(found)
        assertEquals(job.id, found!!.id)
    }

    @Test
    fun `getJob returns null for nonexistent id`() {
        assertNull(agent.getJob(99999))
    }

    @Test
    fun `activeJobCount counts running jobs`() = runTest {
        val before = agent.activeJobCount()
        agent.executeBackground("default", "cmd1")
        agent.executeBackground("default", "cmd2")
        val after = agent.activeJobCount()
        assertTrue(after >= before + 2)
    }

    @Test
    fun `cancelJob cancels running job`() = runTest {
        val job = agent.executeBackground("default", "sleep 100")
        val cancelled = agent.cancelJob(job.id)
        assertTrue(cancelled)
        assertEquals(TerminalAgent.BackgroundJobStatus.CANCELLED, job.status)
    }

    @Test
    fun `cancelJob returns false for nonexistent id`() {
        assertFalse(agent.cancelJob(99999))
    }

    @Test
    fun `cancelAllJobs cancels all session jobs`() = runTest {
        agent.executeBackground("default", "sleep 100")
        agent.executeBackground("default", "sleep 200")
        agent.cancelAllJobs("default")
        val jobs = agent.listJobsForSession("default")
        assertTrue(jobs.none { it.isActive })
    }

    // ── History ────────────────────────────────────────────────────────

    @Test
    fun `getHistory returns empty for new session`() {
        val history = agent.getHistory("default")
        assertTrue(history.isEmpty())
    }

    @Test
    fun `getHistory respects limit`() {
        // No history yet — returns empty
        val history = agent.getHistory("default", limit = 10)
        assertTrue(history.size <= 10)
    }

    // ── Output Streaming ──────────────────────────────────────────────

    @Test
    fun `outputFlow emits events`() = runTest(UnconfinedTestDispatcher()) {
        val events = mutableListOf<TerminalAgent.StreamEvent>()
        val job = launch(UnconfinedTestDispatcher()) {
            agent.outputFlow.collect { events.add(it) }
        }
        // With UnconfinedTestDispatcher, collector is active immediately
        agent.createSession("stream-test")
        // Should have at least session creation event
        assertTrue(events.isNotEmpty())
        job.cancel()
    }

    // ── Session Persistence ────────────────────────────────────────────

    @Test
    fun `save and load sessions roundtrip`() {
        agent.createSession("persist-test", "/custom/path")
        agent.saveSessionsToDisk()

        // Create a new agent (simulating restart)
        val agent2 = TerminalAgent(context, shizukuShell, scope = testScope)
        agent2.loadSessionsFromDisk()
        val names = agent2.getSessionNames()
        assertTrue(names.contains("persist-test"))
        agent2.shutdown()
    }

    // ── Edge Cases ─────────────────────────────────────────────────────

    @Test
    fun `removeSession does not crash on nonexistent`() {
        agent.removeSession("nonexistent")
        // Should not throw
        assertTrue(true)
    }

    @Test
    fun `getWorkdir returns default for nonexistent session`() {
        val dir = agent.getWorkdir("nonexistent")
        assertEquals(TerminalAgent.DEFAULT_WORKDIR, dir)
    }

    @Test
    fun `getOutputLines returns empty for nonexistent session`() {
        val lines = agent.getOutputLines("nonexistent")
        assertTrue(lines.isEmpty())
    }
}
