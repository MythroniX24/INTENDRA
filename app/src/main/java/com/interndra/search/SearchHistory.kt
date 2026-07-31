package com.interndra.search

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * SearchHistory — lightweight in-memory record of recent searches.
 *
 * Lets the app (and future UI) see what was searched, when, and by which
 * provider. Kept in memory only — no sensitive query history is persisted.
 */
class SearchHistory {

    data class Entry(
        val query: String,
        val providers: List<SearchProviderId>,
        val resultCount: Int,
        val cacheHit: Boolean,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()

    private val maxEntries = 30

    fun record(entry: Entry) {
        _entries.value = (listOf(entry) + _entries.value).take(maxEntries)
    }

    fun clear() {
        _entries.value = emptyList()
    }
}
