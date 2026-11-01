package com.aiquotamonitor.data.remote

import com.aiquotamonitor.domain.crypto.GistEnvelopeDecryptor
import com.aiquotamonitor.domain.model.QuotaSnapshot
import java.io.IOException
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

    @Throws(IOException::class)
    open fun fetchAndDecryptSnapshot(gistId: String, aesKey: String): QuotaSnapshot {
        val url = if (gistId.startsWith("http://") || gistId.startsWith("https://")) {
            gistId
        } else {
            "https://api.github.com/gists/$gistId"
        }

        val request = Request.Builder()
            .url(url)
            .addHeader("Accept", "application/vnd.github.v3+json")
            .addHeader("User-Agent", "AI-Quota-Monitor-Android")
            .get()
            .build()

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
                // Find first file content in Gist
                val firstFile = filesObj.values.firstOrNull()?.jsonObject
                val content = firstFile?.get("content")?.jsonPrimitive?.content
                content ?: rawGistResponse
            } else {
                rawGistResponse
            }
        } catch (_: Exception) {
            rawGistResponse
        }
    }
}
