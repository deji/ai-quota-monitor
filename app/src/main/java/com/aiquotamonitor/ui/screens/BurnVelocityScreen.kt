package com.aiquotamonitor.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.ui.components.BurnVelocityChart
import com.aiquotamonitor.ui.components.QuotaPolarityToggle
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald
import com.aiquotamonitor.ui.viewmodel.QuotaViewModel

@Composable
fun BurnVelocityScreen(
    viewModel: QuotaViewModel,
    modifier: Modifier = Modifier
) {
    val snapshot by viewModel.activeSnapshot.collectAsState()
    val polarity by viewModel.polarity.collectAsState()

    val allBuckets = snapshot.providers.flatMap { it.buckets }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Screen Header + Polarity Toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "BURN VELOCITY & PACE",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = "Kinetic Consumption HUD",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            QuotaPolarityToggle(
                polarity = polarity,
                onToggle = { viewModel.setPolarity(it) }
            )
        }

        // Multi-Stream Horizon Velocity Bezier Chart
        BurnVelocityChart(
            history = snapshot.history,
            polarity = polarity
        )

        // 2x2 Metric Squircles
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricSquircle(
                label = "PEAK VELOCITY",
                value = "1.25%/hr",
                subtext = "Gemini Flash Burst",
                color = QuotaCyan,
                modifier = Modifier.weight(1f)
            )
            MetricSquircle(
                label = "24H TOTAL BURN",
                value = "25.0%",
                subtext = "Argon Planning Pool",
                color = QuotaAmber,
                modifier = Modifier.weight(1f)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            MetricSquircle(
                label = "NARROW HORIZON",
                value = "3h 22m",
                subtext = "Argon Window Reset",
                color = QuotaCoral,
                modifier = Modifier.weight(1f)
            )
            MetricSquircle(
                label = "OAUTH LEASE",
                value = "88m",
                subtext = "Grok Smart Primed",
                color = QuotaEmerald,
                modifier = Modifier.weight(1f)
            )
        }

        // All-Buckets Linear Pace Breakdown
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "ALL-BUCKET PACE TELEMETRY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )

                Spacer(modifier = Modifier.height(12.dp))

                allBuckets.forEachIndexed { index, bucket ->
                    BucketPaceRow(bucket = bucket, polarity = polarity)
                    if (index < allBuckets.lastIndex) {
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                }
            }
        }

        // Orca Orchestration Stage Checkpoints
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.surfaceContainer)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "ORCA STAGE CHECKPOINTS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )

                Spacer(modifier = Modifier.height(10.dp))

                StageCheckpointRow(
                    stage = "PLANNER",
                    model = "Gemini 4 Argon High",
                    status = "Ready · -33.1% pace delta",
                    statusColor = QuotaEmerald
                )
                Spacer(modifier = Modifier.height(8.dp))
                StageCheckpointRow(
                    stage = "BUILDER",
                    model = "Gemini 3.8 Flash High + OpenCode",
                    status = "Ready · 99% 5h pool remaining",
                    statusColor = QuotaCyan
                )
                Spacer(modifier = Modifier.height(8.dp))
                StageCheckpointRow(
                    stage = "REVIEWER",
                    model = "Grok 4.7 Build (medium effort)",
                    status = "Smart Primed · 97% unified credits",
                    statusColor = QuotaEmerald
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
    }
}

@Composable
private fun MetricSquircle(
    label: String,
    value: String,
    subtext: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp,
                letterSpacing = 0.5.sp
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = color
            )
            Text(
                text = subtext,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun BucketPaceRow(
    bucket: QuotaBucket,
    polarity: com.aiquotamonitor.domain.model.QuotaPolarity
) {
    val displayPercent = bucket.displayPercent(polarity)
    val paceColor = if (bucket.paceDeltaPercent <= 0) QuotaEmerald else QuotaAmber

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = bucket.label,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )
            Text(
                text = "Elapsed: ${bucket.elapsedPercent}% · Burn: ${bucket.burnRatePerHour}%/h",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(paceColor.copy(alpha = 0.15f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text(
                    text = "${bucket.paceDeltaPercent}%",
                    style = MaterialTheme.typography.labelSmall,
                    color = paceColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp
                )
            }

            Text(
                text = "${displayPercent.toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun StageCheckpointRow(
    stage: String,
    model: String,
    status: String,
    statusColor: Color
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(statusColor)
            )
            Column {
                Text(
                    text = stage,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontSize = 11.sp
                )
                Text(
                    text = model,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }
        }

        Text(
            text = status,
            style = MaterialTheme.typography.labelSmall,
            color = statusColor,
            fontWeight = FontWeight.Medium,
            fontSize = 10.sp
        )
    }
}
