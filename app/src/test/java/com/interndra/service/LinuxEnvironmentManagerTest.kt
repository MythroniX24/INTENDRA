package com.interndra.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for the pure logic of the Linux environment manager (§28). */
class LinuxEnvironmentManagerTest {

    // ── formatBytes ───────────────────────────────────────────────────────

    @Test
    fun `formatBytes handles bytes and orders of magnitude`() {
        assertEquals("0 B", LinuxEnvironmentManager.Utils.formatBytes(0))
        assertEquals("512 B", LinuxEnvironmentManager.Utils.formatBytes(512))
        assertEquals("1.0 KB", LinuxEnvironmentManager.Utils.formatBytes(1024))
        assertEquals("25.0 MB", LinuxEnvironmentManager.Utils.formatBytes(25L * 1024 * 1024))
        assertEquals("2.5 GB", LinuxEnvironmentManager.Utils.formatBytes((2.5 * 1024 * 1024 * 1024).toLong()))
    }

    // ── EnvironmentState helpers ──────────────────────────────────────────

    @Test
    fun `state derives storage label and package count`() {
        val state = LinuxEnvironmentManager.EnvironmentState(
            installed = true,
            storageUsedBytes = 25L * 1024 * 1024,
            installedPackages = listOf("bash", "python", "git")
        )
        assertEquals("25.0 MB", state.storageLabel)
        assertEquals(3, state.packageCount)
    }

    @Test
    fun `empty state is not installed`() {
        val state = LinuxEnvironmentManager.EnvironmentState()
        assertFalse(state.installed)
        assertEquals(0, state.packageCount)
        assertEquals("0 B", state.storageLabel)
    }

    @Test
    fun `installed state reports installed`() {
        val state = LinuxEnvironmentManager.EnvironmentState(installed = true)
        assertTrue(state.installed)
    }

    @Test
    fun `arch unsupported state is visible in label`() {
        val state = LinuxEnvironmentManager.EnvironmentState(
            installed = false,
            archSupported = false,
            archLabel = "Unsupported (mips)"
        )
        assertFalse(state.archSupported)
        assertTrue(state.archLabel.contains("Unsupported"))
    }
}
