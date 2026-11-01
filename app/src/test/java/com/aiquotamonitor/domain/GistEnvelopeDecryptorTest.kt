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
              "v": 1,
              "alg": "AES-256-GCM",
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
              "v": 1,
              "alg": "AES-256-GCM",
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
              "v": 1,
              "alg": "AES-256-GCM",
              "iv": "$ivWithPrefix",
              "ciphertext": "${Base64.getEncoder().encodeToString(cipherBytes)}"
            }
        """.trimIndent()

        val decrypted = GistEnvelopeDecryptor.decryptEnvelope(keyWithPrefix, envelopeJson)
        assertEquals(plaintext, decrypted)
    }

    @Test
    fun testDeterministicCrossLanguageGoldenVector() {
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val expectedPlaintext = "{\"schemaVersion\":1,\"capturedAt\":\"2026-11-01T16:27:47Z\",\"host\":\"collector\",\"status\":\"healthy\"}"
        val goldenEnvelopeJson = """
            {
              "v": 1,
              "alg": "AES-256-GCM",
              "nonce": "oKGio6Slpqeoqaqr",
              "ciphertext": "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
              "tag": "Dj/3Kj4fHyzmMwdjW4rKGQ==",
              "capturedAt": "2026-11-01T16:27:47Z"
            }
        """.trimIndent()

        val decrypted = GistEnvelopeDecryptor.decryptEnvelope(keyHex, goldenEnvelopeJson)
        assertEquals(expectedPlaintext, decrypted)
    }

    @Test
    fun testCapturedAtMismatchThrows() {
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        // Tampered outer timestamp
        val tamperedEnvelope = """
            {
              "v": 1,
              "alg": "AES-256-GCM",
              "nonce": "oKGio6Slpqeoqaqr",
              "ciphertext": "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
              "tag": "Dj/3Kj4fHyzmMwdjW4rKGQ==",
              "capturedAt": "2026-11-01T19:00:00Z"
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.decryptEnvelope(keyHex, tamperedEnvelope)
        }
    }

    @Test
    fun testUnsupportedVersionOrAlgThrows() {
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val badVersionEnvelope = """
            {
              "v": 2,
              "alg": "AES-256-GCM",
              "nonce": "oKGio6Slpqeoqaqr",
              "ciphertext": "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
              "tag": "Dj/3Kj4fHyzmMwdjW4rKGQ=="
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.decryptEnvelope(keyHex, badVersionEnvelope)
        }

        val badAlgEnvelope = """
            {
              "v": 1,
              "alg": "AES-128-CBC",
              "nonce": "oKGio6Slpqeoqaqr",
              "ciphertext": "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
              "tag": "Dj/3Kj4fHyzmMwdjW4rKGQ=="
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.decryptEnvelope(keyHex, badAlgEnvelope)
        }

        val missingVersionEnvelope = """
            {
              "alg": "AES-256-GCM",
              "nonce": "oKGio6Slpqeoqaqr",
              "ciphertext": "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
              "tag": "Dj/3Kj4fHyzmMwdjW4rKGQ=="
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.decryptEnvelope(keyHex, missingVersionEnvelope)
        }

        val missingAlgEnvelope = """
            {
              "v": 1,
              "nonce": "oKGio6Slpqeoqaqr",
              "ciphertext": "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
              "tag": "Dj/3Kj4fHyzmMwdjW4rKGQ=="
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            GistEnvelopeDecryptor.decryptEnvelope(keyHex, missingAlgEnvelope)
        }
    }
}
