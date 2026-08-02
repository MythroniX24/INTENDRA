package com.interndra.search

import org.junit.Assert.*
import org.junit.Test

class SourceMarkerTest {

    private fun result(title: String, url: String) =
        SearchResult(title = title, url = url, snippet = "snippet")

    // ── encode ────────────────────────────────────────────────────────────

    @Test
    fun `empty sources produce empty marker`() {
        assertEquals("", SourceMarker.encode(emptyList()))
    }

    @Test
    fun `encode embeds a hidden marker with JSON`() {
        val marker = SourceMarker.encode(listOf(
            result("Android Docs", "https://developer.android.com")
        ))
        assertTrue(marker.contains(SourceMarker.START))
        assertTrue(marker.contains(SourceMarker.END))
        assertTrue(marker.contains("https://developer.android.com"))
        // Must NOT contain markdown link syntax (links are not dumped in chat)
        assertFalse(marker.contains("[Android Docs]("))
    }

    @Test
    fun `encode caps sources at 5`() {
        val many = (1..10).map { result("Title $it", "https://example.com/$it") }
        val decoded = SourceMarker.decode(SourceMarker.encode(many))
        assertEquals(5, decoded.size)
    }

    // ── decode ────────────────────────────────────────────────────────────

    @Test
    fun `decode round-trips encode output`() {
        val sources = listOf(
            result("Python Docs", "https://docs.python.org/3"),
            result("GitHub", "https://github.com/termux/termux-app")
        )
        val decoded = SourceMarker.decode(SourceMarker.encode(sources))
        assertEquals(2, decoded.size)
        assertEquals("Python Docs", decoded[0].title)
        assertEquals("https://docs.python.org/3", decoded[0].url)
        assertEquals("GitHub", decoded[1].title)
    }

    @Test
    fun `decode returns empty when no marker`() {
        assertTrue(SourceMarker.decode("just a normal reply").isEmpty())
        assertTrue(SourceMarker.decode("").isEmpty())
    }

    @Test
    fun `decode returns empty on malformed json`() {
        val broken = "${SourceMarker.START}\n{{{{not json\n${SourceMarker.END}"
        assertTrue(SourceMarker.decode(broken).isEmpty())
    }

    @Test
    fun `decode ignores empty urls`() {
        val marker = "${SourceMarker.START}\n" +
            "[{\"title\":\"ok\",\"url\":\"https://example.com\"},{\"title\":\"no url\",\"url\":\"\"}]\n" +
            SourceMarker.END
        val decoded = SourceMarker.decode(marker)
        assertEquals(1, decoded.size)
        assertEquals("https://example.com", decoded[0].url)
    }

    // ── strip ─────────────────────────────────────────────────────────────

    @Test
    fun `strip removes the marker block`() {
        val reply = "The answer is here."
        val withMarker = reply + SourceMarker.encode(listOf(result("S", "https://x.com")))
        assertEquals(reply, SourceMarker.strip(withMarker))
    }

    @Test
    fun `strip handles partially typed marker during streaming`() {
        val reply = "Streaming answer"
        val partial = reply + "\n\n<!--INTENDRA_SOURCES_ST"
        assertEquals(reply, SourceMarker.strip(partial))
    }

    @Test
    fun `strip leaves plain content untouched`() {
        val plain = "No sources here at all"
        assertEquals(plain, SourceMarker.strip(plain))
        assertEquals("", SourceMarker.strip(""))
    }

    @Test
    fun `strip keeps text appended before the marker`() {
        val content = "Answer.\n\n### Output\n```\nls\n```\n\n<!--INTENDRA_SOURCES_START-->\n[]\n<!--INTENDRA_SOURCES_END-->"
        val stripped = SourceMarker.strip(content)
        assertTrue(stripped.contains("### Output"))
        assertTrue(stripped.contains("ls"))
        assertFalse(stripped.contains("INTENDRA_SOURCES"))
    }

    // ── insertBeforeMarker ────────────────────────────────────────────────

    @Test
    fun `insertBeforeMarker inserts before the marker`() {
        val marker = SourceMarker.encode(listOf(result("S", "https://x.com")))
        val content = "Answer." + marker
        val updated = SourceMarker.insertBeforeMarker(content, "### Output\n```\nresult\n```")
        // Output must be visible after strip
        assertTrue(SourceMarker.strip(updated).contains("result"))
        // Marker still at the end
        assertTrue(updated.endsWith(SourceMarker.END))
        // Sources still decodable
        assertEquals(1, SourceMarker.decode(updated).size)
    }

    @Test
    fun `insertBeforeMarker appends when no marker`() {
        val updated = SourceMarker.insertBeforeMarker("Answer", "More")
        assertEquals("Answer\n\nMore", updated)
    }

    @Test
    fun `insertBeforeMarker ignores blank extra`() {
        val content = "Answer" + SourceMarker.encode(listOf(result("S", "https://x.com")))
        assertEquals(content, SourceMarker.insertBeforeMarker(content, ""))
    }

    // ── hostname ──────────────────────────────────────────────────────────

    @Test
    fun `hostname strips scheme and www`() {
        assertEquals("docs.python.org", SourceMarker.hostname("https://docs.python.org/3/tutorial/"))
        assertEquals("github.com", SourceMarker.hostname("https://www.github.com/termux/termux-app"))
    }

    @Test
    fun `hostname falls back gracefully`() {
        // Unparseable but non-empty input falls back to the raw text
        assertFalse(SourceMarker.hostname("not-a-url").isBlank())
        // Blank input has no host — returns empty; the UI only renders
        // sources whose url is non-blank anyway.
        assertEquals("", SourceMarker.hostname(""))
    }
}
