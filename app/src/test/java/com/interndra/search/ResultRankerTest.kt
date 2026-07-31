package com.interndra.search

import org.junit.Assert.*
import org.junit.Test

class ResultRankerTest {

    private fun result(url: String, title: String = "Title", snippet: String = "A decently long snippet describing the page content that is at least fifty characters long for sure.", quality: Int = 0) =
        SearchResult(title = title, url = url, snippet = snippet, qualityScore = quality)

    @Test
    fun `dedupes by normalized url`() {
        val results = listOf(
            result("https://example.com/docs/page"),
            result("https://example.com/docs/page/"),
            result("https://example.com/docs/page")
        )
        val ranked = ResultRanker.rank(results, maxResults = 10)
        assertEquals(1, ranked.size)
    }

    @Test
    fun `prefers official docs`() {
        val results = listOf(
            result("https://random-blog.example.com/post"),
            result("https://developer.android.com/guide")
        )
        val ranked = ResultRanker.rank(results, maxResults = 10)
        assertEquals("developer.android.com/guide", ranked.first().url)
    }

    @Test
    fun `penalizes low quality sites`() {
        val results = listOf(
            result("https://pinterest.com/pin/123"),
            result("https://stackoverflow.com/questions/123")
        )
        val ranked = ResultRanker.rank(results, maxResults = 10)
        assertEquals("stackoverflow.com", ranked.first().url)
    }

    @Test
    fun `prefers longer informative snippets`() {
        val results = listOf(
            result("https://a.example.com", snippet = "short"),
            result("https://b.example.com", snippet = "A much longer and more detailed snippet that clearly contains useful information about the topic at hand.")
        )
        val ranked = ResultRanker.rank(results, maxResults = 10)
        assertEquals("b.example.com", ranked.first().url)
    }

    @Test
    fun `empty input returns empty`() {
        assertTrue(ResultRanker.rank(emptyList()).isEmpty())
    }

    @Test
    fun `respects maxResults`() {
        val results = (1..10).map { result("https://example.com/$it") }
        val ranked = ResultRanker.rank(results, maxResults = 3)
        assertTrue(ranked.size <= 3)
    }

    @Test
    fun `freshness bonus boosts recent results`() {
        val now = System.currentTimeMillis()
        val fresh = result("https://fresh.example.com", quality = 0).copy(publishedMs = now - 1000)
        val old = result("https://old.example.com", quality = 0).copy(publishedMs = now - 100L * 86_400_000L)
        val boosted = ResultRanker.applyFreshnessBonus(listOf(fresh, old))
        val freshScore = boosted.first { it.url == "https://fresh.example.com" }.qualityScore
        val oldScore = boosted.first { it.url == "https://old.example.com" }.qualityScore
        assertTrue("fresh should be boosted", freshScore > oldScore)
    }
}
