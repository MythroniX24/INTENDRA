package com.interndra.ai

import android.util.Log
import com.interndra.data.knowledge.KnowledgeRepository
import com.interndra.data.local.AgentDao
import com.interndra.data.model.KnowledgeType
import com.interndra.search.WebSearchEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * MemoryTrainer — powers the "Train Memory" button in Settings.
 *
 * One tap does two things:
 *  1. FETCH — runs a few web searches for trending/current topics and saves
 *     the results into the Knowledge Vault (tagged "auto-trained") so the AI
 *     can reference recent info without a search every time.
 *  2. COMPRESS — old auto-trained entries (older than [RETENTION_DAYS], not
 *     pinned by the user) are merged into a single compact digest note and
 *     the originals are deleted. This is NOT "clear all" — pinned entries
 *     and anything the user created manually (NOTE/DOCUMENT/CODE without the
 *     auto-trained tag) are never touched.
 */
class MemoryTrainer(
    private val knowledgeRepo: KnowledgeRepository,
    private val webSearch: WebSearchEngine,
    private val dao: AgentDao
) {
    companion object {
        private const val TAG = "MemoryTrainer"
        const val AUTO_TAG = "auto-trained"
        private const val RETENTION_DAYS = 7L
        private const val MIN_TO_COMPRESS = 6 // don't bother compressing just 1-2 stale items
        private const val TOPICS_PER_RUN   = 6
        private const val RESULTS_PER_TOPIC = 4

        /**
         * A broad, varied pool — each training run randomly samples
         * [TOPICS_PER_RUN] of these. Fixes "same data every click": a fixed
         * 3-topic list always returns near-identical top results from the
         * search engine. With 16+ topics and random sampling, repeated runs
         * surface different combinations and genuinely new information.
         */
        val TOPIC_POOL = listOf(
            "trending news today",
            "world news today",
            "India news today",
            "latest technology news",
            "latest AI news",
            "latest smartphone launches",
            "latest gadget reviews",
            "latest software updates",
            "latest cybersecurity news",
            "latest space exploration news",
            "latest science discoveries",
            "stock market news today",
            "cryptocurrency news today",
            "latest gaming news",
            "latest startup news",
            "latest research breakthroughs"
        )
    }

    data class TrainResult(
        val fetched: Int,
        val compressedInto: Int,
        val deletedStale: Int,
        val errors: List<String> = emptyList()
    ) {
        fun summary(): String = buildString {
            append("✅ Fetched $fetched fresh item${if (fetched == 1) "" else "s"}")
            if (deletedStale > 0) {
                append(", compressed $deletedStale old item${if (deletedStale == 1) "" else "s"} into $compressedInto digest")
            }
            if (errors.isNotEmpty()) append(" (${errors.size} topic(s) failed)")
        }
    }

    /**
     * Runs the full train cycle: fetch fresh info, then compress stale entries.
     * Safe to call repeatedly — compression only triggers once enough stale
     * items have built up (see [MIN_TO_COMPRESS]).
     */
    suspend fun train(topics: List<String> = TOPIC_POOL.shuffled().take(TOPICS_PER_RUN)): TrainResult = withContext(Dispatchers.IO) {
        var fetched = 0
        val errors = mutableListOf<String>()

        topics.forEach { topic ->
            try {
                val results = webSearch.search(topic, maxResults = RESULTS_PER_TOPIC)
                results.forEach { r ->
                    if (r.title.isNotBlank()) {
                        knowledgeRepo.addEntry(
                            title     = r.title.take(150),
                            content   = r.snippet,
                            type      = KnowledgeType.WEB_CLIP,
                            tags      = AUTO_TAG,
                            sourceUrl = r.url
                        )
                        fetched++
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Train fetch failed for '$topic': ${e.message}")
                errors.add(topic)
            }
        }

        val (compressedInto, deletedStale) = compressOldEntries()

        TrainResult(fetched, compressedInto, deletedStale, errors)
    }

    /**
     * Finds auto-trained entries older than [RETENTION_DAYS] that are NOT
     * pinned, merges their gist into ONE compact digest entry, and deletes
     * the originals. This is how memory stays small without losing context —
     * "compress, don't wipe."
     */
    private suspend fun compressOldEntries(): Pair<Int, Int> {
        val autoEntries = dao.getKnowledgeByTag(AUTO_TAG)
        val cutoff = System.currentTimeMillis() - (RETENTION_DAYS * 24 * 60 * 60 * 1000)
        val stale = autoEntries.filter { !it.isPinned && it.createdAt < cutoff }

        if (stale.size < MIN_TO_COMPRESS) return 0 to 0

        val dateLabel = SimpleDateFormat("d MMM", Locale.getDefault()).format(Date())
        val digestLines = stale
            .sortedByDescending { it.createdAt }
            .take(50) // cap digest size so it stays compact
            .joinToString("\n") { "• ${it.title}: ${it.content.take(120)}" }

        knowledgeRepo.addEntry(
            title   = "Digest — ${stale.size} older items ($dateLabel)",
            content = digestLines.take(4000),
            type    = KnowledgeType.NOTE,
            tags    = "$AUTO_TAG,digest"
        )

        stale.forEach { knowledgeRepo.deleteEntry(it) }

        return 1 to stale.size
    }
}
