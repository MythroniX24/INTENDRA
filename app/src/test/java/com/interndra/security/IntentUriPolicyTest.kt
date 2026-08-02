package com.interndra.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class IntentUriPolicyTest {
    @Test
    fun `allows approved user facing schemes`() {
        assertThat(IntentUriPolicy.isAllowed("https://example.com")).isTrue()
        assertThat(IntentUriPolicy.isAllowed("https:")).isFalse()
        assertThat(IntentUriPolicy.isAllowed("tel:+1234567890")).isTrue()
        assertThat(IntentUriPolicy.isAllowed("mailto:user@example.com")).isTrue()
        assertThat(IntentUriPolicy.isAllowed("geo:28.6,77.2")).isTrue()
    }

    @Test
    fun `rejects private and executable schemes`() {
        assertThat(IntentUriPolicy.isAllowed("file:///data/data/com.interndra/secret")).isFalse()
        assertThat(IntentUriPolicy.isAllowed("content://com.example.provider/item")).isFalse()
        assertThat(IntentUriPolicy.isAllowed("javascript:alert(1)")).isFalse()
        assertThat(IntentUriPolicy.isAllowed("intent://example.com")).isFalse()
    }

    @Test
    fun `rejects missing or malformed schemes`() {
        assertThat(IntentUriPolicy.isAllowed("example.com")).isFalse()
        assertThat(IntentUriPolicy.isAllowed("   ")).isFalse()
    }
}
