package com.interndra.search

/**
 * CitationManager — formats search results into a clean, compact sources
 * block for the AI's reply. Keeps links minimal (like ChatGPT) — only shown
 * when the user asks for them or when the result is an official page.
 */
object CitationManager {

    private const val MAX_SOURCES = 5

    /**
     * Build the "Sources" footer appended to an AI reply.
     *
     * @param results ranked results.
     * @param includeAll when true, always include the block even for generic
     *        chats; when false (default), still include it — the AI itself is
     *        instructed to mention sources naturally. We keep the block lean.
     */
    fun buildSourcesBlock(results: List<SearchResult>, includeAll: Boolean = false): String {
        if (results.isEmpty()) return ""
        val top = results.take(MAX_SOURCES)
        if (top.isEmpty()) return ""

        val sb = StringBuilder("\n\n**🔗 Sources:**\n")
        top.forEachIndexed { i, r ->
            val title = r.title.ifBlank { "Source ${i + 1}" }.take(80)
            sb.append("${i + 1}. [$title](${r.url})\n")
        }
        return sb.toString().trimEnd()
    }

    /**
     * Build an inline context digest the AI uses to ground its answer.
     * This is internal (never shown raw to the user) — it's injected into the
     * system prompt so the model can cite naturally.
     */
    fun buildContextDigest(results: List<SearchResult>): String {
        if (results.isEmpty()) return ""
        val sb = StringBuilder()
        sb.appendLine("\n[Web Context — ranked search results]")
        results.forEachIndexed { i, r ->
            sb.appendLine("${i + 1}. ${r.title}")
            if (r.snippet.isNotBlank()) {
                sb.appendLine("   ${r.snippet.take(250)}")
            }
            sb.appendLine("   ${r.url}")
            if (r.publishedMs != null) {
                sb.appendLine("   (published ${r.publishedMs})")
            }
        }
        return sb.toString().trim()
    }
}
