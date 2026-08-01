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
        val storedJson = prefs[PROVIDERS_JSON].orEmpty()
        val providers = if (storedJson.isBlank() && prefs[INITIALIZED] != "true") {
            // Publish a usable catalog before the asynchronous initialize()
            // transaction completes. This prevents a cold-start chat request
            // from observing an empty provider list and falling back wrongly.
            BuiltInProviderCatalog.providers
        } else {
            decodeProviders(storedJson)
        }
        val storedDefaults = decodeDefaults(prefs[DEFAULTS_JSON].orEmpty())
        val defaults = if (prefs[INITIALIZED] != "true" && storedDefaults.chat == null) {
            ProviderDefaults(chat = "openrouter")
        } else {
            storedDefaults
        }
        ProviderState(
            providers = providers,
            defaults = defaults
        )
    }

    suspend fun initialize() {
        context.providerManagerDataStore.edit { prefs ->
            if (prefs[INITIALIZED] == "true") return@edit

            // A user can save a credential, add a model, or toggle a provider
            // before this first coroutine finishes. Preserve that transaction
            // instead of replacing it with the catalog snapshot.
            if (prefs[PROVIDERS_JSON].isNullOrBlank()) {
                prefs[PROVIDERS_JSON] = gson.toJson(BuiltInProviderCatalog.providers)
            }
            if (prefs[DEFAULTS_JSON].isNullOrBlank()) {
                prefs[DEFAULTS_JSON] = gson.toJson(ProviderDefaults(chat = "openrouter"))
            }
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
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
            val key = apiKey ?: secrets.decrypt(prefs[secretKey(config.id, "api")].orEmpty())
            val headers = headersJson ?: secrets.decrypt(prefs[secretKey(config.id, "headers")].orEmpty())
            // Custom IDs are made unique by ProviderManager when a provider is
            // created. Re-saving an existing custom provider (for example when
            // editing its API key) must update that same ID, not create a
            // hidden "-2" duplicate.
            val candidate = config.copy(
                id = config.id,
                apiKeyConfigured = key.isNotBlank(),
                status = when {
                    key.isNotBlank() || config.isLocal -> ProviderStatus.CONFIGURED
                    else -> ProviderStatus.NOT_CONFIGURED
                },
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

    suspend fun clearCredentials(providerId: String): ProviderValidation {
        var validation = ProviderValidation(listOf("Provider not found."))
        context.providerManagerDataStore.edit { prefs ->
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
            val target = current.firstOrNull { it.id == providerId } ?: return@edit
            val updated = target.copy(
                apiKeyConfigured = false,
                status = if (target.isLocal) ProviderStatus.CONFIGURED else ProviderStatus.NOT_CONFIGURED,
                updatedAt = System.currentTimeMillis()
            )
            prefs[PROVIDERS_JSON] = gson.toJson(current.map { if (it.id == providerId) updated else it })
            prefs.remove(secretKey(providerId, "api"))
            prefs.remove(secretKey(providerId, "headers"))
            validation = ProviderValidation(emptyList())
        }
        return validation
    }

    suspend fun updateStatus(providerId: String, status: ProviderStatus) {
        context.providerManagerDataStore.edit { prefs ->
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
            val updated = current.map {
                if (it.id == providerId) it.copy(status = status, updatedAt = System.currentTimeMillis()) else it
            }
            prefs[PROVIDERS_JSON] = gson.toJson(updated)
        }
    }

    suspend fun updateModels(providerId: String, models: List<ProviderModel>) {
        context.providerManagerDataStore.edit { prefs ->
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
            val updated = current.map {
                if (it.id == providerId) {
                    it.copy(
                        models = models,
                        activeModelId = it.activeModelId.takeIf { selected -> models.any { model -> model.id == selected } }
                            ?: models.firstOrNull()?.id.orEmpty(),
                        lastSyncAt = System.currentTimeMillis(),
                        status = ProviderStatus.CONNECTED,
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
            prefs[PROVIDERS_JSON] = gson.toJson(updated)
        }
    }

    suspend fun setActiveModel(providerId: String, modelId: String): Boolean {
        var updated = false
        context.providerManagerDataStore.edit { prefs ->
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
            val providers = current.map {
                if (it.id == providerId && it.models.any { model -> model.id == modelId }) {
                    updated = true
                    it.copy(activeModelId = modelId, updatedAt = System.currentTimeMillis())
                } else it
            }
            if (updated) prefs[PROVIDERS_JSON] = gson.toJson(providers)
        }
        return updated
    }

    suspend fun addManualModel(providerId: String, model: ProviderModel): Boolean {
        var updated = false
        context.providerManagerDataStore.edit { prefs ->
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
            val providers = current.map {
                if (it.id == providerId && it.models.none { existing -> existing.id == model.id }) {
                    updated = true
                    it.copy(
                        models = it.models + model,
                        activeModelId = if (it.activeModelId.isBlank()) model.id else it.activeModelId,
                        updatedAt = System.currentTimeMillis()
                    )
                } else it
            }
            if (updated) prefs[PROVIDERS_JSON] = gson.toJson(providers)
        }
        return updated
    }

    suspend fun setDefault(role: ProviderRole, providerId: String): Boolean {
        var accepted = false
        context.providerManagerDataStore.edit { prefs ->
            val storedJson = prefs[PROVIDERS_JSON].orEmpty()
            val providers = providersFrom(storedJson, prefs[INITIALIZED])
            // Enforce the same readiness contract as the UI and chat router.
            // A provider without credentials or a selectable model must never
            // become the persisted default, even if a stale caller invokes
            // this repository method directly.
            if (providers.none { it.id == providerId && it.isReadyForChat }) return@edit
            val defaults = decodeDefaults(prefs[DEFAULTS_JSON].orEmpty())
            val selected = providers.firstOrNull { it.id == providerId }
            if (selected != null && selected.activeModelId.isBlank() && selected.models.isNotEmpty()) {
                prefs[PROVIDERS_JSON] = gson.toJson(providers.map {
                    if (it.id == providerId) it.copy(activeModelId = it.models.first().id) else it
                })
            }
            prefs[DEFAULTS_JSON] = gson.toJson(defaults.withRole(role, providerId))
            accepted = true
            // A user action completed the first provider-manager write. Mark
            // the store initialized so the next initialize() call cannot
            // replace that selection with the catalog defaults.
            prefs[INITIALIZED] = "true"
        }
        return accepted
    }

    suspend fun setEnabled(providerId: String, enabled: Boolean) {
        context.providerManagerDataStore.edit { prefs ->
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
            prefs[PROVIDERS_JSON] = gson.toJson(current.map {
                if (it.id == providerId) it.copy(enabled = enabled, updatedAt = System.currentTimeMillis()) else it
            })
        }
    }

    suspend fun delete(providerId: String): Boolean {
        var found = false
        context.providerManagerDataStore.edit { prefs ->
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
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
            val current = providersFrom(prefs[PROVIDERS_JSON].orEmpty(), prefs[INITIALIZED])
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

    /**
     * Returns the persisted providers, or the built-in catalog while the first
     * DataStore initialization is still in flight. UI actions can happen before
     * initialize() finishes, so never treat an empty pre-init snapshot as the
     * complete provider list.
     */
    private fun providersFrom(json: String, initialized: String?): List<ProviderConfig> =
        if (json.isBlank() && initialized != "true") BuiltInProviderCatalog.providers
        else decodeProviders(json)

    private fun decodeProviders(json: String): List<ProviderConfig> = runCatching {
        if (json.isBlank()) emptyList()
        else (gson.fromJson<List<ProviderConfig>>(json, object : TypeToken<List<ProviderConfig>>() {}.type) ?: emptyList())
            // Gson can bypass Kotlin constructors and leave newly-added fields
            // null when reading an older JSON snapshot. Normalize nullable
            // collection/string fields before they reach Compose or validation.
            .map { provider ->
                val normalizeGemini = provider.id == "gemini"
                val normalizedModels = provider.models.orEmpty().map { model ->
                    val normalizedId = if (normalizeGemini) {
                        model.id.orEmpty().removePrefix("models/").removePrefix("gemini/")
                    } else {
                        model.id.orEmpty()
                    }
                    val displayName = if (normalizeGemini) {
                        model.displayName.orEmpty().removePrefix("models/").removePrefix("gemini/")
                    } else {
                        model.displayName.orEmpty()
                    }
                    model.copy(id = normalizedId, displayName = displayName)
                }
                provider.copy(
                    headersJson = provider.headersJson.orEmpty(),
                    models = normalizedModels,
                    capabilities = provider.capabilities.orEmpty(),
                    activeModelId = if (normalizeGemini) {
                        provider.activeModelId.orEmpty().removePrefix("models/").removePrefix("gemini/")
                    } else {
                        provider.activeModelId.orEmpty()
                    }
                )
            }
    }.getOrDefault(emptyList())

    private fun decodeDefaults(json: String): ProviderDefaults = runCatching {
        if (json.isBlank()) ProviderDefaults() else gson.fromJson(json, ProviderDefaults::class.java)
    }.getOrDefault(ProviderDefaults())
}
