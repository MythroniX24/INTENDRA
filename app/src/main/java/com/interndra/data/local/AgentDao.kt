package com.interndra.data.local

import androidx.room.*
import com.interndra.data.model.*
import kotlinx.coroutines.flow.Flow

@Dao
interface AgentDao {

    // ── Chat messages ───────────────────────────────────────────────────────
    @Query("SELECT * FROM chat_messages WHERE workspaceId = :workspaceId ORDER BY timestamp ASC")
    fun getMessages(workspaceId: Long = 0): Flow<List<ChatMessage>>

    @Insert
    suspend fun insertMessage(msg: ChatMessage): Long

    @Query("UPDATE chat_messages SET content = :content, isLoading = 0 WHERE id = :id")
    suspend fun updateMessage(id: Long, content: String)

    @Query("SELECT * FROM chat_messages WHERE id = :id LIMIT 1")
    suspend fun getMessageById(id: Long): ChatMessage?

    @Query("SELECT * FROM chat_messages ORDER BY id DESC LIMIT :limit")
    suspend fun getRecentMessages(limit: Int): List<ChatMessage>

    @Query("DELETE FROM chat_messages WHERE id = :messageId")
    suspend fun deleteMessage(messageId: Long)

    @Query("DELETE FROM chat_messages WHERE workspaceId = :workspaceId")
    suspend fun clearMessages(workspaceId: Long = 0)

    @Query("DELETE FROM chat_messages")
    suspend fun clearAllMessages()

    // ── Terminal logs ───────────────────────────────────────────────────────
    @Insert
    suspend fun insertLog(log: TerminalLog)

    @Query("SELECT * FROM terminal_logs WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    fun getLogsForSession(sessionId: String): Flow<List<TerminalLog>>

    @Query("SELECT * FROM terminal_logs ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentLogs(limit: Int = 200): Flow<List<TerminalLog>>

    @Query("DELETE FROM terminal_logs")
    suspend fun clearAllLogs()

    // ── Persistent Memory ───────────────────────────────────────────────────
    @Insert
    suspend fun insertMemory(memory: MemoryEntry): Long

    @Update
    suspend fun updateMemory(memory: MemoryEntry)

    @Delete
    suspend fun deleteMemory(memory: MemoryEntry)

    @Query("SELECT * FROM memories WHERE isArchived = 0 ORDER BY isPinned DESC, importanceScore DESC, timestamp DESC")
    fun getAllMemories(): Flow<List<MemoryEntry>>

    @Query("SELECT * FROM memories WHERE workspaceId = :workspaceId AND isArchived = 0 ORDER BY isPinned DESC, importanceScore DESC, timestamp DESC")
    fun getMemoriesForWorkspace(workspaceId: Long): Flow<List<MemoryEntry>>

    @Query("SELECT * FROM memories WHERE isPinned = 1 AND isArchived = 0 ORDER BY timestamp DESC")
    fun getPinnedMemories(): Flow<List<MemoryEntry>>

    @Query("SELECT COUNT(*) FROM memories WHERE isArchived = 0")
    suspend fun memoryCount(): Int

    @Query("SELECT * FROM memories WHERE (content LIKE '%' || :query || '%' OR title LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%') AND isArchived = 0 ORDER BY importanceScore DESC LIMIT 20")
    suspend fun searchMemories(query: String): List<MemoryEntry>

    @Query("UPDATE memories SET accessCount = accessCount + 1, lastAccessedAt = :now WHERE id = :id")
    suspend fun touchMemory(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE memories SET importanceScore = :score WHERE id = :id")
    suspend fun setImportance(id: Long, score: Int)

    @Query("UPDATE memories SET isPinned = :pinned WHERE id = :id")
    suspend fun setPinned(id: Long, pinned: Boolean)

    @Query("UPDATE memories SET isArchived = :archived WHERE id = :id")
    suspend fun setArchived(id: Long, archived: Boolean)

    @Query("DELETE FROM memories")
    suspend fun clearAllMemories()

    // ── Workspaces ──────────────────────────────────────────────────────────
    @Insert
    suspend fun insertWorkspace(workspace: Workspace): Long

    @Update
    suspend fun updateWorkspace(workspace: Workspace)

    @Delete
    suspend fun deleteWorkspace(workspace: Workspace)

    @Query("SELECT * FROM workspaces WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllWorkspaces(): Flow<List<Workspace>>

    @Query("SELECT * FROM workspaces WHERE id = :id LIMIT 1")
    suspend fun getWorkspaceById(id: Long): Workspace?

    // ── Network Events ──────────────────────────────────────────────────────
    @Insert
    suspend fun insertNetworkEvent(event: NetworkEvent)

    @Query("SELECT * FROM network_events ORDER BY timestamp DESC LIMIT :limit")
    fun getNetworkEvents(limit: Int = 100): Flow<List<NetworkEvent>>

    @Query("DELETE FROM network_events WHERE timestamp < :before")
    suspend fun pruneNetworkEvents(before: Long)

    @Query("DELETE FROM network_events")
    suspend fun clearNetworkEvents()

    // ── Web search cache ────────────────────────────────────────────────────
    @Query("SELECT * FROM web_search_cache WHERE `query` = :query AND timestamp >= :minTimestamp LIMIT 1")
    suspend fun getSearchCache(query: String, minTimestamp: Long): WebSearchCache?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSearchCache(cache: WebSearchCache)

    // ── Knowledge Vault ─────────────────────────────────────────────────────
    @Insert
    suspend fun insertKnowledgeEntry(entry: KnowledgeEntry): Long

    @Update
    suspend fun updateKnowledgeEntry(entry: KnowledgeEntry)

    @Delete
    suspend fun deleteKnowledgeEntry(entry: KnowledgeEntry)

    @Query("SELECT * FROM knowledge_entries WHERE isArchived = 0 ORDER BY isPinned DESC, updatedAt DESC")
    fun getAllKnowledgeEntries(): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge_entries WHERE type = :type AND isArchived = 0 ORDER BY updatedAt DESC")
    fun getKnowledgeByType(type: KnowledgeType): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge_entries WHERE isPinned = 1 AND isArchived = 0 ORDER BY updatedAt DESC")
    fun getPinnedKnowledgeEntries(): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge_entries WHERE isArchived = 0 ORDER BY updatedAt DESC LIMIT :limit")
    fun getRecentKnowledgeEntries(limit: Int): Flow<List<KnowledgeEntry>>

    @Query("SELECT * FROM knowledge_entries WHERE (title LIKE '%' || :q || '%' OR content LIKE '%' || :q || '%' OR tags LIKE '%' || :q || '%') AND isArchived = 0 ORDER BY updatedAt DESC LIMIT 30")
    suspend fun searchKnowledgeEntries(q: String): List<KnowledgeEntry>

    @Query("SELECT * FROM knowledge_entries WHERE tags LIKE '%' || :tag || '%' AND isArchived = 0")
    suspend fun getKnowledgeByTag(tag: String): List<KnowledgeEntry>

    @Query("UPDATE knowledge_entries SET isPinned = :pinned WHERE id = :id")
    suspend fun pinKnowledgeEntry(id: Long, pinned: Boolean)

    @Query("UPDATE knowledge_entries SET isArchived = 1 WHERE id = :id")
    suspend fun archiveKnowledgeEntry(id: Long)

    @Query("SELECT COUNT(*) FROM knowledge_entries WHERE isArchived = 0")
    suspend fun knowledgeCount(): Int

    @Query("SELECT COUNT(*) FROM knowledge_entries WHERE type = :type AND isArchived = 0")
    suspend fun knowledgeCountByType(type: KnowledgeType): Int

    // ── Timeline ────────────────────────────────────────────────────────────
    @Insert
    suspend fun insertTimelineEntry(entry: TimelineEntry): Long

    @Query("SELECT * FROM timeline_entries ORDER BY timestamp DESC")
    fun getAllTimelineEntries(): Flow<List<TimelineEntry>>

    @Query("SELECT * FROM timeline_entries WHERE type = :type ORDER BY timestamp DESC")
    fun getTimelineByType(type: TimelineEventType): Flow<List<TimelineEntry>>

    @Query("SELECT * FROM timeline_entries WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getTimelineInRange(start: Long, end: Long): Flow<List<TimelineEntry>>

    @Query("SELECT * FROM timeline_entries ORDER BY timestamp DESC LIMIT :limit")
    fun getRecentTimelineEntries(limit: Int): Flow<List<TimelineEntry>>

    @Query("SELECT * FROM timeline_entries WHERE (title LIKE '%' || :q || '%' OR detail LIKE '%' || :q || '%') ORDER BY timestamp DESC LIMIT 50")
    suspend fun searchTimelineEntries(q: String): List<TimelineEntry>

    @Query("SELECT COUNT(*) FROM timeline_entries")
    suspend fun timelineCount(): Int

    @Query("SELECT COUNT(*) FROM timeline_entries WHERE timestamp BETWEEN :start AND :end")
    suspend fun timelineCountInRange(start: Long, end: Long): Int

    @Query("DELETE FROM timeline_entries WHERE timestamp < :before")
    suspend fun deleteTimelineOlderThan(before: Long): Int

    // ── Automation Rules ────────────────────────────────────────────────────
    @Insert
    suspend fun insertAutomationRule(rule: AutomationRule): Long

    @Update
    suspend fun updateAutomationRule(rule: AutomationRule)

    @Delete
    suspend fun deleteAutomationRule(rule: AutomationRule)

    @Query("SELECT * FROM automation_rules ORDER BY createdAt DESC")
    fun getAllAutomationRules(): Flow<List<AutomationRule>>

    @Query("SELECT * FROM automation_rules WHERE isEnabled = 1 AND triggerCondition != ''")
    suspend fun getActiveTriggerRules(): List<AutomationRule>

    @Query("UPDATE automation_rules SET runCount = runCount + 1, lastRunAt = :now WHERE id = :id")
    suspend fun incrementRunCount(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE automation_rules SET isEnabled = :enabled WHERE id = :id")
    suspend fun setRuleEnabled(id: Long, enabled: Boolean)

    // ── Plugins ─────────────────────────────────────────────────────────────
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlugin(plugin: PluginEntry)

    @Delete
    suspend fun deletePlugin(plugin: PluginEntry)

    @Query("SELECT * FROM plugin_entries ORDER BY installedAt DESC")
    fun getAllPlugins(): Flow<List<PluginEntry>>

    @Query("SELECT COUNT(*) FROM plugin_entries WHERE status = 'ACTIVE'")
    suspend fun activePluginCount(): Int
}
