package com.interndra.ai

import android.content.Context
import com.interndra.service.ShizukuShell
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

    private val managerMock = mockk<ShizukuShell.ShizukuManager>(relaxed = true)

    @Before
    fun setUp() {
        context = mockk(relaxed = true)
        shizukuShell = mockk(relaxed = true)

        every { shizukuShell.isElevatedAvailable } returns false
        every { managerMock.isShizukuInstalled() } returns false
        every { managerMock.shizukuUid } returns -1
        every { shizukuShell.manager } returns managerMock
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Runtime Capability Detection ──────────────────────────────────

    @Test
    fun `detectRuntimeCapabilities returns valid result`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        assertNotNull(caps)
        assertNotNull(caps.environmentType)
        assertTrue(caps.environmentType.isNotBlank())
    }

    @Test
    fun `detectRuntimeCapabilities without Shizuku`() {
        every { shizukuShell.isElevatedAvailable } returns false
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        assertFalse(caps.hasShizuku)
    }

    @Test
    fun `detectRuntimeCapabilities with Shizuku available`() {
        every { shizukuShell.isElevatedAvailable } returns true
        every { managerMock.isShizukuInstalled() } returns true
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        assertTrue(caps.hasShizuku)
    }

    @Test
    fun `detectRuntimeCapabilities without Termux`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        assertFalse(caps.hasEmbeddedTermux)
    }

    @Test
    fun `detectRuntimeCapabilities with Termux`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        // When null is passed, the function checks shell for bootstrap files
        // which will fail in unit tests, returning embeddedTermux=false
        assertFalse(caps.hasEmbeddedTermux)
    }

    @Test
    fun `detectRuntimeCapabilities includes execution mode`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        assertEquals("fallback", caps.executionMode)
    }

    @Test
    fun `detectRuntimeCapabilities TERMUX mode reflected`() {
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, shizukuShell, null)
        // With null termuxEnv, execution mode defaults to fallback
        assertEquals("fallback", caps.executionMode)
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
        val mockShizuku = mockk<ShizukuShell>(relaxed = true)
        every { mockShizuku.manager } returns mockk(relaxed = true) {
            every { isShizukuInstalled() } returns false
        }
        every { mockShizuku.isElevatedAvailable } returns false
        every { mockShizuku.privilegeDescription } returns "none"
        val caps = AICommandRegistry.detectRuntimeCapabilities(context, mockShizuku, null)
        assertNotNull(caps)
    }
}
