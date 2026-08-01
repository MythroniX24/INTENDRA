package com.interndra.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderModelsTest {
    @Test
    fun `catalog includes core cloud and local providers`() {
        val ids = BuiltInProviderCatalog.providers.map { it.id }.toSet()

        assertThat(ids).containsAtLeast("openai", "anthropic", "gemini", "openrouter", "deepseek")
        assertThat(ids).containsAtLeast("ollama", "lmstudio", "vllm")
    }

    @Test
    fun `built in providers never contain configured secrets`() {
        assertThat(BuiltInProviderCatalog.providers).isNotEmpty()
        assertThat(BuiltInProviderCatalog.providers.all { !it.apiKeyConfigured }).isTrue()
        assertThat(BuiltInProviderCatalog.providers.all { it.headersJson.isBlank() }).isTrue()
    }

    @Test
    fun `managed chat support matches the currently implemented adapters`() {
        assertThat(BuiltInProviderCatalog.providers.first { it.id == "anthropic" }.supportsManagedChat).isFalse()
        assertThat(BuiltInProviderCatalog.providers.first { it.id == "bedrock" }.supportsManagedChat).isFalse()
        assertThat(BuiltInProviderCatalog.providers.first { it.id == "vertex-ai" }.supportsManagedChat).isFalse()
        assertThat(BuiltInProviderCatalog.providers.first { it.id == "openrouter" }.supportsManagedChat).isTrue()
        assertThat(BuiltInProviderCatalog.providers.first { it.id == "gemini" }.supportsManagedChat).isTrue()
        assertThat(ProviderConfig("custom", "Custom", ProviderKind.CUSTOM, "https://example.com").supportsManagedChat).isTrue()
    }

    @Test
    fun `valid https cloud provider passes validation`() {
        val config = ProviderConfig(
            id = "custom-cloud",
            name = "Custom Cloud",
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com",
            endpointPath = "/v1/models",
            headersJson = "{\"X-Org\":\"demo\"}"
        )

        assertThat(ProviderValidator.validate(config).isValid).isTrue()
    }

    @Test
    fun `plain http is rejected for non local providers`() {
        val config = ProviderConfig(
            id = "unsafe-cloud",
            name = "Unsafe Cloud",
            kind = ProviderKind.CUSTOM,
            baseUrl = "http://api.example.com",
            endpointPath = "/v1/models"
        )

        val validation = ProviderValidator.validate(config)
        assertThat(validation.isValid).isFalse()
        assertThat(validation.errors).contains("Unencrypted HTTP is allowed only for local providers.")
    }

    @Test
    fun `localhost http is accepted for local provider`() {
        val config = ProviderConfig(
            id = "local-ollama",
            name = "Local Ollama",
            kind = ProviderKind.LOCAL,
            baseUrl = "http://127.0.0.1:11434",
            endpointPath = "/api/tags",
            authType = ProviderAuthType.NONE
        )

        assertThat(ProviderValidator.validate(config).isValid).isTrue()
    }

    @Test
    fun `invalid headers and endpoint are rejected`() {
        val config = ProviderConfig(
            id = "bad-provider",
            name = "Bad Provider",
            kind = ProviderKind.CUSTOM,
            baseUrl = "https://api.example.com",
            endpointPath = "models",
            headersJson = "not-json"
        )

        val validation = ProviderValidator.validate(config)
        assertThat(validation.isValid).isFalse()
        assertThat(validation.errors).contains("Endpoint path must start with '/'.")
        assertThat(validation.errors).contains("Headers must be a JSON object of string values.")
    }

    @Test
    fun `provider defaults route every role independently`() {
        val defaults = ProviderDefaults()
            .withRole(ProviderRole.CHAT, "openrouter")
            .withRole(ProviderRole.VISION, "openai")
            .withRole(ProviderRole.EMBEDDINGS, "cohere")
            .withRole(ProviderRole.REASONING, "deepseek")

        assertThat(defaults.forRole(ProviderRole.CHAT)).isEqualTo("openrouter")
        assertThat(defaults.forRole(ProviderRole.VISION)).isEqualTo("openai")
        assertThat(defaults.forRole(ProviderRole.EMBEDDINGS)).isEqualTo("cohere")
        assertThat(defaults.forRole(ProviderRole.REASONING)).isEqualTo("deepseek")
        assertThat(defaults.forRole(ProviderRole.AUDIO)).isNull()
    }

    @Test
    fun `clearing a provider removes it from every default role`() {
        val defaults = ProviderDefaults(
            chat = "custom",
            vision = "openai",
            embeddings = "custom",
            imageGeneration = "custom",
            audio = "custom",
            reasoning = "deepseek"
        )

        val cleared = defaults.clearProvider("custom")

        assertThat(cleared.chat).isNull()
        assertThat(cleared.vision).isEqualTo("openai")
        assertThat(cleared.embeddings).isNull()
        assertThat(cleared.imageGeneration).isNull()
        assertThat(cleared.audio).isNull()
        assertThat(cleared.reasoning).isEqualTo("deepseek")
    }

    @Test
    fun `empty api key can be used for a local no-auth provider`() {
        val config = ProviderConfig(
            id = "local-server",
            name = "Local Server",
            kind = ProviderKind.LOCAL,
            baseUrl = "http://localhost:8000",
            endpointPath = "/v1/models",
            authType = ProviderAuthType.NONE,
            apiKeyConfigured = false
        )

        assertThat(ProviderValidator.validate(config).isValid).isTrue()
    }

    @Test
    fun `gemini model resource prefixes are safe to normalize`() {
        val raw = "models/gemini-2.5-flash"
        val normalized = raw.removePrefix("models/").removePrefix("gemini/")
        assertThat(normalized).isEqualTo("gemini-2.5-flash")
    }

    @Test
    fun `provider is not ready for chat before credential and model are configured`() {
        val provider = BuiltInProviderCatalog.providers.first { it.id == "openrouter" }

        assertThat(provider.isReadyForChat).isFalse()
        assertThat(provider.copy(apiKeyConfigured = true).isReadyForChat).isFalse()
    }

    @Test
    fun `provider becomes ready for chat after credential and model are configured`() {
        val provider = BuiltInProviderCatalog.providers.first { it.id == "openrouter" }
            .copy(
                apiKeyConfigured = true,
                models = listOf(ProviderModel("openai/gpt-4o-mini")),
                activeModelId = "openai/gpt-4o-mini"
            )

        assertThat(provider.isReadyForChat).isTrue()
    }

    @Test
    fun `local provider can be ready without an api key when a model exists`() {
        val provider = BuiltInProviderCatalog.providers.first { it.id == "ollama" }
            .copy(models = listOf(ProviderModel("llama3.2")), activeModelId = "llama3.2")

        assertThat(provider.isReadyForChat).isTrue()
    }

    @Test
    fun `configured status is distinct from a successful connection`() {
        assertThat(ProviderStatus.CONFIGURED).isNotEqualTo(ProviderStatus.CONNECTED)
    }

    @Test
    fun `custom provider id slug is compatible with provider id rules`() {
        val id = "My Local Provider".lowercase()
            .replace(Regex("[^a-z0-9]+"), "-")
            .trim('-')
        assertThat(Regex("^[a-z0-9._-]{2,80}$").matches(id)).isTrue()
    }
}
