package com.aiquotamonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaEmerald
import java.time.Duration
import java.time.Instant

@Composable
fun HeroBottleneckCard(
    bucket: QuotaBucket,
    polarity: QuotaPolarity,
    historyPoints: List<Double>,
    modifier: Modifier = Modifier,
    isPinned: Boolean = false,
    isScrubbing: Boolean = false,
    onClick: (() -> Unit)? = null
) {
    val displayPercent = bucket.displayPercent(polarity)
    val subtitle = if (polarity == QuotaPolarity.REMAINING) "Remaining" else "Consumed"

    // Format remaining time to reset
    val resetCountdown = bucket.resetsAt?.let { at ->
        try {
            val resetInstant = Instant.parse(at)
            val now = Instant.now()
            val duration = Duration.between(now, resetInstant)
            if (duration.isNegative) "Resetting now"
            else {
                val hours = duration.toHours()
                val minutes = duration.toMinutes() % 60
                "${hours}h ${minutes}m"
            }
        } catch (_: Exception) {
            null
        }
    } ?: "3h 22m"

    val paceColor = if (bucket.paceDeltaPercent <= 0) QuotaEmerald else QuotaAmber
    val paceText = if (bucket.paceDeltaPercent <= 0) {
        "Ahead of Pace (${bucket.paceDeltaPercent}%)"
    } else {
        "Behind Pace (+${bucket.paceDeltaPercent}%)"
    }

    val cardModifier = if (onClick != null) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .clickable { onClick() }
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
            .padding(20.dp)
    } else {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(28.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(28.dp))
            .padding(20.dp)
    }

    Box(
        modifier = cardModifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Top Row: Bottleneck Spotlight label & Pace Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        if (isPinned) {
                            Icon(
                                imageVector = Icons.Default.PushPin,
                                contentDescription = "Pinned",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                        Text(
                            text = if (isPinned) "PINNED SPOTLIGHT" else "PRIMARY BOTTLENECK",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp,
                            fontSize = 10.sp
                        )
                    }
                    Text(
                        text = bucket.label,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                // Pace Pill
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(paceColor.copy(alpha = 0.15f))
                        .padding(horizontal = 9.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = paceText,
                        style = MaterialTheme.typography.labelSmall,
                        color = paceColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Center: Radial Arc Gauge with Pace Notch
            QuotaRadialArcGauge(
                percent = displayPercent,
                elapsedPercent = bucket.elapsedPercent,
                subtitle = subtitle,
                size = 180.dp,
                strokeWidth = 14.dp,
                isScrubbing = isScrubbing
            )

            Spacer(modifier = Modifier.height(10.dp))

            // Mini 24h Burn Sparkline
            if (historyPoints.isNotEmpty()) {
                MiniSparkline(
                    points = historyPoints,
                    lineColor = if (displayPercent <= 20.0) QuotaCoral else MaterialTheme.colorScheme.primary,
                    height = 32.dp
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Bottom 3-Column Telemetry Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TelemetryItem(
                    label = "RESETS IN",
                    value = resetCountdown
                )

                TelemetryItem(
                    label = "BURN RATE",
                    value = "${bucket.burnRatePerHour}%/hr"
                )

                TelemetryItem(
                    label = "PROJECTED AT RESET",
                    value = "${bucket.projectedRemainingAtReset.toInt()}%"
                )
            }
        }
    }
}

@Composable
private fun TelemetryItem(
    label: String,
    value: String
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 9.sp,
            letterSpacing = 0.5.sp
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.Bold,
            fontSize = 12.sp
        )
    }
}
