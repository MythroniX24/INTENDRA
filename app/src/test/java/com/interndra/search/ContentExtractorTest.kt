package com.interndra.search

import org.junit.Assert.*
import org.junit.Test

class ContentExtractorTest {

    @Test
    fun `extracts main content from article`() {
        val html = """
            <html>
              <head><title>Test Article</title></head>
              <body>
                <nav>Navigation Links Here</nav>
                <header>Header Banner</header>
                <article>
                  <h1>Main Heading</h1>
                  <p>This is the first paragraph of the main article content which should definitely be long enough to pass the twenty character minimum threshold.</p>
                  <p>Second paragraph continues the story with more useful details and information for the reader to consume.</p>
                  <div class="advertisement">Buy stuff now!!!</div>
                </article>
                <footer>Footer copyright text</footer>
              </body>
            </html>
        """.trimIndent()

        val content = ContentExtractor.extractMainContent(html, "fallback")
        assertTrue("should include heading", content.contains("Main Heading"))
        assertTrue("should include paragraph", content.contains("first paragraph"))
        assertFalse("should not include nav", content.contains("Navigation Links"))
        assertFalse("should not include ad", content.contains("Buy stuff"))
        assertFalse("should not include footer", content.contains("Footer copyright"))
    }

    @Test
    fun `extracts title`() {
        val html = "<html><head><title>My Cool Page</title></head><body><p>hi</p></body></html>"
        assertEquals("My Cool Page", ContentExtractor.extractTitle(html))
    }

    @Test
    fun `blank html returns fallback or empty`() {
        assertEquals("", ContentExtractor.extractMainContent("", "fallback"))
        assertEquals("", ContentExtractor.extractTitle(""))
    }

    @Test
    fun `tiny content falls back to title`() {
        val html = "<html><body><p>tiny</p></body></html>"
        val content = ContentExtractor.extractMainContent(html, "Fallback Title")
        assertEquals("Fallback Title", content)
    }
}
