package com.interndra.ai

import com.google.common.truth.Truth.assertThat
import okhttp3.OkHttpClient
import org.junit.Before
import org.junit.Test

/**
 * Comprehensive test suite for CloudAiEngine.
 *
 * Tests focus on the JSON extraction/cleanJson logic since
 * the actual HTTP calls require network and API keys.
 *
 * Tests:
 * - cleanJson: strips markdown fences, extracts balanced JSON
 * - cleanJson: action-key-aware extraction
 * - cleanJson: handles plain text responses
 * - cleanJson: handles blank responses
 * - cleanJson: handles trailing garbage after JSON
 * - isConfigured: API key checks
 */
class CloudAiEngineTest {

    private lateinit var engine: CloudAiEngine

    @Before
    fun setUp() {
        engine = CloudAiEngine(apiKey = "test-api-key")
    }

    @Test
    fun `isConfigured returns true for non-blank key`() {
        val e = CloudAiEngine(apiKey = "sk-test123")
        assertThat(e.isConfigured()).isTrue()
    }

    @Test
    fun `isConfigured returns false for blank key`() {
        val e = CloudAiEngine(apiKey = "")
        assertThat(e.isConfigured()).isFalse()
    }

    @Test
    fun `isConfigured returns false for whitespace key`() {
        val e = CloudAiEngine(apiKey = "   ")
        assertThat(e.isConfigured()).isFalse()
    }

    // ── cleanJson: Markdown Fence Stripping ──────────────────────────

    @Test
    fun `cleanJson strips code fences`() {
        val raw = "```json\n{\"action\":\"chat\",\"reply\":\"hello\"}\n```"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
        assertThat(result).doesNotContain("```")
    }

    @Test
    fun `cleanJson handles no fences`() {
        val raw = "{\"action\":\"chat\",\"reply\":\"hello\"}"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).isEqualTo(raw)
    }

    // ── cleanJson: Action-Key Extraction ─────────────────────────────

    @Test
    fun `cleanJson extracts JSON containing action key`() {
        val raw = "Sure! Here you go: {\"action\":\"battery_info\",\"commands\":[]} Let me know if you need more help!"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"battery_info\"")
        assertThat(result).doesNotContain("Sure!")
    }

    @Test
    fun `cleanJson handles text before the JSON with curly braces`() {
        // Model might say something with {} before the actual JSON
        val raw = "The answer to your question about cities like {New York, London} is: {\"action\":\"chat\",\"reply\":\"Here's the info\"}"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
    }

    // ── cleanJson: Balanced JSON Extraction ─────────────────────────

    @Test
    fun `cleanJson handles nested objects properly`() {
        val raw = "{\"action\":\"chat\",\"extras\":{\"key\":\"value with {} braces\"}}"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).isEqualTo(raw)
    }

    @Test
    fun `cleanJson handles quoted strings with braces`() {
        val raw = "{\"action\":\"chat\",\"reply\":\"Braces like { and } are fine in strings\"}"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).isEqualTo(raw)
    }

    // ── cleanJson: Plain Text Fallback ──────────────────────────────

    @Test
    fun `cleanJson wraps plain text as chat reply`() {
        val raw = "Hello! I'm INTERNDRA. How can I help you today?"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
        assertThat(result).contains("Hello! I'm INTERNDRA")
    }

    @Test
    fun `cleanJson handles blank text gracefully`() {
        val result = invokeCleanJson(engine, "")
        assertThat(result).contains("\"action\":\"unknown\"")
    }

    @Test
    fun `cleanJson handles whitespace text gracefully`() {
        val result = invokeCleanJson(engine, "   \n  \t  ")
        assertThat(result).contains("\"action\":\"unknown\"")
    }

    // ── cleanJson: Edge Cases ───────────────────────────────────────

    @Test
    fun `cleanJson handles model appending garbage after JSON`() {
        // Old code used lastIndexOf('}') which would grab trailing garbage
        val raw = "{\"action\":\"chat\",\"reply\":\"ok\",\"commands\":[]}{\"extra\":\"garbage\"}"
        val result = invokeCleanJson(engine, raw)
        // The balanced parser should stop at the first complete object
        assertThat(result).contains("\"action\":\"chat\"")
        assertThat(result).doesNotContain("\"extra\"")
    }

    @Test
    fun `cleanJson handles model with duplicated fragment at end`() {
        val raw = "{\"action\":\"chat\",\"reply\":\"ok\",\"commands\":[]},\"steps\":[],\"commands\":[]}"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
    }

    @Test
    fun `cleanJson handles markdown fence without json specifier`() {
        val raw = "```\n{\"action\":\"chat\",\"reply\":\"test\"}\n```"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).contains("\"action\":\"chat\"")
        assertThat(result).doesNotContain("```")
    }

    @Test
    fun `cleanJson escapes quotes in plain text fallback`() {
        val raw = "It's a \"great\" day!"
        val result = invokeCleanJson(engine, raw)
        assertThat(result).contains("action")
        assertThat(result).contains("chat")
    }

    @Test
    fun `cleanJson truncates long plain text`() {
        val longText = "a".repeat(1000)
        val result = invokeCleanJson(engine, longText)
        assertThat(result.length).isLessThan(1000) // Should be truncated
    }

    // ── API Key Security ──────────────────────────────────────────────

    @Test
    fun `api key is not exposed in configuration`() {
        val e = CloudAiEngine(apiKey = "super-secret-key-12345")
        assertThat(e.isConfigured()).isTrue()
        // toString should not leak key
        assertThat(e.toString()).doesNotContain("super-secret-key-12345")
    }
}

/**
 * Helper to invoke the private cleanJson method via reflection.
 */
private fun invokeCleanJson(engine: CloudAiEngine, raw: String): String {
    val method = CloudAiEngine::class.java.getDeclaredMethod("cleanJson", String::class.java)
    method.isAccessible = true
    return method.invoke(engine, raw) as String
}
