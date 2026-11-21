package com.aiquotamonitor.domain.calculator

import com.aiquotamonitor.domain.model.ActivityBin
import com.aiquotamonitor.domain.model.ActivityRange
import com.aiquotamonitor.domain.model.ActivitySummary
import com.aiquotamonitor.domain.model.QuotaSnapshot
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/** One normalized column or quilt cell in the activity board. */
data class ActivityCell(
    val index: Int,
    val startAt: String,
    val endAt: String,
    val shortLabel: String,
    val tooltipDateLabel: String,
    val totalDelta: Double,
    val agyDelta: Double,
    val openCodeDelta: Double,
    val grokDelta: Double,
    val intensityLevel: Int,
    val agyDots: Int,
    val openCodeDots: Int,
    val grokDots: Int,
    val hasWallHit: Boolean,
    val hasReset: Boolean,
    val dominantProviderId: String?
)

/** Pure, deterministic view model for the Burst Spectrum / GitHub Quilt. */
data class ActivityBoardModel(
    val range: ActivityRange,
    val cells: List<ActivityCell> = emptyList(),
    val activeCount: Int = 0,
    val peakDelta: Double = 0.0,
    val resetCount: Int = 0,
    val wallHitCount: Int = 0,
    val defaultSelectedIndex: Int = 0,
    val hasData: Boolean = false,
    val selectedBucketId: String? = null,
    val selectedProviderId: String? = null
)

/**
 * Aggregates collector activity bins into a provider-aware activity board.
 *
 * Filtering precedence is a concrete bucket (tapped Chronograph wedge), then a
 * provider legend selection, then every visible bucket ("All"). Hidden buckets
 * are always excluded. When the snapshot predates activity support, bins are
 * synthesized honestly from [QuotaSnapshot.history] instead of inventing data.
 */
object ActivityAggregator {

    fun aggregate(
        snapshot: QuotaSnapshot,
        range: ActivityRange,
        selectedBucketId: String? = null,
        hiddenBucketIds: Set<String> = emptySet(),
        selectedProviderId: String? = null,
        zone: ZoneId = ZoneId.systemDefault()
    ): ActivityBoardModel {
        val bucketProvider = bucketProviderMap(snapshot)
        val cleanProvider = selectedProviderId?.let(::normalizeProviderId)
        val cleanBucket = selectedBucketId?.takeIf { it !in hiddenBucketIds }

        val relevantBucketIds: Set<String> = when {
            cleanBucket != null -> setOf(cleanBucket)
            cleanProvider != null -> bucketProvider
                .filterValues { it == cleanProvider }
                .keys
                .filterNot { it in hiddenBucketIds }
                .toSet()
            else -> bucketProvider.keys.filterNot { it in hiddenBucketIds }.toSet()
        }

        val bins = snapshot.activity?.window(range)?.takeIf { it.isNotEmpty() }
            ?: synthesizeFromHistory(snapshot, relevantBucketIds)

        if (bins.isEmpty()) {
            return ActivityBoardModel(
                range = range,
                cells = emptyList(),
                defaultSelectedIndex = 0,
                hasData = false,
                selectedBucketId = cleanBucket,
                selectedProviderId = cleanProvider
            )
        }

        val rawCells = bins.mapIndexed { index, bin ->
            buildCell(
                index = index,
                bin = bin,
                range = range,
                relevantBucketIds = relevantBucketIds,
                bucketProvider = bucketProvider,
                zone = zone
            )
        }

        val peakDelta = rawCells.maxOfOrNull { it.totalDelta } ?: 0.0
        val cells = rawCells.map { cell -> quantize(cell, peakDelta) }

        val activeCount = cells.count { it.totalDelta > 0.0 || it.hasReset || it.hasWallHit }
        val resetCount = cells.count { it.hasReset }
        val wallHitCount = cells.count { it.hasWallHit }
        val defaultIndex = cells
            .filter { it.totalDelta > 0.0 }
            .maxByOrNull { it.totalDelta }
            ?.index
            ?: cells.lastOrNull { it.totalDelta > 0.0 || it.hasReset || it.hasWallHit }?.index
            ?: cells.lastIndex.coerceAtLeast(0)

        return ActivityBoardModel(
            range = range,
            cells = cells,
            activeCount = activeCount,
            peakDelta = peakDelta,
            resetCount = resetCount,
            wallHitCount = wallHitCount,
            defaultSelectedIndex = defaultIndex,
            hasData = cells.any { it.totalDelta > 0.0 || it.hasReset || it.hasWallHit },
            selectedBucketId = cleanBucket,
            selectedProviderId = cleanProvider
        )
    }

    private fun buildCell(
        index: Int,
        bin: ActivityBin,
        range: ActivityRange,
        relevantBucketIds: Set<String>,
        bucketProvider: Map<String, String>,
        zone: ZoneId
    ): ActivityCell {
        var agyDelta = 0.0
        var openCodeDelta = 0.0
        var grokDelta = 0.0

        bin.deltas.forEach { (bucketId, delta) ->
            if (bucketId !in relevantBucketIds || delta <= 0.0) return@forEach
            when (bucketProvider[bucketId]) {
                "agy" -> agyDelta += delta
                "opencode" -> openCodeDelta += delta
                "grok" -> grokDelta += delta
            }
        }

        val totalDelta = agyDelta + openCodeDelta + grokDelta
        val hasWallHit = bin.wallHits.any { it in relevantBucketIds }
        val hasReset = bin.resets.any { it in relevantBucketIds }

        val labels = labelsFor(bin, range, zone)

        return ActivityCell(
            index = index,
            startAt = bin.startAt,
            endAt = bin.endAt,
            shortLabel = labels.first,
            tooltipDateLabel = labels.second,
            totalDelta = roundToTenth(totalDelta),
            agyDelta = roundToTenth(agyDelta),
            openCodeDelta = roundToTenth(openCodeDelta),
            grokDelta = roundToTenth(grokDelta),
            intensityLevel = 0,
            agyDots = 0,
            openCodeDots = 0,
            grokDots = 0,
            hasWallHit = hasWallHit,
            hasReset = hasReset,
            dominantProviderId = dominantProvider(agyDelta, openCodeDelta, grokDelta)
        )
    }

    private fun quantize(cell: ActivityCell, peakDelta: Double): ActivityCell {
        if (cell.totalDelta <= 0.0) {
            return cell.copy(
                intensityLevel = if (cell.hasWallHit || cell.hasReset) 1 else 0
            )
        }
        val level = if (peakDelta <= 0.0) {
            1
        } else {
            ((cell.totalDelta / peakDelta) * 4.0).roundToInt().coerceIn(1, 4)
        }
        val dots = providerDots(
            agy = cell.agyDelta,
            openCode = cell.openCodeDelta,
            grok = cell.grokDelta,
            peak = peakDelta
        )
        return cell.copy(
            intensityLevel = level,
            agyDots = dots[0],
            openCodeDots = dots[1],
            grokDots = dots[2]
        )
    }

    /**
     * Partition up to ten LED dots across the three providers. A provider only
     * receives dots when it burned something in the bin, and the busiest
     * provider always keeps at least one.
     */
    private fun providerDots(agy: Double, openCode: Double, grok: Double, peak: Double): IntArray {
        if (peak <= 0.0) return intArrayOf(0, 0, 0)
        val dots = intArrayOf(
            dotsFor(agy, peak),
            dotsFor(openCode, peak),
            dotsFor(grok, peak)
        )
        while (dots.sum() > MAX_LED_DOTS) {
            val largest = dots.indices.maxByOrNull { dots[it] } ?: break
            if (dots[largest] <= 1) break
            dots[largest] -= 1
        }
        return dots
    }

    private fun dotsFor(value: Double, peak: Double): Int {
        if (value <= 0.0 || peak <= 0.0) return 0
        return max(1, ((value / peak) * MAX_LED_DOTS).roundToInt())
    }

    private fun dominantProvider(agy: Double, openCode: Double, grok: Double): String? {
        val ranked = listOf(
            "agy" to agy,
            "opencode" to openCode,
            "grok" to grok
        ).filter { it.second > 0.0 }
        if (ranked.isEmpty()) return null
        return ranked.maxByOrNull { it.second }?.first
    }

    private fun synthesizeFromHistory(
        snapshot: QuotaSnapshot,
        relevantBucketIds: Set<String>
    ): List<ActivityBin> {
        val points = snapshot.history
        if (points.size < 2) return emptyList()
        return points.zipWithNext().map { (previous, current) ->
            val deltas = mutableMapOf<String, Double>()
            val wallHits = mutableListOf<String>()
            val resets = mutableListOf<String>()

            relevantBucketIds.forEach { bucketId ->
                val before = previous.bucketRemaining[bucketId]
                val after = current.bucketRemaining[bucketId]
                if (before != null && after != null) {
                    val delta = before - after
                    if (delta > 0.0) deltas[bucketId] = delta
                    if (after <= 10.0) wallHits.add(bucketId)
                    if (after - before >= 10.0) resets.add(bucketId)
                }
            }

            ActivityBin(
                startAt = previous.capturedAt,
                endAt = current.capturedAt,
                deltas = deltas,
                wallHits = wallHits.sorted(),
                resets = resets.sorted()
            )
        }
    }

    private fun bucketProviderMap(snapshot: QuotaSnapshot): Map<String, String> {
        val map = mutableMapOf<String, String>()
        snapshot.providers.forEach { provider ->
            val normalized = normalizeProviderId(provider.id)
            provider.buckets.forEach { bucket -> map[bucket.id] = normalized }
        }
        return map
    }

    private fun normalizeProviderId(providerId: String): String = when (providerId.lowercase()) {
        "opencode_go", "opencode" -> "opencode"
        else -> providerId.lowercase()
    }

    private fun ActivitySummary.window(range: ActivityRange): List<ActivityBin> = when (range) {
        ActivityRange.H5 -> window5h
        ActivityRange.H24 -> window24h
        ActivityRange.D7 -> window7d
        ActivityRange.D30 -> window30d
    }

    private fun labelsFor(bin: ActivityBin, range: ActivityRange, zone: ZoneId): Pair<String, String> {
        val start = parseInstant(bin.startAt)
        val end = parseInstant(bin.endAt)
        if (start == null) {
            return bin.startAt to bin.startAt
        }
        val startZoned = start.atZone(zone)
        return when (range) {
            ActivityRange.H5, ActivityRange.H24 -> {
                val short = TIME_FORMAT.format(startZoned)
                val endLabel = end?.let { TIME_FORMAT.format(it.atZone(zone)) } ?: short
                short to "$short–$endLabel · ${DAY_FORMAT.format(startZoned)}"
            }
            ActivityRange.D7 -> {
                val period = PERIOD_LABELS[(startZoned.hour / 6).coerceIn(0, PERIOD_LABELS.lastIndex)]
                period to "${DAY_OF_WEEK_FORMAT.format(startZoned)} · $period"
            }
            ActivityRange.D30 -> {
                // window30d bins are Monday 00:00 UTC..Sunday 24:00 UTC calendar days,
                // so format labels in UTC to keep 5x7 weekday columns and dates aligned.
                val startUtc = start.atZone(ZoneOffset.UTC)
                val short = MONTH_DAY_FORMAT.format(startUtc)
                short to DAY_DATE_FORMAT.format(startUtc)
            }
        }
    }

    private fun parseInstant(iso: String): Instant? = try {
        Instant.parse(iso)
    } catch (_: Exception) {
        null
    }

    private fun roundToTenth(value: Double): Double = (value * 10.0).roundToInt() / 10.0

    private const val MAX_LED_DOTS = 10
    private val PERIOD_LABELS = listOf("Night", "AM", "PM", "Eve")
    private val TIME_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
    private val DAY_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
    private val DAY_OF_WEEK_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.US)
    private val MONTH_DAY_FORMAT = DateTimeFormatter.ofPattern("MMM d", Locale.US)
    private val DAY_DATE_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d", Locale.US)
}
