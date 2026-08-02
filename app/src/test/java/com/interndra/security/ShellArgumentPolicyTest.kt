package com.interndra.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ShellArgumentPolicyTest {
    @Test
    fun `allows ordinary plugin arguments`() {
        assertThat(
            ShellArgumentPolicy.firstUnsafeValue(
                mapOf("package" to "python", "workdir" to "/sdcard/Projects")
            )
        ).isNull()
    }

    @Test
    fun `structured values remain valid when shell quoted`() {
        val value = "ok; still one argument"
        assertThat(ShellArgumentPolicy.firstUnsafeValue(mapOf("arg" to value)))
            .isNull()
        assertThat(ShellArgumentPolicy.shellQuote(value))
            .isEqualTo("'ok; still one argument'")
    }

    @Test
    fun `raw shell syntax is rejected by the dedicated policy`() {
        assertThat(ShellArgumentPolicy.containsShellSyntax("echo ok; rm -rf /"))
            .isTrue()
    }

    @Test
    fun `quotes structured values without changing their content`() {
        assertThat(ShellArgumentPolicy.shellQuote("it's safe"))
            .isEqualTo("'it'\\''s safe'")
        assertThat(ShellArgumentPolicy.firstUnsafeValue(mapOf("message" to "it's safe")))
            .isNull()
    }

    @Test
    fun `template replacement removes pre-existing placeholder quotes`() {
        assertThat(
            ShellArgumentPolicy.replaceTemplateValue("echo '{value}'", "value", "hello world")
        ).isEqualTo("echo 'hello world'")
        assertThat(
            ShellArgumentPolicy.replaceTemplateValue("echo \"{value}\"", "value", "it's safe")
        ).isEqualTo("echo 'it'\\''s safe'")
    }

    @Test
    fun `allows substitution text as structured data but rejects newlines`() {
        assertThat(ShellArgumentPolicy.firstUnsafeValue(mapOf("arg" to "$(whoami)")))
            .isNull()
        assertThat(ShellArgumentPolicy.containsShellSyntax("$(whoami)"))
            .isTrue()
        assertThat(ShellArgumentPolicy.firstUnsafeValue(mapOf("arg" to "safe\nnext")))
            .isEqualTo("arg")
    }
}
