package com.aiquotamonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.widget.widgetHealthColor
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

internal val ChronoAgy = Color(0xFF38BDF8)
internal val ChronoOpenCode = Color(0xFFA78BFA)
internal val ChronoGrok = Color(0xFF5CE196)
internal val LedAgy = Color(0xFF22D3EE)

internal fun providerHue(providerId: String?): Color = when (providerId) {
    "agy" -> ChronoAgy
    "opencode", "opencode_go" -> ChronoOpenCode
    "grok" -> ChronoGrok
    else -> Color(0xFF94A3B8)
}

internal fun providerShortLabel(providerId: String?): String = when (providerId) {
    "agy" -> "AGY"
    "opencode", "opencode_go" -> "OpenCode"
    "grok" -> "Grok"
    else -> "All"
}

internal fun providerWedgeLabel(providerId: String?): String = when (providerId) {
    "agy" -> "AGY"
    "opencode", "opencode_go" -> "OPENCODE"
    "grok" -> "GROK"
    else -> "ALL PROVIDERS"
}

internal fun normalizeProviderId(providerId: String?): String? = when (providerId?.lowercase()) {
    null -> null
    "opencode_go", "opencode" -> "opencode"
    else -> providerId.lowercase()
}

internal fun providerForBucket(snapshot: QuotaSnapshot, bucketId: String?): String? {
    if (bucketId == null) return null
    val provider = snapshot.providers.firstOrNull { it.buckets.any { bucket -> bucket.id == bucketId } }
    return normalizeProviderId(provider?.id)
}

internal fun formatLocalReset(resetsAt: String?, nowEpochMs: Long): String {
    val reset = parseInstantOrNull(resetsAt) ?: return "—"
    val zone = ZoneId.systemDefault()
    val resetZoned = reset.atZone(zone)
    val nowZoned = Instant.ofEpochMilli(nowEpochMs).atZone(zone)
    val time = resetZoned.format(CLOCK_FORMAT)
    return if (resetZoned.toLocalDate() == nowZoned.toLocalDate()) {
        "Today $time"
    } else {
        "${resetZoned.format(WEEKDAY_FORMAT)} $time"
    }
}

internal fun parseInstantOrNull(iso: String?): Instant? = try {
    if (iso.isNullOrBlank()) null else Instant.parse(iso)
} catch (_: Exception) {
    null
}

private val CLOCK_FORMAT = DateTimeFormatter.ofPattern("h:mm a", Locale.US)
private val WEEKDAY_FORMAT = DateTimeFormatter.ofPattern("EEE", Locale.US)

private data class ChronoWedge(
    val groupId: String,
    val item: com.aiquotamonitor.domain.model.WidgetQuotaItem,
    val slotStart: Float,
    val slotSweep: Float
)

/**
 * Bank 3 — 360° segmented quota chronograph.
 *
 * Wedge order comes straight from [QuotaSnapshot.resolveWideProviderGroups], so
 * every pool keeps the same permanent address as the 4x3 Glance widget. Tapping
 * a wedge isolates it; tapping the centre returns to All Providers.
 */
@Composable
fun QuotaChronographCard(
    snapshot: QuotaSnapshot,
    polarity: QuotaPolarity,
    hiddenBucketIds: Set<String>,
    selectedBucketId: String?,
    selectedProviderId: String?,
    onSelectBucket: (String?) -> Unit,
    onSelectProvider: (String?) -> Unit,
    nowEpochMs: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier
) {
    val groups = snapshot.resolveWideProviderGroups(hiddenBucketIds)
    val totalItems = groups.sumOf { it.items.size }

    val wedges = buildList {
        if (totalItems > 0) {
            val slotSweep = 360f / totalItems
            var slot = 0
            groups.forEach { group ->
                group.items.forEach { item ->
                    add(
                        ChronoWedge(
                            groupId = normalizeProviderId(group.providerId) ?: group.providerId,
                            item = item,
                            slotStart = -90f + slot * slotSweep,
                            slotSweep = slotSweep
                        )
                    )
                    slot += 1
                }
            }
        }
    }

    val visibleBuckets = snapshot.visibleBuckets(hiddenBucketIds)
    val selectedBucket = selectedBucketId?.let { snapshot.findBucket(it) }
    val selectedProvider = selectedBucket?.let { providerForBucket(snapshot, it.id) }
        ?: normalizeProviderId(selectedProviderId)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(30.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(30.dp)
            )
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(CHRONO_SIZE)
                .pointerInput(wedges, selectedBucket?.id) {
                    detectTapGestures { tap ->
                        val center = Offset(size.width / 2f, size.height / 2f)
                        val dx = tap.x - center.x
                        val dy = tap.y - center.y
                        val radius = sqrt(dx * dx + dy * dy)
                        val outer = minOf(size.width, size.height) / 2f - 6.dp.toPx()
                        val inner = outer - CHRONO_BAND.toPx()

                        if (radius < inner * 0.72f) {
                            onSelectBucket(null)
                            onSelectProvider(null)
                            return@detectTapGestures
                        }
                        if (radius > outer + 16.dp.toPx() || radius < inner - 12.dp.toPx()) {
                            return@detectTapGestures
                        }

                        var degrees = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat() + 90f
                        if (degrees < 0f) degrees += 360f
                        if (degrees >= 360f) degrees -= 360f

                        val hit = wedges.firstOrNull { wedge ->
                            degrees >= (wedge.slotStart + 90f) &&
                                degrees < (wedge.slotStart + 90f + wedge.slotSweep)
                        }
                        if (hit != null) {
                            if (hit.item.id == selectedBucket?.id) {
                                onSelectBucket(null)
                                onSelectProvider(null)
                            } else {
                                onSelectBucket(hit.item.id)
                                onSelectProvider(null)
                            }
                        }
                    }
                },
            contentAlignment = Alignment.Center
        ) {
            val tickColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.22f)
            val trackBaseColor = MaterialTheme.colorScheme.onSurfaceVariant
            Canvas(modifier = Modifier.size(CHRONO_SIZE)) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val outerRadius = size.minDimension / 2f - 6.dp.toPx()
                val band = CHRONO_BAND.toPx()
                val trackRadius = outerRadius - band / 2f
                val arcTopLeft = Offset(center.x - trackRadius, center.y - trackRadius)
                val arcSize = Size(trackRadius * 2f, trackRadius * 2f)

                // 1. Outer three-provider halo arcs.
                var haloStart = -90f
                groups.forEach { group ->
                    val providerId = normalizeProviderId(group.providerId) ?: group.providerId
                    val sweep = if (totalItems > 0) {
                        360f * group.items.size / totalItems
                    } else {
                        0f
                    }
                    if (sweep > 0f) {
                        val haloGap = if (groups.size > 1) 8f else 2f
                        drawArc(
                            color = providerHue(providerId).copy(alpha = 0.85f),
                            startAngle = haloStart + haloGap / 2f,
                            sweepAngle = (sweep - haloGap).coerceAtLeast(1f),
                            useCenter = false,
                            topLeft = Offset(
                                center.x - (outerRadius + 4.dp.toPx()),
                                center.y - (outerRadius + 4.dp.toPx())
                            ),
                            size = Size(
                                (outerRadius + 4.dp.toPx()) * 2f,
                                (outerRadius + 4.dp.toPx()) * 2f
                            ),
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                        haloStart += sweep
                    }
                }

                // 2. Thirty-six subtle mechanical bezel ticks.
                val tickOuter = outerRadius - band - 3.dp.toPx()
                val tickInner = tickOuter - 5.dp.toPx()
                repeat(36) { index ->
                    val angle = Math.toRadians((-90f + index * 10f).toDouble())
                    drawLine(
                        color = tickColor,
                        start = Offset(
                            center.x + tickInner * cos(angle).toFloat(),
                            center.y + tickInner * sin(angle).toFloat()
                        ),
                        end = Offset(
                            center.x + tickOuter * cos(angle).toFloat(),
                            center.y + tickOuter * sin(angle).toFloat()
                        ),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 3. Permanent proportional pool wedges.
                wedges.forEach { wedge ->
                    val isSelected = wedge.item.id == selectedBucket?.id
                    val isProviderSelected = selectedBucket == null &&
                        selectedProvider != null &&
                        normalizeProviderId(wedge.groupId) == selectedProvider
                    val hasSelection = selectedBucket != null || selectedProvider != null
                    val dim = hasSelection && !isSelected && !isProviderSelected

                    val usableSweep = (wedge.slotSweep - WEDGE_GAP).coerceAtLeast(2f)
                    drawArc(
                        color = trackBaseColor.copy(
                            alpha = if (dim) 0.08f else 0.16f
                        ),
                        startAngle = wedge.slotStart + WEDGE_GAP / 2f,
                        sweepAngle = usableSweep,
                        useCenter = false,
                        topLeft = arcTopLeft,
                        size = arcSize,
                        style = Stroke(width = band, cap = StrokeCap.Round)
                    )

                    val percent = wedge.item.displayPercent(polarity)
                        .coerceIn(0.0, 100.0)
                    val sweep = usableSweep * (percent / 100.0).toFloat()
                    if (sweep > 0.2f) {
                        val color = widgetHealthColor(percent, polarity)
                        drawArc(
                            color = color.copy(
                                alpha = when {
                                    dim -> 0.28f
                                    isSelected -> 1f
                                    else -> 0.92f
                                }
                            ),
                            startAngle = wedge.slotStart + WEDGE_GAP / 2f,
                            sweepAngle = sweep,
                            useCenter = false,
                            topLeft = arcTopLeft,
                            size = arcSize,
                            style = Stroke(
                                width = if (isSelected) band + 4.dp.toPx() else band,
                                cap = StrokeCap.Round
                            )
                        )
                    }
                }
            }

            // Centre readout (never a fake metric).
            val providerScopedBuckets = if (selectedProvider != null) {
                visibleBuckets.filter { providerForBucket(snapshot, it.id) == selectedProvider }
            } else {
                visibleBuckets
            }
            when {
                selectedBucket != null -> SelectedReadout(
                    bucket = selectedBucket,
                    providerId = selectedProvider,
                    polarity = polarity,
                    nowEpochMs = nowEpochMs
                )
                selectedProvider != null && providerScopedBuckets.size == 1 -> SelectedReadout(
                    bucket = providerScopedBuckets.first(),
                    providerId = selectedProvider,
                    polarity = polarity,
                    nowEpochMs = nowEpochMs
                )
                else -> AllProvidersReadout(
                    headerLabel = providerWedgeLabel(selectedProvider),
                    headerColor = if (selectedProvider != null) {
                        providerHue(selectedProvider)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    visibleBuckets = providerScopedBuckets,
                    healthyCount = providerScopedBuckets.count { it.remainingPercent > 20.0 },
                    nowEpochMs = nowEpochMs
                )
            }
        }

        // Interactive provider legend: every provider and All is reachable by tap.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            LegendPill(
                label = "AGY",
                color = ChronoAgy,
                selected = selectedProvider == "agy",
                modifier = Modifier.weight(1f),
                onClick = { onSelectProvider(if (selectedProvider == "agy") null else "agy") }
            )
            LegendPill(
                label = "OpenCode",
                color = ChronoOpenCode,
                selected = selectedProvider == "opencode",
                modifier = Modifier.weight(1f),
                onClick = { onSelectProvider(if (selectedProvider == "opencode") null else "opencode") }
            )
            LegendPill(
                label = "Grok",
                color = ChronoGrok,
                selected = selectedProvider == "grok",
                modifier = Modifier.weight(1f),
                onClick = { onSelectProvider(if (selectedProvider == "grok") null else "grok") }
            )
            LegendPill(
                label = "All",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                selected = selectedProvider == null && selectedBucket == null,
                modifier = Modifier.weight(0.8f),
                onClick = {
                    onSelectBucket(null)
                    onSelectProvider(null)
                }
            )
        }
    }
}

@Composable
private fun AllProvidersReadout(
    headerLabel: String,
    headerColor: Color,
    visibleBuckets: List<QuotaBucket>,
    healthyCount: Int,
    nowEpochMs: Long
) {
    val nextReset = visibleBuckets
        .mapNotNull { bucket ->
            val reset = parseInstantOrNull(bucket.resetsAt) ?: return@mapNotNull null
            if (reset.toEpochMilli() <= nowEpochMs) return@mapNotNull null
            bucket to reset
        }
        .minByOrNull { it.second }

    Column(
        modifier = Modifier.widthIn(max = 160.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = headerLabel,
            style = MaterialTheme.typography.labelSmall,
            color = headerColor,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.8.sp,
            fontSize = 9.sp
        )
        Text(
            text = "$healthyCount/${visibleBuckets.size}",
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Pools Ready",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp
        )
        Text(
            text = nextReset?.let { (bucket, _) ->
                "Next reset: ${bucket.shortHeaderLabel()} ${bucket.formatResetCountdown(nowEpochMs)}"
            } ?: "Next reset: —",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun SelectedReadout(
    bucket: QuotaBucket,
    providerId: String?,
    polarity: QuotaPolarity,
    nowEpochMs: Long
) {
    val displayPercent = bucket.displayPercent(polarity)
    Column(
        modifier = Modifier.widthIn(max = 166.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "${providerWedgeLabel(providerId)} • ${bucket.shortWindowLabel().uppercase()}",
            style = MaterialTheme.typography.labelSmall,
            color = providerHue(providerId),
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.5.sp,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = bucket.label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = "${displayPercent.toInt()}%",
            style = MaterialTheme.typography.displaySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.ExtraBold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = "Resets in ${bucket.formatResetCountdown(nowEpochMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp
        )
        Text(
            text = formatLocalReset(bucket.resetsAt, nowEpochMs),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun LegendPill(
    label: String,
    color: Color,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .height(34.dp)
            .semantics { contentDescription = "$label provider filter" }
            .clip(RoundedCornerShape(12.dp))
            .background(
                if (selected) color.copy(alpha = 0.18f)
                else MaterialTheme.colorScheme.surfaceContainerHigh
            )
            .border(
                width = 1.dp,
                color = if (selected) color.copy(alpha = 0.8f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(12.dp)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(6.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) color else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            fontSize = 9.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

private val CHRONO_SIZE = 244.dp
private val CHRONO_BAND = 14.dp
private const val WEDGE_GAP = 11f
