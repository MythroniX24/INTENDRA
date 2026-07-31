package com.interndra.search

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ProviderManager — builds and selects search providers based on settings.
 *
 * Future providers (Tavily, Exa, Firecrawl, SearXNG, Bing…) register here
 * without touching the pipeline.
 *
 * NOTE: The settings themselves live in [ProviderSettings] (owned by the
 * ViewModel, single source of truth). This class only *uses* a settings
 * snapshot to build the provider chain — it never re-reads persisted config.
 */
class ProviderManager(
    private val context: Context,
    /** Resolves the Gemini provider lazily (needs the app's Gemini key + model). */
    private val geminiProviderFactory: () -> SearchProvider? = { null }
) : SearchProviderResolver {

    private val ddgProvider: DuckDuckGoSearchProvider by lazy {
        DuckDuckGoSearchProvider(
            WebSearchEngine(com.interndra.data.local.AgentDatabase.getInstance(context).dao())
        )
    }

    /**
     * Resolve the Gemini provider freshly on every call. NOT cached: the
     * factory reads the app's current Gemini key + model, so a user who adds
     * or changes their key mid-session must be picked up immediately.
     * OkHttpClient construction is negligible for infrequent searches.
     */
    private fun geminiProvider(): SearchProvider? =
        geminiProviderFactory()?.takeIf { it.isAvailable() }

    override fun hasGeminiConfigured(): Boolean = geminiProvider() != null

    /** Returns the ordered provider chain for a given plan + settings. */
    override fun buildChain(plan: SearchPlan, settings: WebSearchSettings): List<SearchProvider> {
        val chain = mutableListOf<SearchProvider>()

        // Resolve available providers by id.
        val available = mutableMapOf<SearchProviderId, SearchProvider>()
        if (settings.braveConfigured && settings.braveEnabled) {
            available[SearchProviderId.BRAVE] = BraveSearchProvider(settings.braveApiKey)
        }
        geminiProvider()?.let { available[SearchProviderId.GEMINI] = it }
        available[SearchProviderId.DUCKDUCKGO] = ddgProvider

        for (id in plan.preferredProviders) {
            available[id]?.let { chain.add(it) }
        }

        // Fallback safety: always ensure DDG is in the chain.
        if (chain.none { it.id == SearchProviderId.DUCKDUCKGO }) {
            chain.add(ddgProvider)
        }
        return chain.distinctBy { it.id }
    }

    /** Validate a Brave API key with a live test request. */
    suspend fun testBraveApiKey(key: String): Boolean = withContext(Dispatchers.IO) {
        if (key.isBlank()) return@withContext false
        try {
            val provider = BraveSearchProvider(key)
            provider.search("test", maxResults = 1).isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}
