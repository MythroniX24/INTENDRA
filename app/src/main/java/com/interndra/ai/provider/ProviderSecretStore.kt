package com.interndra.ai.provider

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Crypto boundary used by [ProviderSecretStore].
 *
 * Production uses [AndroidKeyStoreCrypto]. Tests can inject a deterministic
 * implementation without requiring the Android Keystore provider.
 */
interface ProviderSecretCrypto {
    fun encrypt(value: String): String
    fun decrypt(encoded: String): String
}

/** Small Android Keystore-backed provider secret store. */
class ProviderSecretStore(
    private val crypto: ProviderSecretCrypto = AndroidKeyStoreCrypto()
) {
    companion object {
        private const val ENCRYPTED_PREFIX = "v1:"
    }

    fun encrypt(value: String): String {
        if (value.isEmpty()) return ""
        return ENCRYPTED_PREFIX + crypto.encrypt(value)
    }

    fun decrypt(value: String): String {
        if (value.isBlank()) return ""

        // Versioned values are unambiguously encrypted. Older releases did not
        // write a prefix, so attempt legacy decryption for every unversioned
        // value and preserve the original on failure. This avoids shape-based
        // guesses that can misclassify long API keys.
        return runCatching {
            crypto.decrypt(value.removePrefix(ENCRYPTED_PREFIX))
        }.getOrElse {
            // Explicit v1 ciphertext fails closed; legacy/plaintext values are
            // preserved so migration cannot silently erase credentials.
            if (isEncrypted(value)) "" else value
        }
    }

    /** True when the value was written by this versioned store format. */
    fun isEncrypted(value: String): Boolean = value.startsWith(ENCRYPTED_PREFIX)
}

private class AndroidKeyStoreCrypto : ProviderSecretCrypto {
    companion object {
        private const val KEYSTORE = "AndroidKeyStore"
        private const val ALIAS = "intendra_provider_secrets_v1"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val IV_BYTES = 12
        private const val TAG_BITS = 128
    }

    private fun key(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }

    override fun encrypt(value: String): String {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val packed = cipher.iv + cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(packed, Base64.NO_WRAP)
    }

    override fun decrypt(encoded: String): String {
        val packed = Base64.decode(encoded, Base64.NO_WRAP)
        require(packed.size > IV_BYTES) { "Invalid encrypted provider secret" }
        val iv = packed.copyOfRange(0, IV_BYTES)
        val ciphertext = packed.copyOfRange(IV_BYTES, packed.size)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(TAG_BITS, iv))
        return String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8)
    }
}
