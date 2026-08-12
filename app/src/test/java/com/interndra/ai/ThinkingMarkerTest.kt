package com.interndra.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ThinkingMarkerTest — pure JVM tests for the Claude-style collapsible
 * thinking-block marker (encode/decode/strip/insert + round-trips).
 */
class ThinkingMarkerTest {

    private val sampleEpisodes = listOf(
        ThinkingEpisode("Reasoning", listOf("🤔 Intent: search", "⚡ 2 commands planned")),
        ThinkingEpisode("Workflow: Find files", listOf("✅ Done: scanned storage"))
    )

    @Test
    fun `encode then decode round-trips episodes`() {
        val encoded = ThinkingMarker.encode(sampleEpisodes)
        assertTrue("marker must contain start tag", encoded.contains("INTENDRA_THINKING_START"))
        val decoded = ThinkingMarker.decode(encoded)
        assertEquals(2, decoded.size)
        assertEquals("Reasoning", decoded[0].title)
        assertEquals(listOf("🤔 Intent: search", "⚡ 2 commands planned"), decoded[0].steps)
        assertEquals("Workflow: Find files", decoded[1].title)
    }

    @Test
    fun `encode with empty list returns empty string`() {
        assertEquals("", ThinkingMarker.encode(emptyList()))
    }

    @Test
    fun `decode on content without marker returns empty`() {
        assertEquals(emptyList<ThinkingEpisode>(), ThinkingMarker.decode("plain reply text"))
        assertEquals(emptyList<ThinkingEpisode>(), ThinkingMarker.decode(""))
    }

    @Test
    fun `decode on corrupted marker returns empty without crashing`() {
        val corrupted = "text <!--INTENDRA_THINKING_START-->not-json{broken<!--INTENDRA_THINKING_END-->"
        assertEquals(emptyList<ThinkingEpisode>(), ThinkingMarker.decode(corrupted))
    }

    @Test
    fun `strip removes the marker and keeps reply text`() {
        val content = "Hello, here is my answer.\n" +
            ThinkingMarker.encode(sampleEpisodes) +
            "<!--INTENDRA_SOURCES_START-->[]<!--INTENDRA_SOURCES_END-->"
        val stripped = ThinkingMarker.strip(content)
        assertTrue("reply must survive", stripped.contains("Hello, here is my answer"))
        assertTrue("marker must be gone", !stripped.contains("INTENDRA_THINKING"))
        assertTrue("sources marker must survive", stripped.contains("INTENDRA_SOURCES_START"))
    }

    @Test
    fun `strip on content without marker returns unchanged`() {
        val plain = "just a reply"
        assertEquals(plain, ThinkingMarker.strip(plain))
    }

    @Test
    fun `strip on truncated marker removes trailing partial`() {
        val partial = "reply<!--INTENDRA_THINKING_START-->{\"title\":\"x\",\"steps\":[]}"
        assertEquals("reply", ThinkingMarker.strip(partial))
    }

    @Test
    fun `insertBeforeMarker inserts before existing marker`() {
        val content = "answer\n" + ThinkingMarker.encode(sampleEpisodes)
        val updated = ThinkingMarker.insertBeforeMarker(content, "### command output\nok")
        // encode() emits a leading newline, so the marker is preceded by "\n\n".
        assertTrue(updated.startsWith("answer\n\n### command output\nok"))
        assertTrue("marker must come after inserted text", updated.indexOf("command output") < updated.indexOf("INTENDRA_THINKING_START"))
    }

    @Test
    fun `insertBeforeMarker appends when no marker present`() {
        val updated = ThinkingMarker.insertBeforeMarker("answer", "extra")
        assertTrue(updated.endsWith("extra"))
    }

    @Test
    fun `insertBeforeMarker with blank extra returns content unchanged`() {
        assertEquals("answer", ThinkingMarker.insertBeforeMarker("answer", ""))
        assertEquals("answer", ThinkingMarker.insertBeforeMarker("answer", "   "))
    }

    @Test
    fun `count returns number of episodes`() {
        val content = "reply" + ThinkingMarker.encode(sampleEpisodes)
        assertEquals(2, ThinkingMarker.count(content))
        assertEquals(0, ThinkingMarker.count("no marker"))
    }

    @Test
    fun `multiple messages with marker strip cleanly together with source marker`() {
        val reply = "My final answer with **bold** and `code`."
        val sources = "<!--INTENDRA_SOURCES_START-->[{\"title\":\"t\",\"url\":\"https://x.com\"}]<!--INTENDRA_SOURCES_END-->"
        val full = reply + "\n" + ThinkingMarker.encode(sampleEpisodes) + sources
        val clean = ThinkingMarker.strip(full)
        // The marker block is removed; the reply and the (still-present) sources
        // marker are separated by the collapsed surrounding newlines.
        assertEquals(reply + "\n\n" + sources, clean)
    }
}
