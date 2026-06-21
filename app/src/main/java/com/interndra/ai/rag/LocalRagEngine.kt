package com.interndra.ai.rag

import android.content.Context
import android.util.Log
import com.interndra.data.knowledge.KnowledgeRepository
import com.interndra.data.model.KnowledgeEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * LocalRagEngine — Retrieval-Augmented Generation pipeline.
 *
 * Strategy: keyword TF-IDF scoring (no external embedding model required).
 * When a local LLM embedding API becomes available, swap score() for a
 * cosine-similarity call without changing any caller code.
 *
 * Flow: query → tokenize → score each chunk → top-k → inject into prompt
 */
class LocalRagEngine(
    private val context: Context,
    private val knowledgeRepo: KnowledgeRepository
) {
    companion object {
        private const val TAG        = "LocalRagEngine"
        private const val CHUNK_SIZE = 400  // characters per chunk
        private const val TOP_K      = 3    // top results to include
        val INDEXABLE_EXTENSIONS = setOf("txt", "md", "kt", "py", "js", "ts", "java", "json", "xml", "csv", "html")
        val STOP_WORDS = setOf(
            "the","is","in","it","of","and","to","a","an","that","this","for","on","are","was",
            "with","at","be","have","from","or","by","but","not","what","all","can","will",
            "do","did","has","had","he","she","they","we","you","i","me","my","your","our"
        )
    }

    data class RagChunk(
        val entryId: Long,
        val title: String,
        val chunk: String,
        val score: Float
    )

    // ── Main retrieval ────────────────────────────────────────────────────
    suspend fun retrieve(query: String, topK: Int = TOP_K): List<RagChunk> = withContext(Dispatchers.Default) {
        if (query.isBlank()) return@withContext emptyList()
        try {
            val entries = knowledgeRepo.search(query)
            val queryTokens = tokenize(query)
            val scored = entries.flatMap { entry -> chunkEntry(entry) }
                .map { chunk -> chunk.copy(score = score(queryTokens, chunk.chunk)) }
                .filter  { it.score > 0f }
                .sortedByDescending { it.score }
                .take(topK)
            Log.d(TAG, "RAG retrieved ${scored.size} chunks for: '${query.take(40)}'")
            scored
        } catch (e: Exception) {
            Log.e(TAG, "RAG error: ${e.message}")
            emptyList()
        }
    }

    suspend fun buildAugmentedPrompt(query: String): String {
        val chunks = retrieve(query)
        if (chunks.isEmpty()) return query
        val context = buildString {
            appendLine("[Retrieved Context]")
            chunks.forEachIndexed { i, chunk ->
                appendLine("${i + 1}. **${chunk.title}**")
                appendLine(chunk.chunk)
                appendLine()
            }
            appendLine("[User Query]")
            appendLine(query)
        }
        return context
    }

    // ── Chunking ──────────────────────────────────────────────────────────
    private fun chunkEntry(entry: KnowledgeEntry): List<RagChunk> {
        val text = "${entry.title}. ${entry.content}"
        return text.chunked(CHUNK_SIZE).mapIndexed { i, chunk ->
            RagChunk(
                entryId = entry.id,
                title   = entry.title,
                chunk   = chunk,
                score   = 0f
            )
        }
    }

    // ── TF-IDF-inspired scoring ────────────────────────────────────────────
    private fun score(queryTokens: Set<String>, text: String): Float {
        val textTokens = tokenize(text)
        if (textTokens.isEmpty()) return 0f
        val matches = queryTokens.count { it in textTokens }
        return matches.toFloat() / queryTokens.size.coerceAtLeast(1)
    }

    private fun tokenize(text: String): Set<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.length > 2 }
            .filter { it !in STOP_WORDS }
            .toSet()
    }

    // ── Document indexer ──────────────────────────────────────────────────
    suspend fun indexDirectory(dirPath: String): Int = withContext(Dispatchers.IO) {
        val dir = java.io.File(dirPath)
        if (!dir.exists() || !dir.isDirectory) return@withContext 0
        var count = 0
        dir.walkTopDown().filter { it.isFile && it.extension in INDEXABLE_EXTENSIONS }.forEach { file ->
            try {
                knowledgeRepo.importTextFile(file)
                count++
                Log.d(TAG, "Indexed: ${file.name}")
            } catch (e: Exception) {
                Log.w(TAG, "Skip ${file.name}: ${e.message}")
            }
        }
        count
    }

}
