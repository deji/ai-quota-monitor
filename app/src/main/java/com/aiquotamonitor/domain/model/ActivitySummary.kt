package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

/**
 * One historical aggregation bin emitted by the collector.
 *
 * [deltas] maps a bucket id to the positive percentage-point usage accrued
 * between consecutive readings inside the bin. Zero deltas are omitted by the
 * collector so the payload stays compact.
 */
@Serializable
data class ActivityBin(
    val startAt: String,
    val endAt: String,
    val deltas: Map<String, Double> = emptyMap(),
    val wallHits: List<String> = emptyList(),
    val resets: List<String> = emptyList()
)

/**
 * Collector activity payload. Every window defaults to an empty list so legacy
 * cached snapshots (and collector builds without activity support) decode
 * cleanly.
 */
@Serializable
data class ActivitySummary(
    val window5h: List<ActivityBin> = emptyList(),
    val window24h: List<ActivityBin> = emptyList(),
    val window7d: List<ActivityBin> = emptyList(),
    val window30d: List<ActivityBin> = emptyList()
)

/** Activity range pills shown above the Burst Spectrum / GitHub Quilt. */
enum class ActivityRange(val label: String) {
    H5("5h"),
    H24("24h"),
    D7("7d"),
    D30("30d")
}

/** Two complementary historical visualizations. */
enum class ActivityViewMode {
    LED_SPECTRUM,
    GITHUB_QUILT
}

/**
 * Wildcard Hybrid default: short horizons read as a live LED spectrum while
 * multi-day horizons read as a calendar quilt. The view toggle can still
 * override this per range.
 */
fun ActivityRange.defaultViewMode(): ActivityViewMode = when (this) {
    ActivityRange.H5, ActivityRange.H24 -> ActivityViewMode.LED_SPECTRUM
    ActivityRange.D7, ActivityRange.D30 -> ActivityViewMode.GITHUB_QUILT
}
