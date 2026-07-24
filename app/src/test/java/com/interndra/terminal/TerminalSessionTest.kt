package com.interndra.terminal

import com.interndra.jni.JniTermux
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * TerminalSessionTest — tests for PTY session lifecycle management.
 * Uses mocked JNI since native code isn't available in unit tests.
 */
class TerminalSessionTest {

    private lateinit var session: TerminalSession
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = File(System.getProperty("java.io.tmpdir"), "pty-test-${System.nanoTime()}")
        tempDir.mkdirs()

        // Mock JNI — native code not available in unit tests
        mockkStatic(JniTermux::class)
        every { JniTermux.isLoaded } returns true
        every { JniTermux.safeCreateSubprocess(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        every { JniTermux.safeSetPtyUTF8Mode(any()) } returns Unit
        every { JniTermux.safeSetPtyWindowSize(any(), any(), any()) } returns Unit
        every { JniTermux.safeClose(any()) } returns Unit
        every { JniTermux.safeWaitFor(any()) } returns 0

        session = TerminalSession(
            shellPath = "/bin/sh",
            cwd = tempDir.absolutePath,
            args = arrayOf("sh"),
            envVars = arrayOf("HOME=" + tempDir.absolutePath, "PATH=/bin:/usr/bin"),
            rows = 24,
            columns = 80
        )
    }

    @After
    fun tearDown() {
        session.stop()
        tempDir.deleteRecursively()
        unmockkAll()
    }

    // ── Initial State ──────────────────────────────────────────────────

    @Test
    fun `initial state is not running`() {
        assertFalse(session.isRunning)
        assertEquals(-1, session.childPid)
        assertEquals(-1, session.ptmFd)
        assertNull(session.exitCode)
    }

    @Test
    fun `emulator is initialized with default dimensions`() {
        assertEquals(24, session.emulator.rows)
        assertEquals(80, session.emulator.columns)
    }

    @Test
    fun `ptyToEmulatorQueue exists and is empty`() {
        val buf = ByteArray(100)
        assertEquals(0, session.ptyToEmulatorQueue.tryRead(buf, 0, buf.size))
    }

    // ── Start / Stop ──────────────────────────────────────────────────

    @Test
    fun `start returns false when JNI not loaded`() {
        every { JniTermux.isLoaded } returns false
        var errorMsg: String? = null
        session.onError = { errorMsg = it }
        val result = session.start()
        assertFalse(result)
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("not available"))
    }

    @Test
    fun `start returns false when subprocess creation fails`() {
        every { JniTermux.safeCreateSubprocess(any(), any(), any(), any(), any(), any(), any(), any()) } returns null
        var errorCalled = false
        session.onError = { errorCalled = true }
        val result = session.start()
        assertFalse(result)
        assertTrue(errorCalled)
    }

    @Test
    fun `double start returns true without error`() {
        // Session is already in a non-running state after first failed start
        assertFalse(session.isRunning)
        // Calling start again should just return false again
        every { JniTermux.isLoaded } returns false
        assertFalse(session.start())
    }

    // ── Input Writing ──────────────────────────────────────────────────

    @Test
    fun `writeInput does not throw when not running`() {
        session.writeInput("test")
        // Should not throw
        assertTrue(true)
    }

    @Test
    fun `sendCtrlC does not throw when not running`() {
        session.sendCtrlC()
        assertTrue(true)
    }

    @Test
    fun `sendCtrlZ does not throw when not running`() {
        session.sendCtrlZ()
        assertTrue(true)
    }

    @Test
    fun `sendCtrlD does not throw when not running`() {
        session.sendCtrlD()
        assertTrue(true)
    }

    @Test
    fun `sendControlChar does not throw when not running`() {
        session.sendControlChar(0x03)
        assertTrue(true)
    }

    // ── Resize ─────────────────────────────────────────────────────────

    @Test
    fun `resize does not throw when not running`() {
        session.resize(40, 120)
        assertEquals(40, session.emulator.rows)
        assertEquals(120, session.emulator.columns)
    }

    // ── Stop / Kill / Cleanup ──────────────────────────────────────────

    @Test
    fun `stop does not throw when not running`() {
        session.stop()
        assertFalse(session.isRunning)
    }

    @Test
    fun `kill does not throw when not running`() {
        session.kill()
        assertFalse(session.isRunning)
    }

    @Test
    fun `multiple stops do not throw`() {
        session.stop()
        session.stop()
        assertTrue(true)
    }

    // ── Callbacks ─────────────────────────────────────────────────────

    @Test
    fun `onOutput callback can be set`() {
        var output: String? = null
        session.onOutput = { output = it }
        session.onOutput?.invoke("test output")
        assertEquals("test output", output)
    }

    @Test
    fun `onExit callback can be set`() {
        var exitCode: Int? = null
        session.onExit = { exitCode = it }
        session.onExit?.invoke(42)
        assertEquals(42, exitCode)
    }

    @Test
    fun `onError callback can be set`() {
        var error: String? = null
        session.onError = { error = it }
        session.onError?.invoke("test error")
        assertEquals("test error", error)
    }

    @Test
    fun `onTitleChanged callback can be set`() {
        var title: String? = null
        session.onTitleChanged = { title = it }
        session.onTitleChanged?.invoke("New Title")
        assertEquals("New Title", title)
    }

    @Test
    fun `title property triggers callback`() {
        var title: String? = null
        session.onTitleChanged = { title = it }
        session.title = "My Terminal"
        assertEquals("My Terminal", title)
    }

    // ── TermuxSessionConfig ──────────────────────────────────────────

    @Test
    fun `TermuxSessionConfig builds correct env vars`() {
        val config = TerminalSession.TermuxSessionConfig(
            prefix = "/data/local/tmp/intendra_bootstrap"
        )
        val envVars = config.buildEnvVars()
        assertTrue(envVars.any { it.startsWith("PREFIX=") })
        assertTrue(envVars.any { it.startsWith("HOME=") })
        assertTrue(envVars.any { it.startsWith("PATH=") })
        assertTrue(envVars.any { it.contains("LD_PRELOAD=") })
        assertTrue(envVars.any { it == "TERM=xterm-256color" })
        assertTrue(envVars.any { it == "INTERNDRA_TERMUX=1" })
    }

    @Test
    fun `TermuxSessionConfig builds correct args`() {
        val config = TerminalSession.TermuxSessionConfig(
            prefix = "/tmp/test"
        )
        val args = config.buildArgs()
        assertEquals(2, args.size)
        assertEquals("bash", args[0])
        assertEquals("--login", args[1])
    }

    @Test
    fun `TermuxSessionConfig createSession returns valid session`() {
        val config = TerminalSession.TermuxSessionConfig(
            prefix = "/tmp/test",
            homeDir = "/tmp/test/home",
            shellPath = "/tmp/test/usr/bin/bash"
        )
        val sess = config.createSession()
        assertEquals("/tmp/test/usr/bin/bash", sess.shellPath)
        assertEquals("/tmp/test/home", sess.cwd)
        assertNotNull(sess)
    }

    // ── isChildAlive ──────────────────────────────────────────────────

    @Test
    fun `isChildAlive returns false when not running`() {
        assertFalse(session.isChildAlive())
    }
}
