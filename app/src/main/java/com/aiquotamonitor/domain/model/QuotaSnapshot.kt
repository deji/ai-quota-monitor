package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class QuotaSnapshot(
    val schemaVersion: Int = 1,
    val capturedAt: String,
    val host: String = "mac-host",
    val smartPrime: SmartPrimeStatus = SmartPrimeStatus(),
    val orcaRoles: List<OrcaRoleReadiness> = emptyList(),
    val providers: List<ProviderQuota> = emptyList(),
    val history: List<HistoryPoint> = emptyList()
) {
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
