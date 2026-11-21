package com.aiquotamonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald
import kotlin.math.roundToInt

/**
 * Fixed-height contextual strip between the Chronograph and the Activity Board.
 *
 * It deliberately never repeats the nine-pool grid: it shows either the tapped
 * wedge's single command card or the tightest pools when All is selected.
 */
@Composable
fun SelectedPoolStrip(
    snapshot: QuotaSnapshot,
    polarity: QuotaPolarity,
    hiddenBucketIds: Set<String>,
    selectedBucketId: String?,
    selectedProviderId: String?,
    pinnedBucketId: String?,
    onOpenDetails: (QuotaBucket) -> Unit,
    onSetPinnedBucket: (String?) -> Unit,
    onPrime: () -> Unit,
    isPriming: Boolean,
    nowEpochMs: Long = System.currentTimeMillis(),
    modifier: Modifier = Modifier
) {
    val visibleBuckets = snapshot.visibleBuckets(hiddenBucketIds)
    val providerId = normalizeProviderId(selectedProviderId)
        ?: providerForBucket(snapshot, selectedBucketId)
    val effectiveBucketId = selectedBucketId?.takeIf { it !in hiddenBucketIds }

    val selectedBucket: QuotaBucket? = when {
        effectiveBucketId != null -> snapshot.findBucket(effectiveBucketId)
        providerId != null -> visibleBuckets
            .filter { providerForBucket(snapshot, it.id) == providerId }
            .minByOrNull { it.remainingPercent }
        else -> null
    }

    val baseModifier = modifier
        .fillMaxWidth()
        .height(116.dp)
        .clip(RoundedCornerShape(22.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .border(
            width = 1.dp,
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
            shape = RoundedCornerShape(22.dp)
        )
        .padding(horizontal = 14.dp, vertical = 12.dp)

    if (selectedBucket == null) {
        val tightest = visibleBuckets.sortedBy { it.remainingPercent }.take(2)
        Column(
            modifier = baseModifier,
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "TIGHTEST POOLS",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.6.sp
                )
                PrimeButton(
                    label = "⚡ Grok · ${grokLeaseLabel(snapshot)}",
                    isPriming = isPriming,
                    onPrime = onPrime
                )
            }

            if (tightest.isEmpty()) {
                Text(
                    text = "All pools are hidden. Use the filter to bring a provider back.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                tightest.forEach { bucket ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .width(5.dp)
                                .height(20.dp)
                                .clip(RoundedCornerShape(3.dp))
                                .background(providerHue(providerForBucket(snapshot, bucket.id)))
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${providerShortLabel(providerForBucket(snapshot, bucket.id))} · ${bucket.label}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${bucket.displayPercent(polarity).roundToInt()}% ${polarityLabel(polarity)} · resets in ${bucket.formatResetCountdown(nowEpochMs)}",
                                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 10.sp,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
        return
    }

    if (providerId == "grok" && selectedBucket.productBreakdown.isNotEmpty()) {
        val displayPercent = selectedBucket.displayPercent(polarity).roundToInt()
        Column(
            modifier = baseModifier.clickable { onOpenDetails(selectedBucket) },
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Grok Weekly · $displayPercent% ${polarityLabel(polarity)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                PrimeButton(
                    label = "⚡ Prime · ${grokLeaseLabel(snapshot)}",
                    isPriming = isPriming,
                    onPrime = onPrime
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(9.dp)
                    .clip(RoundedCornerShape(5.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainer)
            ) {
                val colors = listOf(ChronoGrok, Color(0xFF3DDC97), QuotaCyan, QuotaAmber)
                selectedBucket.productBreakdown.forEachIndexed { index, product ->
                    val weight = product.usagePercent.toFloat().coerceAtLeast(0.5f)
                    Box(
                        modifier = Modifier
                            .weight(weight)
                            .height(9.dp)
                            .background(colors[index % colors.size])
                    )
                }
            }

            Text(
                text = selectedBucket.productBreakdown.joinToString(" · ") { product ->
                    "${product.label} ${product.usagePercent.roundToInt()}%"
                },
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "Unified credits · resets in ${selectedBucket.formatResetCountdown(nowEpochMs)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 9.sp
            )
        }
        return
    }

    val displayPercent = selectedBucket.displayPercent(polarity)
    val complement = 100.0 - displayPercent
    val isPinned = selectedBucket.id == pinnedBucketId

    Column(
        modifier = baseModifier.clickable { onOpenDetails(selectedBucket) },
        verticalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${providerWedgeLabel(providerId)} • ${selectedBucket.shortWindowLabel().uppercase()}",
                style = MaterialTheme.typography.labelSmall,
                color = providerHue(providerId),
                fontWeight = FontWeight.Bold,
                fontSize = 9.sp,
                letterSpacing = 0.6.sp
            )
            PinButton(
                isPinned = isPinned,
                onClick = { onSetPinnedBucket(if (isPinned) null else selectedBucket.id) }
            )
        }

        Text(
            text = selectedBucket.label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "${displayPercent.roundToInt()}%",
                style = MaterialTheme.typography.headlineSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurface,
                fontWeight = FontWeight.ExtraBold
            )
            Text(
                text = "${polarityLabel(polarity)} · ${complement.roundToInt()}% ${polarityLabel(
                    if (polarity == QuotaPolarity.REMAINING) QuotaPolarity.USED else QuotaPolarity.REMAINING
                )}",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 3.dp)
            )
        }

        Text(
            text = "${siblingContext(selectedBucket)} · resets in ${selectedBucket.formatResetCountdown(nowEpochMs)}",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun PrimeButton(
    label: String,
    isPriming: Boolean,
    onPrime: () -> Unit
) {
    val isDark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val accent = if (isDark) QuotaEmerald else Color(0xFF0F7648)
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(accent.copy(alpha = if (isPriming) 0.08f else 0.14f))
            .border(
                width = 1.dp,
                color = accent.copy(alpha = 0.5f),
                shape = RoundedCornerShape(10.dp)
            )
            .clickable(enabled = !isPriming, onClick = onPrime)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = if (isPriming) "⚡ Priming…" else label,
            style = MaterialTheme.typography.labelSmall,
            color = accent,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun PinButton(
    isPinned: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (isPinned) MaterialTheme.colorScheme.primaryContainer
                else MaterialTheme.colorScheme.surfaceContainer
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(
            text = if (isPinned) "★ Pinned" else "★ Pin",
            style = MaterialTheme.typography.labelSmall,
            color = if (isPinned) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp,
            maxLines = 1
        )
    }
}

private fun polarityLabel(polarity: QuotaPolarity): String = when (polarity) {
    QuotaPolarity.REMAINING -> "left"
    QuotaPolarity.USED -> "used"
}

private fun grokLeaseLabel(snapshot: QuotaSnapshot): String {
    val remaining = snapshot.smartPrime.grokTokenRemainingSeconds ?: 0L
    return when {
        remaining <= 0L && snapshot.smartPrime.primed -> "PRIMED"
        remaining <= 0L -> "EXPIRED"
        else -> "${(remaining / 60L).coerceAtLeast(1L)}m TTL"
    }
}

private fun siblingContext(bucket: QuotaBucket): String = when (bucket.id) {
    "gemini-argon-wtu-weekly" -> "Planner · Gemini 4 Argon"
    "gemini-5h", "gemini-weekly" -> "Gemini Pool (Neon·Hyd·3.8)"
    "3p-5h", "3p-weekly" -> "Standby · Claude & GPT 3P"
    "opencode-rolling", "opencode-weekly", "opencode-monthly" -> "Builder · OpenCode Go"
    "grok-weekly-credits" -> "Reviewer · Unified credits"
    else -> bucket.group
}
