package com.aiquotamonitor.domain.crypto

import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class EncryptedGistEnvelope(
    val iv: String,
    val ciphertext: String,
    val tag: String? = null
)

object GistEnvelopeDecryptor {

    private const val ALGORITHM = "AES/GCM/NoPadding"
    private const val TAG_LENGTH_BITS = 128

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Decrypts AES-256-GCM ciphertext bytes using the provided key and IV.
     * Expects standard GCM format where the 16-byte authentication tag is appended to the ciphertext.
     */
    fun decrypt(keyBytes: ByteArray, ivBytes: ByteArray, ciphertextWithTag: ByteArray): String {
        require(keyBytes.size == 32) { "AES-256 requires exactly 32 key bytes (got ${keyBytes.size})" }
        require(ivBytes.size == 12) { "AES-GCM requires exactly 12 IV bytes (got ${ivBytes.size})" }
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec)

        val decryptedBytes = cipher.doFinal(ciphertextWithTag)
        return String(decryptedBytes, Charsets.UTF_8)
    }

    /**
     * Encrypts plaintext bytes for testing and verification.
     */
    fun encrypt(keyBytes: ByteArray, ivBytes: ByteArray, plaintext: String): ByteArray {
        require(keyBytes.size == 32) { "AES-256 requires exactly 32 key bytes (got ${keyBytes.size})" }
        require(ivBytes.size == 12) { "AES-GCM requires exactly 12 IV bytes (got ${ivBytes.size})" }
        val secretKey = SecretKeySpec(keyBytes, "AES")
        val spec = GCMParameterSpec(TAG_LENGTH_BITS, ivBytes)

        val cipher = Cipher.getInstance(ALGORITHM)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec)

        return cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
    }

    /**
     * Decrypts a JSON envelope string containing {iv, ciphertext, [tag]}.
     * [keyHexOrBase64] can be either a 64-character hex string or base64 encoded 32-byte key.
     */
    fun decryptEnvelope(keyHexOrBase64: String, jsonEnvelope: String): String {
        val keyBytes = decodeKey(keyHexOrBase64.trim())
        val envelope = json.decodeFromString<EncryptedGistEnvelope>(jsonEnvelope)

        val ivBytes = decodeBytes(envelope.iv, expectedByteCount = 12)
        val cipherBytes = decodeBytes(envelope.ciphertext)

        val fullCiphertext = if (envelope.tag != null && envelope.tag.isNotEmpty()) {
            val tagBytes = decodeBytes(envelope.tag, expectedByteCount = 16)
            cipherBytes + tagBytes
        } else {
            cipherBytes
        }

        return decrypt(keyBytes, ivBytes, fullCiphertext)
    }

    fun decodeKey(keyStr: String): ByteArray {
        val trimmed = keyStr.trim()
        val hexPayload = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2)
            trimmed.startsWith("hex:", ignoreCase = true) -> trimmed.substring(4)
            else -> null
        }
        if (hexPayload != null) {
            val bytes = hexToBytes(hexPayload)
            require(bytes.size == 32) { "AES-256 key must be 32 bytes (got ${bytes.size})" }
            return bytes
        }
        if (trimmed.length == 64 && isHex(trimmed)) {
            return hexToBytes(trimmed)
        }
        val bytes = Base64.getDecoder().decode(trimmed)
        require(bytes.size == 32) { "AES-256 key must be 32 bytes (got ${bytes.size})" }
        return bytes
    }

    fun decodeBytes(str: String, expectedByteCount: Int? = null): ByteArray {
        val trimmed = str.trim()
        val hexPayload = when {
            trimmed.startsWith("0x", ignoreCase = true) -> trimmed.substring(2)
            trimmed.startsWith("hex:", ignoreCase = true) -> trimmed.substring(4)
            else -> null
        }
        if (hexPayload != null) {
            return hexToBytes(hexPayload)
        }

        // Only decode as hex when length matches exact expected byte count * 2
        if (expectedByteCount != null && trimmed.length == expectedByteCount * 2 && isHex(trimmed)) {
            return hexToBytes(trimmed)
        }

        // Otherwise decode as Base64 first; fallback to hex only if Base64 decode fails and valid hex
        return try {
            Base64.getDecoder().decode(trimmed)
        } catch (e: IllegalArgumentException) {
            if (trimmed.length % 2 == 0 && isHex(trimmed)) {
                hexToBytes(trimmed)
            } else {
                throw e
            }
        }
    }

    private fun isHex(str: String): Boolean {
        return str.isNotEmpty() && str.all { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
    }

    fun bytesToHex(bytes: ByteArray): String {
        return bytes.joinToString("") { "%02x".format(it) }
    }

    fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val result = ByteArray(len / 2)
        var i = 0
        while (i < len) {
            result[i / 2] = ((Character.digit(hex[i], 16) shl 4) + Character.digit(hex[i + 1], 16)).toByte()
            i += 2
        }
        return result
    }
}
