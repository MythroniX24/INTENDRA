package com.interndra.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Unit tests for ABI detection + architecture mapping (Embedded Linux spec §5). */
class DeviceArchitectureTest {

    @Test
    fun `arm64 maps to aarch64 for termux and proot`() {
        assertEquals("aarch64", DeviceArchitecture.termuxArchName("arm64-v8a"))
        assertEquals("aarch64", DeviceArchitecture.prootArchName("arm64-v8a"))
    }

    @Test
    fun `armeabi maps to arm`() {
        assertEquals("arm", DeviceArchitecture.termuxArchName("armeabi-v7a"))
        assertEquals("arm", DeviceArchitecture.prootArchName("armeabi-v7a"))
    }

    @Test
    fun `x86_64 maps to x86_64`() {
        assertEquals("x86_64", DeviceArchitecture.termuxArchName("x86_64"))
        assertEquals("x86_64", DeviceArchitecture.prootArchName("x86_64"))
    }

    @Test
    fun `x86 maps to i686 for termux`() {
        assertEquals("i686", DeviceArchitecture.termuxArchName("x86"))
    }

    @Test
    fun `unknown abi returns null`() {
        assertNull(DeviceArchitecture.termuxArchName("mips"))
        assertNull(DeviceArchitecture.termuxArchName("riscv64"))
    }

    @Test
    fun `detect picks the first supported abi in order`() {
        val d = DeviceArchitecture.detect(listOf("x86_64", "arm64-v8a"))
        assertEquals("x86_64", d.abi)   // platform order wins
        assertTrue(d.supported)
        assertEquals("x86_64", d.termuxArch)
    }

    @Test
    fun `detect handles a typical arm64 device`() {
        val d = DeviceArchitecture.detect(listOf("arm64-v8a", "armeabi-v7a", "armeabi"))
        assertEquals("arm64-v8a", d.abi)
        assertEquals("aarch64", d.termuxArch)
        assertTrue(d.supported)
        assertEquals("arm64-v8a", d.label)
    }

    @Test
    fun `detect reports unsupported when nothing matches`() {
        val d = DeviceArchitecture.detect(listOf("mips", "riscv64"))
        assertFalse(d.supported)
        assertNull(d.abi)
        assertNull(d.termuxArch)
        assertTrue(d.label.contains("Unsupported"))
    }

    @Test
    fun `detect handles empty abi list without crashing`() {
        val d = DeviceArchitecture.detect(emptyList())
        assertFalse(d.supported)
        assertTrue(d.label.isNotBlank())
    }

    @Test
    fun `never downloads wrong architecture binaries`() {
        // The termux arch name must be a known good one for every supported abi.
        DeviceArchitecture.SUPPORTED_ABIS.forEach { abi ->
            assertNotNull("termux arch for $abi", DeviceArchitecture.termuxArchName(abi))
            assertNotNull("proot arch for $abi", DeviceArchitecture.prootArchName(abi))
        }
    }
}
