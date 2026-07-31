package com.interndra.search

import org.junit.Assert.*
import org.junit.Test

class CitationManagerTest {

    private fun result(title: String, url: String) =
        SearchResult(title = title, url = url, snippet = "snippet text")

    @Test
    fun `empty results produce empty block`() {
        assertEquals("", CitationManager.buildSourcesBlock(emptyList()))
    }

    @Test
    fun `builds compact sources block`() {
        val block = CitationManager.buildSourcesBlock(listOf(
            result("Android Docs", "https://developer.android.com"),
            result("GitHub Repo", "https://github.com/foo/bar")
        ))
        assertTrue(block.contains("🔗 Sources"))
        assertTrue(block.contains("[Android Docs](https://developer.android.com)"))
        assertTrue(block.contains("2."))
    }

    @Test
    fun `caps sources at 5`() {
        val many = (1..10).map { result("Title $it", "https://example.com/$it") }
        val block = CitationManager.buildSourcesBlock(many)
        val linkCount = Regex("""\[""").findAll(block).count()
        assertTrue(linkCount <= 5)
    }

    @Test
    fun `context digest includes snippets and urls`() {
        val digest = CitationManager.buildContextDigest(listOf(
            result("A", "https://a.example.com")
        ))
        assertTrue(digest.contains("https://a.example.com"))
        assertTrue(digest.contains("snippet"))
    }
}
