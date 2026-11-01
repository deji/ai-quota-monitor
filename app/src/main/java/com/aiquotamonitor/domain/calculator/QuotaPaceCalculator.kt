package com.aiquotamonitor.domain.calculator

import com.aiquotamonitor.domain.model.QuotaBucket
import java.time.Instant
import kotlin.math.round

object QuotaPaceCalculator {

    /**
     * Parses an ISO-8601 string (e.g. 2026-11-01T19:39:25Z) to epoch milliseconds.
     */
    fun parseIsoToEpochMs(isoString: String): Long {
        return try {
            Instant.parse(isoString).toEpochMilli()
        } catch (_: Exception) {
            0L
        }
    }

    /**
     * Computes the percentage of the quota window that has elapsed.
     * Returns a value in 0.0 .. 100.0.
     */
    fun computeElapsedPercent(
        windowDurationHours: Int,
        resetsAtEpochMs: Long,
        nowEpochMs: Long
    ): Double {
        if (windowDurationHours <= 0) return 0.0
        val windowDurationMs = windowDurationHours * 3600 * 1000L
        val startEpochMs = resetsAtEpochMs - windowDurationMs
        val elapsedMs = (nowEpochMs - startEpochMs).coerceIn(0L, windowDurationMs)
        val percent = (elapsedMs.toDouble() / windowDurationMs.toDouble()) * 100.0
        return roundToOneDecimal(percent)
    }

    /**
     * Computes the linear pace delta %: (actual used % - expected linear used %).
     * Negative means under linear budget (healthy / safe).
     * Positive means over linear budget (burning fast).
     */
    fun computeLinearPaceDelta(usedPercent: Double, elapsedPercent: Double): Double {
        return roundToOneDecimal(usedPercent - elapsedPercent)
    }

    /**
     * Computes burn rate in percent per hour based on used quota and elapsed hours.
     */
    fun computeBurnRatePerHour(usedPercent: Double, elapsedHours: Double): Double {
        if (elapsedHours <= 0.05) return 0.0
        val rate = usedPercent / elapsedHours
        return round(rate * 100.0) / 100.0
    }

    /**
     * Computes projected remaining quota percentage at reset time.
     */
    fun computeProjectedRemainingAtReset(
        remainingPercent: Double,
        burnRatePerHour: Double,
        remainingHours: Double
    ): Double {
        val safeRemainingHours = remainingHours.coerceAtLeast(0.0)
        val projectedBurn = burnRatePerHour * safeRemainingHours
        val projected = (remainingPercent - projectedBurn).coerceIn(0.0, 100.0)
        return roundToOneDecimal(projected)
    }

    /**
     * Recalculates pace metrics for a single QuotaBucket against a specified current time.
     */
    fun recalculateBucket(bucket: QuotaBucket, nowEpochMs: Long): QuotaBucket {
        val resetsAtEpochMs = parseIsoToEpochMs(bucket.resetsAt)
        val elapsedPercent = computeElapsedPercent(
            windowDurationHours = bucket.windowDurationHours,
            resetsAtEpochMs = resetsAtEpochMs,
            nowEpochMs = nowEpochMs
        )
        val paceDelta = computeLinearPaceDelta(bucket.usedPercent, elapsedPercent)

        val totalWindowHours = bucket.windowDurationHours.toDouble()
        val elapsedHours = (elapsedPercent / 100.0) * totalWindowHours
        val remainingHours = ((100.0 - elapsedPercent) / 100.0) * totalWindowHours

        val burnRate = computeBurnRatePerHour(bucket.usedPercent, elapsedHours)
        val projectedRemaining = computeProjectedRemainingAtReset(
            remainingPercent = bucket.remainingPercent,
            burnRatePerHour = burnRate,
            remainingHours = remainingHours
        )

        val status = when {
            projectedRemaining <= 10.0 || bucket.remainingPercent <= 10.0 -> "critical"
            paceDelta > 15.0 || bucket.remainingPercent <= 25.0 -> "warning"
            else -> "healthy"
        }

        return bucket.copy(
            elapsedPercent = elapsedPercent,
            paceDeltaPercent = paceDelta,
            burnRatePerHour = burnRate,
            projectedRemainingAtReset = projectedRemaining,
            status = status
        )
    }

    private fun roundToOneDecimal(value: Double): Double {
        return round(value * 10.0) / 10.0
    }
}
