package com.interndra.search

/**
 * SearchProvider — provider-agnostic contract.
 *
 * Every web-search backend (Gemini Google Search, Brave, DuckDuckGo, and any
 * future provider: Tavily, Exa, SearXNG, Bing…) implements this interface.
 * The [SearchManager] only talks to this interface, so adding a provider is a
 * matter of implementing the interface and registering it — no pipeline changes.
 */
interface SearchProvider {
    val id: SearchProviderId

    /** Whether this provider can be used right now (key configured, etc.). */
    fun isAvailable(): Boolean

    /**
     * Run a search and return normalized [SearchResult]s.
     * Must be safe to call from any coroutine (implementation handles IO).
     * Throws on hard failures so the manager can fall back to the next provider.
     */
    suspend fun search(query: String, maxResults: Int = 6): List<SearchResult>
}

/**
 * Convenience base: providers that are purely key-gated.
 */
abstract class KeyedSearchProvider(
    protected val apiKey: String
) : SearchProvider {
    override fun isAvailable(): Boolean = apiKey.isNotBlank()
}
