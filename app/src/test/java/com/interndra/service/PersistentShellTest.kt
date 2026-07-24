package com.interndra.service

import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * PersistentShellTest — tests for persistent shell process management.
 * Uses mocked Process to simulate shell behavior without real system access.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PersistentShellTest {

    private lateinit var shell: PersistentShell

    @Before
    fun setUp() {
        // Create shell with test config
        shell = PersistentShell(
            shellPath = "/bin/sh",
            initialWorkdir = "/tmp"
        )
    }

    @After
    fun tearDown() {
        shell.destroy()
    }

    // ── Initial State ──────────────────────────────────────────────────

    @Test
    fun `initial state is not alive`() {
        assertFalse(shell.isAlive)
    }

    @Test
    fun `DEFAULT_ENV contains PATH`() {
        assertTrue(PersistentShell.DEFAULT_ENV.containsKey("PATH"))
    }

    @Test
    fun `DEFAULT_ENV contains HOME`() {
        assertTrue(PersistentShell.DEFAULT_ENV.containsKey("HOME"))
    }

    @Test
    fun `DEFAULT_ENV contains PWD`() {
        assertTrue(PersistentShell.DEFAULT_ENV.containsKey("PWD"))
    }

    @Test
    fun `DEFAULT_ENV has TERM set`() {
        assertEquals("xterm-256color", PersistentShell.DEFAULT_ENV["TERM"])
    }

    @Test
    fun `backend description is not blank`() {
        assertTrue(shell.backendDescription.isNotBlank())
    }

    // ── Shell Path Validation ─────────────────────────────────────────

    @Test
    fun `DEFAULT_SHELL is sh`() {
        assertEquals("/system/bin/sh", PersistentShell.DEFAULT_SHELL)
    }

    @Test
    fun `shellPath is stored correctly`() {
        val sh = PersistentShell(shellPath = "/bin/bash", initialWorkdir = "/home")
        assertEquals("/bin/bash", sh.shellPath)
        assertEquals("/home", sh.initialWorkdir)
        sh.destroy()
    }

    // ── Lifecycle ──────────────────────────────────────────────────────

    @Test
    fun `destroy on non-running shell does not throw`() {
        shell.destroy()
        assertFalse(shell.isAlive)
    }

    @Test
    fun `multiple destroy calls do not throw`() {
        shell.destroy()
        shell.destroy()
        assertTrue(true)
    }

    @Test
    fun `sendRaw does not throw when not alive`() {
        assertFalse(shell.sendRaw("x"))
    }

    // ── Workdir ───────────────────────────────────────────────────────

    @Test
    fun `initial workdir is set`() {
        assertEquals("/tmp", shell.initialWorkdir)
    }

    @Test
    fun `getWorkdir returns initial workdir when not alive`() = runTest {
        val dir = shell.getWorkdir()
        assertEquals("/tmp", dir)
    }

    // ── Complete Deletion ─────────────────────────────────────────────

    @Test
    fun `destroy clears state`() {
        shell.destroy()
        assertFalse(shell.isAlive)
    }
}
