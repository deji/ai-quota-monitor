package com.aiquotamonitor.data.remote

import com.aiquotamonitor.domain.crypto.GistEnvelopeDecryptor
import com.aiquotamonitor.domain.model.QuotaSnapshot
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GistRelayClientTest {

    private val jsonMediaType = "application/json".toMediaType()

    private fun createMockClient(responseBody: String, onIntercept: (Request) -> Unit): OkHttpClient {
        return OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                onIntercept(request)
                Response.Builder()
                    .request(request)
                    .protocol(Protocol.HTTP_1_1)
                    .code(200)
                    .message("OK")
                    .body(responseBody.toResponseBody(jsonMediaType))
                    .build()
            }
            .build()
    }

    private fun sampleValidEnvelopeJson(): String {
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"
        val payload = """
            {
              "schemaVersion": 1,
              "capturedAt": "2026-11-01T16:27:47Z",
              "host": "collector",
              "smartPrime": {
                "policy": "active_hours_or_manual",
                "activeHoursStart": "08:00",
                "activeHoursEnd": "22:00",
                "grokTokenExpiresAt": "2026-11-01T22:18:41Z",
                "grokTokenRemainingSeconds": 21054,
                "lastPrimedAt": null,
                "primed": false,
                "reason": "token_healthy"
              },
              "orcaRoles": [],
              "providers": [],
              "history": []
            }
        """.trimIndent()
        // Deterministic cross-language vector matching golden test
        return """
            {
              "v": 1,
              "alg": "AES-256-GCM",
              "nonce": "oKGio6Slpqeoqaqr",
              "ciphertext": "nToPTi2ub940APWgbhWu/EqddTLx1jIY6XxD4j7fVzvwRHfNmQ9iDHKsNZw4TLnLcCFyfzjyNlwpMXgqhkqn09vQ6QpT0YuS1s7da/uvuM++KZGGraT4FpodR7jM",
              "tag": "Dj/3Kj4fHyzmMwdjW4rKGQ==",
              "capturedAt": "2026-11-01T16:27:47Z"
            }
        """.trimIndent()
    }

    @Test
    fun testNonHttpsUrlIsRejected() {
        val client = GistRelayClient()
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

        val ex = assertThrows(IllegalArgumentException::class.java) {
            client.fetchAndDecryptSnapshot("http://api.github.com/gists/test12345", keyHex)
        }
        assertTrue(ex.message!!.contains("Only HTTPS"))
    }

    @Test
    fun testDisallowedHostIsRejected() {
        val client = GistRelayClient()
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

        val ex = assertThrows(IllegalArgumentException::class.java) {
            client.fetchAndDecryptSnapshot("https://untrusted-domain.com/gists/test12345", keyHex)
        }
        assertTrue(ex.message!!.contains("Disallowed host"))
    }

    @Test
    fun testAuthorizationHeaderSentOnlyToApiGithubCom() {
        var capturedRequest: Request? = null
        val goldenEnvelope = sampleValidEnvelopeJson()
        val gistResponse = """
            {
              "files": {
                "quota_snapshot.enc.json": {
                  "content": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), goldenEnvelope)}
                }
              }
            }
        """.trimIndent()

        val mockClient = createMockClient(gistResponse) { request ->
            capturedRequest = request
        }
        val client = GistRelayClient(client = mockClient)
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

        // 1. Bare ID translates to https://api.github.com/gists/... -> Authorization header present
        client.fetchAndDecryptSnapshot("test_gist_id", keyHex, githubToken = "ghp_secret_token")
        assertEquals("Bearer ghp_secret_token", capturedRequest?.header("Authorization"))

        // 2. Full api.github.com URL -> Authorization header present
        client.fetchAndDecryptSnapshot("https://api.github.com/gists/test_gist_id", keyHex, githubToken = "ghp_secret_token")
        assertEquals("Bearer ghp_secret_token", capturedRequest?.header("Authorization"))

        // 3. raw.githubusercontent.com URL -> Authorization header must NOT be attached
        client.fetchAndDecryptSnapshot("https://raw.githubusercontent.com/user/gist/raw/test.json", keyHex, githubToken = "ghp_secret_token")
        assertNull(capturedRequest?.header("Authorization"))

        // 4. gist.githubusercontent.com URL -> Authorization header must NOT be attached
        client.fetchAndDecryptSnapshot("https://gist.githubusercontent.com/user/raw/test.json", keyHex, githubToken = "ghp_secret_token")
        assertNull(capturedRequest?.header("Authorization"))
    }

    @Test
    fun testExtractEnvelopePrefersEncryptedJsonFilename() {
        val goldenEnvelope = sampleValidEnvelopeJson()
        val gistResponseWithMultipleFiles = """
            {
              "files": {
                "quota_snapshot.json": {
                  "content": "{\"invalid\": \"plain\"}"
                },
                "quota_snapshot.enc.json": {
                  "content": ${kotlinx.serialization.json.Json.encodeToString(kotlinx.serialization.serializer(), goldenEnvelope)}
                }
              }
            }
        """.trimIndent()

        val mockClient = createMockClient(gistResponseWithMultipleFiles) {}
        val client = GistRelayClient(client = mockClient)
        val keyHex = "000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f"

        val snapshot = client.fetchAndDecryptSnapshot("https://api.github.com/gists/sample", keyHex)
        assertEquals(1, snapshot.schemaVersion)
        assertEquals("collector", snapshot.host)
    }
}
