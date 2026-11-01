package com.aiquotamonitor.domain

import com.aiquotamonitor.domain.crypto.GistEnvelopeDecryptor
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class GistEnvelopeDecryptorTest {

    @Test
    fun testAesGcmEncryptionDecryptionRoundTrip() {
        val key = ByteArray(32) { it.toByte() }
        val iv = ByteArray(12) { (it + 5).toByte() }
        val plaintext = "{\"schemaVersion\": 1, \"status\": \"healthy\"}"

        val ciphertextWithTag = GistEnvelopeDecryptor.encrypt(key, iv, plaintext)
        val decrypted = GistEnvelopeDecryptor.decrypt(key, iv, ciphertextWithTag)

        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testDecryptEnvelopeJson() {
        val key = ByteArray(32) { 0x42.toByte() }
        val iv = ByteArray(12) { 0x11.toByte() }
        val plaintext = "Hello AI Quota Monitor Relay"

        val cipherBytes = GistEnvelopeDecryptor.encrypt(key, iv, plaintext)
        val keyHex = GistEnvelopeDecryptor.bytesToHex(key)
        val ivBase64 = Base64.getEncoder().encodeToString(iv)
        val cipherBase64 = Base64.getEncoder().encodeToString(cipherBytes)

        val envelopeJson = """
            {
              "iv": "$ivBase64",
              "ciphertext": "$cipherBase64"
            }
        """.trimIndent()

        val decrypted = GistEnvelopeDecryptor.decryptEnvelope(keyHex, envelopeJson)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testInvalidKeySizeThrows() {
        val invalidKey = ByteArray(16) // 128-bit key when 256-bit required
        val iv = ByteArray(12)

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.decrypt(invalidKey, iv, ByteArray(32))
        }
    }

    @Test
    fun testInvalidIvSizeThrows() {
        val key = ByteArray(32) { 1 }
        val shortIv = ByteArray(8) // 8 bytes instead of 12
        val longIv = ByteArray(16) // 16 bytes instead of 12

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.decrypt(key, shortIv, ByteArray(32))
        }

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.encrypt(key, longIv, "test")
        }
    }

    @Test
    fun testBase64IvMatchingHexCharactersDoesNotMisdecodeAsHex() {
        val key = ByteArray(32) { 0x33.toByte() }
        // 12-byte IV whose Base64 encoding consists entirely of hex characters [0-9a-fA-F]
        // E.g., 12 bytes = 16 Base64 characters. If misdecoded as hex, it would yield 8 bytes instead of 12.
        val iv = ByteArray(12) { (it + 1).toByte() }
        val plaintext = "Test IV Hex Collision"

        val cipherBytes = GistEnvelopeDecryptor.encrypt(key, iv, plaintext)
        val ivBase64 = Base64.getEncoder().encodeToString(iv)

        val envelopeJson = """
            {
              "iv": "$ivBase64",
              "ciphertext": "${Base64.getEncoder().encodeToString(cipherBytes)}"
            }
        """.trimIndent()

        val keyBase64 = Base64.getEncoder().encodeToString(key)
        val decrypted = GistEnvelopeDecryptor.decryptEnvelope(keyBase64, envelopeJson)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testPrefixedHexIvAndKey() {
        val key = ByteArray(32) { 0x55.toByte() }
        val iv = ByteArray(12) { 0x22.toByte() }
        val plaintext = "Prefixed hex verification"

        val cipherBytes = GistEnvelopeDecryptor.encrypt(key, iv, plaintext)
        val keyWithPrefix = "0x" + GistEnvelopeDecryptor.bytesToHex(key)
        val ivWithPrefix = "hex:" + GistEnvelopeDecryptor.bytesToHex(iv)

        val envelopeJson = """
            {
              "iv": "$ivWithPrefix",
              "ciphertext": "${Base64.getEncoder().encodeToString(cipherBytes)}"
            }
        """.trimIndent()

        val decrypted = GistEnvelopeDecryptor.decryptEnvelope(keyWithPrefix, envelopeJson)
        assertEquals(plaintext, decrypted)
    }
}
