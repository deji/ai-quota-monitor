package com.aiquotamonitor.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald

@Composable
fun ProviderBentoRow(
    openCodeProvider: ProviderQuota?,
    grokProvider: ProviderQuota?,
    smartPrime: SmartPrimeStatus,
    polarity: QuotaPolarity,
    onPrimePulse: () -> Unit,
    isPriming: Boolean,
    modifier: Modifier = Modifier,
    hiddenBucketIds: Set<String> = emptySet(),
    isScrubbing: Boolean = false,
    onBucketClick: ((QuotaBucket) -> Unit)? = null
) {
    val visibleOpenCodeBuckets = openCodeProvider?.buckets?.filterNot { it.id in hiddenBucketIds } ?: emptyList()
    val visibleGrokBuckets = grokProvider?.buckets?.filterNot { it.id in hiddenBucketIds } ?: emptyList()

    val showOpenCode = visibleOpenCodeBuckets.isNotEmpty()
    val showGrok = visibleGrokBuckets.isNotEmpty()

    if (!showOpenCode && !showGrok) {
        return
    }

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        if (showOpenCode) {
            OpenCodeBentoCard(
                provider = openCodeProvider,
                visibleBuckets = visibleOpenCodeBuckets,
                polarity = polarity,
                isScrubbing = isScrubbing,
                onBucketClick = onBucketClick,
                modifier = if (showGrok) Modifier.weight(1f) else Modifier.fillMaxWidth()
            )
        }

        if (showGrok) {
            GrokBentoCard(
                provider = grokProvider,
                visibleBuckets = visibleGrokBuckets,
                smartPrime = smartPrime,
                polarity = polarity,
                onPrimePulse = onPrimePulse,
                isPriming = isPriming,
                isScrubbing = isScrubbing,
                onBucketClick = onBucketClick,
                modifier = if (showOpenCode) Modifier.weight(1f) else Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun OpenCodeBentoCard(
    provider: ProviderQuota?,
    visibleBuckets: List<QuotaBucket>,
    polarity: QuotaPolarity,
    isScrubbing: Boolean,
    onBucketClick: ((QuotaBucket) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val primaryBucket = visibleBuckets.firstOrNull { it.id == "opencode-weekly" }
        ?: visibleBuckets.firstOrNull()
    val secondaryBucket = visibleBuckets.firstOrNull { it.id != primaryBucket?.id }

    val displayPercent = primaryBucket?.displayPercent(polarity) ?: 98.0

    val boxModifier = if (onBucketClick != null && primaryBucket != null) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable { onBucketClick(primaryBucket) }
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    } else {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    }

    Box(
        modifier = boxModifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header
            Text(
                text = "OPENCODE GO",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "DeepSeek 4.1 Flash",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1
            )

            Spacer(modifier = Modifier.height(12.dp))

            // Radial Ring with Percentage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                contentAlignment = Alignment.Center
            ) {
                SmallRadialRing(
                    percent = displayPercent,
                    color = QuotaEmerald,
                    size = 80.dp,
                    strokeWidth = 9.dp,
                    isScrubbing = isScrubbing
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${displayPercent.toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    val ringLabel = when (primaryBucket?.id) {
                        "opencode-weekly" -> "WEEKLY"
                        "opencode-rolling" -> "5H ROLLING"
                        "opencode-monthly" -> "MONTHLY"
                        else -> primaryBucket?.label?.uppercase() ?: "QUOTA"
                    }
                    Text(
                        text = ringLabel,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 8.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            // Secondary visible metric pill if available
            if (secondaryBucket != null) {
                Spacer(modifier = Modifier.height(10.dp))

                val pillModifier = if (onBucketClick != null) {
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable { onBucketClick(secondaryBucket) }
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                } else {
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .padding(horizontal = 8.dp, vertical = 6.dp)
                }

                Row(
                    modifier = pillModifier,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = secondaryBucket.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "${secondaryBucket.displayPercent(polarity).toInt()}%",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = QuotaEmerald,
                        fontSize = 10.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun GrokBentoCard(
    provider: ProviderQuota?,
    visibleBuckets: List<QuotaBucket>,
    smartPrime: SmartPrimeStatus,
    polarity: QuotaPolarity,
    onPrimePulse: () -> Unit,
    isPriming: Boolean,
    isScrubbing: Boolean,
    onBucketClick: ((QuotaBucket) -> Unit)?,
    modifier: Modifier = Modifier
) {
    val creditBucket = visibleBuckets.firstOrNull()
    val displayPercent = creditBucket?.displayPercent(polarity) ?: 97.0

    // Grok token lease scaling: dynamically normalize against max lease so leaseSec > 5400 doesn't distort/overflow
    val leaseSec = smartPrime.grokTokenRemainingSeconds ?: 5400L
    val maxLeaseSec = maxOf(5400f, leaseSec.toFloat())
    val leaseFraction = if (maxLeaseSec > 0f) (leaseSec.toFloat() / maxLeaseSec).coerceIn(0f, 1f) else 1f
    val leaseMinutes = (leaseSec / 60).coerceAtLeast(0)

    val boxModifier = if (onBucketClick != null && creditBucket != null) {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .clickable { onBucketClick(creditBucket) }
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    } else {
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    }

    Box(
        modifier = boxModifier
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "GROK BUILD",
                        style = MaterialTheme.typography.labelSmall,
                        color = QuotaCyan,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                    Text(
                        text = "Grok 4.7 Credits",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1
                    )
                }

                // Manual Prime Pulse Button
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable(enabled = !isPriming) { onPrimePulse() },
                    contentAlignment = Alignment.Center
                ) {
                    if (isPriming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = "Prime Pulse",
                            tint = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Radial Ring with Percentage
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(90.dp),
                contentAlignment = Alignment.Center
            ) {
                SmallRadialRing(
                    percent = displayPercent,
                    color = QuotaCyan,
                    size = 80.dp,
                    strokeWidth = 9.dp,
                    isScrubbing = isScrubbing
                )
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${displayPercent.toInt()}%",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Text(
                        text = "CREDITS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 8.sp,
                        letterSpacing = 0.5.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // 90-Minute OAuth Lease Bar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "OAuth Lease",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                    Text(
                        text = "${leaseMinutes}m",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = if (leaseMinutes < 15) QuotaAmber else QuotaCyan,
                        fontSize = 9.sp
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                LinearProgressIndicator(
                    progress = { leaseFraction },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp)),
                    color = if (leaseMinutes < 15) QuotaAmber else QuotaCyan,
                    trackColor = Color(0xFF263238).copy(alpha = 0.5f),
                )
            }
        }
    }
}

@Composable
private fun SmallRadialRing(
    percent: Double,
    color: Color,
    size: Dp = 80.dp,
    strokeWidth: Dp = 8.dp,
    isScrubbing: Boolean = false
) {
    val animSpec = if (isScrubbing) snap<Float>() else spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )

    val animatedPercent by animateFloatAsState(
        targetValue = percent.toFloat().coerceIn(0f, 100f),
        animationSpec = animSpec,
        label = "small_ring_anim"
    )

    Canvas(modifier = Modifier.size(size)) {
        val strokePx = strokeWidth.toPx()
        val arcSize = androidx.compose.ui.geometry.Size(
            size.toPx() - strokePx,
            size.toPx() - strokePx
        )
        val topLeft = androidx.compose.ui.geometry.Offset(strokePx / 2f, strokePx / 2f)

        // Background track
        drawArc(
            color = Color(0xFF263238).copy(alpha = 0.4f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )

        // Progress
        val sweep = (animatedPercent / 100f) * 360f
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = sweep,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = strokePx, cap = StrokeCap.Round)
        )
    }
}
