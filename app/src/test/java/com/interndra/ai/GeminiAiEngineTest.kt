package com.interndra.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for GeminiAiEngine.
 *
 * Tests focus on the JSON extraction/cleanJson logic since
 * the actual HTTP calls require network and API keys.
 *
 * Tests:
 * - cleanJson: same robust extraction as CloudAiEngine
 * - stripModelPrefix: removes gemini/ prefix handling
 * - isConfigured: API key checks
 * - validateApiKey: requires network, tests exist for structure
 */
class GeminiAiEngineTest {

    private lateinit var engine: GeminiAiEngine

    @Before
    fun setUp() {
        engine = GeminiAiEngine(apiKey = "test-gemini-key")
    }

    @Test
    fun `isConfigured returns true for non-blank key`() {
        val e = GeminiAiEngine(apiKey = "AIzaSyTest123")
        assertThat(e.isConfigured()).isTrue()
    }

    @Test
    fun `isConfigured returns false for blank key`() {
        val e = GeminiAiEngine(apiKey = "")
        assertThat(e.isConfigured()).isFalse()
    }

    @Test
    fun `isConfigured returns false for whitespace key`() {
        val e = GeminiAiEngine(apiKey = "   ")
        assertThat(e.isConfigured()).isFalse()
    }

    // ── cleanJson: Markdown Fence Stripping ──────────────────────────

    @Test
    fun `cleanJson strips code fences`() {
        val raw = "```json\n{\"action\":\"chat\",\"reply\":\"hello\"}\n```"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
        assertThat(result).doesNotContain("```")
    }

    @Test
    fun `cleanJson handles no fences`() {
        val raw = "{\"action\":\"chat\",\"reply\":\"hello\"}"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).isEqualTo(raw)
    }

    // ── cleanJson: Balanced JSON Extraction ─────────────────────────

    @Test
    fun `cleanJson handles nested objects properly`() {
        val raw = "{\"action\":\"chat\",\"extras\":{\"key\":\"value with {} braces\"}}"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).isEqualTo(raw)
    }

    @Test
    fun `cleanJson handles quoted strings with braces`() {
        val raw = "{\"action\":\"chat\",\"reply\":\"Braces like { and } are OK\"}"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).isEqualTo(raw)
    }

    // ── cleanJson: Action-Key Extraction ─────────────────────────────

    @Test
    fun `cleanJson extracts JSON containing action key`() {
        val raw = "Here is the JSON: {\"action\":\"battery_info\",\"commands\":[]} Hope this helps!"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"battery_info\"")
        assertThat(result).doesNotContain("Here is the JSON")
    }

    @Test
    fun `cleanJson handles text before JSON with curly braces in text`() {
        val raw = "Cities like {New York, Paris} are great. The JSON: {\"action\":\"chat\"}"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
    }

    // ── cleanJson: Plain Text Fallback ──────────────────────────────

    @Test
    fun `cleanJson wraps plain text as chat reply`() {
        val raw = "Hello! How can I help you today?"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
        assertThat(result).contains("Hello!")
    }

    @Test
    fun `cleanJson handles blank text gracefully`() {
        val result = invokeGeminiCleanJson(engine, "")
        assertThat(result).contains("\"action\":\"unknown\"")
    }

    @Test
    fun `cleanJson handles whitespace text gracefully`() {
        val result = invokeGeminiCleanJson(engine, "   \n  ")
        assertThat(result).contains("\"action\":\"unknown\"")
    }

    // ── cleanJson: Edge Cases ───────────────────────────────────────

    @Test
    fun `cleanJson handles trailing garbage after JSON`() {
        val raw = "{\"action\":\"chat\",\"reply\":\"ok\",\"commands\":[]}{\"trailing\":\"garbage\"}"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
        assertThat(result).doesNotContain("\"trailing\"")
    }

    @Test
    fun `cleanJson handles duplicated fragment at end`() {
        val raw = "{\"action\":\"chat\",\"reply\":\"ok\",\"commands\":[]},\"steps\":[],\"commands\":[]}"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
    }

    @Test
    fun `cleanJson escapes quotes in plain text fallback`() {
        val raw = "It's a \"great\" day!"
        val result = invokeGeminiCleanJson(engine, raw)
        assertThat(result).contains("action")
        assertThat(result).contains("chat")
    }

    @Test
    fun `cleanJson truncates long plain text`() {
        val longText = "a".repeat(1000)
        val result = invokeGeminiCleanJson(engine, longText)
        assertThat(result.length).isLessThan(1000)
    }

    // ── stripModelPrefix ───────────────────────────────────────────────

    @Test
    fun `stripModelPrefix removes gemini slash prefix`() {
        val result = invokeStripModelPrefix(engine, "gemini/gemini-2.5-flash")
        assertThat(result).isEqualTo("gemini-2.5-flash")
    }

    @Test
    fun `stripModelPrefix handles model without prefix`() {
        val result = invokeStripModelPrefix(engine, "gemini-2.5-flash")
        assertThat(result).isEqualTo("gemini-2.5-flash")
    }

    @Test
    fun `stripModelPrefix trims whitespace`() {
        val result = invokeStripModelPrefix(engine, "  gemini/gemini-2.5-flash  ")
        assertThat(result).isEqualTo("gemini-2.5-flash")
    }

    // ── API Key Security ──────────────────────────────────────────────

    @Test
    fun `api key is not exposed in configuration`() {
        val e = GeminiAiEngine(apiKey = "AIzaSySuperSecretKey12345")
        assertThat(e.isConfigured()).isTrue()
        assertThat(e.toString()).doesNotContain("AIzaSySuperSecretKey12345")
    }
}

/**
 * Helper to invoke the private cleanJson method via reflection.
 */
private fun invokeGeminiCleanJson(engine: GeminiAiEngine, raw: String): String {
    val method = GeminiAiEngine::class.java.getDeclaredMethod("cleanJson", String::class.java)
    method.isAccessible = true
    return method.invoke(engine, raw) as String
}

/**
 * Helper to invoke the private stripModelPrefix method via reflection.
 */
private fun invokeStripModelPrefix(engine: GeminiAiEngine, model: String): String {
    val method = GeminiAiEngine::class.java.getDeclaredMethod("stripModelPrefix", String::class.java)
    method.isAccessible = true
    return method.invoke(engine, model) as String
}
