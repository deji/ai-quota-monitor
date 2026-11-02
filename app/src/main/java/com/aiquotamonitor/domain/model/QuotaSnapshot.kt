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
     * Buckets across all providers excluding hidden ones.
     */
    fun visibleBuckets(hiddenBucketIds: Set<String> = emptySet()): List<QuotaBucket> {
        return providers.flatMap { it.buckets }.filterNot { it.id in hiddenBucketIds }
    }

    /**
     * The spotlight bucket for the hero card:
     * Pinned bucket if specified, present, and not hidden;
     * otherwise the lowest remaining % bucket among visible buckets (tie-broken by bucket id).
     * If all visible buckets are hidden and no usable pin exists, returns null.
     */
    fun heroBucket(hiddenBucketIds: Set<String> = emptySet(), pinnedBucketId: String? = null): QuotaBucket? {
        if (pinnedBucketId != null && pinnedBucketId !in hiddenBucketIds) {
            val pinned = findBucket(pinnedBucketId)
            if (pinned != null) return pinned
        }
        return visibleBuckets(hiddenBucketIds).minWithOrNull(
            compareBy<QuotaBucket> { it.remainingPercent }.thenBy { it.id }
        )
    }

    /**
     * The primary bottleneck bucket (lowest remaining %, tie-broken by bucket id)
     */
    val bottleneckBucket: QuotaBucket?
        get() = providers.flatMap { it.buckets }.minWithOrNull(
            compareBy<QuotaBucket> { it.remainingPercent }.thenBy { it.id }
        )

    companion object {
        const val DEFAULT_NEAREST_RESET_HOURS = 3.4
    }
}

@Serializable
data class HistoryPoint(
    val capturedAt: String,
    val bucketRemaining: Map<String, Double> = emptyMap()
)
