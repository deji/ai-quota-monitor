package com.aiquotamonitor.data

import com.aiquotamonitor.data.remote.TailscaleApiClient
import java.io.IOException
import org.junit.Assert.fail
import org.junit.Test

class TailscaleApiClientTest {

    private val client = TailscaleApiClient()

    @Test
    fun testValidPrivateAndTailscaleUrlsSucceed() {
        val validUrls = listOf(
            "http://127.0.0.1:8080",
            "http://localhost:8080",
            "http://app.localhost:8080",
            "http://100.64.0.1:8080",
            "http://100.115.92.2:8080",
            "http://100.127.255.255:8080",
            "http://node.ts.net:8080",
            "http://device.magic-dns.ts.net:8080",
            "http://10.0.0.1:8080",
            "http://192.168.1.100:8080",
            "http://172.16.0.1:8080",
            "http://172.31.255.255:8080",
            "https://api.github.com",
            "https://example.com:8080"
        )

        for (url in validUrls) {
            try {
                client.validateBaseUrl(url)
            } catch (e: Exception) {
                fail("Expected valid URL '$url' to succeed, but threw ${e.javaClass.simpleName}: ${e.message}")
            }
        }
    }

    @Test
    fun testPublicCleartextUrlsThrowIOException() {
        val invalidUrls = listOf(
            "http://example.com",
            "http://8.8.8.8:8080",
            "http://100.128.0.1:8080", // Outside CGNAT 100.64.0.0/10
            "http://172.32.0.1:8080",  // Outside RFC1918 172.16.0.0/12
            "http://public.service.org"
        )

        for (url in invalidUrls) {
            try {
                client.validateBaseUrl(url)
                fail("Expected invalid cleartext URL '$url' to throw IOException, but it succeeded")
            } catch (_: IOException) {
                // Expected
            }
        }
    }
}
