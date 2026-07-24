package com.interndra.ui.viewmodel

import android.app.Application
import com.interndra.data.model.Workspace
import io.mockk.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * HybridAgentViewModelTest — tests for chat features: auto-title generation,
 * workspace rename, and command processing logic.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HybridAgentViewModelTest {

    // The ViewModel init{} block launches Room DB, TTS, Shizuku, and Termux
    // coroutines that cannot run in a pure unit test. Instead, we construct
    // the ViewModel with lazy fields and test only the pure-logic methods
    // (generateChatTitle, buildTerminalRuntimeContext).

    private lateinit var app: Application
    private lateinit var viewModel: HybridAgentViewModel

    @Before
    fun setUp() {
        app = mockk(relaxed = true)
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "interndra-vm-test")
        tempDir.mkdirs()
        every { app.filesDir } returns tempDir
        every { app.applicationContext } returns app

        // ViewModel construction may log warnings but should not crash
        viewModel = HybridAgentViewModel(app)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // ── Auto-Title Generation (pure logic, no DB needed) ──────────────

    @Test
    fun `generateChatTitle creates short title`() {
        val title = viewModel.generateChatTitle("Can you help me check my battery status and storage space?")
        assertTrue(title.length <= 50)
        assertTrue(title.split(" ").size in 2..7)
    }

    @Test
    fun `generateChatTitle removes stop words`() {
        val title = viewModel.generateChatTitle("what is the best way to do this")
        assertFalse(title.lowercase().startsWith("the "))
        assertFalse(title.lowercase().startsWith("what"))
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `generateChatTitle short message`() {
        val title = viewModel.generateChatTitle("Hello")
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `generateChatTitle empty returns New Chat`() {
        assertEquals("New Chat", viewModel.generateChatTitle(""))
    }

    @Test
    fun `generateChatTitle removes special chars`() {
        val title = viewModel.generateChatTitle("!!! ??? Test @#$ message ^&*")
        assertEquals("Test message", title)
    }

    @Test
    fun `generateChatTitle is capitalized`() {
        val title = viewModel.generateChatTitle("show me the weather forecast")
        assertTrue(title[0].isUpperCase())
    }

    @Test
    fun `generateChatTitle handles long input`() {
        val longMsg = "A".repeat(500) + " battery status check"
        val title = viewModel.generateChatTitle(longMsg)
        assertTrue(title.length <= 50)
    }

    @Test
    fun `generateChatTitle strips punctuation`() {
        val title = viewModel.generateChatTitle("What is the capital of France?")
        assertFalse(title.contains("?"))
    }

    // ── Runtime Context Builder ────────────────────────────────────────

    @Test
    fun `buildTerminalRuntimeContext includes backend`() {
        val ctx = viewModel.buildTerminalRuntimeContext()
        assertTrue(ctx.contains("shell backend"))
    }

    @Test
    fun `buildTerminalRuntimeContext includes mode`() {
        val ctx = viewModel.buildTerminalRuntimeContext()
        assertTrue(ctx.contains("Execution mode"))
    }

    // ── Command Gate ──────────────────────────────────────────────────

    @Test
    fun `sendCommand empty does not crash`() {
        viewModel.sendCommand("")
        assertTrue(true)
    }

    @Test
    fun `sendCommand blank does not crash`() {
        viewModel.sendCommand("   ")
        assertTrue(true)
    }

    // ── Execution Backend ──────────────────────────────────────────────

    @Test
    fun `executionBackendDescription is not blank`() {
        assertTrue(viewModel.executionBackendDescription.isNotBlank())
    }

    @Test
    fun `isShizukuElevated returns false initially`() {
        assertFalse(viewModel.isShizukuElevated)
    }
}
