package com.interndra.ai

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * One "thinking episode" of the AI — what it reasoned about and the steps it
 * took (intent detected, plan, commands planned, search performed…). Rendered
 * in the chat as a Claude-style collapsible "Thinking" block that the user can
 * expand/collapse, without leaking into the LLM context or exports.
 */
data class ThinkingEpisode(
    val title: String,
    val steps: List<String>
)

/**
 * Embeds [ThinkingEpisode]s inside an AI chat message as a hidden marker block
 * (like [com.interndra.search.SourceMarker]). The visible reply text stays
 * clean; the marker is decoded by the UI to draw collapsible thinking blocks
 * and stripped everywhere the raw text must not leak (LLM history, copy, logs).
 */
object ThinkingMarker {
    private const val START = "<!--INTENDRA_THINKING_START-->"
    private const val END = "<!--INTENDRA_THINKING_END-->"
    private val gson = Gson()

    fun encode(episodes: List<ThinkingEpisode>): String {
        if (episodes.isEmpty()) return ""
        val json = runCatching { gson.toJson(episodes) }.getOrNull() ?: return ""
        return "\n$START$json$END\n"
    }

    fun decode(content: String): List<ThinkingEpisode> {
        if (content.isBlank() || !content.contains(START)) return emptyList()
        val startIdx = content.indexOf(START)
        val jsonStart = startIdx + START.length
        val endIdx = content.indexOf(END, jsonStart)
        if (endIdx < 0) return emptyList()
        val json = content.substring(jsonStart, endIdx)
        return runCatching {
            val type = object : TypeToken<List<ThinkingEpisode>>() {}.type
            gson.fromJson<List<ThinkingEpisode>>(json, type) ?: emptyList()
        }.getOrElse { emptyList() }
    }

    /** Removes the marker block (if any) from the raw message content. */
    fun strip(content: String): String {
        if (content.isBlank() || !content.contains(START)) return content
        val startIdx = content.indexOf(START)
        val endIdx = content.indexOf(END, startIdx)
        val cleaned = if (endIdx >= 0) {
            content.removeRange(startIdx, endIdx + END.length)
        } else {
            content.removeRange(startIdx, content.length)
        }
        return cleaned.replace("\n\n\n", "\n\n").trim()
    }

    /** Inserts [extra] right before the thinking marker (or appends if absent). */
    fun insertBeforeMarker(content: String, extra: String): String {
        if (extra.isBlank()) return content
        val startIdx = content.indexOf(START)
        return if (startIdx >= 0) {
            content.substring(0, startIdx) + extra + "\n" + content.substring(startIdx)
        } else {
            content + extra
        }
    }

    fun count(content: String): Int = decode(content).size
}
