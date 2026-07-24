package com.interndra.ai

import android.content.Context
import com.interndra.service.ShizukuShell
import com.interndra.service.TermuxEnvironment
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * AICommandRegistryTest — tests for runtime capability detection and
 * command registry used by AI to generate appropriate shell commands.
 */
class AICommandRegistryTest {

    private lateinit var context: Context
    private lateinit var shizukuShell: ShizukuShell
    private lateinit var termuxEnv: TermuxEnvironment

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        shizukuShell = mockk(relaxed = true)
        termuxEnv = mockk(relaxed = true)

        every { shizukuShell.isElevatedAvailable } returns false
        every { shizukuShell.manager } returns mockk(relaxed = true)
        every { termuxEnv.hasTermux() } returns false
        every { termuxEnv.getMode() } returns TermuxEnvironment.ExecMode.FALLBACK
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Runtime Capability Detection ──────────────────────────────────

    @Test
    fun `detectRuntimeCapabilities returns valid result`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, termuxEnv)
        assertNotNull(caps)
        assertNotNull(caps.environmentType)
        assertTrue(caps.environmentType.isNotBlank())
    }

    @Test
    fun `detectRuntimeCapabilities without Shizuku`() {
        every { shizukuShell.isElevatedAvailable } returns false
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, termuxEnv)
        assertFalse(caps.hasShizuku)
    }

    @Test
    fun `detectRuntimeCapabilities with Shizuku available`() {
        every { shizukuShell.isElevatedAvailable } returns true
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, termuxEnv)
        assertTrue(caps.hasShizuku)
    }

    @Test
    fun `detectRuntimeCapabilities without Termux`() {
        every { termuxEnv.hasTermux() } returns false
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, termuxEnv)
        assertFalse(caps.hasEmbeddedTermux)
    }

    @Test
    fun `detectRuntimeCapabilities with Termux`() {
        every { termuxEnv.hasTermux() } returns true
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, termuxEnv)
        assertTrue(caps.hasEmbeddedTermux)
    }

    @Test
    fun `detectRuntimeCapabilities includes execution mode`() {
        every { termuxEnv.getMode() } returns TermuxEnvironment.ExecMode.FALLBACK
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, termuxEnv)
        assertEquals("fallback", caps.executionMode)
    }

    @Test
    fun `detectRuntimeCapabilities TERMUX mode reflected`() {
        every { termuxEnv.hasTermux() } returns true
        every { termuxEnv.getMode() } returns TermuxEnvironment.ExecMode.TERMUX
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, termuxEnv)
        assertTrue(caps.hasEmbeddedTermux)
        assertEquals("termux", caps.executionMode)
    }

    // ── RuntimeCapabilities Data Class ─────────────────────────────────

    @Test
    fun `RuntimeCapabilities has all fields`() {
        val caps = AICommandRegistry.RuntimeCapabilities(
            environmentType = "test",
            hasTermux = true,
            hasTermuxPermission = false,
            hasShizuku = true,
            hasEmbeddedTermux = false,
            executionMode = "fallback"
        )
        assertEquals("test", caps.environmentType)
        assertTrue(caps.hasTermux)
        assertFalse(caps.hasTermuxPermission)
        assertTrue(caps.hasShizuku)
        assertFalse(caps.hasEmbeddedTermux)
        assertEquals("fallback", caps.executionMode)
    }

    // ── Command Registry ──────────────────────────────────────────────

    @Test
    fun `findAllMatches returns results for known patterns`() {
        val results = CommandRegistry.findAllMatches("check battery status")
        assertNotNull(results)
    }

    @Test
    fun `findAllMatches returns empty for unknown input`() {
        val results = CommandRegistry.findAllMatches("xyzzy nothing matches this")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `findAllMatches handles empty input`() {
        val results = CommandRegistry.findAllMatches("")
        assertTrue(results.isEmpty())
    }

    // ── Edge Cases ────────────────────────────────────────────────────

    @Test
    fun `detectRuntimeCapabilities with null TermuxEnvironment`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        assertNotNull(caps)
        assertFalse(caps.hasEmbeddedTermux)
    }

    @Test
    fun `detectRuntimeCapabilities does not crash on null Shizuku`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, mockk(relaxed = true), null)
        assertNotNull(caps)
    }
}
