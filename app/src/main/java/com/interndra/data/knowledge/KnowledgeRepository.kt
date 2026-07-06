package com.interndra.data.knowledge

import android.content.Context
import android.util.Log
import com.interndra.data.local.AgentDao
import com.interndra.data.model.KnowledgeEntry
import com.interndra.data.model.KnowledgeType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File

/**
 * KnowledgeRepository — CRUD for the local Knowledge Vault.
 *
 * The vault stores structured knowledge items (notes, web clips,
 * research articles, code snippets, documents) with tags and embeddings
 * for RAG retrieval.
 */
class KnowledgeRepository(
    private val dao: AgentDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "KnowledgeRepo"
    }

    // ── Flows ─────────────────────────────────────────────────────────────
    fun getAllEntries(): Flow<List<KnowledgeEntry>>          = dao.getAllKnowledgeEntries()
    fun getByType(type: KnowledgeType): Flow<List<KnowledgeEntry>> = dao.getKnowledgeByType(type)
    fun getPinned(): Flow<List<KnowledgeEntry>>              = dao.getPinnedKnowledgeEntries()
    fun getRecent(limit: Int = 50): Flow<List<KnowledgeEntry>> = dao.getRecentKnowledgeEntries(limit)

    // ── Write ─────────────────────────────────────────────────────────────
    suspend fun addEntry(
        title: String,
        content: String,
        type: KnowledgeType = KnowledgeType.NOTE,
        tags: String        = "",
        sourceUrl: String   = "",
        filePath: String    = ""
    ): Long = withContext(Dispatchers.IO) {
        val entry = KnowledgeEntry(
            title       = title.take(200),
            content     = content,
            type        = type,
            tags        = tags,
            sourceUrl   = sourceUrl,
            filePath    = filePath,
            wordCount   = content.split(Regex("\\s+")).size,
            createdAt   = System.currentTimeMillis(),
            updatedAt   = System.currentTimeMillis()
        )
        val id = dao.insertKnowledgeEntry(entry)
        Log.d(TAG, "Added knowledge entry #$id: $title")
        id
    }

    suspend fun updateEntry(entry: KnowledgeEntry) = withContext(Dispatchers.IO) {
        dao.updateKnowledgeEntry(entry.copy(updatedAt = System.currentTimeMillis()))
    }

    suspend fun deleteEntry(entry: KnowledgeEntry) = withContext(Dispatchers.IO) {
        dao.deleteKnowledgeEntry(entry)
        // Delete associated file if it exists
        if (entry.filePath.isNotBlank()) {
            try { File(entry.filePath).delete() } catch (e: Exception) { /* ignore */ }
        }
        Log.d(TAG, "Deleted knowledge entry #${entry.id}")
    }

    suspend fun pinEntry(id: Long, pinned: Boolean) = withContext(Dispatchers.IO) {
        dao.pinKnowledgeEntry(id, pinned)
    }

    suspend fun archiveEntry(id: Long) = withContext(Dispatchers.IO) {
        dao.archiveKnowledgeEntry(id)
    }

    // ── Search ────────────────────────────────────────────────────────────
    suspend fun search(query: String): List<KnowledgeEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        dao.searchKnowledgeEntries(query)
    }

    suspend fun searchByTag(tag: String): List<KnowledgeEntry> = withContext(Dispatchers.IO) {
        dao.getKnowledgeByTag(tag)
    }

    // ── Stats ─────────────────────────────────────────────────────────────
    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.knowledgeCount() }

    suspend fun countByType(type: KnowledgeType): Int = withContext(Dispatchers.IO) {
        dao.knowledgeCountByType(type)
    }

    // ── Import helpers ────────────────────────────────────────────────────
    suspend fun importTextFile(file: File): Long = withContext(Dispatchers.IO) {
        val content = try { file.readText() } catch (e: Exception) { return@withContext -1L }
        addEntry(
            title    = file.nameWithoutExtension,
            content  = content,
            type     = KnowledgeType.DOCUMENT,
            filePath = file.absolutePath
        )
    }

    suspend fun addWebClip(url: String, title: String, snippet: String): Long {
        return addEntry(
            title     = title,
            content   = snippet,
            type      = KnowledgeType.WEB_CLIP,
            sourceUrl = url
        )
    }

    suspend fun addCodeSnippet(title: String, code: String, language: String): Long {
        return addEntry(
            title   = title,
            content = code,
            type    = KnowledgeType.CODE,
            tags    = language
        )
    }

    // ── RAG context builder ───────────────────────────────────────────────
    suspend fun buildRagContext(query: String, maxEntries: Int = 3): String = withContext(Dispatchers.IO) {
        val results = search(query).take(maxEntries)
        if (results.isEmpty()) return@withContext ""
        val sb = StringBuilder()
        sb.appendLine("\n[Knowledge Vault Context]")
        results.forEach { entry ->
            sb.appendLine("## ${entry.title}")
            sb.appendLine(entry.content.take(400))
            sb.appendLine()
        }
        sb.toString().trim()
    }
}
