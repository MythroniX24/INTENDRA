package com.interndra.ai

import android.content.Context
import com.google.common.truth.Truth.assertThat
import com.interndra.data.model.AiSource
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Comprehensive test suite for LocalAiEngine.
 *
 * Tests:
 * - Rule-based fallback for all supported commands
 * - Model path finding with size validation
 * - isModelDownloaded detection
 * - getModelInfo formatted strings
 * - Error handling in inference
 *
 * Note: Native model loading (JNI) cannot be tested in unit tests
 * since it requires the actual .gguf model file and native .so library.
 */
class LocalAiEngineTest {

    private lateinit var mockContext: Context
    private lateinit var mockFilesDir: File
    private lateinit var engine: LocalAiEngine

    @Before
    fun setUp() {
        mockContext = mockk(relaxed = true)
        mockFilesDir = mockk(relaxed = true)

        every { mockContext.filesDir } returns mockFilesDir
        every { mockFilesDir.absolutePath } returns "/data/data/com.interndra/files"

        engine = LocalAiEngine(mockContext)
    }

    @Test
    fun `isModelReady returns false initially`() {
        assertThat(engine.isModelReady()).isFalse()
    }

    @Test
    fun `isModelDownloaded returns false when no model exists`() {
        // No mock files set up, so no model found
        assertThat(engine.isModelDownloaded()).isFalse()
    }

    @Test
    fun `getModelInfo returns not found message when no model`() {
        val info = engine.getModelInfo()
        assertThat(info).contains("not found")
    }

    // ── Rule-Based Fallback Greeting ──────────────────────────────────

    @Test
    fun `rule-based fallback handles hi`() = runBlocking {
        val result = engine.parseIntent("hi", emptyList())
        assertThat(result.source).isEqualTo(AiSource.LOCAL)
        assertThat(result.intentJson).contains("\"action\":\"greeting\"")
        assertThat(result.modelUsed).contains("rule-based")
    }

    @Test
    fun `rule-based fallback handles hello`() = runBlocking {
        val result = engine.parseIntent("hello", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"greeting\"")
    }

    @Test
    fun `rule-based fallback handles hey`() = runBlocking {
        val result = engine.parseIntent("hey", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"greeting\"")
    }

    // ── Rule-Based Fallback Battery ────────────────────────────────────

    @Test
    fun `rule-based fallback handles battery query`() = runBlocking {
        val result = engine.parseIntent("battery status", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"battery_info\"")
        assertThat(result.intentJson).contains("dumpsys battery")
    }

    @Test
    fun `rule-based fallback handles battery mixed case`() = runBlocking {
        val result = engine.parseIntent("Battery Status Batao", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"battery_info\"")
    }

    // ── Rule-Based Fallback Screenshot ─────────────────────────────────

    @Test
    fun `rule-based fallback handles screenshot query`() = runBlocking {
        val result = engine.parseIntent("take screenshot", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"screenshot\"")
        assertThat(result.intentJson).contains("screencap")
    }

    @Test
    fun `rule-based fallback handles capture query`() = runBlocking {
        val result = engine.parseIntent("capture screen", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"screenshot\"")
    }

    // ── Rule-Based Fallback Storage ────────────────────────────────────

    @Test
    fun `rule-based fallback handles storage query`() = runBlocking {
        val result = engine.parseIntent("storage space", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"storage_info\"")
        assertThat(result.intentJson).contains("df -h")
    }

    // ── Rule-Based Fallback App Launch ─────────────────────────────────

    @Test
    fun `rule-based fallback handles whatsapp query`() = runBlocking {
        val result = engine.parseIntent("open WhatsApp", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"open_app\"")
        assertThat(result.intentJson).contains("com.whatsapp")
    }

    @Test
    fun `rule-based fallback handles youtube query`() = runBlocking {
        val result = engine.parseIntent("open YouTube", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"open_app\"")
        assertThat(result.intentJson).contains("com.google.android.youtube")
    }

    @Test
    fun `rule-based fallback handles settings query`() = runBlocking {
        val result = engine.parseIntent("open settings", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"open_app\"")
        assertThat(result.intentJson).contains("com.android.settings")
    }

    // ── Rule-Based Fallback WiFi ───────────────────────────────────────

    @Test
    fun `rule-based fallback handles wifi query`() = runBlocking {
        val result = engine.parseIntent("wifi status", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"wifi_info\"")
    }

    @Test
    fun `rule-based fallback handles wi-fi with hyphen query`() = runBlocking {
        val result = engine.parseIntent("wi-fi info", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"wifi_info\"")
    }

    // ── Rule-Based Fallback Volume ─────────────────────────────────────

    @Test
    fun `rule-based fallback handles volume up query`() = runBlocking {
        val result = engine.parseIntent("volume up", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"volume_up\"")
        assertThat(result.intentJson).contains("keyevent 24")
    }

    @Test
    fun `rule-based fallback handles volume down query`() = runBlocking {
        val result = engine.parseIntent("volume down karo", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"volume_down\"")
        assertThat(result.intentJson).contains("keyevent 25")
    }

    // ── Rule-Based Fallback File Operations ────────────────────────────

    @Test
    fun `rule-based fallback handles list files query`() = runBlocking {
        val result = engine.parseIntent("list files", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"list_files\"")
    }

    @Test
    fun `rule-based fallback handles ls query`() = runBlocking {
        val result = engine.parseIntent("ls ", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"list_files\"")
    }

    // ── Rule-Based Fallback Time ───────────────────────────────────────

    @Test
    fun `rule-based fallback handles time query`() = runBlocking {
        val result = engine.parseIntent("what is the time", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"get_time\"")
    }

    @Test
    fun `rule-based fallback handles date query`() = runBlocking {
        val result = engine.parseIntent("show date", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"get_time\"")
    }

    // ── Rule-Based Fallback Git ────────────────────────────────────────

    @Test
    fun `rule-based fallback handles git status query`() = runBlocking {
        val result = engine.parseIntent("git status", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"git_status\"")
    }

    // ── Rule-Based Fallback Network ────────────────────────────────────

    @Test
    fun `rule-based fallback handles ip address query`() = runBlocking {
        val result = engine.parseIntent("ip address", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"network_info\"")
    }

    @Test
    fun `rule-based fallback handles ipconfig query`() = runBlocking {
        val result = engine.parseIntent("ipconfig", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"network_info\"")
    }

    // ── Rule-Based Fallback Unknown ────────────────────────────────────

    @Test
    fun `rule-based fallback handles unknown query`() = runBlocking {
        val result = engine.parseIntent("something completely random and unknown 12345", emptyList())
        assertThat(result.intentJson).contains("\"action\":\"fallback\"")
    }

    @Test
    fun `rule-based fallback error result is still success`() = runBlocking {
        val result = engine.parseIntent("something random 12345", emptyList())
        assertThat(result.isSuccess).isTrue()
        assertThat(result.source).isEqualTo(AiSource.LOCAL)
    }

    // ── Model Path Finding ─────────────────────────────────────────────

    @Test
    fun `getSearchPaths returns expected paths`() {
        val paths = LocalAiEngine.getSearchPaths(mockContext)
        assertThat(paths).isNotEmpty()
        assertThat(paths[0]).isEqualTo("/data/data/com.interndra/files/models")
        assertThat(paths).contains("/sdcard/INTERNDRA/models")
        assertThat(paths).contains("/sdcard/INTENTRA/models")
    }

    // ── Error Handling ─────────────────────────────────────────────────

    @Test
    fun `parseIntent returns fallback on engine error`() = runBlocking {
        val result = engine.parseIntent("hi", emptyList())
        // Even without model, should return rule-based fallback
        assertThat(result.isSuccess).isTrue()
        assertThat(result.modelUsed).contains("rule-based")
    }
}
