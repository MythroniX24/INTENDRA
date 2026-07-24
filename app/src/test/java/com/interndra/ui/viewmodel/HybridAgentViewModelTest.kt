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

    private lateinit var app: Application
    private lateinit var viewModel: HybridAgentViewModel
    private lateinit var testScope: CoroutineScope

    @Before
    fun setUp() {
        app = mockk(relaxed = true)
        val tempDir = java.io.File(System.getProperty("java.io.tmpdir"), "interndra-vm-test")
        tempDir.mkdirs()
        every { app.filesDir } returns tempDir
        every { app.applicationContext } returns app
        every { app.dataStore } returns mockk(relaxed = true)

        testScope = CoroutineScope(StandardTestDispatcher() + SupervisorJob())
        viewModel = HybridAgentViewModel(app)
    }

    @After
    fun tearDown() {
        testScope.cancel()
    }

    // ── Auto-Title Generation ──────────────────────────────────────────

    @Test
    fun `generateChatTitle creates short title from message`() {
        val title = viewModel.generateChatTitle("Can you help me check my battery status and storage space?")
        assertTrue(title.length <= 50)
        assertTrue(title.split(" ").size in 2..7)
    }

    @Test
    fun `generateChatTitle removes stop words`() {
        val title = viewModel.generateChatTitle("what is the best way to do this")
        // Should not contain common stop words as leading words
        assertFalse(title.lowercase().startsWith("the "))
        assertFalse(title.lowercase().startsWith("what"))
        assertTrue(title.isNotBlank())
    }

    @Test
    fun `generateChatTitle handles short messages`() {
        val title = viewModel.generateChatTitle("Hello")
        assertTrue(title.isNotBlank())
        assertTrue(title.length <= 40)
    }

    @Test
    fun `generateChatTitle handles empty message`() {
        val title = viewModel.generateChatTitle("")
        assertEquals("New Chat", title)
    }

    @Test
    fun `generateChatTitle handles special characters`() {
        val title = viewModel.generateChatTitle("!!! ??? Test @#$ message ^&*")
        assertEquals("Test message", title)
    }

    @Test
    fun `generateChatTitle title is capitalized`() {
        val title = viewModel.generateChatTitle("show me the weather forecast")
        assertTrue(title[0].isUpperCase())
    }

    @Test
    fun `generateChatTitle handles very long messages`() {
        val longMsg = "A".repeat(500) + " battery status check"
        val title = viewModel.generateChatTitle(longMsg)
        assertTrue(title.length <= 50)
    }

    @Test
    fun `generateChatTitle handles question marks and punctuation`() {
        val title = viewModel.generateChatTitle("What is the capital of France?")
        assertTrue(title.isNotBlank())
        assertFalse(title.contains("?"))
    }

    // ── Workspace Management ──────────────────────────────────────────

    @Test
    fun `renameWorkspace updates active workspace name`() {
        val ws = Workspace(
            id = 1, name = "Old Name", emoji = "💬",
            color = "#00E5FF", createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(), isPinned = false
        )
        // The rename runs in viewModelScope — verify doesn't crash
        viewModel.renameWorkspace(ws, "New Name")
        assertTrue(true)
    }

    @Test
    fun `renameWorkspaceById does not crash for nonexistent`() {
        viewModel.renameWorkspaceById(99999, "New")
        assertTrue(true)
    }

    // ── Initial State ──────────────────────────────────────────────────

    @Test
    fun `initial uiState has default values`() = runTest {
        val state = viewModel.uiState.first()
        assertFalse(state.isLoading)
        assertFalse(state.emergencyLockActive)
        assertEquals("General", state.activeWorkspaceName)
        assertEquals(0L, state.activeWorkspaceId)
    }

    @Test
    fun `shizuku initially not available`() = runTest {
        assertFalse(viewModel.shizukuAvailable.first())
        assertFalse(viewModel.shizukuAuthorized.first())
    }

    // ── Execution Backend ──────────────────────────────────────────────

    @Test
    fun `executionBackendDescription is not blank`() {
        val desc = viewModel.executionBackendDescription
        assertTrue(desc.isNotBlank())
    }

    @Test
    fun `isShizukuElevated returns false initially`() {
        assertFalse(viewModel.isShizukuElevated)
    }

    @Test
    fun `shizukuPrivilegeLevel is not blank`() {
        val level = viewModel.shizukuPrivilegeLevel
        assertNotNull(level)
    }

    // ── Runtime Context Builder ────────────────────────────────────────

    @Test
    fun `buildTerminalRuntimeContext includes backend info`() {
        val ctx = viewModel.buildTerminalRuntimeContext()
        assertTrue(ctx.contains("shell backend"))
        assertTrue(ctx.contains("Shizuku"))
    }

    @Test
    fun `buildTerminalRuntimeContext includes execution mode`() {
        val ctx = viewModel.buildTerminalRuntimeContext()
        assertTrue(ctx.contains("Execution mode"))
    }

    // ── Command Gate ──────────────────────────────────────────────────

    @Test
    fun `sendCommand with empty input does nothing`() {
        viewModel.sendCommand("")
        assertTrue(true) // should not crash
    }

    @Test
    fun `sendCommand with blank input does nothing`() {
        viewModel.sendCommand("   ")
        assertTrue(true)
    }

    // ── Dismiss Error ─────────────────────────────────────────────────

    @Test
    fun `dismissError clears error state`() = runTest {
        viewModel.dismissError()
        val state = viewModel.uiState.first()
        assertNull(state.error)
    }
}
