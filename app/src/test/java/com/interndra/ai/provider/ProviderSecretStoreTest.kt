package com.interndra.ai.provider

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ProviderSecretStoreTest {
    private val crypto = FakeSecretCrypto()
    private val store = ProviderSecretStore(crypto)

    @Test
    fun `blank secret decrypts to blank`() {
        assertThat(store.decrypt("   ")).isEmpty()
    }

    @Test
    fun `encrypt and decrypt round trip uses v1 prefix`() {
        val encoded = store.encrypt("api-key-123")

        assertThat(encoded).startsWith("v1:")
        assertThat(store.isEncrypted(encoded)).isTrue()
        assertThat(store.decrypt(encoded)).isEqualTo("api-key-123")
    }

    @Test
    fun `unversioned legacy ciphertext decrypts successfully`() {
        val legacy = crypto.encrypt("legacy-key")

        assertThat(store.isEncrypted(legacy)).isFalse()
        assertThat(store.decrypt(legacy)).isEqualTo("legacy-key")
    }

    @Test
    fun `unversioned malformed plaintext is preserved`() {
        assertThat(store.decrypt("plain-api-key-value")).isEqualTo("plain-api-key-value")
    }

    @Test
    fun `long plaintext resembling base64 is preserved when legacy decrypt fails`() {
        val plaintext = "A".repeat(48)
        assertThat(store.decrypt(plaintext)).isEqualTo(plaintext)
    }

    @Test
    fun `malformed versioned ciphertext fails closed`() {
        assertThat(store.decrypt("v1:not-valid-ciphertext")).isEmpty()
    }

    @Test
    fun `only versioned values are marked encrypted`() {
        assertThat(store.isEncrypted("v1:payload")).isTrue()
        assertThat(store.isEncrypted("payload")).isFalse()
    }

    private class FakeSecretCrypto : ProviderSecretCrypto {
        override fun encrypt(value: String): String = "legacy:$value"

        override fun decrypt(encoded: String): String {
            require(encoded.startsWith("legacy:")) { "invalid" }
            return encoded.removePrefix("legacy:")
        }
    }
}
