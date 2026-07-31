package com.interndra.search

/**
 * DuckDuckGoSearchProvider — zero-configuration fallback provider.
 *
 * Wraps the existing [WebSearchEngine] (DuckDuckGo HTML scraping) so the app
 * always has a working search backend even when no API keys are configured.
 * Used as the last resort in the provider chain.
 */
class DuckDuckGoSearchProvider(
    private val engine: WebSearchEngine
) : SearchProvider {

    override val id: SearchProviderId = SearchProviderId.DUCKDUCKGO

    override fun isAvailable(): Boolean = true

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> {
        val raw = engine.search(query, maxResults)
        return raw.map {
            SearchResult(
                title = it.title,
                url = it.url,
                snippet = it.snippet,
                provider = SearchProviderId.DUCKDUCKGO
            )
        }
    }
}
