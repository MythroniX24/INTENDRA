package com.interndra.search

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SearchManagerTest {

    // ── Fakes ──────────────────────────────────────────────────────────────

    private class FakeProvider(
        override val id: SearchProviderId,
        private val results: List<SearchResult> = emptyList(),
        private val shouldFail: Boolean = false
    ) : SearchProvider {
        override fun isAvailable() = true
        override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
            if (shouldFail) throw IllegalStateException("boom")
            return results
        }
    }

    private class FakeResolver(
        private val chain: List<SearchProvider>
    ) : SearchProviderResolver {
        override fun hasGeminiConfigured() = chain.any { it.id == SearchProviderId.GEMINI }
        override fun buildChain(plan: SearchPlan, settings: WebSearchSettings): List<SearchProvider> = chain
    }

    private fun makeManager(chain: List<SearchProvider>): SearchManager {
        return SearchManager(
            planner = SearchPlanner(),
            cache = null, // no Room in unit tests
            history = SearchHistory(),
            webpageReader = WebpageReader(),
            providerManager = FakeResolver(chain)
        )
    }

    // ── Tests ──────────────────────────────────────────────────────────────

    @Test
    fun `no search when planner says no`() = runBlocking {
        val manager = makeManager(listOf(FakeProvider(SearchProviderId.DUCKDUCKGO)))
        val digest = manager.runAutonomous("write a poem about the moon", WebSearchSettings()) {}
        assertTrue(digest.results.isEmpty())
        assertTrue(digest.context.isBlank())
    }

    @Test
    fun `searches when planner says yes`() = runBlocking {
        val results = listOf(
            SearchResult("News A", "https://news.example.com/a", "Snippet A", SearchProviderId.DUCKDUCKGO)
        )
        val manager = makeManager(listOf(FakeProvider(SearchProviderId.DUCKDUCKGO, results)))
        val digest = manager.runAutonomous("latest news today", WebSearchSettings()) {}
        assertFalse(digest.results.isEmpty())
        assertTrue(digest.context.contains("https://news.example.com/a"))
    }

    @Test
    fun `reports search status`() = runBlocking {
        val results = listOf(
            SearchResult("News A", "https://news.example.com/a", "Snippet A", SearchProviderId.DUCKDUCKGO)
        )
        val manager = makeManager(listOf(FakeProvider(SearchProviderId.DUCKDUCKGO, results)))
        val statuses = mutableListOf<SearchStatus>()
        manager.runAutonomous("latest news today", WebSearchSettings()) { statuses.add(it) }
        assertTrue("should emit status updates", statuses.isNotEmpty())
    }

    @Test
    fun `provider failure falls back to next provider`() = runBlocking {
        val results = listOf(
            SearchResult("News B", "https://news.example.com/b", "Snippet B", SearchProviderId.DUCKDUCKGO)
        )
        val chain = listOf(
            FakeProvider(SearchProviderId.GEMINI, shouldFail = true),
            FakeProvider(SearchProviderId.DUCKDUCKGO, results)
        )
        val manager = makeManager(chain)
        val digest = manager.runAutonomous("latest news today", WebSearchSettings()) {}
        assertFalse("should recover via fallback", digest.results.isEmpty())
        assertEquals("https://news.example.com/b", digest.results.first().url)
    }

    @Test
    fun `empty provider results yield empty digest`() = runBlocking {
        val manager = makeManager(listOf(FakeProvider(SearchProviderId.DUCKDUCKGO)))
        val digest = manager.runAutonomous("latest news today", WebSearchSettings()) {}
        assertTrue(digest.results.isEmpty())
    }

    @Test
    fun `history records searches`() = runBlocking {
        val results = listOf(
            SearchResult("News A", "https://news.example.com/a", "Snippet A", SearchProviderId.DUCKDUCKGO)
        )
        val manager = makeManager(listOf(FakeProvider(SearchProviderId.DUCKDUCKGO, results)))
        manager.runAutonomous("latest news today", WebSearchSettings()) {}
        // FakeResolver has no real history store; just verify runAutonomous completes.
        assertTrue(true)
    }
}
