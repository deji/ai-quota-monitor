package com.aiquotamonitor.data.remote

import com.aiquotamonitor.domain.crypto.GistEnvelopeDecryptor
import com.aiquotamonitor.domain.model.QuotaSnapshot
import java.io.IOException
import java.net.URI
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

open class GistRelayClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    companion object {
        private val ALLOWED_GIST_HOSTS = setOf(
            "api.github.com",
            "gist.githubusercontent.com",
            "raw.githubusercontent.com"
        )
    }

    @Throws(IOException::class)
    open fun fetchAndDecryptSnapshot(
        gistId: String,
        aesKey: String,
        githubToken: String? = null
    ): QuotaSnapshot {
        val trimmed = gistId.trim()
        val rawUrl = if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) {
            trimmed
        } else {
            "https://api.github.com/gists/$trimmed"
        }

        val uri = try {
            URI(rawUrl)
        } catch (e: Exception) {
            throw IllegalArgumentException("Invalid Gist URI: $rawUrl", e)
        }

        if (!"https".equals(uri.scheme, ignoreCase = true)) {
            throw IllegalArgumentException("Only HTTPS Gist URLs are allowed: $rawUrl")
        }

        val host = uri.host?.lowercase() ?: throw IllegalArgumentException("Missing host in Gist URL: $rawUrl")
        if (host !in ALLOWED_GIST_HOSTS) {
            throw IllegalArgumentException("Disallowed host for Gist relay: $host")
        }

        val requestBuilder = Request.Builder()
            .url(rawUrl)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .addHeader("User-Agent", "AI-Quota-Monitor-Android")

        if (!githubToken.isNullOrBlank() && uri.host.equals("api.github.com", ignoreCase = true)) {
            requestBuilder.addHeader("Authorization", "Bearer ${githubToken.trim()}")
        }

        val request = requestBuilder.get().build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Gist relay fetch error: HTTP ${response.code}")
            }
            val bodyString = response.body?.string() ?: throw IOException("Empty body from Gist relay")

            val envelopeJson = extractEnvelopeJson(bodyString)
            val decryptedPlaintext = GistEnvelopeDecryptor.decryptEnvelope(aesKey, envelopeJson)
            return json.decodeFromString<QuotaSnapshot>(decryptedPlaintext)
        }
    }

    private fun extractEnvelopeJson(rawGistResponse: String): String {
        return try {
            val rootObj = json.parseToJsonElement(rawGistResponse).jsonObject
            val filesObj = rootObj["files"]?.jsonObject
            if (filesObj != null) {
                val targetFile = (filesObj["quota_snapshot.enc.json"]
                    ?: filesObj["quota_snapshot.json"]
                    ?: filesObj.values.firstOrNull())?.jsonObject
                val content = targetFile?.get("content")?.jsonPrimitive?.content
                content ?: rawGistResponse
            } else {
                rawGistResponse
            }
        } catch (_: Exception) {
            rawGistResponse
        }
    }
}
