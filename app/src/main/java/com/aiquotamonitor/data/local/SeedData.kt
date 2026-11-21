package com.aiquotamonitor.data.local

import com.aiquotamonitor.domain.model.HistoryPoint
import com.aiquotamonitor.domain.model.OrcaRoleReadiness
import com.aiquotamonitor.domain.model.ProductUsageBreakdown
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import com.aiquotamonitor.domain.model.ActivityBin
import com.aiquotamonitor.domain.model.ActivitySummary
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

object SeedData {

    fun createSeedSnapshot(): QuotaSnapshot {
        val now = Instant.now()
        val nowIso = now.toString()

        val argonReset = now.plus(3, ChronoUnit.HOURS).plus(22, ChronoUnit.MINUTES).toString()
        val gemini5hReset = now.plus(4, ChronoUnit.HOURS).plus(12, ChronoUnit.MINUTES).toString()
        val geminiWeeklyReset = now.plus(5, ChronoUnit.DAYS).toString()
        val openCodeRollingReset = now.plus(4, ChronoUnit.HOURS).plus(55, ChronoUnit.MINUTES).toString()
        val openCodeWeeklyReset = now.plus(1, ChronoUnit.DAYS).plus(8, ChronoUnit.HOURS).toString()
        val openCodeMonthlyReset = now.plus(27, ChronoUnit.DAYS).toString()
        val grokWeeklyReset = now.plus(4, ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS).toString()
        val grokTokenExpires = now.plus(5, ChronoUnit.HOURS).plus(50, ChronoUnit.MINUTES).toString()

        val agyProvider = ProviderQuota(
            id = "agy",
            displayName = "Antigravity (CLI)",
            subtitle = "Gemini 4 Argon, Gemini Pool & 3P",
            status = "ok",
            buckets = listOf(
                QuotaBucket(
                    id = "gemini-argon-wtu-weekly",
                    group = "Gemini 4 Argon",
                    label = "Gemini 4 Argon Weekly",
                    windowType = "weekly",
                    windowDurationHours = 168,
                    remainingPercent = 35.0,
                    usedPercent = 65.0,
                    resetsAt = argonReset,
                    elapsedPercent = 98.1,
                    paceDeltaPercent = -33.1,
                    burnRatePerHour = 0.39,
                    projectedRemainingAtReset = 33.8,
                    status = "healthy"
                ),
                QuotaBucket(
                    id = "gemini-5h",
                    group = "Gemini Models",
                    label = "Gemini Pool 5h",
                    windowType = "rolling",
                    windowDurationHours = 5,
                    remainingPercent = 99.0,
                    usedPercent = 1.0,
                    resetsAt = gemini5hReset,
                    elapsedPercent = 16.0,
                    paceDeltaPercent = -15.0,
                    burnRatePerHour = 1.25,
                    projectedRemainingAtReset = 94.0,
                    status = "healthy"
                ),
                QuotaBucket(
                    id = "gemini-weekly",
                    group = "Gemini Models",
                    label = "Gemini Pool 7d",
                    windowType = "weekly",
                    windowDurationHours = 168,
                    remainingPercent = 100.0,
                    usedPercent = 0.0,
                    resetsAt = geminiWeeklyReset,
                    elapsedPercent = 28.5,
                    paceDeltaPercent = -28.5,
                    burnRatePerHour = 0.0,
                    projectedRemainingAtReset = 100.0,
                    status = "healthy"
                ),
                QuotaBucket(
                    id = "3p-5h",
                    group = "Claude and GPT models",
                    label = "Claude & GPT 5h Rolling",
                    windowType = "rolling",
                    windowDurationHours = 5,
                    remainingPercent = 100.0,
                    usedPercent = 0.0,
                    resetsAt = gemini5hReset,
                    elapsedPercent = 10.0,
                    paceDeltaPercent = -10.0,
                    burnRatePerHour = 0.0,
                    projectedRemainingAtReset = 100.0,
                    status = "healthy"
                ),
                QuotaBucket(
                    id = "3p-weekly",
                    group = "Claude and GPT models",
                    label = "Claude & GPT Weekly",
                    windowType = "weekly",
                    windowDurationHours = 168,
                    remainingPercent = 41.7,
                    usedPercent = 58.3,
                    resetsAt = geminiWeeklyReset,
                    elapsedPercent = 28.5,
                    paceDeltaPercent = 29.8,
                    burnRatePerHour = 0.35,
                    projectedRemainingAtReset = 0.0,
                    status = "warning"
                )
            )
        )

        val openCodeProvider = ProviderQuota(
            id = "opencode_go",
            displayName = "OpenCode Go",
            subtitle = "5h, Weekly & Monthly",
            status = "ok",
            buckets = listOf(
                QuotaBucket(
                    id = "opencode-rolling",
                    group = "OpenCode Go",
                    label = "5-Hour Rolling",
                    windowType = "rolling",
                    windowDurationHours = 5,
                    remainingPercent = 100.0,
                    usedPercent = 0.0,
                    resetsAt = openCodeRollingReset,
                    elapsedPercent = 5.0,
                    paceDeltaPercent = -5.0,
                    burnRatePerHour = 0.0,
                    projectedRemainingAtReset = 100.0,
                    status = "healthy"
                ),
                QuotaBucket(
                    id = "opencode-weekly",
                    group = "OpenCode Go",
                    label = "Weekly Pool",
                    windowType = "weekly",
                    windowDurationHours = 168,
                    remainingPercent = 98.0,
                    usedPercent = 2.0,
                    resetsAt = openCodeWeeklyReset,
                    elapsedPercent = 82.0,
                    paceDeltaPercent = -80.0,
                    burnRatePerHour = 0.02,
                    projectedRemainingAtReset = 97.4,
                    status = "healthy"
                ),
                QuotaBucket(
                    id = "opencode-monthly",
                    group = "OpenCode Go",
                    label = "Monthly Pool",
                    windowType = "monthly",
                    windowDurationHours = 720,
                    remainingPercent = 99.0,
                    usedPercent = 1.0,
                    resetsAt = openCodeMonthlyReset,
                    elapsedPercent = 10.0,
                    paceDeltaPercent = -9.0,
                    burnRatePerHour = 0.01,
                    projectedRemainingAtReset = 92.5,
                    status = "healthy"
                )
            )
        )

        val grokProvider = ProviderQuota(
            id = "grok",
            displayName = "Grok",
            subtitle = "Build, Bot & Cursor Weekly",
            status = "ok",
            buckets = listOf(
                QuotaBucket(
                    id = "grok-weekly-credits",
                    group = "Grok Unified",
                    label = "Grok Weekly Credits",
                    windowType = "weekly",
                    windowDurationHours = 168,
                    remainingPercent = 45.0,
                    usedPercent = 55.0,
                    resetsAt = grokWeeklyReset,
                    elapsedPercent = 42.0,
                    paceDeltaPercent = 13.0,
                    burnRatePerHour = 0.12,
                    projectedRemainingAtReset = 10.0,
                    status = "warning",
                    productBreakdown = listOf(
                        ProductUsageBreakdown(
                            product = "GrokBot",
                            label = "Grok Bot",
                            usagePercent = 49.0
                        ),
                        ProductUsageBreakdown(
                            product = "GrokBuild",
                            label = "Grok Build",
                            usagePercent = 4.0
                        ),
                        ProductUsageBreakdown(
                            product = "Cursor",
                            label = "Cursor",
                            usagePercent = 2.0
                        )
                    )
                )
            )
        )

        val orcaRoles = listOf(
            OrcaRoleReadiness(
                role = "PLANNER",
                label = "Gemini 4 Argon",
                primaryBucketId = "gemini-argon-wtu-weekly",
                remainingPercent = 35.0,
                status = "healthy"
            ),
            OrcaRoleReadiness(
                role = "BUILDER",
                label = "Gemini Pool / OpenCode",
                primaryBucketId = "gemini-5h",
                remainingPercent = 99.0,
                status = "healthy"
            ),
            OrcaRoleReadiness(
                role = "REVIEWER",
                label = "Grok Unified",
                primaryBucketId = "grok-weekly-credits",
                remainingPercent = 45.0,
                status = "warning"
            )
        )

        // Generate 24 historical points (hourly progression)
        val historyPoints = (24 downTo 0).map { hoursAgo ->
            val pointTime = now.minus(hoursAgo.toLong(), ChronoUnit.HOURS).toString()
            val fraction = (24 - hoursAgo).toDouble() / 24.0

            val argonVal = 60.0 - (fraction * 25.0) // 60% down to 35%
            val gemini5hVal = 100.0 - (fraction * 1.0)
            val openCodeWeeklyVal = 100.0 - (fraction * 2.0)
            val grokVal = 100.0 - (fraction * 55.0)
            val threePWeeklyVal = 65.0 - (fraction * 23.3)

            HistoryPoint(
                capturedAt = pointTime,
                bucketRemaining = mapOf(
                    "gemini-argon-wtu-weekly" to argonVal,
                    "gemini-5h" to gemini5hVal,
                    "gemini-weekly" to 100.0,
                    "opencode-rolling" to 100.0,
                    "opencode-weekly" to openCodeWeeklyVal,
                    "opencode-monthly" to 99.0,
                    "grok-weekly-credits" to grokVal,
                    "3p-5h" to 100.0,
                    "3p-weekly" to threePWeeklyVal
                )
            )
        }

        return QuotaSnapshot(
            schemaVersion = 1,
            capturedAt = nowIso,
            host = "mac-host",
            smartPrime = SmartPrimeStatus(
                policy = "active_hours_or_manual",
                activeHoursStart = "08:00",
                activeHoursEnd = "22:00",
                grokTokenExpiresAt = grokTokenExpires,
                grokTokenRemainingSeconds = 21054L,
                lastPrimedAt = now.minus(2, ChronoUnit.HOURS).toString()
            ),
            orcaRoles = orcaRoles,
            providers = listOf(agyProvider, openCodeProvider, grokProvider),
            history = historyPoints,
            activity = buildSeedActivity(now)
        )
    }

    private data class SeedBinContent(
        val deltas: Map<String, Double> = emptyMap(),
        val wallHits: List<String> = emptyList(),
        val resets: List<String> = emptyList()
    )

    private fun buildSeedActivity(now: Instant): ActivitySummary {
        val monday = now
            .atZone(ZoneOffset.UTC)
            .toLocalDate()
            .with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .atStartOfDay(ZoneOffset.UTC)
            .toInstant()

        return ActivitySummary(
            window5h = seedBins(now.minus(Duration.ofHours(5)), 20, Duration.ofMinutes(15), ::seed5h),
            window24h = seedBins(now.minus(Duration.ofHours(24)), 24, Duration.ofHours(1), ::seed24h),
            window7d = seedBins(now.minus(Duration.ofDays(7)), 28, Duration.ofHours(6), ::seed7d),
            window30d = seedBins(monday.minus(Duration.ofDays(28)), 35, Duration.ofDays(1), ::seed30d)
        )
    }

    private fun seedBins(
        start: Instant,
        count: Int,
        step: Duration,
        content: (Int) -> SeedBinContent
    ): List<ActivityBin> = (0 until count).map { index ->
        val binStart = start.plus(step.multipliedBy(index.toLong()))
        val body = content(index)
        ActivityBin(
            startAt = binStart.toString(),
            endAt = binStart.plus(step).toString(),
            deltas = body.deltas,
            wallHits = body.wallHits,
            resets = body.resets
        )
    }

    private fun seed5h(index: Int): SeedBinContent = when (index) {
        2 -> SeedBinContent(mapOf("gemini-5h" to 4.2))
        3 -> SeedBinContent(mapOf("gemini-5h" to 8.7, "opencode-rolling" to 2.1))
        4 -> SeedBinContent(mapOf("gemini-5h" to 12.4, "opencode-rolling" to 5.3))
        5 -> SeedBinContent(mapOf("gemini-5h" to 6.1, "opencode-rolling" to 3.0))
        8 -> SeedBinContent(mapOf("opencode-rolling" to 4.4, "grok-weekly-credits" to 1.8))
        11 -> SeedBinContent(mapOf("gemini-5h" to 7.6, "opencode-rolling" to 6.2))
        12 -> SeedBinContent(
            mapOf("gemini-5h" to 14.2, "opencode-rolling" to 3.1, "grok-weekly-credits" to 1.2),
            wallHits = listOf("gemini-5h")
        )
        13 -> SeedBinContent(mapOf("gemini-5h" to 5.4))
        15 -> SeedBinContent(resets = listOf("gemini-5h"))
        17 -> SeedBinContent(mapOf("opencode-rolling" to 8.3, "grok-weekly-credits" to 2.4))
        else -> SeedBinContent()
    }

    private fun seed24h(index: Int): SeedBinContent = when (index) {
        1 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 3.2, "opencode-weekly" to 1.1))
        5 -> SeedBinContent(mapOf("gemini-5h" to 6.5, "opencode-rolling" to 2.2))
        6 -> SeedBinContent(mapOf("gemini-5h" to 11.3, "opencode-rolling" to 4.8))
        7 -> SeedBinContent(mapOf("gemini-5h" to 9.1, "opencode-rolling" to 5.6, "grok-weekly-credits" to 2.3))
        9 -> SeedBinContent(mapOf("gemini-5h" to 7.4, "opencode-rolling" to 3.2))
        12 -> SeedBinContent(
            mapOf("gemini-5h" to 16.8, "opencode-rolling" to 6.4),
            wallHits = listOf("gemini-5h")
        )
        13 -> SeedBinContent(mapOf("gemini-5h" to 4.6, "opencode-rolling" to 2.9))
        14 -> SeedBinContent(resets = listOf("gemini-5h"))
        16 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 5.1, "grok-weekly-credits" to 3.4))
        18 -> SeedBinContent(mapOf("opencode-weekly" to 4.7, "opencode-rolling" to 3.6))
        20 -> SeedBinContent(
            mapOf("grok-weekly-credits" to 13.2, "gemini-5h" to 3.1),
            wallHits = listOf("grok-weekly-credits")
        )
        22 -> SeedBinContent(mapOf("gemini-5h" to 2.4))
        else -> SeedBinContent()
    }

    private fun seed7d(index: Int): SeedBinContent = when (index) {
        1 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 4.1, "opencode-weekly" to 2.2))
        3 -> SeedBinContent(mapOf("gemini-5h" to 12.6, "opencode-rolling" to 5.4))
        5 -> SeedBinContent(mapOf("3p-weekly" to 3.8, "gemini-weekly" to 1.2))
        7 -> SeedBinContent(mapOf("gemini-5h" to 8.2, "opencode-weekly" to 4.6))
        9 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 7.3, "grok-weekly-credits" to 6.1))
        11 -> SeedBinContent(
            mapOf("gemini-5h" to 17.4, "opencode-rolling" to 7.2),
            wallHits = listOf("gemini-5h")
        )
        12 -> SeedBinContent(resets = listOf("opencode-weekly"))
        14 -> SeedBinContent(mapOf("gemini-weekly" to 4.9, "3p-weekly" to 6.2))
        17 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 5.7, "opencode-weekly" to 3.1))
        19 -> SeedBinContent(
            mapOf("grok-weekly-credits" to 14.8, "gemini-5h" to 6.3),
            wallHits = listOf("grok-weekly-credits")
        )
        21 -> SeedBinContent(mapOf("gemini-5h" to 9.6, "opencode-rolling" to 4.1))
        23 -> SeedBinContent(resets = listOf("gemini-5h"))
        25 -> SeedBinContent(mapOf("opencode-weekly" to 5.9, "gemini-weekly" to 2.7))
        else -> SeedBinContent()
    }

    private fun seed30d(index: Int): SeedBinContent = when (index) {
        1 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 6.4, "opencode-weekly" to 3.2))
        3 -> SeedBinContent(mapOf("gemini-5h" to 14.7, "opencode-rolling" to 6.1))
        5 -> SeedBinContent(mapOf("3p-weekly" to 8.3, "gemini-weekly" to 3.4))
        7 -> SeedBinContent(resets = listOf("opencode-weekly"))
        8 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 5.1, "grok-weekly-credits" to 7.2))
        10 -> SeedBinContent(mapOf("gemini-5h" to 18.1, "opencode-rolling" to 8.4))
        12 -> SeedBinContent(mapOf("gemini-weekly" to 6.8, "opencode-weekly" to 4.2))
        13 -> SeedBinContent(
            mapOf("grok-weekly-credits" to 16.3, "gemini-5h" to 5.9),
            wallHits = listOf("grok-weekly-credits")
        )
        14 -> SeedBinContent(resets = listOf("gemini-5h"))
        17 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 7.9, "3p-weekly" to 5.4))
        19 -> SeedBinContent(
            mapOf("gemini-5h" to 19.6, "opencode-rolling" to 9.1),
            wallHits = listOf("gemini-5h")
        )
        21 -> SeedBinContent(resets = listOf("gemini-5h", "opencode-weekly"))
        23 -> SeedBinContent(mapOf("grok-weekly-credits" to 11.4, "gemini-weekly" to 4.7))
        26 -> SeedBinContent(
            mapOf("gemini-5h" to 15.2, "opencode-weekly" to 6.8),
            wallHits = listOf("gemini-5h")
        )
        28 -> SeedBinContent(resets = listOf("gemini-5h"))
        30 -> SeedBinContent(mapOf("gemini-argon-wtu-weekly" to 4.3, "opencode-monthly" to 2.1))
        32 -> SeedBinContent(mapOf("3p-weekly" to 7.6, "gemini-5h" to 10.4))
        34 -> SeedBinContent(resets = listOf("opencode-weekly"))
        else -> SeedBinContent()
    }
}
