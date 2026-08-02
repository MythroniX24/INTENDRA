package com.interndra.security

import com.google.common.truth.Truth.assertThat
import com.interndra.plugin.PluginExecutionGuard
import org.junit.Test

class PluginExecutionGuardTest {
    @Test
    fun `allows ordinary plugin command and arguments`() {
        assertThat(
            PluginExecutionGuard.rejection(
                "pkg:install",
                mapOf("name" to "python")
            )
        ).isNull()
    }

    @Test
    fun `rejects high risk plugin command`() {
        val rejection = PluginExecutionGuard.rejection(
            "pkg:uninstall",
            mapOf("name" to "python")
        )

        assertThat(rejection).isNotNull()
        assertThat(rejection?.error).contains("SafetyEngine")
    }

    @Test
    fun `allows normal raw shell flags without separators`() {
        assertThat(
            PluginExecutionGuard.rejection(
                "shell:git",
                mapOf("args" to "status --short")
            )
        ).isNull()
    }

    @Test
    fun `rejects shell metacharacters before plugin execution`() {
        val rejection = PluginExecutionGuard.rejection(
            "shell:exec",
            mapOf("cmd" to "echo ok; rm -rf /")
        )

        assertThat(rejection).isNotNull()
        assertThat(rejection?.error).contains("shell syntax")
    }
}
