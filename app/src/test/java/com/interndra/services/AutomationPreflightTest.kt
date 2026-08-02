package com.interndra.services

import com.google.common.truth.Truth.assertThat
import com.interndra.data.model.AutomationRule
import org.junit.Test

class AutomationPreflightTest {
    @Test
    fun `allows safe shell automation`() {
        val result = AutomationPreflight.validate(rule(type = "ADB_SHELL", command = "echo hello"))

        assertThat(result.success).isTrue()
        assertThat(result.refused).isFalse()
    }

    @Test
    fun `refuses high risk shell automation`() {
        val result = AutomationPreflight.validate(rule(type = "ADB_SHELL", command = "reboot"))

        assertThat(result.success).isFalse()
        assertThat(result.refused).isTrue()
        assertThat(result.refusalSource).isEqualTo("SafetyEngine")
    }

    @Test
    fun `refuses invalid intent URI`() {
        val result = AutomationPreflight.validate(
            rule(type = "ANDROID_INTENT", command = "file:///data/data/com.interndra/secret")
        )

        assertThat(result.success).isFalse()
        assertThat(result.refused).isTrue()
        assertThat(result.refusalSource).isEqualTo("IntentUriPolicy")
    }

    @Test
    fun `refuses unknown command type`() {
        val result = AutomationPreflight.validate(rule(type = "UNKNOWN", command = "echo hello"))

        assertThat(result.success).isFalse()
        assertThat(result.refused).isFalse()
        assertThat(result.error).contains("Unknown command type")
    }

    @Test
    fun `allows approved web intent`() {
        val result = AutomationPreflight.validate(
            rule(type = "ANDROID_INTENT", command = "https://example.com")
        )

        assertThat(result.success).isTrue()
    }

    private fun rule(type: String, command: String) = AutomationRule(
        description = "test",
        commandType = type,
        command = command
    )
}
