package com.aiquotamonitor.data.remote

import com.aiquotamonitor.domain.model.QuotaSnapshot
import java.io.IOException
import java.net.URI
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
    fun validateBaseUrl(baseUrl: String) {
        val trimmed = baseUrl.trim()
        if (trimmed.startsWith("http://", ignoreCase = true)) {
            val uri = try {
                URI.create(trimmed)
            } catch (e: Exception) {
                throw IOException("Invalid URL: $baseUrl", e)
            }
            val host = uri.host ?: throw IOException("Missing host in URL: $baseUrl")
            if (!isPrivateOrTailscaleHost(host)) {
                throw IOException(
                    "Cleartext HTTP is strictly forbidden for public host '$host'. Use HTTPS or a private Tailscale/LAN host (100.64.0.0/10, 127.0.0.1, localhost, 10.x, 192.168.x, 172.16-31.x, *.ts.net)."
                )
            }
        }
    }

    private fun isPrivateOrTailscaleHost(host: String): Boolean {
        val lowerHost = host.lowercase()
        if (lowerHost == "localhost" || lowerHost.endsWith(".localhost") || lowerHost.endsWith(".ts.net") || lowerHost == "ts.net") {
            return true
        }
        if (lowerHost == "::1" || lowerHost == "[::1]") {
            return true
        }
        val parts = lowerHost.split(".")
        if (parts.size == 4 && parts.all { it.all { c -> c.isDigit() } }) {
            val o0 = parts[0].toIntOrNull() ?: return false
            val o1 = parts[1].toIntOrNull() ?: return false
            val o2 = parts[2].toIntOrNull() ?: return false
            val o3 = parts[3].toIntOrNull() ?: return false

            if (o0 in 0..255 && o1 in 0..255 && o2 in 0..255 && o3 in 0..255) {
                // Loopback 127.0.0.0/8
                if (o0 == 127) return true
                // Tailscale CGNAT 100.64.0.0/10 (100.64.x.x - 100.127.x.x)
                if (o0 == 100 && o1 in 64..127) return true
                // RFC1918 10.0.0.0/8
                if (o0 == 10) return true
                // RFC1918 172.16.0.0/12 (172.16.x.x - 172.31.x.x)
                if (o0 == 172 && o1 in 16..31) return true
                // RFC1918 192.168.0.0/16
                if (o0 == 192 && o1 == 168) return true
            }
        }
        return false
    }

    @Throws(IOException::class)
    open fun fetchQuota(baseUrl: String, token: String?): QuotaSnapshot {
        validateBaseUrl(baseUrl)
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
        validateBaseUrl(baseUrl)
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
    open fun primeGrok(baseUrl: String, token: String?): QuotaSnapshot {
        validateBaseUrl(baseUrl)
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
            return json.decodeFromString<QuotaSnapshot>(bodyString)
        }
    }

    @Throws(IOException::class)
    open fun updateActiveHours(baseUrl: String, token: String?, start: String, end: String): QuotaSnapshot {
        validateBaseUrl(baseUrl)
        val cleanUrl = baseUrl.trimEnd('/') + "/v1/config/active-hours"
        val payloadJson = """{"activeHoursStart":"$start","activeHoursEnd":"$end"}"""
        val body = payloadJson.toRequestBody("application/json".toMediaType())
        val requestBuilder = Request.Builder().url(cleanUrl).post(body)
        if (!token.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $token")
        }

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("Tailscale active hours sync error: HTTP ${response.code}")
            }
            val bodyString = response.body?.string() ?: throw IOException("Empty body from active hours update")
            return json.decodeFromString<QuotaSnapshot>(bodyString)
        }
    }
}
