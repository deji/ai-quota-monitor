package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

/** Maximum number of quota cells rendered by the 4x3 wide Glance widget deck. */
const val MAX_WIDE_WIDGET_SLOTS = 3

/**
 * Normalized quota cell used by both the in-app Glance previews and the
 * home-screen widgets. It can represent a concrete provider bucket or an
 * Orca stage role, and records whether it is a fallback substitute.
 */
data class WidgetQuotaItem(
    val id: String,
    val categoryLabel: String,
    val titleLabel: String,
    val remainingPercent: Double,
    val usedPercent: Double,
    val paceDeltaPercent: Double = 0.0,
    val status: String = "healthy",
    val isRole: Boolean = false,
    val isFallback: Boolean = false,
    val resetsAt: String? = null,
    val windowDurationHours: Int = 0,
    val shortHeaderLabel: String = categoryLabel,
    val shortWindowLabel: String = "",
    val shortWideRowLabel: String = titleLabel
) {
    fun displayPercent(polarity: QuotaPolarity): Double = when (polarity) {
        QuotaPolarity.REMAINING -> remainingPercent
        QuotaPolarity.USED -> usedPercent
    }

    fun formatResetCountdown(
        nowEpochMs: Long = System.currentTimeMillis(),
        includeMinutesUnderDay: Boolean = true
    ): String = formatResetCountdownFromIso(
        resetsAt = resetsAt,
        nowEpochMs = nowEpochMs,
        includeMinutesUnderDay = includeMinutesUnderDay
    )
}

/**
 * One column of the 4x3 wide Provider Bento Deck: every visible bucket that
 * belongs to a single provider, ordered canonically.
 */
data class ProviderWidgetGroup(
    val providerId: String,
    val title: String,
    val items: List<WidgetQuotaItem>,
    val oauthLeaseLabel: String? = null
)

/**
 * Critical-pool ordering shared by the 2x2 Quad Micro-Bento grid: buckets at or
 * under 50% remaining float to the top (lowest first), everything else follows by
 * window length, with `grok-weekly-credits` winning the Grok tie (`COL-P5`).
 */
private val criticalBucketComparator = compareBy<QuotaBucket>(
    { if (it.remainingPercent <= 50.0) 0 else 1 },
    { if (it.remainingPercent <= 50.0) it.remainingPercent else it.windowDurationHours.toDouble() },
    { it.remainingPercent },
    { it.windowDurationHours },
    { it.id == "grok-bot-weekly" },
    { it.id }
)

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
            compareBy<QuotaBucket> { it.remainingPercent }
                .thenBy { it.id == "grok-bot-weekly" }
                .thenBy { it.id }
        )
    }

    /**
     * The primary bottleneck bucket (lowest remaining %, tie-broken by bucket id)
     */
    val bottleneckBucket: QuotaBucket?
        get() = providers.flatMap { it.buckets }.minWithOrNull(
            compareBy<QuotaBucket> { it.remainingPercent }
                .thenBy { it.id == "grok-bot-weekly" }
                .thenBy { it.id }
        )

    /**
     * Resolve the single cell rendered by the 2x2 compact widget.
     *
     * - `compactBucketId == null` selects the hero spotlight bucket.
     * - A `role:NAME` token resolves the matching Orca role when its primary bucket is visible.
     * - A concrete bucket id resolves when it exists and is not hidden.
     * - Any missing/hidden selection falls back to the hero bucket with `isFallback = true`.
     * - Returns null only when no visible bucket exists at all.
     */
    fun resolveCompactWidgetItem(
        compactBucketId: String? = null,
        hiddenBucketIds: Set<String> = emptySet(),
        pinnedBucketId: String? = null
    ): WidgetQuotaItem? {
        val cleanId = compactBucketId?.trim()?.takeIf { it.isNotEmpty() }
            ?: return heroBucket(hiddenBucketIds, pinnedBucketId)
                ?.toWidgetQuotaItem(isFallback = false)

        if (cleanId.startsWith("role:", ignoreCase = true)) {
            val roleName = cleanId.substringAfter(":")
            val role = orcaRoles.firstOrNull { it.role.equals(roleName, ignoreCase = true) }
            if (role != null &&
                role.primaryBucketId.isNotBlank() &&
                role.primaryBucketId !in hiddenBucketIds
            ) {
                return role.toWidgetQuotaItem(isFallback = false)
            }
        } else if (cleanId !in hiddenBucketIds) {
            findBucket(cleanId)?.let { return it.toWidgetQuotaItem(isFallback = false) }
        }

        return heroBucket(hiddenBucketIds, pinnedBucketId)?.toWidgetQuotaItem(isFallback = true)
    }

    /**
     * Resolve the three ordered cells rendered by the 4x2 wide widget.
     *
     * Always returns exactly [MAX_WIDE_WIDGET_SLOTS] cells. Custom mode performs a
     * two-pass resolution so an earlier missing slot can never steal a bucket that a
     * later slot explicitly requested. Default mode reserves every visible role
     * primary before filling holes from the visible pool.
     */
    fun resolveWideWidgetItems(
        wideBucketIds: List<String> = emptyList(),
        hiddenBucketIds: Set<String> = emptySet()
    ): List<WidgetQuotaItem> {
        val visiblePool = visibleBuckets(hiddenBucketIds)
            .sortedWith(
                compareBy<QuotaBucket> { it.remainingPercent }
                    .thenBy { it.id == "grok-bot-weekly" }
                    .thenBy { it.id }
            )
        val sanitizedIds = wideBucketIds
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .take(MAX_WIDE_WIDGET_SLOTS)

        val resolvedSlots = arrayOfNulls<WidgetQuotaItem>(MAX_WIDE_WIDGET_SLOTS)
        val usedBucketIds = mutableSetOf<String>()

        if (sanitizedIds.isEmpty()) {
            // Case A — default mode: reserve visible role primaries first.
            val defaultRoles = orcaRoles.take(MAX_WIDE_WIDGET_SLOTS)
            for (slotIndex in 0 until MAX_WIDE_WIDGET_SLOTS) {
                val role = defaultRoles.getOrNull(slotIndex) ?: continue
                if (role.primaryBucketId.isNotBlank() && role.primaryBucketId !in hiddenBucketIds) {
                    resolvedSlots[slotIndex] = role.toWidgetQuotaItem(isFallback = false)
                    usedBucketIds.add(role.primaryBucketId)
                }
            }
            for (slotIndex in 0 until MAX_WIDE_WIDGET_SLOTS) {
                if (resolvedSlots[slotIndex] != null) continue
                val substitute = visiblePool.firstOrNull { it.id !in usedBucketIds }
                if (substitute != null) {
                    usedBucketIds.add(substitute.id)
                    resolvedSlots[slotIndex] = substitute.toWidgetQuotaItem(isFallback = true)
                } else {
                    resolvedSlots[slotIndex] = placeholderItem(
                        id = "placeholder-$slotIndex",
                        slotIndex = slotIndex,
                        visiblePool = visiblePool,
                        defaultCategory = "ORCA STAGE"
                    )
                }
            }
        } else {
            // Case B — custom mode: pass 1 resolves role tokens and concrete ids.
            for (slotIndex in sanitizedIds.indices) {
                val reqId = sanitizedIds[slotIndex]
                if (reqId.startsWith("role:", ignoreCase = true)) {
                    val roleName = reqId.substringAfter(":")
                    val role = orcaRoles.firstOrNull { it.role.equals(roleName, ignoreCase = true) }
                    if (role != null &&
                        role.primaryBucketId.isNotBlank() &&
                        role.primaryBucketId !in hiddenBucketIds
                    ) {
                        resolvedSlots[slotIndex] = role.toWidgetQuotaItem(isFallback = false)
                        usedBucketIds.add(role.primaryBucketId)
                    }
                } else if (reqId !in hiddenBucketIds) {
                    val bucket = findBucket(reqId)
                    if (bucket != null) {
                        resolvedSlots[slotIndex] = bucket.toWidgetQuotaItem(isFallback = false)
                        usedBucketIds.add(bucket.id)
                    }
                }
            }
            // Pass 2 fills missing/hidden/unassigned slots without stealing reserved ids.
            for (slotIndex in 0 until MAX_WIDE_WIDGET_SLOTS) {
                if (resolvedSlots[slotIndex] != null) continue
                val substitute = visiblePool.firstOrNull { it.id !in usedBucketIds }
                if (substitute != null) {
                    usedBucketIds.add(substitute.id)
                    resolvedSlots[slotIndex] = substitute.toWidgetQuotaItem(isFallback = true)
                } else {
                    resolvedSlots[slotIndex] = placeholderItem(
                        id = sanitizedIds.getOrNull(slotIndex) ?: "placeholder-$slotIndex",
                        slotIndex = slotIndex,
                        visiblePool = visiblePool,
                        defaultCategory = "FALLBACK"
                    )
                }
            }
        }

        return resolvedSlots.map { requireNotNull(it) }
    }

    /**
     * Resolve the 2x2 Quad Micro-Bento grid used by the live compact widget and
     * its in-app preview.
     *
     * Slot 0 honours [compactBucketId] (a `role:NAME` token or a concrete bucket
     * id) strictly: a hidden or unknown selection never falls back to
     * [pinnedBucketId] and never flags neighbours. Otherwise [pinnedBucketId]
     * spotlights its bucket. The remaining slots are filled by one lowest-remaining
     * bucket per provider in canonical provider order, then by the remaining
     * visible pool, capped at 4 cells in result order.
     */
    fun resolveCompactCriticalBuckets(
        hiddenBucketIds: Set<String> = emptySet(),
        pinnedBucketId: String? = null,
        compactBucketId: String? = null
    ): List<WidgetQuotaItem> {
        val providerOrder = providers
            .map { it.id }
            .distinct()
            .sortedWith(
                compareBy<String>(
                    { providerRank(it) },
                    { it.lowercase() }
                )
            )
        val visiblePool = visibleBuckets(hiddenBucketIds)
        if (visiblePool.isEmpty()) return emptyList()

        val result = mutableListOf<WidgetQuotaItem>()
        val usedBucketIds = mutableSetOf<String>()

        // Step A — Slot 0 override (compact selection wins over the pin).
        val cleanCompact = compactBucketId?.trim()?.takeIf { it.isNotEmpty() }
        if (cleanCompact != null) {
            if (cleanCompact.startsWith("role:", ignoreCase = true)) {
                val roleName = cleanCompact
                    .removePrefix("role:")
                    .removePrefix("ROLE:")
                    .trim()
                    .uppercase()
                val role = orcaRoles.firstOrNull { it.role.equals(roleName, ignoreCase = true) }
                if (role != null &&
                    role.primaryBucketId.isNotBlank() &&
                    role.primaryBucketId !in hiddenBucketIds
                ) {
                    val primaryBucket = findBucket(role.primaryBucketId)
                    result.add(role.toWidgetQuotaItem(isFallback = false, primaryBucket = primaryBucket))
                    usedBucketIds.add(role.primaryBucketId)
                }
            } else if (cleanCompact !in hiddenBucketIds) {
                findBucket(cleanCompact)?.let {
                    result.add(it.toWidgetQuotaItem(isFallback = false))
                    usedBucketIds.add(it.id)
                }
            }
        } else {
            val cleanPin = pinnedBucketId?.trim()?.takeIf { it.isNotEmpty() }
            if (cleanPin != null && cleanPin !in hiddenBucketIds) {
                findBucket(cleanPin)?.let {
                    result.add(it.toWidgetQuotaItem(isFallback = false))
                    usedBucketIds.add(it.id)
                }
            }
        }

        // Step B — One top visible bucket per provider, in canonical provider order.
        for (providerId in providerOrder) {
            if (result.size >= 4) break
            val provider = providers.firstOrNull { it.id == providerId } ?: continue
            val next = provider.buckets
                .filter { it.id !in hiddenBucketIds && it.id !in usedBucketIds }
                .minWithOrNull(criticalBucketComparator)
                ?: continue
            result.add(next.toWidgetQuotaItem(isFallback = false))
            usedBucketIds.add(next.id)
        }

        // Step C — Fill the remaining slots from the leftover visible pool.
        if (result.size < 4) {
            val remaining = providers
                .flatMap { it.buckets }
                .filter { it.id !in hiddenBucketIds && it.id !in usedBucketIds }
                .sortedWith(criticalBucketComparator)
            for (bucket in remaining) {
                if (result.size >= 4) break
                result.add(bucket.toWidgetQuotaItem(isFallback = false))
                usedBucketIds.add(bucket.id)
            }
        }

        return result.toList()
    }

    /**
     * Resolve the 4x3 wide Provider Bento Deck: one column per provider that still
     * has a visible bucket, ordered canonically with canonical bucket ordering.
     */
    fun resolveWideProviderGroups(
        hiddenBucketIds: Set<String> = emptySet()
    ): List<ProviderWidgetGroup> {
        val orderedProviders = providers.sortedWith(
            compareBy<ProviderQuota>(
                { providerRank(it.id) },
                { it.id.lowercase() }
            )
        )
        return orderedProviders.mapNotNull { provider ->
            val items = provider.buckets
                .filter { it.id !in hiddenBucketIds }
                .sortedWith(bucketOrderComparator(provider.id))
                .map { it.toWidgetQuotaItem(isFallback = false) }
            if (items.isEmpty()) return@mapNotNull null
            ProviderWidgetGroup(
                providerId = provider.id,
                title = providerGroupTitle(provider),
                items = items,
                oauthLeaseLabel = if (provider.id.lowercase() == "grok") grokOAuthLeaseLabel() else null
            )
        }
    }

    private fun grokOAuthLeaseLabel(): String? {
        val remSec = smartPrime.grokTokenRemainingSeconds ?: return null
        return when {
            remSec <= 0L && smartPrime.primed -> "PRIMED"
            remSec <= 0L -> "EXPIRED"
            else -> "${(remSec / 60L).coerceAtLeast(1L)}m TTL"
        }
    }

    private fun providerRank(providerId: String): Int = when (providerId.lowercase()) {
        "agy" -> 0
        "opencode_go", "opencode" -> 1
        "grok" -> 2
        else -> 3
    }

    private fun providerGroupTitle(provider: ProviderQuota): String = when (provider.id.lowercase()) {
        "agy" -> "ANTIGRAVITY"
        "opencode_go", "opencode" -> "OPENCODE GO"
        "grok" -> "GROK BUILD"
        else -> provider.displayName.uppercase().ifBlank { provider.id.uppercase() }
    }

    private fun bucketOrderComparator(providerId: String): Comparator<QuotaBucket> {
        val canonical = canonicalBucketOrder(providerId)
        return compareBy<QuotaBucket>(
            { bucket -> canonical.indexOf(bucket.id).let { if (it < 0) Int.MAX_VALUE else it } },
            { it.id }
        )
    }

    private fun canonicalBucketOrder(providerId: String): List<String> = when (providerId.lowercase()) {
        "agy" -> listOf("gemini-argon-wtu-weekly", "gemini-5h", "gemini-weekly", "3p-5h", "3p-weekly")
        "opencode_go", "opencode" -> listOf("opencode-rolling", "opencode-weekly", "opencode-monthly")
        "grok" -> listOf("grok-weekly-credits", "grok-bot-weekly")
        else -> emptyList()
    }

    private fun placeholderItem(
        id: String,
        slotIndex: Int,
        visiblePool: List<QuotaBucket>,
        defaultCategory: String
    ): WidgetQuotaItem {
        val poolEmpty = visiblePool.isEmpty()
        return WidgetQuotaItem(
            id = id.ifBlank { "placeholder-$slotIndex" },
            categoryLabel = if (poolEmpty) defaultCategory else "FALLBACK",
            titleLabel = if (poolEmpty) "All models hidden" else "Unavailable",
            remainingPercent = Double.NaN,
            usedPercent = Double.NaN,
            paceDeltaPercent = 0.0,
            status = "unavailable",
            isFallback = true
        )
    }

    private fun QuotaBucket.toWidgetQuotaItem(isFallback: Boolean): WidgetQuotaItem {
        return WidgetQuotaItem(
            id = id,
            categoryLabel = providerCategoryLabel(id),
            titleLabel = label,
            remainingPercent = remainingPercent,
            usedPercent = usedPercent,
            paceDeltaPercent = paceDeltaPercent,
            status = status,
            isRole = false,
            isFallback = isFallback,
            resetsAt = resetsAt,
            windowDurationHours = windowDurationHours,
            // Known bucket ids keep their tailored label; anything else falls back to the
            // provider-derived category (which may be a custom provider name).
            shortHeaderLabel = shortHeaderLabel().takeIf { it != "QUOTA" } ?: providerCategoryLabel(id),
            shortWindowLabel = shortWindowLabel(),
            shortWideRowLabel = shortWideRowLabel()
        )
    }

    private fun OrcaRoleReadiness.toWidgetQuotaItem(
        isFallback: Boolean,
        primaryBucket: QuotaBucket? = null
    ): WidgetQuotaItem {
        return WidgetQuotaItem(
            id = "role:${role.uppercase()}",
            categoryLabel = role,
            titleLabel = label,
            remainingPercent = remainingPercent,
            usedPercent = (100.0 - remainingPercent).coerceAtLeast(0.0),
            paceDeltaPercent = findBucket(primaryBucketId)?.paceDeltaPercent ?: 0.0,
            status = status,
            isRole = true,
            isFallback = isFallback,
            resetsAt = primaryBucket?.resetsAt,
            windowDurationHours = primaryBucket?.windowDurationHours ?: 0,
            shortHeaderLabel = role,
            shortWindowLabel = primaryBucket?.shortWindowLabel() ?: "",
            shortWideRowLabel = label
        )
    }

    private fun providerCategoryLabel(bucketId: String): String {
        val provider = providers.firstOrNull { provider -> provider.buckets.any { it.id == bucketId } }
        return when (provider?.id?.lowercase()) {
            "agy" -> "AGY"
            "opencode", "opencode_go" -> "OPENCODE"
            "grok" -> "GROK"
            else -> provider?.id?.uppercase() ?: "QUOTA"
        }
    }

    companion object {
        const val DEFAULT_NEAREST_RESET_HOURS = 3.4
    }
}

@Serializable
data class HistoryPoint(
    val capturedAt: String,
    val bucketRemaining: Map<String, Double> = emptyMap()
)
