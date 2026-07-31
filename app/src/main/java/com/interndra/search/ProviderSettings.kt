package com.interndra.search

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * ProviderSettings — persisted web-search configuration.
 *
 * Stored via DataStore (same mechanism as the app's other API keys). The
 * Brave API key is a secret and is stored the same way the app already stores
 * OpenRouter/Gemini keys (DataStore-backed preferences).
 *
 * All settings live under the "Web Search" section of the existing Settings
 * page — there is no separate screen.
 */
data class WebSearchSettings(
    /** Master switch: autonomous search on/off. */
    val searchEnabled: Boolean = true,
    /** Enable the Brave Search provider. */
    val braveEnabled: Boolean = true,
    /** Brave API key (empty = not configured). */
    val braveApiKey: String = "",
    /** Prefer Brave over Gemini when both are configured. */
    val preferBrave: Boolean = false
) {
    val braveConfigured: Boolean get() = braveApiKey.isNotBlank()
}

private val Context.searchDataStore by preferencesDataStore("interndra_search_prefs")

class ProviderSettings(private val context: Context) {

    companion object {
        private val SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        private val BRAVE_ENABLED   = booleanPreferencesKey("brave_enabled")
        private val BRAVE_API_KEY   = stringPreferencesKey("brave_api_key")
        private val PREFER_BRAVE    = booleanPreferencesKey("prefer_brave")
    }

    val settings: Flow<WebSearchSettings> = context.searchDataStore.data.map { prefs ->
        WebSearchSettings(
            searchEnabled = prefs[SEARCH_ENABLED] ?: true,
            braveEnabled   = prefs[BRAVE_ENABLED] ?: true,
            braveApiKey    = prefs[BRAVE_API_KEY] ?: "",
            preferBrave    = prefs[PREFER_BRAVE] ?: false
        )
    }

    suspend fun setSearchEnabled(enabled: Boolean) {
        context.searchDataStore.edit { it[SEARCH_ENABLED] = enabled }
    }

    suspend fun setBraveEnabled(enabled: Boolean) {
        context.searchDataStore.edit { it[BRAVE_ENABLED] = enabled }
    }

    suspend fun setBraveApiKey(key: String) {
        context.searchDataStore.edit { it[BRAVE_API_KEY] = key.trim() }
    }

    suspend fun setPreferBrave(prefer: Boolean) {
        context.searchDataStore.edit { it[PREFER_BRAVE] = prefer }
    }

    suspend fun clearBraveKey() {
        context.searchDataStore.edit { it.remove(BRAVE_API_KEY) }
    }

    suspend fun resetAll() {
        context.searchDataStore.edit {
            it[SEARCH_ENABLED] = true
            it[BRAVE_ENABLED] = true
            it.remove(BRAVE_API_KEY)
            it[PREFER_BRAVE] = false
        }
    }
}
