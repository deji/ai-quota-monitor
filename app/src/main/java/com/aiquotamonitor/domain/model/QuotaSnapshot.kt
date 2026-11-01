package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class QuotaSnapshot(
    val schemaVersion: Int = 1,
    val capturedAt: String,
    val host: String = "collector",
    val smartPrime: SmartPrimeStatus = SmartPrimeStatus(),
    val orcaRoles: List<OrcaRoleReadiness> = emptyList(),
    val providers: List<ProviderQuota> = emptyList(),
    val history: List<HistoryPoint> = emptyList()
) {
    /**
     * Snapshots older than 2 hours (7200 seconds) are considered stale.
     */
    val isStale: Boolean
        get() = try {
            val captured = java.time.Instant.parse(capturedAt)
            java.time.Duration.between(captured, java.time.Instant.now()).seconds > 7200
        } catch (_: Exception) {
            false
        }
    /**
     * Find bucket by id across all providers
     */
    fun findBucket(bucketId: String): QuotaBucket? {
        return providers.flatMap { it.buckets }.firstOrNull { it.id == bucketId }
    }

    /**
     * The primary bottleneck bucket (lowest remaining %)
     */
    val bottleneckBucket: QuotaBucket?
        get() = providers.flatMap { it.buckets }.minByOrNull { it.remainingPercent }
}

@Serializable
data class HistoryPoint(
    val capturedAt: String,
    val bucketRemaining: Map<String, Double> = emptyMap()
)
