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
     * Age of this snapshot in seconds relative to [nowEpochMs].
     * Returns null when [capturedAt] is blank or cannot be parsed.
     * Negative values mean the snapshot timestamp is in the future.
     */
    fun ageSeconds(nowEpochMs: Long = System.currentTimeMillis()): Long? {
        if (capturedAt.isBlank()) return null
        return try {
            val captured = java.time.Instant.parse(capturedAt)
            java.time.Duration.between(captured, java.time.Instant.ofEpochMilli(nowEpochMs)).seconds
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Compact, non-blocking age label for status pills:
     * `"Just now"`, `"3m old"`, `"2h old"`, `"2h 5m old"`, or `""` when unknown.
     */
    fun formatAgeShort(nowEpochMs: Long = System.currentTimeMillis()): String {
        val seconds = ageSeconds(nowEpochMs) ?: return ""
        return when {
            seconds < 60 -> "Just now"
            seconds < 3600 -> "${seconds / 60}m old"
            (seconds / 60) % 60 == 0L -> "${seconds / 3600}h old"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m old"
        }
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
