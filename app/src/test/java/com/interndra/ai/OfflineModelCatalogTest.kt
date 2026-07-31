package com.interndra.ai

import com.google.common.truth.Truth.assertThat
import com.interndra.ai.model.DeviceModelProfile
import com.interndra.ai.model.ModelRole
import com.interndra.ai.model.OfflineModelCatalog
import org.junit.Test

class OfflineModelCatalogTest {
    @Test
    fun `catalog exposes replaceable planner and chat roles`() {
        assertThat(OfflineModelCatalog.find(OfflineModelCatalog.PLANNER_MODEL_ID)?.role)
            .isEqualTo(ModelRole.PLANNER)
        assertThat(OfflineModelCatalog.find(OfflineModelCatalog.CHAT_MODEL_ID)?.role)
            .isEqualTo(ModelRole.CHAT)
    }

    @Test
    fun `small device receives smallest downloadable planner`() {
        val profile = DeviceModelProfile(
            totalRamMb = 2_048,
            freeStorageGb = 1f,
            cpuAbi = "arm64-v8a",
            androidApi = 34
        )
        val recommendation = OfflineModelCatalog.recommended(profile, ModelRole.PLANNER)
        assertThat(recommendation?.id).isEqualTo(OfflineModelCatalog.PLANNER_MODEL_ID)
    }

    @Test
    fun `vision has no false downloadable recommendation`() {
        val profile = DeviceModelProfile(8_192, 32f, "arm64-v8a", 35)
        assertThat(OfflineModelCatalog.recommended(profile, ModelRole.VISION)).isNull()
    }

    @Test
    fun `recommendation does not claim a model fits when storage is too low`() {
        val profile = DeviceModelProfile(8_192, 0.1f, "arm64-v8a", 35)
        // The catalog still returns the smallest safe downloadable fallback,
        // but never a vision/embedding model that has no verified backend.
        assertThat(OfflineModelCatalog.recommended(profile, ModelRole.VISION)).isNull()
        assertThat(OfflineModelCatalog.recommended(profile, ModelRole.EMBEDDINGS)).isNull()
    }
}
