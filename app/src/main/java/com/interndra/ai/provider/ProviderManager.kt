package com.interndra.ai.provider

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/** Provider-independent API used by settings and future AI engine adapters. */
class ProviderManager(context: Context) {
    private val repository = ProviderRepository(context.applicationContext)
    private val gson = Gson()
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val state: Flow<ProviderState> = repository.state

    suspend fun initialize() = repository.initialize()

    suspend fun saveProvider(config: ProviderConfig, apiKey: String? = null, headersJson: String? = null): ProviderValidation =
        repository.save(config, apiKey, headersJson)

    suspend fun createCustomProvider(
        name: String,
        baseUrl: String,
        apiKey: String,
        authType: ProviderAuthType,
        headersJson: String = "",
        organizationId: String = "",
        projectId: String = "",
        apiVersion: String = "",
        endpointPath: String = "/v1/models",
        notes: String = ""
    ): ProviderValidation {
        val requestedId = slugify(name)
        // Never let a second custom provider silently replace the first one.
        // IDs are stable for edits, while new names receive a deterministic
        // suffix when their slug is already occupied.
        val existingIds = state.first().providers.map { it.id }.toSet()
        val id = uniqueCustomId(requestedId, existingIds)
        return saveProvider(
            ProviderConfig(
                id = id,
                name = name.trim(),
                kind = ProviderKind.CUSTOM,
                baseUrl = baseUrl.trim().removeSuffix("/"),
                endpointPath = endpointPath.trim().ifBlank { "/v1/models" },
                authType = authType,
                organizationId = organizationId.trim(),
                projectId = projectId.trim(),
                apiVersion = apiVersion.trim(),
                notes = notes.trim(),
                isBuiltIn = false
            ),
            apiKey = apiKey,
            headersJson = headersJson
        )
    }

    suspend fun setEnabled(providerId: String, enabled: Boolean) = repository.setEnabled(providerId, enabled)
    suspend fun deleteProvider(providerId: String) = repository.delete(providerId)
    suspend fun setDefault(role: ProviderRole, providerId: String) = repository.setDefault(role, providerId)
    suspend fun getApiKey(providerId: String) = repository.getApiKey(providerId)
    suspend fun getHeaders(providerId: String) = repository.getHeaders(providerId)

    suspend fun testConnection(providerId: String): ProviderStatus = withContext(Dispatchers.IO) {
        val config = state.first().providers.firstOrNull { it.id == providerId }
            ?: return@withContext ProviderStatus.UNKNOWN_ERROR
        if (!config.enabled) return@withContext ProviderStatus.OFFLINE
        if (!config.isLocal && !config.apiKeyConfigured) {
            repository.updateStatus(providerId, ProviderStatus.NOT_CONFIGURED)
            return@withContext ProviderStatus.NOT_CONFIGURED
        }
        val key = repository.getApiKey(providerId)
        val headers = repository.getHeaders(providerId)
        val request = buildRequest(config, key, headers, config.endpointPath)
        val status = try {
            client.newCall(request).execute().use { response ->
                when {
                    response.isSuccessful -> ProviderStatus.CONNECTED
                    response.code == 401 || response.code == 403 -> ProviderStatus.AUTHENTICATION_FAILED
                    response.code == 429 -> ProviderStatus.RATE_LIMITED
                    else -> ProviderStatus.API_ERROR
                }
            }
        } catch (_: java.net.UnknownHostException) {
            ProviderStatus.OFFLINE
        } catch (_: Exception) {
            ProviderStatus.UNKNOWN_ERROR
        }
        repository.updateStatus(providerId, status)
        status
    }

    suspend fun refreshModels(providerId: String): Result<List<ProviderModel>> = withContext(Dispatchers.IO) {
        val config = state.first().providers.firstOrNull { it.id == providerId }
            ?: return@withContext Result.failure(IllegalArgumentException("Provider not found"))
        if (!config.enabled) return@withContext Result.failure(IllegalStateException("Provider is disabled"))
        runCatching {
            val request = buildRequest(config, repository.getApiKey(providerId), repository.getHeaders(providerId), config.endpointPath)
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                val body = response.body?.string().orEmpty()
                val parsed = parseModels(body)
                repository.updateModels(providerId, parsed)
                parsed
            }
        }.onFailure {
            repository.updateStatus(providerId, when (it.message?.contains("401") == true) {
                true -> ProviderStatus.AUTHENTICATION_FAILED
                else -> ProviderStatus.API_ERROR
            })
        }
    }

    /** Migrate the old two-provider preferences without deleting them abruptly. */
    suspend fun migrateLegacy(openRouterKey: String, geminiKey: String, openRouterModel: String, geminiModel: String) =
        repository.migrateLegacy(openRouterKey, geminiKey, openRouterModel, geminiModel)

    private fun buildRequest(config: ProviderConfig, apiKey: String, headersJson: String, path: String): Request {
        val url = config.baseUrl.trimEnd('/') + "/" + path.trimStart('/')
        val builder = Request.Builder().url(url).get().header("Accept", "application/json")
        when (config.authType) {
            ProviderAuthType.BEARER -> if (apiKey.isNotBlank()) builder.header("Authorization", "Bearer $apiKey")
            ProviderAuthType.API_KEY_HEADER -> if (apiKey.isNotBlank()) builder.header("x-api-key", apiKey)
            ProviderAuthType.QUERY_PARAMETER -> if (apiKey.isNotBlank()) {
                val encoded = URLEncoder.encode(apiKey, Charsets.UTF_8.name())
                builder.url("$url${if ('?' in url) '&' else '?'}key=$encoded")
            }
            ProviderAuthType.NONE -> Unit
        }
        if (config.organizationId.isNotBlank()) builder.header("OpenAI-Organization", config.organizationId)
        if (config.projectId.isNotBlank()) builder.header("OpenAI-Project", config.projectId)
        if (config.apiVersion.isNotBlank()) builder.header("api-version", config.apiVersion)
        parseHeaders(headersJson).forEach { (name, value) ->
            if (name.isNotBlank() && !name.equals("Authorization", true) && !name.equals("x-api-key", true)) {
                builder.header(name, value)
            }
        }
        return builder.build()
    }

    private fun parseModels(body: String): List<ProviderModel> = runCatching {
        val root = JsonParser.parseString(body)
        val array = when {
            root.isJsonObject && root.asJsonObject.has("data") -> root.asJsonObject.getAsJsonArray("data")
            root.isJsonObject && root.asJsonObject.has("models") -> root.asJsonObject.getAsJsonArray("models")
            root.isJsonArray -> root.asJsonArray
            else -> null
        } ?: return@runCatching emptyList()
        array.mapNotNull { item ->
            if (!item.isJsonObject) return@mapNotNull null
            val obj = item.asJsonObject
            val id = obj.string("id") ?: obj.string("name") ?: return@mapNotNull null
            ProviderModel(id = id, displayName = obj.string("name") ?: id)
        }.distinctBy { it.id }
    }.getOrDefault(emptyList())

    private fun parseHeaders(json: String): Map<String, String> = runCatching {
        if (json.isBlank()) emptyMap() else gson.fromJson(json, Map::class.java).entries.associate { it.key.toString() to it.value.toString() }
    }.getOrDefault(emptyMap())

    private fun JsonObject.string(name: String): String? = get(name)?.takeUnless { it.isJsonNull }?.asString?.takeIf { it.isNotBlank() }

    private fun slugify(name: String): String = name.lowercase().trim()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .ifBlank { "custom-provider" }

    private fun uniqueCustomId(base: String, existingIds: Set<String>): String {
        if (base !in existingIds) return base
        var suffix = 2
        while ("$base-$suffix" in existingIds) suffix++
        return "$base-$suffix"
    }
}
