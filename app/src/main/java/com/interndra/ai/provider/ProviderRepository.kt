package com.interndra.ai.provider

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

private val Context.providerManagerDataStore by preferencesDataStore("intendra_provider_manager_v1")

data class ProviderState(
    val providers: List<ProviderConfig> = emptyList(),
    val defaults: ProviderDefaults = ProviderDefaults()
)

/** Repository boundary for provider metadata and Keystore-backed secrets. */
class ProviderRepository(
    private val context: Context,
    private val secrets: ProviderSecretStore = ProviderSecretStore()
) {
    private val gson = Gson()

    companion object {
        private val PROVIDERS_JSON = stringPreferencesKey("providers_json")
        private val DEFAULTS_JSON = stringPreferencesKey("defaults_json")
        private val INITIALIZED = stringPreferencesKey("initialized")
        private const val SECRET_PREFIX = "secret_"
    }

    val state: Flow<ProviderState> = context.providerManagerDataStore.data.map { prefs ->
        ProviderState(
            providers = decodeProviders(prefs[PROVIDERS_JSON].orEmpty()),
            defaults = decodeDefaults(prefs[DEFAULTS_JSON].orEmpty())
        )
    }

    suspend fun initialize() {
        context.providerManagerDataStore.edit { prefs ->
            if (prefs[INITIALIZED] == "true") return@edit
            prefs[PROVIDERS_JSON] = gson.toJson(BuiltInProviderCatalog.providers)
            prefs[DEFAULTS_JSON] = gson.toJson(ProviderDefaults(chat = "openrouter"))
            prefs[INITIALIZED] = "true"
        }
    }

    suspend fun save(
        config: ProviderConfig,
        apiKey: String? = null,
        headersJson: String? = null
    ): ProviderValidation {
        // Read metadata, existing credentials, validate, and write inside one
        // DataStore transaction. This prevents a stale StateFlow snapshot from
        // overwriting a concurrent provider edit.
        var validation = ProviderValidation(listOf("Provider could not be saved."))
        context.providerManagerDataStore.edit { prefs ->
            val current = decodeProviders(prefs[PROVIDERS_JSON].orEmpty())
            val key = apiKey ?: secrets.decrypt(prefs[secretKey(config.id, "api")].orEmpty())
            val headers = headersJson ?: secrets.decrypt(prefs[secretKey(config.id, "headers")].orEmpty())
            val requestedId = config.id
            val savedId = if (config.kind == ProviderKind.CUSTOM && current.any { it.id == requestedId }) {
                uniqueCustomId(requestedId, current.map { it.id }.toSet())
            } else {
                requestedId
            }
            val candidate = config.copy(
                id = savedId,
                apiKeyConfigured = key.isNotBlank(),
                headersJson = headers,
                updatedAt = System.currentTimeMillis()
            )
            validation = ProviderValidator.validate(candidate)
            if (!validation.isValid) return@edit

            val providers = current.filterNot { it.id == candidate.id } + candidate.copy(headersJson = "")
            prefs[PROVIDERS_JSON] = gson.toJson(providers)
            if (apiKey != null) {
                val trimmedKey = apiKey.trim()
                if (trimmedKey.isBlank()) prefs.remove(secretKey(candidate.id, "api"))
                else prefs[secretKey(candidate.id, "api")] = secrets.encrypt(trimmedKey)
                if (candidate.id != config.id) prefs.remove(secretKey(config.id, "api"))
            }
            if (headersJson != null) {
                if (headersJson.isBlank()) prefs.remove(secretKey(candidate.id, "headers"))
                else prefs[secretKey(candidate.id, "headers")] = secrets.encrypt(headersJson)
                if (candidate.id != config.id) prefs.remove(secretKey(config.id, "headers"))
            }
            prefs[INITIALIZED] = "true"
        }
        return validation
    }

    suspend fun updateStatus(providerId: String, status: ProviderStatus) {
        context.providerManagerDataStore.edit { prefs ->
            val current = decodeProviders(prefs[PROVIDERS_JSON].orEmpty())
            val updated = current.map {
                if (it.id == providerId) it.copy(status = status, updatedAt = System.currentTimeMillis()) else it
            }
            prefs[PROVIDERS_JSON] = gson.toJson(updated)
        }
    }

    suspend fun updateModels(providerId: String, models: List<ProviderModel>) {
        context.providerManagerDataStore.edit { prefs ->
            val current = decodeProviders(prefs[PROVIDERS_JSON].orEmpty())
            val updated = current.map {
                if (it.id == providerId) {
                    it.copy(
                        models = models,
                        lastSyncAt = System.currentTimeMillis(),
                        status = ProviderStatus.CONNECTED,
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
            prefs[PROVIDERS_JSON] = gson.toJson(updated)
        }
    }

    suspend fun setDefault(role: ProviderRole, providerId: String) {
        context.providerManagerDataStore.edit { prefs ->
            val providers = decodeProviders(prefs[PROVIDERS_JSON].orEmpty())
            if (providers.none { it.id == providerId && it.enabled }) return@edit
            val defaults = decodeDefaults(prefs[DEFAULTS_JSON].orEmpty())
            prefs[DEFAULTS_JSON] = gson.toJson(defaults.withRole(role, providerId))
        }
    }

    suspend fun setEnabled(providerId: String, enabled: Boolean) {
        context.providerManagerDataStore.edit { prefs ->
            val current = decodeProviders(prefs[PROVIDERS_JSON].orEmpty())
            prefs[PROVIDERS_JSON] = gson.toJson(current.map {
                if (it.id == providerId) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it
            })
        }
    }

    suspend fun delete(providerId: String): Boolean {
        var found = false
        context.providerManagerDataStore.edit { prefs ->
            val current = decodeProviders(prefs[PROVIDERS_JSON].orEmpty())
            val target = current.firstOrNull { it.id == providerId } ?: return@edit
            found = true
            if (target.isBuiltIn) {
                prefs[PROVIDERS_JSON] = gson.toJson(current.map {
                    if (it.id == providerId) it.copy(enabled = false, updatedAt = System.currentTimeMillis()) else it
                })
            } else {
                prefs[PROVIDERS_JSON] = gson.toJson(current.filterNot { it.id == providerId })
                prefs.remove(secretKey(providerId, "api"))
                prefs.remove(secretKey(providerId, "headers"))
            }
            val defaults = decodeDefaults(prefs[DEFAULTS_JSON].orEmpty())
            prefs[DEFAULTS_JSON] = gson.toJson(defaults.clearProvider(providerId))
        }
        return found
    }

    suspend fun migrateLegacy(openRouterKey: String, geminiKey: String, openRouterModel: String, geminiModel: String) {
        initialize()
        context.providerManagerDataStore.edit { prefs ->
            // Read existing encrypted values from the same transaction. Legacy
            // migration is idempotent and never replaces a credential with a
            // transient blank StateFlow/default value.
            val openRouterSecret = secrets.decrypt(prefs[secretKey("openrouter", "api")].orEmpty())
            val geminiSecret = secrets.decrypt(prefs[secretKey("gemini", "api")].orEmpty())
            val current = decodeProviders(prefs[PROVIDERS_JSON].orEmpty())
            val updated = current.map { provider ->
                when (provider.id) {
                    "openrouter" -> provider.copy(
                        apiKeyConfigured = openRouterSecret.isNotBlank() || openRouterKey.isNotBlank(),
                        models = if (openRouterModel.isBlank()) provider.models else listOf(ProviderModel(openRouterModel))
                    )
                    "gemini" -> provider.copy(
                        apiKeyConfigured = geminiSecret.isNotBlank() || geminiKey.isNotBlank(),
                        models = if (geminiModel.isBlank()) provider.models else listOf(ProviderModel(geminiModel))
                    )
                    else -> provider
                }
            }
            prefs[PROVIDERS_JSON] = gson.toJson(updated)
            if (openRouterKey.isNotBlank() && openRouterSecret.isBlank()) {
                prefs[secretKey("openrouter", "api")] = secrets.encrypt(openRouterKey.trim())
            }
            if (geminiKey.isNotBlank() && geminiSecret.isBlank()) {
                prefs[secretKey("gemini", "api")] = secrets.encrypt(geminiKey.trim())
            }
        }
    }

    suspend fun getApiKey(providerId: String): String = withContext(Dispatchers.IO) {
        secrets.decrypt(readSecret(providerId, "api"))
    }

    suspend fun getHeaders(providerId: String): String = withContext(Dispatchers.IO) {
        secrets.decrypt(readSecret(providerId, "headers"))
    }

    private suspend fun readSecret(providerId: String, type: String): String =
        context.providerManagerDataStore.data.map { it[secretKey(providerId, type)].orEmpty() }.first()

    private fun secretKey(providerId: String, type: String) = stringPreferencesKey("$SECRET_PREFIX${providerId}_$type")

    private fun uniqueCustomId(base: String, existingIds: Set<String>): String {
        if (base !in existingIds) return base
        var suffix = 2
        while ("$base-$suffix" in existingIds) suffix++
        return "$base-$suffix"
    }

    private fun decodeProviders(json: String): List<ProviderConfig> = runCatching {
        if (json.isBlank()) emptyList()
        else (gson.fromJson<List<ProviderConfig>>(json, object : TypeToken<List<ProviderConfig>>() {}.type) ?: emptyList())
            // Gson can bypass Kotlin constructors and leave newly-added fields
            // null when reading an older JSON snapshot. Normalize nullable
            // collection/string fields before they reach Compose or validation.
            .map { it.copy(headersJson = it.headersJson.orEmpty(), models = it.models.orEmpty(), capabilities = it.capabilities.orEmpty()) }
    }.getOrDefault(emptyList())

    private fun decodeDefaults(json: String): ProviderDefaults = runCatching {
        if (json.isBlank()) ProviderDefaults() else gson.fromJson(json, ProviderDefaults::class.java)
    }.getOrDefault(ProviderDefaults())
}
