package com.interndra.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SensitiveDataRedactorTest {
    @Test
    fun `redacts labeled api keys`() {
        val result = SensitiveDataRedactor.redact("api_key=sk-test-secret-value")
        assertThat(result).doesNotContain("sk-test-secret-value")
        assertThat(result).contains("REDACTED")
    }

    @Test
    fun `redacts bearer tokens`() {
        val result = SensitiveDataRedactor.redact("Authorization: Bearer abcdefghijklmnop")
        assertThat(result).doesNotContain("abcdefghijklmnop")
        assertThat(result).contains("Bearer ***REDACTED***")
    }

    @Test
    fun `redacts common provider token formats`() {
        val result = SensitiveDataRedactor.redact(
            "openai=sk_abcdefghijklmnopqrstuvwxyz github=ghp_abcdefghijklmnopqrstuvwxyz"
        )
        assertThat(result).doesNotContain("sk_abcdefghijklmnopqrstuvwxyz")
        assertThat(result).doesNotContain("ghp_abcdefghijklmnopqrstuvwxyz")
    }

    @Test
    fun `preserves ordinary diagnostic text`() {
        assertThat(SensitiveDataRedactor.redact("command completed with exit code 0"))
            .isEqualTo("command completed with exit code 0")
    }
}
