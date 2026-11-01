package com.aiquotamonitor.data

import com.aiquotamonitor.domain.model.QuotaSnapshot
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class QuotaSnapshotJsonTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun testParseCanonicalSnapshotJson() {
        val canonicalJson = """
        {
          "schemaVersion": 1,
          "capturedAt": "2026-11-01T16:27:47Z",
          "host": "mac-host",
          "smartPrime": {
            "policy": "active_hours_or_manual",
            "activeHoursStart": "08:00",
            "activeHoursEnd": "22:00",
            "grokTokenExpiresAt": "2026-11-01T22:18:41Z",
            "grokTokenRemainingSeconds": 21054,
            "lastPrimedAt": null
          },
          "orcaRoles": [
            {
              "role": "PLANNER",
              "label": "Gemini 4 Argon",
              "primaryBucketId": "gemini-argon-wtu-weekly",
              "remainingPercent": 35.0,
              "status": "healthy"
            },
            {
              "role": "BUILDER",
              "label": "Flash 3.8 / OpenCode",
              "primaryBucketId": "gemini-5h",
              "remainingPercent": 99.0,
              "status": "healthy"
            },
            {
              "role": "REVIEWER",
              "label": "Grok 4.7 Build",
              "primaryBucketId": "grok-weekly-credits",
              "remainingPercent": 97.0,
              "status": "primed"
            }
          ],
          "providers": [
            {
              "id": "agy",
              "displayName": "Antigravity (CLI)",
              "subtitle": "Gemini 4 Argon & 3.8 Flash",
              "status": "ok",
              "buckets": [
                {
                  "id": "gemini-argon-wtu-weekly",
                  "group": "Gemini 4 Argon",
                  "label": "Gemini 4 Argon Weekly",
                  "windowType": "weekly",
                  "windowDurationHours": 168,
                  "remainingPercent": 35.0,
                  "usedPercent": 65.0,
                  "resetsAt": "2026-11-01T19:39:25Z",
                  "elapsedPercent": 98.1,
                  "paceDeltaPercent": -33.1,
                  "burnRatePerHour": 0.39,
                  "projectedRemainingAtReset": 33.8,
                  "status": "healthy"
                }
              ]
            }
          ],
          "history": [
            {
              "capturedAt": "2026-11-01T15:04:00Z",
              "bucketRemaining": {
                "gemini-argon-wtu-weekly": 55.0
              }
            }
          ]
        }
        """.trimIndent()

        val snapshot = json.decodeFromString<QuotaSnapshot>(canonicalJson)

        assertEquals(1, snapshot.schemaVersion)
        assertEquals("mac-host", snapshot.host)
        assertEquals(3, snapshot.orcaRoles.size)
        assertEquals(1, snapshot.providers.size)

        val argonBucket = snapshot.findBucket("gemini-argon-wtu-weekly")
        assertNotNull(argonBucket)
        assertEquals(35.0, argonBucket!!.remainingPercent, 0.01)
        assertEquals(65.0, argonBucket.usedPercent, 0.01)
        assertEquals(-33.1, argonBucket.paceDeltaPercent, 0.01)

        val bottleneck = snapshot.bottleneckBucket
        assertNotNull(bottleneck)
        assertEquals("gemini-argon-wtu-weekly", bottleneck!!.id)
    }
}
