package com.interndra.data.local

import com.interndra.ai.SmartMemoryEngine
import com.interndra.ai.ThinkingMarker
import com.interndra.data.model.*
import com.interndra.search.SourceMarker
import kotlinx.coroutines.flow.Flow

/**
 * AgentRepository — single source of truth for all Room DAO operations.
 *
 * Wraps AgentDao to:
 *  - maintain current session and workspace scope
 *  - provide convenience methods with sensible defaults
 *  - prevent direct DAO access from ViewModels (testability)
 */
class AgentRepository(private val db: AgentDatabase) {

    private val dao get() = db.dao()
    private val smartMemoryEngine = SmartMemoryEngine()

    // ── Session tracking ────────────────────────────────────────────────────
    private var currentSession: String = ""
    private var currentWorkspaceId: Long = 0L

    fun newSession(): String {
        currentSession = System.currentTimeMillis().toString()
        return currentSession
    }

    fun setWorkspace(workspaceId: Long) {
        currentWorkspaceId = workspaceId
    }

    // ── Chat messages ───────────────────────────────────────────────────────
    fun getMessages(workspaceId: Long = 0): Flow<List<ChatMessage>> =
        dao.getMessages(workspaceId)

    suspend fun addUserMessage(text: String) {
        dao.insertMessage(ChatMessage(role = MessageRole.USER, content = text, workspaceId = currentWorkspaceId))
    }

    suspend fun addAiPlaceholder(): Long =
        dao.insertMessage(ChatMessage(role = MessageRole.AI, content = "...", isLoading = true, workspaceId = currentWorkspaceId))

    /**
     * Returns last N messages as (role, content) pairs for AI conversation history.
     * Gives the AI proper memory of the current conversation.
     */
    suspend fun getChatHistory(limit: Int = 12): List<Pair<String, String>> {
        val msgs = dao.getRecentMessages(currentWorkspaceId, limit)
        return msgs.reversed().mapNotNull { msg ->
            when (msg.role) {
                MessageRole.USER -> Pair("user", msg.content)
                MessageRole.AI   -> {
                    // Strip the hidden thinking + web-source marker blocks FIRST
                    // (they sit at the very end), then our metadata footer
                    // (*Cloud AI · Xms*). Order matters: with the markers present
                    // the footer regex anchored at $ would otherwise never match.
                    val clean = ThinkingMarker.strip(SourceMarker.strip(msg.content))
                        .replace(Regex("""\n\n\*[^*]+\*\s*$"""), "")
                        .trim()
                    if (clean.isNotBlank() && clean != "...") Pair("assistant", clean)
                    else null
                }
                else -> null
            }
        }
    }

    suspend fun getMessageText(id: Long): String? =
        dao.getMessageById(id)?.content

    suspend fun updateAiMessage(id: Long, text: String) =
        dao.updateMessage(id, text)

    suspend fun deleteMessage(id: Long) = dao.deleteMessage(id)

    suspend fun clearMessages() = dao.clearMessages(currentWorkspaceId)

    // ── Terminal logs ───────────────────────────────────────────────────────
    fun getRecentLogs(limit: Int = 200): Flow<List<TerminalLog>> = dao.getRecentLogs(limit)
    fun getLogsForSession(sessionId: String): Flow<List<TerminalLog>> = dao.getLogsForSession(sessionId)

    suspend fun log(session: String, type: LogType, message: String) {
        dao.insertLog(TerminalLog(sessionId = session, logType = type, content = message))
    }

    suspend fun clearLogs() = dao.clearAllLogs()

    // ── Persistent Memory ───────────────────────────────────────────────────
    fun getAllMemories(): Flow<List<MemoryEntry>>         = dao.getAllMemories()
    fun getMemoriesForWorkspace(id: Long): Flow<List<MemoryEntry>> = dao.getMemoriesForWorkspace(id)
    fun getPinnedMemories(): Flow<List<MemoryEntry>>     = dao.getPinnedMemories()
    suspend fun memoryCount(): Int                        = dao.memoryCount()
    suspend fun searchMemories(query: String): List<MemoryEntry> = dao.searchMemories(query)

    suspend fun addMemory(
        title: String, content: String,
        actionType: String = "", commandsJson: String = "",
        workspaceId: Long = currentWorkspaceId,
        tags: String = "", importanceScore: Int = 5
    ): Long = dao.insertMemory(MemoryEntry(
        title = title, content = content, workspaceId = workspaceId,
        tags = tags, importanceScore = importanceScore,
        actionType = actionType, commandsJson = commandsJson
    ))

    suspend fun updateMemory(memory: MemoryEntry)         = dao.updateMemory(memory)
    suspend fun deleteMemory(memory: MemoryEntry)         = dao.deleteMemory(memory)
    suspend fun pinMemory(id: Long, pinned: Boolean)      = dao.setPinned(id, pinned)
    suspend fun archiveMemory(id: Long)                   = dao.setArchived(id, true)
    suspend fun setMemoryImportance(id: Long, score: Int) = dao.setImportance(id, score)
    suspend fun touchMemory(id: Long)                     = dao.touchMemory(id)
    suspend fun clearMemory()                             = dao.clearAllMemories()

    // ── Unified smart memory ────────────────────────────────────────────────
    fun getAllSmartMemories(): Flow<List<SmartMemoryEntity>> = dao.getAllSmartMemories()

    suspend fun buildSmartMemoryContext(
        query: String,
        chatId: Long,
        projectId: Long? = null,
        maxTokens: Int = SmartMemoryEngine.SMALL_MODEL_BUDGET,
        policy: SmartMemoryPolicy = SmartMemoryPolicy()
    ): List<CommandMemory> {
        if (!policy.enabled) return emptyList()
        val candidates = dao.getSmartMemoryCandidates(projectId, chatId)
        val retrieval = smartMemoryEngine.retrieve(
            query = query,
            candidates = candidates,
            scope = SmartMemoryScope(chatId = chatId, projectId = projectId),
            maxTokens = minOf(maxTokens, policy.maxTokens).coerceIn(0, SmartMemoryEngine.LARGE_MODEL_BUDGET),
            policy = policy,
            projectName = ""
        )
        if (retrieval.records.isNotEmpty()) {
            dao.touchSmartMemories(retrieval.records.map { it.id })
        }
        return smartMemoryEngine.toCommandMemory(retrieval)
    }

    suspend fun addSmartMemory(
        type: SmartMemoryType,
        title: String,
        summary: String,
        keywords: String = "",
        chatId: Long? = null,
        projectId: Long? = null,
        importanceScore: Int = 5
    ): Long {
        val cleanSummary = SmartMemoryEngine.compress(summary)
        val existing = dao.getSmartMemoryCandidates(projectId, chatId)
            .firstOrNull { it.memoryType() == type && smartMemoryEngine.isDuplicate(cleanSummary, it) }
        if (existing != null) {
            dao.updateSmartMemory(existing.copy(
                title = title.ifBlank { existing.title },
                summary = cleanSummary,
                keywords = keywords.ifBlank { existing.keywords },
                embeddingJson = SmartMemoryEngine.embeddingJson(cleanSummary),
                importanceScore = maxOf(existing.importanceScore, importanceScore.coerceIn(1, 10)),
                isArchived = false,
                lastAccessedAt = System.currentTimeMillis()
            ))
            return existing.id
        }
        return dao.insertSmartMemory(SmartMemoryEntity(
            type = type.name,
            title = title.take(120),
            summary = cleanSummary,
            keywords = keywords.take(240),
            embeddingJson = SmartMemoryEngine.embeddingJson(cleanSummary),
            chatId = chatId,
            projectId = projectId,
            importanceScore = importanceScore.coerceIn(1, 10)
        ))
    }

    suspend fun updateSmartMemory(memory: SmartMemoryEntity) = dao.updateSmartMemory(memory)
    suspend fun deleteSmartMemory(id: Long) = dao.deleteSmartMemory(id)
    suspend fun archiveSmartMemory(id: Long) = dao.archiveSmartMemory(id)
    suspend fun searchSmartMemories(query: String): List<SmartMemoryEntity> = dao.searchSmartMemories(query)
    suspend fun clearSmartMemory() = dao.clearAllSmartMemories()
    suspend fun archiveStaleSmartMemories(before: Long, maxImportance: Int = 3): Int =
        dao.archiveStaleSmartMemories(before, maxImportance)

    /**
     * Builds a privacy-safe legacy memory context for older callers.

     *
     * SECURITY FIX (Phase 10): the previous implementation shipped raw
     * `memory.content` (which is the user's original input text) to the AI
     * on EVERY request — including cloud calls. That meant anything the user
     * ever typed (passwords, OTPs, phone numbers, API keys) was re-sent to
     * OpenRouter on every subsequent cloud call.
     *
     * New behavior:
     *  - Only include PINNED memories or memories with importanceScore >= 7
     *    (i.e. things the user explicitly marked as worth remembering).
     *  - Run `sanitizeForCloud()` on every memory's content — strips things
     *    that look like passwords, OTPs, API keys, credit-card numbers,
     *    phone numbers, and email addresses.
     *  - Truncate each memory's content to 200 chars so context stays bounded.
     */
    suspend fun buildMemoryContext(limit: Int = 10): List<CommandMemory> {
        val recent = searchMemories("").take(limit * 2)  // over-fetch, then filter
        return recent
            .filter { it.isPinned || it.importanceScore >= 7 }
            .take(limit)
            .map {
                CommandMemory(
                    userInput    = sanitizeForCloud(it.content).take(200),
                    aiIntent     = "",
                    success      = true,
                    actionType   = it.actionType,
                    commandsJson = ""  // NEVER send raw command JSON to cloud
                )
            }
    }

    /**
     * Strips obviously-sensitive substrings before memory is sent to a cloud model.
     * Conservative by design: better to over-redact than to leak a credential.
     */
    private fun sanitizeForCloud(text: String): String {
        var t = text
        // API keys / bearer tokens (long hex or base64-ish strings after a label)
        t = t.replace(Regex("""(?i)(api[_-]?key|token|bearer|secret|password|otp|pin)\s*[:=]\s*[\w\-]{8,}""", RegexOption.IGNORE_CASE)) {
            "${it.value.substringBefore('=').substringBefore(':')}=***REDACTED***"
        }
        // Standalone long tokens (40+ hex chars, common for OAuth/GitHub/etc.)
        t = t.replace(Regex("""\b[0-9a-fA-F]{40,}\b"""), "***REDACTED***")
        // Credit card numbers (16 digits, optional separators)
        t = t.replace(Regex("""\b(?:\d[ -]?){13,16}\b"""), "***CARD***")
        // Phone numbers (+ international or 10-digit)
        t = t.replace(Regex("""\+?\d[\d\s\-]{8,}\d"""), "***PHONE***")
        // Email addresses
        t = t.replace(Regex("""\b[\w.+-]+@[\w.-]+\.\w+\b"""), "***EMAIL***")
        return t
    }

    suspend fun rememberSuccess(
        userInput: String, action: String,
        commands: List<ShellCommand>, aiSource: String
    ) {
        if (!SmartMemoryEngine.shouldPersistCandidate(userInput)) return
        val compressed = SmartMemoryEngine.compress(userInput)
        addMemory(
            title        = compressed.take(80),
            content      = compressed,
            actionType   = action,
            commandsJson = "",
            tags         = aiSource.lowercase(),
            importanceScore = 7
        )
        addSmartMemory(
            type = SmartMemoryType.CHAT,
            title = action.ifBlank { "Successful command" },
            summary = compressed,
            keywords = "$action ${aiSource.lowercase()}",
            chatId = currentWorkspaceId,
            importanceScore = 7
        )
    }

    // ── Workspaces ──────────────────────────────────────────────────────────
    fun getAllWorkspaces(): Flow<List<Workspace>> = dao.getAllWorkspaces()

    suspend fun createWorkspace(
        name: String, emoji: String = "📁",
        colorHex: String = "#00E5FF", persona: String = ""
    ): Long = dao.insertWorkspace(Workspace(
        name = name, emoji = emoji, colorHex = colorHex, persona = persona,
        createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis()
    ))

    suspend fun updateWorkspace(workspace: Workspace) =
        dao.updateWorkspace(workspace.copy(updatedAt = System.currentTimeMillis()))

    suspend fun deleteWorkspace(workspace: Workspace) = dao.deleteWorkspace(workspace)

    suspend fun pinWorkspace(workspace: Workspace, pinned: Boolean) =
        dao.updateWorkspace(workspace.copy(isPinned = pinned, updatedAt = System.currentTimeMillis()))

    suspend fun archiveWorkspace(workspace: Workspace) =
        dao.updateWorkspace(workspace.copy(isArchived = true, updatedAt = System.currentTimeMillis()))

    // ── Network Events ──────────────────────────────────────────────────────
    fun getNetworkEvents(): Flow<List<NetworkEvent>> = dao.getNetworkEvents()

    suspend fun logNetworkEvent(
        domain: String, feature: String,
        dataSentBytes: Int = 0, wasBlocked: Boolean = false
    ) {
        dao.insertNetworkEvent(NetworkEvent(domain = domain, feature = feature,
            dataSentBytes = dataSentBytes, wasBlocked = wasBlocked))
        dao.pruneNetworkEvents(System.currentTimeMillis() - 7 * 24 * 60 * 60 * 1000L)
    }

    suspend fun clearNetworkEvents() = dao.clearNetworkEvents()

    // ── Web search cache ────────────────────────────────────────────────────
    fun dao(): AgentDao = dao
}
