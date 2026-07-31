package com.interndra.data.model

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** Memory namespaces are deliberately explicit to prevent cross-chat leakage. */
enum class SmartMemoryType { USER, PROJECT, CHAT }

data class SmartMemoryScope(
    val chatId: Long = 0L,
    val projectId: Long? = null
)

/** User-controlled switches. No setting enables cloud synchronization. */
data class SmartMemoryPolicy(
    val enabled: Boolean = true,
    val userEnabled: Boolean = true,
    val projectEnabled: Boolean = true,
    val chatEnabled: Boolean = true,
    val maxTokens: Int = 500
)

@Entity(
    tableName = "smart_memories",
    indices = [
        Index(value = ["type"]),
        Index(value = ["projectId"]),
        Index(value = ["chatId"]),
        Index(value = ["isArchived"])
    ]
)
data class SmartMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val type: String,
    val title: String,
    val summary: String,
    val keywords: String = "",
    val embeddingJson: String = "",
    val projectId: Long? = null,
    val chatId: Long? = null,
    val importanceScore: Int = 5,
    val createdAt: Long = System.currentTimeMillis(),
    val lastAccessedAt: Long = System.currentTimeMillis(),
    val accessCount: Int = 0,
    val isArchived: Boolean = false
) {
    fun memoryType(): SmartMemoryType = runCatching { SmartMemoryType.valueOf(type) }
        .getOrDefault(SmartMemoryType.CHAT)
}

enum class MemoryNeedReason {
    EXPLICIT_RECALL,
    CONTINUATION,
    PREVIOUS_DECISION,
    PROJECT_REFERENCE,
    LOW_CONTEXT,
    NO_RETRIEVAL
}

data class MemoryNeedDecision(
    val shouldRetrieve: Boolean,
    val score: Int,
    val reason: MemoryNeedReason
)

data class SmartMemoryRetrieval(
    val records: List<SmartMemoryEntity>,
    val injectedText: String,
    val estimatedTokens: Int,
    val decision: MemoryNeedDecision
)
