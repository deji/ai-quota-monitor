package com.aiquotamonitor.data.local

import com.aiquotamonitor.domain.model.HistoryPoint
import com.aiquotamonitor.domain.model.OrcaRoleReadiness
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import java.time.Instant
import java.time.temporal.ChronoUnit

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
            subtitle = "Gemini 4 Argon & 3.8 Flash",
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
                    label = "Gemini 3.8 Flash 5h Rolling",
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
                    label = "Gemini Weekly Pool",
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
            subtitle = "5h Rolling, Weekly & Monthly Pools",
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
            displayName = "Grok Build CLI",
            subtitle = "Grok 4.7 Weekly Unified Credits",
            status = "ok",
            buckets = listOf(
                QuotaBucket(
                    id = "grok-weekly-credits",
                    group = "Grok 4.7",
                    label = "Weekly Unified Credits",
                    windowType = "weekly",
                    windowDurationHours = 168,
                    remainingPercent = 97.0,
                    usedPercent = 3.0,
                    resetsAt = grokWeeklyReset,
                    elapsedPercent = 42.0,
                    paceDeltaPercent = -39.0,
                    burnRatePerHour = 0.04,
                    projectedRemainingAtReset = 93.1,
                    status = "primed"
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
                label = "Flash 3.8 / OpenCode",
                primaryBucketId = "gemini-5h",
                remainingPercent = 99.0,
                status = "healthy"
            ),
            OrcaRoleReadiness(
                role = "REVIEWER",
                label = "Grok 4.7 Build",
                primaryBucketId = "grok-weekly-credits",
                remainingPercent = 97.0,
                status = "primed"
            )
        )

        // Generate 24 historical points (hourly progression)
        val historyPoints = (24 downTo 0).map { hoursAgo ->
            val pointTime = now.minus(hoursAgo.toLong(), ChronoUnit.HOURS).toString()
            val fraction = (24 - hoursAgo).toDouble() / 24.0

            val argonVal = 60.0 - (fraction * 25.0) // 60% down to 35%
            val gemini5hVal = 100.0 - (fraction * 1.0)
            val openCodeWeeklyVal = 100.0 - (fraction * 2.0)
            val grokVal = 100.0 - (fraction * 3.0)
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
            history = historyPoints
        )
    }
}
