package com.interndra.services

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AutomationExecutionResultTest {
    @Test
    fun `successful result exposes output and legacy output`() {
        val result = AutomationExecutionResult(success = true, output = "Intent dispatched")

        assertThat(result.success).isTrue()
        assertThat(result.refused).isFalse()
        assertThat(result.legacyMessage()).isEqualTo("Intent dispatched")
    }

    @Test
    fun `refused result is distinguishable from execution failure`() {
        val result = AutomationExecutionResult(
            success = false,
            error = "unsupported URI scheme",
            refused = true,
            refusalSource = "IntentUriPolicy"
        )

        assertThat(result.success).isFalse()
        assertThat(result.refused).isTrue()
        assertThat(result.legacyMessage())
            .isEqualTo("Refused by IntentUriPolicy: unsupported URI scheme")
    }

    @Test
    fun `failed result keeps error semantics`() {
        val result = AutomationExecutionResult(
            success = false,
            error = "exit code 127"
        )

        assertThat(result.legacyMessage()).isEqualTo("Error: exit code 127")
    }
}
