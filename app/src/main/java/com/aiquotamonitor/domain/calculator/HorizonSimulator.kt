package com.aiquotamonitor.domain.calculator

import com.aiquotamonitor.domain.model.OrcaRoleReadiness
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaSnapshot
import kotlin.math.round

object HorizonSimulator {

    /**
     * Projects quota consumption forward by [offsetHours].
     *
     * @param snapshot Base snapshot captured at [baseNowEpochMs].
     * @param offsetHours Number of hours into the future to project (e.g. 0.0, 1.0, 3.0, 24.0).
     * @param baseNowEpochMs Baseline timestamp in epoch milliseconds (defaults to snapshot capturedAt).
     */
    fun project(
        snapshot: QuotaSnapshot,
        offsetHours: Double,
        baseNowEpochMs: Long = QuotaPaceCalculator.parseIsoToEpochMs(snapshot.capturedAt)
    ): QuotaSnapshot {
        if (offsetHours <= 0.0) return snapshot

        val futureEpochMs = baseNowEpochMs + (offsetHours * 3600 * 1000L).toLong()

        // Simulate each provider's buckets
        val simulatedProviders = snapshot.providers.map { provider ->
            val simulatedBuckets = provider.buckets.map { bucket ->
                simulateBucket(bucket, offsetHours, futureEpochMs)
            }
            provider.copy(buckets = simulatedBuckets)
        }

        // Map bucket ID to simulated remaining percentage
        val simulatedBucketMap = simulatedProviders.flatMap { it.buckets }.associateBy { it.id }

        // Update Orca roles according to simulated primary buckets
        val simulatedRoles = snapshot.orcaRoles.map { role ->
            val simulatedBucket = simulatedBucketMap[role.primaryBucketId]
            if (simulatedBucket != null) {
                role.copy(
                    remainingPercent = simulatedBucket.remainingPercent,
                    status = simulatedBucket.status
                )
            } else {
                role
            }
        }

        // Update Smart Prime lease countdown
        val simulatedSmartPrime = snapshot.smartPrime.let { sp ->
            val remainingSec = sp.grokTokenRemainingSeconds
            if (remainingSec != null) {
                val offsetSec = (offsetHours * 3600).toLong()
                val updatedRemaining = (remainingSec - offsetSec).coerceAtLeast(0L)
                sp.copy(grokTokenRemainingSeconds = updatedRemaining)
            } else {
                sp
            }
        }

        return snapshot.copy(
            smartPrime = simulatedSmartPrime,
            orcaRoles = simulatedRoles,
            providers = simulatedProviders
        )
    }

    private fun simulateBucket(
        bucket: QuotaBucket,
        offsetHours: Double,
        futureEpochMs: Long
    ): QuotaBucket {
        val resetsAtEpochMs = QuotaPaceCalculator.parseIsoToEpochMs(bucket.resetsAt)

        // Case 1: Window has reset in the future
        if (futureEpochMs >= resetsAtEpochMs && resetsAtEpochMs > 0) {
            return bucket.copy(
                remainingPercent = 100.0,
                usedPercent = 0.0,
                elapsedPercent = 0.0,
                paceDeltaPercent = 0.0,
                projectedRemainingAtReset = 100.0,
                status = "healthy"
            )
        }

        // Case 2: Window still active, project burn
        val burnRate = bucket.burnRatePerHour.coerceAtLeast(0.0)
        val offsetBurn = burnRate * offsetHours
        val projectedRemaining = (bucket.remainingPercent - offsetBurn).coerceIn(0.0, 100.0)
        val roundedRemaining = round(projectedRemaining * 10.0) / 10.0
        val roundedUsed = round((100.0 - roundedRemaining) * 10.0) / 10.0

        val elapsedPercent = QuotaPaceCalculator.computeElapsedPercent(
            windowDurationHours = bucket.windowDurationHours,
            resetsAtEpochMs = resetsAtEpochMs,
            nowEpochMs = futureEpochMs
        )
        val paceDelta = QuotaPaceCalculator.computeLinearPaceDelta(roundedUsed, elapsedPercent)

        val totalHours = bucket.windowDurationHours.toDouble()
        val remainingHours = ((100.0 - elapsedPercent) / 100.0) * totalHours
        val projectedAtReset = QuotaPaceCalculator.computeProjectedRemainingAtReset(
            remainingPercent = roundedRemaining,
            burnRatePerHour = burnRate,
            remainingHours = remainingHours
        )

        val status = when {
            roundedRemaining <= 10.0 -> "critical"
            roundedRemaining <= 25.0 || paceDelta > 15.0 -> "warning"
            else -> "healthy"
        }

        return bucket.copy(
            remainingPercent = roundedRemaining,
            usedPercent = roundedUsed,
            elapsedPercent = elapsedPercent,
            paceDeltaPercent = paceDelta,
            projectedRemainingAtReset = projectedAtReset,
            status = status
        )
    }
}
