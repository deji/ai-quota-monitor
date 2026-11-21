package com.aiquotamonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.HistoryPoint
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Factual bucket telemetry sheet: current quota, reset window, shared-pool
 * breakdown, and recorded history. Burn-rate projections and target-pace
 * fiction were deliberately removed in APP-UX8.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BucketDetailSheet(
    bucket: QuotaBucket,
    polarity: QuotaPolarity,
    history: List<HistoryPoint>,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
        val scrollState = rememberScrollState()

        // Pinned title row so drags on the header stay on the sheet surface.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 20.dp, top = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "QUOTA BUCKET TELEMETRY",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.sp
                    )
                    Text(
                        text = bucket.label,
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "Group: ${bucket.group} • Window: ${bucket.windowType.replaceFirstChar { it.uppercase() }} (${bucket.windowDurationHours}h)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        // Bounded scroll body beneath the pinned title (AQM-LINT-011).
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .sheetContentNestedScroll(sheetState, scrollState)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val displayPercent = bucket.displayPercent(polarity)
            val subtitle = if (polarity == QuotaPolarity.REMAINING) "Remaining" else "Consumed"
            Box(
                modifier = Modifier.fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                QuotaRadialArcGauge(
                    percent = displayPercent,
                    elapsedPercent = bucket.elapsedPercent,
                    subtitle = subtitle,
                    size = 170.dp,
                    strokeWidth = 14.dp
                )
            }

            // Factual current state and reset window.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "CURRENT QUOTA & RESET WINDOW",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp
                    )

                    TelemetryRow(
                        label = "Remaining:",
                        value = "${bucket.remainingPercent.toInt()}%",
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    TelemetryRow(
                        label = "Used:",
                        value = "${bucket.usedPercent.toInt()}%",
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    TelemetryRow(
                        label = "Resets In:",
                        value = bucket.formatResetCountdown().ifBlank { "N/A" },
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    TelemetryRow(
                        label = "Exact Reset (local):",
                        value = formatExactReset(bucket.resetsAt),
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )
                    TelemetryRow(
                        label = "Window Type:",
                        value = "${bucket.windowType.replaceFirstChar { it.uppercase() }} · ${bucket.windowDurationHours}h",
                        valueColor = MaterialTheme.colorScheme.onSurface
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Schedule,
                            contentDescription = null,
                            tint = QuotaCyan,
                            modifier = Modifier.size(15.dp)
                        )
                        Text(
                            text = if (bucket.remainingPercent <= 10.0) {
                                "Wall hit: this pool is nearly exhausted."
                            } else {
                                "Reset time is authoritative for this window."
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = if (bucket.remainingPercent <= 10.0) QuotaCoral else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp
                        )
                    }
                }
            }

            // Shared unified-pool breakdown (for example Grok Unified).
            if (bucket.productBreakdown.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(16.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = "SHARED POOL BREAKDOWN",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )

                        bucket.productBreakdown.forEach { product ->
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = product.label,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "${String.format(Locale.US, "%.1f", product.usagePercent)}%",
                                        style = MaterialTheme.typography.bodySmall.copy(
                                            fontFeatureSettings = "tnum"
                                        ),
                                        fontFamily = FontFamily.Monospace,
                                        fontWeight = FontWeight.Bold,
                                        color = if (product.usagePercent >= 50.0) QuotaAmber else QuotaCyan
                                    )
                                }

                                LinearProgressIndicator(
                                    progress = {
                                        (product.usagePercent / 100.0).toFloat().coerceIn(0f, 1f)
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(4.dp)
                                        .clip(RoundedCornerShape(2.dp)),
                                    color = if (product.usagePercent >= 50.0) QuotaAmber else QuotaCyan,
                                    trackColor = Color(0xFF263238).copy(alpha = 0.5f)
                                )
                            }
                        }
                    }
                }
            }

            // Recorded history only; never a synthetic pace claim.
            var sparklineRange by remember { mutableStateOf("24h") }
            val datedPoints = history.mapNotNull { point ->
                val remaining = point.bucketRemaining[bucket.id] ?: return@mapNotNull null
                val ts = parseInstantOrNull(point.capturedAt)
                ts to remaining
            }
            val latestTs = datedPoints.mapNotNull { it.first }.maxOrNull()
            val windowSeconds = if (sparklineRange == "24h") 24L * 3600L else 7L * 24L * 3600L
            val points = when {
                datedPoints.isEmpty() -> emptyList()
                latestTs != null -> {
                    val cutoff = latestTs.minusSeconds(windowSeconds)
                    datedPoints
                        .filter { (ts, _) -> ts != null && !ts.isBefore(cutoff) }
                        .map { it.second }
                        .ifEmpty { datedPoints.map { it.second }.takeLast(24) }
                }
                sparklineRange == "24h" -> datedPoints.map { it.second }.takeLast(24)
                else -> datedPoints.map { it.second }.takeLast(168)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(16.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "RECORDED HISTORY",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )

                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            listOf("24h", "7d").forEach { range ->
                                val selected = sparklineRange == range
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(
                                            if (selected) MaterialTheme.colorScheme.primaryContainer
                                            else MaterialTheme.colorScheme.surfaceContainer
                                        )
                                        .clickable { sparklineRange = range }
                                        .padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = range,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 9.sp
                                    )
                                }
                            }
                        }
                    }

                    if (points.isEmpty()) {
                        Text(
                            text = "No history yet — the collector records a reading on each sync.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp)
                        )
                    } else {
                        MiniSparkline(
                            points = points,
                            lineColor = if (displayPercent <= 20.0) QuotaCoral else QuotaCyan,
                            height = 64.dp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "Min: ${(points.minOrNull() ?: 0.0).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                            Text(
                                text = "Max: ${(points.maxOrNull() ?: 100.0).toInt()}%",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun TelemetryRow(
    label: String,
    value: String,
    valueColor: Color
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall.copy(fontFeatureSettings = "tnum"),
            fontWeight = FontWeight.Bold,
            color = valueColor,
            fontFamily = FontFamily.Monospace
        )
    }
}

private fun formatExactReset(resetsAt: String?): String {
    val instant = try {
        if (resetsAt.isNullOrBlank()) return "No scheduled reset"
        Instant.parse(resetsAt)
    } catch (_: Exception) {
        return "No scheduled reset"
    }
    return instant.atZone(ZoneId.systemDefault()).format(EXACT_RESET_FORMAT)
}

private val EXACT_RESET_FORMAT = DateTimeFormatter.ofPattern("EEE MMM d · h:mm a", Locale.US)
