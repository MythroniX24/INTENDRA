package com.interndra.ai

import com.interndra.data.model.CommandMemory
import com.interndra.data.model.MemoryNeedDecision
import com.interndra.data.model.MemoryNeedReason
import com.interndra.data.model.SmartMemoryEntity
import com.interndra.data.model.SmartMemoryRetrieval
import com.interndra.data.model.SmartMemoryScope
import com.interndra.data.model.SmartMemoryType
import kotlin.math.max
import kotlin.math.min

/**
 * Pure, model-independent memory policy. It never talks to the network and
 * never receives the complete chat database; callers provide only gated rows.
 */
class SmartMemoryEngine {
    companion object {
        const val SMALL_MODEL_BUDGET = 400
        const val LARGE_MODEL_BUDGET = 1_200
        // Project references and low-context follow-ups score 3; allowing
        // that score keeps implicit project memory useful without scanning on
        // every ordinary question.
        private const val RETRIEVAL_THRESHOLD = 3
        private val STOP_WORDS = setOf(
            "a", "an", "and", "are", "as", "at", "be", "but", "by", "for", "from",
            "how", "i", "in", "is", "it", "me", "my", "of", "on", "or", "that", "the",
            "this", "to", "was", "what", "when", "with", "you", "your"
        )
        private val EXPLICIT_RECALL = listOf(
            "what do you remember", "do you remember", "recall", "memory", "from before",
            "previous discussion", "previous conversation", "earlier chat", "history"
        )
        private val CONTINUATION = listOf(
            "continue", "pick up", "where we left", "as before", "last time", "again",
            "still working", "resume", "go on"
        )
        private val PREVIOUS_DECISION = listOf(
            "what did we decide", "why did we choose", "previous decision", "you said",
            "we agreed", "the approach we used", "our plan"
        )

        fun estimateTokens(text: String): Int = max(1, (text.length + 3) / 4)

        /** Small deterministic local embedding metadata; no model or network required. */
        fun embeddingJson(text: String, dimensions: Int = 32): String {
            val vector = IntArray(dimensions)
            Regex("[a-z0-9_]{3,}").findAll(text.lowercase()).forEach { match ->
                val bucket = (match.value.hashCode() and Int.MAX_VALUE) % dimensions
                vector[bucket] += 1
            }
            val magnitude = kotlin.math.sqrt(vector.sumOf { it * it }.toDouble()).coerceAtLeast(1.0)
            return vector.joinToString(",") { "%.4f".format(java.util.Locale.US, it / magnitude) }
        }

        /** Avoids storing ordinary one-off commands unless the user signals durable value. */
        fun shouldPersistCandidate(text: String): Boolean {
            val value = text.trim().lowercase()
            if (value.isBlank()) return false
            return listOf(
                "remember", "save this", "keep in mind", "don't forget", "do not forget",
                "prefer", "always use", "default", "project", "architecture", "we decided",
                "our plan", "long term", "future"
            ).any(value::contains)
        }

        /** Compresses a candidate without a model call, preserving privacy and meaning. */
        fun sanitizeForExternal(text: String): String {
            var value = text
            value = value.replace(Regex("(?i)(api[_-]?key|token|bearer|secret|password|otp|pin)\\s*[:=]\\s*[^\\s,;]+"), "$1=***REDACTED***")
            value = value.replace(Regex("\\b[0-9a-fA-F]{40,}\\b"), "***REDACTED***")
            value = value.replace(Regex("\\b(?:\\d[ -]?){13,16}\\b"), "***CARD***")
            value = value.replace(Regex("\\+?\\d[\\d\\s\\-]{8,}\\d"), "***PHONE***")
            value = value.replace(Regex("\\b[\\w.+-]+@[\\w.-]+\\.\\w+\\b"), "***EMAIL***")
            return value
        }

        /**
         * Normalizes and bounds text for local storage. This deliberately does
         * not redact secrets: local memory is user-owned and must remain
         * lossless enough to be useful when the user explicitly asks INTENDRA
         * to remember something. Use [compressForExternal] at cloud boundaries.
         */
        fun compress(text: String, maxChars: Int = 360): String {
            val value = text.replace(Regex("\\s+"), " ").trim()
            return if (value.length <= maxChars) value else value.take(maxChars).trimEnd() + "…"
        }

        /** Redacts sensitive values only for a bounded external payload. */
        fun compressForExternal(text: String, maxChars: Int = 360): String =
            compress(sanitizeForExternal(text), maxChars)
    }

    fun decideNeed(query: String, currentContext: String = "", projectName: String = ""): MemoryNeedDecision {
        val text = query.trim().lowercase()
        if (text.isBlank()) return MemoryNeedDecision(false, 0, MemoryNeedReason.NO_RETRIEVAL)

        var score = 0
        var reason = MemoryNeedReason.NO_RETRIEVAL
        fun add(points: Int, candidate: MemoryNeedReason) {
            if (points > score) reason = candidate
            score += points
        }
        if (EXPLICIT_RECALL.any(text::contains)) add(6, MemoryNeedReason.EXPLICIT_RECALL)
        if (CONTINUATION.any(text::contains)) add(4, MemoryNeedReason.CONTINUATION)
        if (PREVIOUS_DECISION.any(text::contains)) add(4, MemoryNeedReason.PREVIOUS_DECISION)
        if (projectName.isNotBlank() && text.contains(projectName.lowercase())) {
            add(3, MemoryNeedReason.PROJECT_REFERENCE)
        } else if (Regex("\\b(project|repository|codebase|workspace|our app|this app)\\b").containsMatchIn(text)) {
            add(3, MemoryNeedReason.PROJECT_REFERENCE)
        }
        if (Regex("\\b(it|that|this|they|those|the same)\\b").containsMatchIn(text) && currentContext.isBlank()) {
            add(3, MemoryNeedReason.LOW_CONTEXT)
        }

        return if (score >= RETRIEVAL_THRESHOLD) {
            MemoryNeedDecision(true, min(score, 10), reason)
        } else {
            MemoryNeedDecision(false, score, MemoryNeedReason.NO_RETRIEVAL)
        }
    }

    fun retrieve(
        query: String,
        candidates: List<SmartMemoryEntity>,
        scope: SmartMemoryScope,
        maxTokens: Int,
        policy: com.interndra.data.model.SmartMemoryPolicy = com.interndra.data.model.SmartMemoryPolicy(),
        currentContext: String = "",
        projectName: String = ""
    ): SmartMemoryRetrieval {
        val decision = decideNeed(query, currentContext, projectName)
        if (!policy.enabled || !decision.shouldRetrieve || maxTokens <= 0) {
            return SmartMemoryRetrieval(emptyList(), "", 0, decision.copy(shouldRetrieve = false))
        }

        val queryTerms = terms(query)
        val allowed = candidates.asSequence()
            .filterNot { it.isArchived }
            .filter { entity ->
                when (entity.memoryType()) {
                    SmartMemoryType.USER -> policy.userEnabled
                    SmartMemoryType.PROJECT -> policy.projectEnabled && entity.projectId != null && entity.projectId == scope.projectId
                    SmartMemoryType.CHAT -> policy.chatEnabled && entity.chatId != null && entity.chatId == scope.chatId
                }
            }
            .map { entity -> entity to relevance(entity, queryTerms, decision) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(compareByDescending<Pair<SmartMemoryEntity, Int>> { it.second }
                .thenByDescending { it.first.importanceScore }
                .thenByDescending { it.first.lastAccessedAt })
            .map { it.first }
            .toList()

        val selected = ArrayList<SmartMemoryEntity>()
        val blocks = ArrayList<String>()
        var used = 0
        for (entity in allowed) {
            // Keep the local record intact. Cloud engines sanitize this
            // CommandMemory at their outbound boundary; local inference gets
            // the user's original remembered value.
            val summary = compress(entity.summary)
            val block = "[${entity.memoryType().name}] ${entity.title}: $summary"
            val cost = estimateTokens(block)
            if (used + cost > maxTokens) continue
            selected += entity
            blocks += block
            used += cost
        }
        return SmartMemoryRetrieval(selected, blocks.joinToString("\n"), used, decision)
    }

    fun toCommandMemory(retrieval: SmartMemoryRetrieval): List<CommandMemory> =
        retrieval.records.map { entity ->
            CommandMemory(
                userInput = entity.summary,
                aiIntent = entity.title,
                success = true,
                actionType = entity.memoryType().name
            )
        }

    fun isDuplicate(candidate: String, existing: SmartMemoryEntity): Boolean {
        val left = terms(candidate)
        val right = terms(existing.summary + " " + existing.title)
        if (left.isEmpty() || right.isEmpty()) return false
        val intersection = left.intersect(right).size.toDouble()
        val union = left.union(right).size.toDouble()
        return union > 0 && intersection / union >= 0.72
    }

    private fun relevance(entity: SmartMemoryEntity, queryTerms: Set<String>, decision: MemoryNeedDecision): Int {
        val memoryTerms = terms("${entity.title} ${entity.summary} ${entity.keywords}")
        val overlap = queryTerms.intersect(memoryTerms).size
        val embeddingBoost = embeddingSimilarity(queryTerms, entity.embeddingJson)
        val scopeBoost = when {
            decision.reason == MemoryNeedReason.PROJECT_REFERENCE && entity.memoryType() == SmartMemoryType.PROJECT -> 5
            decision.reason != MemoryNeedReason.PROJECT_REFERENCE && entity.memoryType() == SmartMemoryType.CHAT -> 3
            entity.memoryType() == SmartMemoryType.USER -> 2
            else -> 0
        }
        val recencyBoost = if (entity.lastAccessedAt > System.currentTimeMillis() - 7 * 86_400_000L) 1 else 0
        return overlap * 4 + embeddingBoost + entity.importanceScore + scopeBoost + recencyBoost
    }

    private fun embeddingSimilarity(queryTerms: Set<String>, encoded: String): Int {
        if (encoded.isBlank()) return 0
        val query = IntArray(32)
        queryTerms.forEach { term -> query[(term.hashCode() and Int.MAX_VALUE) % query.size] += 1 }
        val values = encoded.split(',').mapNotNull { it.toFloatOrNull() }
        if (values.size != query.size) return 0
        val dot = values.indices.sumOf { values[it].toDouble() * query[it].toDouble() }
        return if (dot > 0.15) 2 else 0
    }

    private fun terms(text: String): Set<String> = Regex("[a-z0-9_]{3,}")
        .findAll(text.lowercase())
        .map { it.value }
        .filterNot(STOP_WORDS::contains)
        .toSet()
}
