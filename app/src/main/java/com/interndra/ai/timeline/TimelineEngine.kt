package com.interndra.ai.timeline

import android.content.Context
import android.util.Log
import com.interndra.data.local.AgentDao
import com.interndra.data.model.TimelineEntry
import com.interndra.data.model.TimelineEventType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

/**
 * TimelineEngine — records every meaningful user + system event in a
 * chronological log for replay, audit, and temporal search.
 *
 * Events captured:
 *  - AI messages (chat, terminal)
 *  - Shell commands executed + outcomes
 *  - Knowledge vault additions / deletions
 *  - File imports, OCR runs
 *  - Automation triggers fired
 *  - Model downloads / switches
 *  - App settings changes
 */
class TimelineEngine(
    private val dao: AgentDao,
    private val context: Context
) {
    companion object {
        private const val TAG = "TimelineEngine"
        private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    }

    // ── Flows ─────────────────────────────────────────────────────────────
    fun getAllEvents(): Flow<List<TimelineEntry>>         = dao.getAllTimelineEntries()
    fun getEventsByType(type: TimelineEventType)         = dao.getTimelineByType(type)
    fun getEventsInRange(start: Long, end: Long)         = dao.getTimelineInRange(start, end)
    fun getRecentEvents(limit: Int = 100): Flow<List<TimelineEntry>> = dao.getRecentTimelineEntries(limit)

    // ── Record events ─────────────────────────────────────────────────────
    suspend fun record(
        type: TimelineEventType,
        title: String,
        detail: String     = "",
        outcome: String    = "",
        durationMs: Long   = 0L,
        tags: String       = "",
        relatedId: Long    = 0L
    ) = withContext(Dispatchers.IO) {
        try {
            val entry = TimelineEntry(
                type        = type,
                title       = title.take(200),
                detail      = detail.take(2000),
                outcome     = outcome.take(500),
                durationMs  = durationMs,
                tags        = tags,
                relatedId   = relatedId,
                timestamp   = System.currentTimeMillis()
            )
            dao.insertTimelineEntry(entry)
        } catch (e: Exception) {
            Log.e(TAG, "Timeline record error: ${e.message}")
        }
    }

    // ── Convenience recorders ─────────────────────────────────────────────
    suspend fun recordChat(userMessage: String, aiReply: String, source: String, latencyMs: Long) =
        record(
            type       = TimelineEventType.AI_CHAT,
            title      = "Chat: ${userMessage.take(60)}",
            detail     = userMessage,
            outcome    = aiReply.take(200),
            durationMs = latencyMs,
            tags       = source
        )

    suspend fun recordCommand(command: String, output: String, success: Boolean, durationMs: Long) =
        record(
            type       = TimelineEventType.SHELL_COMMAND,
            title      = "$ ${command.take(60)}",
            detail     = command,
            outcome    = if (success) output.take(500) else "FAILED: ${output.take(300)}",
            durationMs = durationMs,
            tags       = if (success) "success" else "error"
        )

    suspend fun recordKnowledgeAdd(title: String, type: String, entryId: Long) =
        record(
            type      = TimelineEventType.KNOWLEDGE_ADD,
            title     = "Added: $title",
            detail    = "Type: $type",
            relatedId = entryId
        )

    suspend fun recordAutomation(ruleName: String, trigger: String, outcome: String) =
        record(
            type    = TimelineEventType.AUTOMATION_FIRED,
            title   = "Automation: $ruleName",
            detail  = "Trigger: $trigger",
            outcome = outcome
        )

    suspend fun recordModelEvent(event: String, model: String) =
        record(
            type   = TimelineEventType.MODEL_EVENT,
            title  = event,
            detail = "Model: $model"
        )

    suspend fun recordOcr(fileName: String, wordCount: Int, duration: Long) =
        record(
            type       = TimelineEventType.OCR_RUN,
            title      = "OCR: $fileName",
            detail     = "$wordCount words extracted",
            durationMs = duration
        )

    // ── Search ────────────────────────────────────────────────────────────
    suspend fun search(query: String): List<TimelineEntry> = withContext(Dispatchers.IO) {
        if (query.isBlank()) return@withContext emptyList()
        dao.searchTimelineEntries(query)
    }

    // ── Stats ─────────────────────────────────────────────────────────────
    suspend fun count(): Int = withContext(Dispatchers.IO) { dao.timelineCount() }

    suspend fun countToday(): Int = withContext(Dispatchers.IO) {
        val startOfDay = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        dao.timelineCountInRange(startOfDay, System.currentTimeMillis())
    }

    // ── Purge old entries ─────────────────────────────────────────────────
    suspend fun purgeOlderThan(days: Int) = withContext(Dispatchers.IO) {
        val cutoff = System.currentTimeMillis() - days * 86_400_000L
        val deleted = dao.deleteTimelineOlderThan(cutoff)
        Log.d(TAG, "Purged $deleted timeline entries older than $days days")
    }
}
