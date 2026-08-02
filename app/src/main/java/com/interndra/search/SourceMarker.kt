package com.interndra.search

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * SourceMarker — embeds web-search sources inside an AI message as a hidden
 * marker block so the chat text stays clean (no links dumped in the answer).
 *
 * The ViewModel appends [encode] output to the reply; the chat UI parses it
 * with [decode] and renders a collapsible "Sources" box below the message.
 * [strip] removes the block everywhere the raw text is used (display, copy,
 * LLM history) so the marker never leaks to the user or to the model.
 */
object SourceMarker {

    const val START = "<!--INTENDRA_SOURCES_START-->"
    const val END = "<!--INTENDRA_SOURCES_END-->"

    /** One clickable source shown inside the Sources box. */
    data class SourceLink(val title: String, val url: String)

    private val gson = Gson()
    private val linksType = object : TypeToken<List<SourceLink>>() {}.type

    /** Max sources persisted per message (kept small like ChatGPT). */
    private const val MAX_SOURCES = 5

    /**
     * Build the hidden marker block appended to an AI reply.
     * Returns "" when there are no sources.
     */
    fun encode(sources: List<SearchResult>): String {
        if (sources.isEmpty()) return ""
        val links = sources.take(MAX_SOURCES).map {
            SourceLink(
                title = it.title.ifBlank { "Source" }.take(80),
                url = it.url
            )
        }
        return "\n\n$START\n${gson.toJson(links)}\n$END"
    }

    /** Parse sources out of a message's content; empty when none are present. */
    fun decode(content: String): List<SourceLink> {
        if (content.isBlank()) return emptyList()
        val start = content.indexOf(START)
        val end = content.indexOf(END, start)
        if (start < 0 || end <= start) return emptyList()
        return try {
            val json = content.substring(start + START.length, end).trim()
            val parsed: List<SourceLink> = gson.fromJson(json, linksType) ?: emptyList()
            parsed.filter { it.url.isNotBlank() }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /**
     * Remove the marker block from content. Handles a partially-typed marker
     * (e.g. mid-streaming) by cutting at the first "<!--INTENDRA_SOURCES".
     */
    fun strip(content: String): String {
        if (content.isBlank()) return content
        val idx = content.indexOf("<!--INTENDRA_SOURCES")
        return if (idx >= 0) content.substring(0, idx).trimEnd() else content
    }

    /** Human-friendly host for display, e.g. https://docs.python.org/3 → docs.python.org */
    fun hostname(url: String): String = runCatching {
        java.net.URI(url).host?.removePrefix("www.") ?: url.take(60)
    }.getOrElse { url.take(60) }

    /**
     * Insert [extra] BEFORE the marker block so later appends (e.g. command
     * output) stay visible. Without this, [strip] would hide everything that
     * was appended after the marker.
     */
    fun insertBeforeMarker(content: String, extra: String): String {
        if (extra.isBlank()) return content
        val idx = content.indexOf(START)
        if (idx < 0) return content.trimEnd() + "\n\n" + extra
        val head = content.substring(0, idx).trimEnd()
        val tail = content.substring(idx).trimStart()
        return "$head\n\n$extra\n\n$tail"
    }
}
