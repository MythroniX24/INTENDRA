package com.interndra.search

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.interndra.ai.provider.ProviderSecretStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/** Persisted autonomous web-search configuration. */
data class WebSearchSettings(
    val searchEnabled: Boolean = true,
    val braveEnabled: Boolean = true,
    /** Decrypted only in memory; never persisted in provider metadata. */
    val braveApiKey: String = "",
    val preferBrave: Boolean = false
) {
    val braveConfigured: Boolean get() = braveApiKey.isNotBlank()
}

private val Context.searchDataStore by preferencesDataStore("interndra_search_prefs")

class ProviderSettings(
    private val context: Context,
    private val secrets: ProviderSecretStore = ProviderSecretStore()
) {
    companion object {
        private val SEARCH_ENABLED = booleanPreferencesKey("web_search_enabled")
        private val BRAVE_ENABLED = booleanPreferencesKey("brave_enabled")
        private val BRAVE_API_KEY = stringPreferencesKey("brave_api_key")
        private val PREFER_BRAVE = booleanPreferencesKey("prefer_brave")
    }

    val settings: Flow<WebSearchSettings> = context.searchDataStore.data.map { prefs ->
        WebSearchSettings(
            searchEnabled = prefs[SEARCH_ENABLED] ?: true,
            braveEnabled = prefs[BRAVE_ENABLED] ?: true,
            braveApiKey = decryptOrLegacy(prefs[BRAVE_API_KEY].orEmpty()),
            preferBrave = prefs[PREFER_BRAVE] ?: false
        )
    }

    /** Encrypt an old plaintext Brave key in place. Safe to call repeatedly. */
    suspend fun migrateLegacySecret() {
        context.searchDataStore.edit { prefs ->
            val raw = prefs[BRAVE_API_KEY].orEmpty()
            if (raw.isNotBlank() && !secrets.isEncrypted(raw)) {
                prefs[BRAVE_API_KEY] = secrets.encrypt(raw.trim())
            }
        }
    }

    suspend fun setSearchEnabled(enabled: Boolean) {
        context.searchDataStore.edit { it[SEARCH_ENABLED] = enabled }
    }

    suspend fun setBraveEnabled(enabled: Boolean) {
        context.searchDataStore.edit { it[BRAVE_ENABLED] = enabled }
    }

    suspend fun setBraveApiKey(key: String) {
        context.searchDataStore.edit { prefs ->
            val value = key.trim()
            if (value.isBlank()) prefs.remove(BRAVE_API_KEY)
            else prefs[BRAVE_API_KEY] = secrets.encrypt(value)
        }
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

    private fun decryptOrLegacy(raw: String): String = when {
        raw.isBlank() -> ""
        secrets.isEncrypted(raw) -> secrets.decrypt(raw)
        // Read-through compatibility for an existing pre-Phase-1 plaintext
        // value. migrateLegacySecret() rewrites it on startup.
        else -> raw
    }
}
