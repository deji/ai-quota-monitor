package com.aiquotamonitor.data.remote

import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

open class TailscaleApiClient(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true }
) {

    @Throws(IOException::class)
    open fun fetchQuota(baseUrl: String, token: String?): QuotaSnapshot {
        val cleanUrl = baseUrl.trimEnd('/') + "/v1/quota"
        val requestBuilder = Request.Builder().url(cleanUrl).get()
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Tailscale HTTP fetch error: HTTP ${response.code}")
            }
            val bodyString = response.body?.string() ?: throw IOException("Empty body from Tailscale daemon")
            return json.decodeFromString<QuotaSnapshot>(bodyString)
        }
    }

    @Throws(IOException::class)
    open fun triggerRefresh(baseUrl: String, token: String?): QuotaSnapshot {
        val cleanUrl = baseUrl.trimEnd('/') + "/v1/quota/refresh"
        val emptyBody = "".toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(cleanUrl).post(emptyBody)
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Tailscale HTTP refresh error: HTTP ${response.code}")
            }
            val bodyString = response.body?.string() ?: throw IOException("Empty body from refresh")
            return json.decodeFromString<QuotaSnapshot>(bodyString)
        }
    }

    @Throws(IOException::class)
    open fun primeGrok(baseUrl: String, token: String?): SmartPrimeStatus {
        val cleanUrl = baseUrl.trimEnd('/') + "/v1/grok/prime"
        val emptyBody = "".toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(cleanUrl).post(emptyBody)
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Tailscale Grok prime error: HTTP ${response.code}")
            }
            val bodyString = response.body?.string() ?: throw IOException("Empty body from prime")
            return json.decodeFromString<SmartPrimeStatus>(bodyString)
        }
    }
}
