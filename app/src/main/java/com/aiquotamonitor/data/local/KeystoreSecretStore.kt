package com.aiquotamonitor.data.local

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypts and decrypts sensitive tokens at rest using Android Keystore (AES/GCM/NoPadding).
 * Automatically provides a clean software AES fallback when AndroidKeyStore provider is
 * unavailable (such as in local JVM unit tests).
 */
class KeystoreSecretStore(
    private val keyAlias: String = "ai_quota_monitor_secret_key"
) {

    private val androidKeystoreProvider = "AndroidKeyStore"
    private val transformation = "AES/GCM/NoPadding"
    private val ivSize = 12
    private val tagLengthBits = 128

    @Volatile
    private var fallbackKey: SecretKey? = null

    private fun isAndroidKeyStoreAvailable(): Boolean {
        return java.security.Security.getProvider(androidKeystoreProvider) != null
    }

    private fun getSecretKey(): SecretKey {
        if (!isAndroidKeyStoreAvailable()) {
            // AndroidKeyStore provider absent: use JVM test fallback key
            return getFallbackKey()
        }

        val keyStore = KeyStore.getInstance(androidKeystoreProvider)
        keyStore.load(null)
        if (!keyStore.containsAlias(keyAlias)) {
            val keyGenerator = KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES,
                androidKeystoreProvider
            )
            val spec = KeyGenParameterSpec.Builder(
                keyAlias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build()
            keyGenerator.init(spec)
            keyGenerator.generateKey()
        }
        val entry = keyStore.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry
            ?: throw IllegalStateException("Failed to retrieve secret key entry from AndroidKeyStore")
        return entry.secretKey
    }

    private fun getFallbackKey(): SecretKey {
        val existing = fallbackKey
        if (existing != null) return existing
        synchronized(this) {
            val doubleCheck = fallbackKey
            if (doubleCheck != null) return doubleCheck
            val keyGen = KeyGenerator.getInstance("AES")
            keyGen.init(256)
            val newKey = keyGen.generateKey()
            fallbackKey = newKey
            return newKey
        }
    }

    /**
     * Encrypts plaintext string using AES-256-GCM.
     * Returns an "enc:" prefixed string containing Base64(12-byte IV + ciphertextWithTag).
     * Fails closed on error and NEVER returns plaintext.
     */
    fun encrypt(plaintext: String): String {
        if (plaintext.isBlank()) return ""
        return try {
            val key = getSecretKey()
            val cipher = Cipher.getInstance(transformation)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv ?: ByteArray(ivSize)
            val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + ciphertext.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(ciphertext, 0, combined, iv.size, ciphertext.size)
            ENC_PREFIX + Base64.getEncoder().encodeToString(combined)
        } catch (e: Exception) {
            // Fail closed: never return plaintext
            throw IllegalStateException("Failed to encrypt secret: ${e.message}", e)
        }
    }

    /**
     * Decrypts an "enc:" prefixed string. If string is not prefixed with "enc:",
     * returns it directly for backwards compatibility/migration.
     */
    fun decrypt(stored: String): String {
        if (stored.isBlank()) return ""
        if (!stored.startsWith(ENC_PREFIX)) {
            return stored
        }
        return try {
            val encoded = stored.substring(ENC_PREFIX.length)
            val combined = Base64.getDecoder().decode(encoded)
            if (combined.size <= ivSize) return ""

            val iv = ByteArray(ivSize)
            val ciphertext = ByteArray(combined.size - ivSize)
            System.arraycopy(combined, 0, iv, 0, ivSize)
            System.arraycopy(combined, ivSize, ciphertext, 0, ciphertext.size)

            val key = getSecretKey()
            val cipher = Cipher.getInstance(transformation)
            val spec = GCMParameterSpec(tagLengthBits, iv)
            cipher.init(Cipher.DECRYPT_MODE, key, spec)
            val decrypted = cipher.doFinal(ciphertext)
            String(decrypted, Charsets.UTF_8)
        } catch (_: Exception) {
            ""
        }
    }

    companion object {
        const val ENC_PREFIX = "enc:"
    }
}
