package com.interndra.ai.model

/** Roles are deliberately independent from a concrete model so models can be replaced without UI changes. */
enum class ModelRole(val label: String) {
    PLANNER("Planner"),
    CHAT("Chat"),
    REASONING("Reasoning"),
    VISION("Vision"),
    EMBEDDINGS("Embeddings"),
    SPEECH("Speech")
}

enum class OfflineAiMode(val label: String) {
    AUTOMATIC("Automatic"),
    CLOUD_ONLY("Cloud only"),
    OFFLINE_ONLY("Offline only"),
    HYBRID("Hybrid")
}

data class OfflineModelSpec(
    val id: String,
    val role: ModelRole,
    val displayName: String,
    val filename: String,
    val downloadUrl: String?,
    val minimumBytes: Long,
    val estimatedBytes: Long,
    val minimumRamMb: Long,
    val contextLength: Int,
    val quantization: String,
    val supportsStreaming: Boolean = true
)

data class DeviceModelProfile(
    val totalRamMb: Long,
    val freeStorageGb: Float,
    val cpuAbi: String,
    val androidApi: Int
)

data class OfflineModelSettings(
    val enabled: Boolean = true,
    val mode: OfflineAiMode = OfflineAiMode.AUTOMATIC,
    val plannerModelId: String = OfflineModelCatalog.PLANNER_MODEL_ID,
    val chatModelId: String = OfflineModelCatalog.CHAT_MODEL_ID,
    val reasoningModelId: String = OfflineModelCatalog.CHAT_MODEL_ID,
    val visionModelId: String = "",
    val embeddingsModelId: String = ""
)

/**
 * Single source of truth for model metadata. The engine only consumes paths from
 * this catalog; it never needs to know a vendor-specific filename or URL.
 *
 * Vision and embedding entries intentionally have no URL yet: advertising a
 * model without a verified Android backend would make the Settings UI misleading.
 */
object OfflineModelCatalog {
    const val PLANNER_MODEL_ID = "qwen2.5-0.5b-q4"
    const val CHAT_MODEL_ID = "qwen2.5-3b-q4"

    private const val MB = 1024L * 1024L

    val models: List<OfflineModelSpec> = listOf(
        OfflineModelSpec(
            id = PLANNER_MODEL_ID,
            role = ModelRole.PLANNER,
            displayName = "Qwen2.5 0.5B Instruct (Q4_K_M)",
            filename = "qwen2.5-0.5b-instruct-q4_k_m.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-0.5B-Instruct-GGUF/resolve/main/qwen2.5-0.5b-instruct-q4_k_m.gguf",
            minimumBytes = 200 * MB,
            estimatedBytes = 400 * MB,
            minimumRamMb = 2_048,
            contextLength = 2_048,
            quantization = "Q4_K_M"
        ),
        OfflineModelSpec(
            id = CHAT_MODEL_ID,
            role = ModelRole.CHAT,
            displayName = "Qwen2.5 3B Instruct (Q4_K_M)",
            filename = "Qwen2.5-3B-Instruct-Q4_K_M.gguf",
            downloadUrl = "https://huggingface.co/Qwen/Qwen2.5-3B-Instruct-GGUF/resolve/main/qwen2.5-3b-instruct-q4_k_m.gguf",
            minimumBytes = 500 * MB,
            estimatedBytes = 1_900 * MB,
            minimumRamMb = 4_096,
            contextLength = 2_048,
            quantization = "Q4_K_M"
        ),
        OfflineModelSpec(
            id = "qwen2.5-3b-reasoning-q4",
            role = ModelRole.REASONING,
            displayName = "Reasoning model (bring your own GGUF)",
            filename = "reasoning.gguf",
            downloadUrl = null,
            minimumBytes = 500 * MB,
            estimatedBytes = 2_000 * MB,
            minimumRamMb = 6_144,
            contextLength = 4_096,
            quantization = "Q4_K_M"
        ),
        OfflineModelSpec(
            id = "gemma-3n-vision",
            role = ModelRole.VISION,
            displayName = "Gemma 3n Vision (backend pending)",
            filename = "gemma-3n.gguf",
            downloadUrl = null,
            minimumBytes = 1_000 * MB,
            estimatedBytes = 3_000 * MB,
            minimumRamMb = 6_144,
            contextLength = 4_096,
            quantization = "Q4"
        ),
        OfflineModelSpec(
            id = "bge-m3-embeddings",
            role = ModelRole.EMBEDDINGS,
            displayName = "BGE-M3 Embeddings (backend pending)",
            filename = "bge-m3.gguf",
            downloadUrl = null,
            minimumBytes = 300 * MB,
            estimatedBytes = 1_000 * MB,
            minimumRamMb = 4_096,
            contextLength = 512,
            quantization = "FP16"
        )
    )

    fun forRole(role: ModelRole): List<OfflineModelSpec> = models.filter { it.role == role }

    fun find(id: String): OfflineModelSpec? = models.firstOrNull { it.id == id }

    /**
     * Picks only models that fit both RAM and free-storage constraints. If no
     * downloadable model fits, returns null so the UI never recommends a model
     * that cannot safely be stored on the device.
     */
    fun recommended(profile: DeviceModelProfile, role: ModelRole): OfflineModelSpec? {
        val candidates = forRole(role).filter { it.downloadUrl != null }
        if (candidates.isEmpty()) return null
        val fits = candidates.filter {
            it.minimumRamMb <= profile.totalRamMb &&
                it.estimatedBytes / (1024f * 1024f * 1024f) <= profile.freeStorageGb * 0.8f
        }
        return fits.maxByOrNull { it.minimumRamMb }
    }

    fun recommendations(profile: DeviceModelProfile): Map<ModelRole, OfflineModelSpec?> =
        ModelRole.values().associateWith { recommended(profile, it) }
}
