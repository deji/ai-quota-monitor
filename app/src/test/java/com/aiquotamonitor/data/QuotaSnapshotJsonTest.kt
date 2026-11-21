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
              "label": "Gemini Pool / OpenCode",
              "primaryBucketId": "gemini-5h",
              "remainingPercent": 99.0,
              "status": "healthy"
            },
            {
              "role": "REVIEWER",
              "label": "Grok Unified",
              "primaryBucketId": "grok-weekly-credits",
              "remainingPercent": 97.0,
              "status": "primed"
            }
          ],
          "providers": [
            {
              "id": "agy",
              "displayName": "Antigravity (CLI)",
              "subtitle": "Gemini 4 Argon, Gemini Pool & 3P",
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

    @Test
    fun testParseGrokUnifiedProductBreakdownJson() {
        val grokJson = """
        {
          "schemaVersion": 1,
          "capturedAt": "2026-11-01T16:27:47Z",
          "host": "collector",
          "providers": [
            {
              "id": "grok",
              "displayName": "Grok",
              "subtitle": "Build, Bot & Cursor Weekly",
              "status": "ok",
              "buckets": [
                {
                  "id": "grok-weekly-credits",
                  "group": "Grok Unified",
                  "label": "Grok Weekly Credits",
                  "windowType": "weekly",
                  "windowDurationHours": 168,
                  "remainingPercent": 45.0,
                  "usedPercent": 55.0,
                  "status": "warning",
                  "productBreakdown": [
                    { "product": "GrokBot", "label": "Grok Bot", "usagePercent": 49.0 },
                    { "product": "GrokBuild", "label": "Grok Build", "usagePercent": 4.0 },
                    { "product": "Cursor", "label": "Cursor", "usagePercent": 2.0 }
                  ]
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val snapshot = json.decodeFromString<QuotaSnapshot>(grokJson)
        val grok = snapshot.findBucket("grok-weekly-credits")
        assertNotNull(grok)
        assertEquals("Grok Unified", grok!!.group)
        assertEquals("Grok Weekly Credits", grok.label)
        assertEquals("GROK", grok.shortHeaderLabel())
        assertEquals("Grok Weekly", grok.shortWideRowLabel())
        assertEquals(3, grok.productBreakdown.size)
        assertEquals("Grok Bot", grok.productBreakdown[0].label)
        assertEquals(49.0, grok.productBreakdown[0].usagePercent, 0.001)
        assertEquals("Cursor", grok.productBreakdown[2].label)
        assertEquals(2.0, grok.productBreakdown[2].usagePercent, 0.001)
        assertEquals(listOf("grok-weekly-credits"), snapshot.resolveWideProviderGroups().single().items.map { it.id })
        assertEquals("GROK", snapshot.resolveWideProviderGroups().single().title)
    }

    @Test
    fun testLegacySnapshotWithoutProductBreakdownDeserializesEmptyList() {
        val legacyJson = """
        {
          "schemaVersion": 1,
          "capturedAt": "2026-11-01T16:27:47Z",
          "host": "collector",
          "providers": [
            {
              "id": "grok",
              "displayName": "Grok Build CLI",
              "subtitle": "Build & Bot Weekly",
              "status": "ok",
              "buckets": [
                {
                  "id": "grok-weekly-credits",
                  "group": "Grok Build",
                  "label": "Grok 4.7 Weekly Credits",
                  "windowType": "weekly",
                  "windowDurationHours": 168,
                  "remainingPercent": 97.0,
                  "usedPercent": 3.0,
                  "status": "healthy"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val snapshot = json.decodeFromString<QuotaSnapshot>(legacyJson)
        val grok = snapshot.findBucket("grok-weekly-credits")
        assertNotNull(grok)
        assertEquals(emptyList<String>(), grok!!.productBreakdown.map { it.product })
    }
}
