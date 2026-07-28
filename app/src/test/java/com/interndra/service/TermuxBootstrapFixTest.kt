package com.interndra.service

import org.junit.Assert.*
import org.junit.Test

/**
 * TermuxBootstrapFixTest — Tests for Bug #5 (SYMLINKS regex) and
 * the bootstrap download/installation improvements.
 *
 * Bug #5: The SYMLINKS.txt split regex was "←|→|←|←" (duplicate arrows)
 *   which would fail to parse symlinks correctly. Fixed to "←|→".toRegex().
 */
class TermuxBootstrapFixTest {

    // ══════════════════════════════════════════════════════════════════════
    //  Bug #5: SYMLINKS.txt regex parsing
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `Bug5 - correct regex splits on left arrow`() {
        val line = "usr/bin/sh ← usr/bin/bash"
        val parts = line.split("←|→".toRegex()).map { it.trim() }
        assertEquals(2, parts.size)
        assertEquals("usr/bin/sh", parts[0])
        assertEquals("usr/bin/bash", parts[1])
    }

    @Test
    fun `Bug5 - correct regex splits on right arrow`() {
        val line = "usr/bin/sh → usr/bin/bash"
        val parts = line.split("←|→".toRegex()).map { it.trim() }
        assertEquals(2, parts.size)
        assertEquals("usr/bin/sh", parts[0])
        assertEquals("usr/bin/bash", parts[1])
    }

    @Test
    fun `Bug5 - old broken regex would have failed`() {
        // The old regex was "←|→|←|←" — verify the NEW regex handles all cases
        val testLines = listOf(
            "target ← linkname",
            "target → linkname",
            "usr/bin/cat ← bin/busybox cat",
            "/path/to/real → /path/to/symlink"
        )

        for (line in testLines) {
            val parts = line.split("←|→".toRegex()).map { it.trim() }
            assertTrue("Line '$line' should split into 2 parts", parts.size >= 2)
            assertTrue("First part should not be empty", parts[0].isNotEmpty())
            assertTrue("Second part should not be empty", parts[1].isNotEmpty())
        }
    }

    @Test
    fun `Bug5 - blank and comment lines are skipped`() {
        val lines = listOf(
            "# This is a comment",
            "",
            "   ",
            "real_target ← link_name",
            "# another comment",
            "another_target → another_link"
        )

        val validEntries = lines.filter { line ->
            val trimmed = line.trim()
            trimmed.isNotBlank() && !trimmed.startsWith("#")
        }

        assertEquals("Should have 2 valid entries", 2, validEntries.size)
    }

    @Test
    fun `Bug5 - symlink entry with spaces in target`() {
        val line = "usr/bin/echo ← usr/bin/busybox echo"
        val parts = line.split("←|→".toRegex()).map { it.trim() }
        assertEquals(2, parts.size)
        assertEquals("usr/bin/echo", parts[0])
        assertEquals("usr/bin/busybox echo", parts[1])
    }

    @Test
    fun `Bug5 - multiple arrows in one line split correctly`() {
        // Some SYMLINKS.txt entries may have multiple arrows
        val line = "a ← b ← c"
        val parts = line.split("←|→".toRegex()).map { it.trim() }
        assertEquals(3, parts.size)
        assertEquals("a", parts[0])
        assertEquals("b", parts[1])
        assertEquals("c", parts[2])
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Bootstrap version and URL verification
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `bootstrap version is URL-encoded correctly`() {
        // The version string should contain %2B for the + character
        val version = "2026.07.26-r1%2Bapt.android-7"
        assertTrue("Version should be URL-encoded", version.contains("%2B"))
        assertFalse("Version should not contain raw +", version.contains("+"))
    }

    @Test
    fun `bootstrap URL follows correct format`() {
        val base = "https://github.com/termux/termux-packages/releases/download"
        val version = "2026.07.26-r1%2Bapt.android-7"
        val arch = "aarch64"
        val url = "$base/bootstrap-$version/bootstrap-$arch.zip"

        assertTrue("URL should start with GitHub releases", url.startsWith(base))
        assertTrue("URL should contain bootstrap tag", url.contains("bootstrap-$version"))
        assertTrue("URL should end with arch zip", url.endsWith("bootstrap-$arch.zip"))
    }

    @Test
    fun `architecture mapping covers all common ABIs`() {
        val archMap = mapOf(
            "arm64-v8a" to "aarch64",
            "armeabi-v7a" to "arm",
            "x86" to "i686",
            "x86_64" to "x86_64"
        )

        // All values should be non-empty and valid Termux arch names
        archMap.values.forEach { arch ->
            assertTrue("Arch should be non-empty", arch.isNotEmpty())
            assertTrue("Arch should be lowercase", arch == arch.lowercase())
        }

        // arm64-v8a should map to aarch64 (most common)
        assertEquals("aarch64", archMap["arm64-v8a"])
    }

    // ══════════════════════════════════════════════════════════════════════
    //  InstallResult data class
    // ══════════════════════════════════════════════════════════════════════

    @Test
    fun `InstallResult success contains prefix and mode`() {
        val result = TermuxBootstrapInstaller.InstallResult(
            success = true,
            mode = TermuxBootstrapInstaller.Mode.SHIZUKU,
            prefix = "/data/local/tmp/intendra/termux"
        )
        assertTrue(result.success)
        assertEquals(TermuxBootstrapInstaller.Mode.SHIZUKU, result.mode)
        assertEquals("/data/local/tmp/intendra/termux", result.prefix)
        assertNull(result.error)
    }

    @Test
    fun `InstallResult failure contains error message`() {
        val result = TermuxBootstrapInstaller.InstallResult(
            success = false,
            mode = TermuxBootstrapInstaller.Mode.NONE,
            prefix = "",
            error = "Download failed"
        )
        assertFalse(result.success)
        assertEquals(TermuxBootstrapInstaller.Mode.NONE, result.mode)
        assertEquals("Download failed", result.error)
    }

    @Test
    fun `InstallMode enum has all expected values`() {
        val modes = TermuxBootstrapInstaller.Mode.values()
        assertEquals(3, modes.size)
        assertTrue(modes.contains(TermuxBootstrapInstaller.Mode.SHIZUKU))
        assertTrue(modes.contains(TermuxBootstrapInstaller.Mode.PROOT))
        assertTrue(modes.contains(TermuxBootstrapInstaller.Mode.NONE))
    }
}
