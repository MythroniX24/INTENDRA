package com.interndra.ai.provider

import java.util.Locale

/** Where a provider runs. */
enum class ProviderKind { CLOUD, LOCAL, CUSTOM }

enum class ProviderAuthType { NONE, BEARER, API_KEY_HEADER, QUERY_PARAMETER }

enum class ProviderStatus {
    CONNECTED,
    CONFIGURED,
    NOT_CONFIGURED,
    INVALID_API_KEY,
    OFFLINE,
    RATE_LIMITED,
    AUTHENTICATION_FAILED,
    API_ERROR,
    UNKNOWN_ERROR
}

enum class ProviderCapability {
    STREAMING,
    VISION,
    TOOL_CALLING,
    JSON_MODE,
    IMAGE_GENERATION,
    EMBEDDINGS,
    REASONING,
    AUDIO
}

enum class ProviderRole { CHAT, VISION, EMBEDDINGS, IMAGE_GENERATION, AUDIO, REASONING }

data class ProviderModel(
    val id: String,
    val displayName: String = id,
    val capabilities: Set<ProviderCapability> = emptySet(),
    val contextLength: Long = 0L,
    val maxTokens: Int = 0,
    val favorite: Boolean = false,
    val lastUsedAt: Long = 0L
)

/**
 * Persisted provider metadata. Secrets are deliberately absent from this
 * object; [apiKeyConfigured] is only a boolean and headers are encrypted by
 * ProviderRepository before persistence.
 */
data class ProviderConfig(
    val id: String,
    val name: String,
    val kind: ProviderKind,
    val baseUrl: String,
    val endpointPath: String = "/models",
    val authType: ProviderAuthType = ProviderAuthType.BEARER,
    val organizationId: String = "",
    val projectId: String = "",
    val apiVersion: String = "",
    val notes: String = "",
    val enabled: Boolean = true,
    val isBuiltIn: Boolean = false,
    val apiKeyConfigured: Boolean = false,
    val capabilities: Set<ProviderCapability> = emptySet(),
    val status: ProviderStatus = ProviderStatus.NOT_CONFIGURED,
    val models: List<ProviderModel> = emptyList(),
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** User-selected model for the provider's primary chat role. */
    val activeModelId: String = "",
    val lastSyncAt: Long = 0L,
    @Transient val headersJson: String = ""
) {
    val isLocal: Boolean get() = kind == ProviderKind.LOCAL

    /** A provider that can be shown in the normal chat model selector. */
    val isReadyForChat: Boolean
        get() = enabled && supportsManagedChat &&
            (apiKeyConfigured || isLocal) &&
            (activeModelId.isNotBlank() || models.isNotEmpty())

    /**
     * Whether the current chat router has a real adapter for this provider.
     * Providers without an adapter remain visible for future configuration,
     * but must not be selectable as the active chat backend.
     */
    val supportsManagedChat: Boolean
        get() = when (id) {
            // Native Gemini requests are handled by GeminiAiEngine.
            "gemini" -> true
            // These providers expose the OpenAI-compatible chat contract used
            // by ProviderManager.parseChat().
            "openai", "openrouter", "groq", "fireworks", "dashscope",
            "deepseek", "ollama", "lmstudio", "vllm", "together",
            "cerebras", "mistral", "xai", "nvidia-nim", "sambanova",
            "perplexity" -> true
            // Custom endpoints are explicitly described as OpenAI-compatible
            // by the custom-provider UI.
            else -> kind == ProviderKind.CUSTOM
        }

    val modelCount: Int get() = models.size
}

data class ProviderDefaults(
    val chat: String? = null,
    val vision: String? = null,
    val embeddings: String? = null,
    val imageGeneration: String? = null,
    val audio: String? = null,
    val reasoning: String? = null
) {
    fun forRole(role: ProviderRole): String? = when (role) {
        ProviderRole.CHAT -> chat
        ProviderRole.VISION -> vision
        ProviderRole.EMBEDDINGS -> embeddings
        ProviderRole.IMAGE_GENERATION -> imageGeneration
        ProviderRole.AUDIO -> audio
        ProviderRole.REASONING -> reasoning
    }

    fun withRole(role: ProviderRole, providerId: String): ProviderDefaults = when (role) {
        ProviderRole.CHAT -> copy(chat = providerId)
        ProviderRole.VISION -> copy(vision = providerId)
        ProviderRole.EMBEDDINGS -> copy(embeddings = providerId)
        ProviderRole.IMAGE_GENERATION -> copy(imageGeneration = providerId)
        ProviderRole.AUDIO -> copy(audio = providerId)
        ProviderRole.REASONING -> copy(reasoning = providerId)
    }

    fun clearProvider(providerId: String): ProviderDefaults = copy(
        chat = chat.takeUnless { it == providerId },
        vision = vision.takeUnless { it == providerId },
        embeddings = embeddings.takeUnless { it == providerId },
        imageGeneration = imageGeneration.takeUnless { it == providerId },
        audio = audio.takeUnless { it == providerId },
        reasoning = reasoning.takeUnless { it == providerId }
    )
}

data class ProviderValidation(val errors: List<String>) {
    val isValid: Boolean get() = errors.isEmpty()
}

object ProviderValidator {
    fun validate(config: ProviderConfig): ProviderValidation {
        val errors = mutableListOf<String>()
        if (config.id.isBlank() || !Regex("^[a-z0-9._-]{2,80}$").matches(config.id)) {
            errors += "Provider id must contain only lowercase letters, numbers, '.', '_' or '-'."
        }
        if (config.name.trim().length !in 2..80) errors += "Provider name must be 2–80 characters."
        if (config.baseUrl.isBlank()) {
            errors += "Base URL is required."
        } else {
            val normalized = config.baseUrl.trim().removeSuffix("/")
            val uri = runCatching { java.net.URI(normalized) }.getOrNull()
            if (uri?.scheme !in setOf("https", "http")) errors += "Base URL must use HTTPS, or HTTP for localhost only."
            val host = uri?.host?.lowercase(Locale.US).orEmpty()
            val localHost = host == "localhost" || host == "127.0.0.1" || host == "10.0.2.2" || host.startsWith("192.168.")
            if (uri?.scheme == "http" && !localHost) errors += "Unencrypted HTTP is allowed only for local providers."
            if (host.isBlank()) errors += "Base URL must include a valid host."
        }
        if (config.endpointPath.isBlank() || !config.endpointPath.startsWith("/")) {
            errors += "Endpoint path must start with '/'."
        }
        if (config.kind == ProviderKind.CLOUD && !config.supportsManagedChat) {
            // The provider can still be saved for future adapters, but it must
            // not be advertised as an active chat backend yet.
        }
        if (config.kind == ProviderKind.LOCAL && config.authType != ProviderAuthType.NONE && !config.apiKeyConfigured) {
            // Local servers may still require a key; do not reject them.
        }
        if (config.headersJson.isNotBlank()) {
            val validHeaders = runCatching {
                val element = com.google.gson.JsonParser.parseString(config.headersJson)
                element.isJsonObject && element.asJsonObject.entrySet().all { it.key.isNotBlank() && it.value.isJsonPrimitive }
            }.getOrDefault(false)
            if (!validHeaders) errors += "Headers must be a JSON object of string values."
        }
        return ProviderValidation(errors)
    }
}

/** Built-in metadata only; secrets and user selections are never bundled. */
object BuiltInProviderCatalog {
    val providers: List<ProviderConfig> = listOf(
        provider("openai", "OpenAI", ProviderKind.CLOUD, "https://api.openai.com/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING, ProviderCapability.JSON_MODE, ProviderCapability.EMBEDDINGS, ProviderCapability.AUDIO, ProviderCapability.REASONING)),
        provider("anthropic", "Anthropic", ProviderKind.CLOUD, "https://api.anthropic.com/v1", auth = ProviderAuthType.API_KEY_HEADER, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING, ProviderCapability.REASONING)),
        provider("gemini", "Google Gemini", ProviderKind.CLOUD, "https://generativelanguage.googleapis.com/v1beta", auth = ProviderAuthType.QUERY_PARAMETER, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING, ProviderCapability.JSON_MODE, ProviderCapability.AUDIO, ProviderCapability.REASONING)),
        provider("openrouter", "OpenRouter", ProviderKind.CLOUD, "https://openrouter.ai/api/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING, ProviderCapability.JSON_MODE, ProviderCapability.REASONING)),
        provider("groq", "Groq", ProviderKind.CLOUD, "https://api.groq.com/openai/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.TOOL_CALLING, ProviderCapability.JSON_MODE)),
        provider("fireworks", "Fireworks AI", ProviderKind.CLOUD, "https://api.fireworks.ai/inference/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING)),
        provider("dashscope", "DashScope", ProviderKind.CLOUD, "https://dashscope.aliyuncs.com/compatible-mode/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING)),
        provider("deepseek", "DeepSeek", ProviderKind.CLOUD, "https://api.deepseek.com/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.TOOL_CALLING, ProviderCapability.JSON_MODE, ProviderCapability.REASONING)),
        provider("ollama", "Ollama", ProviderKind.LOCAL, "http://127.0.0.1:11434", endpoint = "/api/tags", auth = ProviderAuthType.NONE, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.TOOL_CALLING)),
        provider("lmstudio", "LM Studio", ProviderKind.LOCAL, "http://127.0.0.1:1234/v1", auth = ProviderAuthType.NONE, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING)),
        provider("vllm", "vLLM", ProviderKind.LOCAL, "http://127.0.0.1:8000/v1", auth = ProviderAuthType.NONE, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING)),
        provider("together", "Together AI", ProviderKind.CLOUD, "https://api.together.xyz/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING)),
        provider("cerebras", "Cerebras", ProviderKind.CLOUD, "https://api.cerebras.ai/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.TOOL_CALLING)),
        provider("mistral", "Mistral AI", ProviderKind.CLOUD, "https://api.mistral.ai/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING, ProviderCapability.EMBEDDINGS)),
        provider("xai", "xAI", ProviderKind.CLOUD, "https://api.x.ai/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING, ProviderCapability.REASONING)),
        provider("cohere", "Cohere", ProviderKind.CLOUD, "https://api.cohere.com/v2", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.TOOL_CALLING, ProviderCapability.EMBEDDINGS)),
        provider("huggingface", "Hugging Face Inference", ProviderKind.CLOUD, "https://api-inference.huggingface.co", endpoint = "/models", caps = setOf(ProviderCapability.STREAMING)),
        provider("nvidia-nim", "NVIDIA NIM", ProviderKind.CLOUD, "https://integrate.api.nvidia.com/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING)),
        provider("azure-openai", "Azure OpenAI", ProviderKind.CLOUD, "https://RESOURCE.openai.azure.com", endpoint = "/openai/models", auth = ProviderAuthType.API_KEY_HEADER, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING)),
        provider("bedrock", "AWS Bedrock", ProviderKind.CLOUD, "https://bedrock-runtime.REGION.amazonaws.com", auth = ProviderAuthType.NONE, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.REASONING)),
        provider("vertex-ai", "Vertex AI", ProviderKind.CLOUD, "https://us-central1-aiplatform.googleapis.com", auth = ProviderAuthType.BEARER, caps = setOf(ProviderCapability.STREAMING, ProviderCapability.VISION, ProviderCapability.TOOL_CALLING, ProviderCapability.REASONING)),
        provider("sambanova", "SambaNova", ProviderKind.CLOUD, "https://api.sambanova.ai/v1", caps = setOf(ProviderCapability.STREAMING, ProviderCapability.TOOL_CALLING, ProviderCapability.REASONING)),
        provider("perplexity", "Perplexity", ProviderKind.CLOUD, "https://api.perplexity.ai", caps = setOf(ProviderCapability.STREAMING))
    )

    private fun provider(id: String, name: String, kind: ProviderKind, base: String, auth: ProviderAuthType = ProviderAuthType.BEARER, endpoint: String = "/models", caps: Set<ProviderCapability>): ProviderConfig =
        ProviderConfig(
            id = id,
            name = name,
            kind = kind,
            baseUrl = base,
            endpointPath = endpoint,
            authType = auth,
            isBuiltIn = true,
            capabilities = caps
        )
}
