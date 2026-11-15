package com.aiquotamonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.ProviderWidgetGroup
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.WidgetQuotaItem
import com.aiquotamonitor.ui.theme.QuotaEmerald
import com.aiquotamonitor.widget.widgetHealthColor
import kotlin.math.roundToInt

private val SLATE = Color(0xFF94A3B8)
private val EMERALD = Color(0xFF5CE196)
private val BENTO_CARD = Color(0xFF1A252A)
private val BENTO_TRACK = Color(0xFF243338)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GlancePreviewCard(
    snapshot: QuotaSnapshot,
    modifier: Modifier = Modifier,
    hiddenBucketIds: Set<String> = emptySet(),
    pinnedBucketId: String? = null,
    polarity: QuotaPolarity = QuotaPolarity.REMAINING,
    compactWidgetBucketId: String? = null,
    wideWidgetBucketIds: List<String> = emptyList(),
    onSelectCompactBucket: (String?) -> Unit = {},
    onSetWideBuckets: (List<String>) -> Unit = {},
    onToggleWideBucket: (String) -> Unit = {}
) {
    val nowEpochMs = System.currentTimeMillis()
    val compactItems = snapshot.resolveCompactCriticalBuckets(
        hiddenBucketIds = hiddenBucketIds,
        pinnedBucketId = pinnedBucketId,
        compactBucketId = compactWidgetBucketId
    )
    val wideGroups = snapshot.resolveWideProviderGroups(hiddenBucketIds)
    val activePoolCount = wideGroups.sumOf { it.items.size }
    // The preview clock only changes once per recomposition, so the countdown renders
    // from the same instant as the resolved items.
    val polarityTag = if (polarity == QuotaPolarity.REMAINING) "REM" else "USED"

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "JETPACK GLANCE WIDGET PREVIEWS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(14.dp))

            // 1. 2x2 Compact Widget In-App Preview (Quad Micro-Bento Grid)
            Text(
                text = "Compact Widget (2x2 Quad Micro-Bento)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .size(width = 196.dp, height = 196.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF11161A))
                    .border(1.dp, Color(0xFF2E3940), RoundedCornerShape(20.dp))
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxSize()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "CRITICAL POOLS • $polarityTag",
                            style = MaterialTheme.typography.labelSmall,
                            color = SLATE,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(QuotaEmerald)
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    if (compactItems.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "--",
                                style = MaterialTheme.typography.displaySmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = SLATE,
                                fontSize = 32.sp
                            )
                            Text(
                                text = "All models hidden",
                                style = MaterialTheme.typography.bodySmall,
                                color = SLATE,
                                fontSize = 10.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CompactBentoPreviewCell(
                                item = compactItems.getOrNull(0),
                                polarity = polarity,
                                nowEpochMs = nowEpochMs,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            CompactBentoPreviewCell(
                                item = compactItems.getOrNull(1),
                                polarity = polarity,
                                nowEpochMs = nowEpochMs,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth().weight(1f),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CompactBentoPreviewCell(
                                item = compactItems.getOrNull(2),
                                polarity = polarity,
                                nowEpochMs = nowEpochMs,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                            CompactBentoPreviewCell(
                                item = compactItems.getOrNull(3),
                                polarity = polarity,
                                nowEpochMs = nowEpochMs,
                                modifier = Modifier.weight(1f).fillMaxWidth()
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                FilterChip(
                    selected = compactWidgetBucketId == null,
                    onClick = { onSelectCompactBucket(null) },
                    label = { Text("Auto (Fastest Draining)") },
                    colors = filterChipColors()
                )

                snapshot.orcaRoles.forEach { role ->
                    val token = "role:${role.role}"
                    FilterChip(
                        selected = compactWidgetBucketId.equals(token, ignoreCase = true),
                        onClick = { onSelectCompactBucket(token) },
                        label = { Text("Role: ${role.role.lowercase().replaceFirstChar { it.uppercase() }}") },
                        colors = filterChipColors()
                    )
                }

                snapshot.providers.forEach { provider ->
                    provider.buckets.forEach { bucket ->
                        FilterChip(
                            selected = compactWidgetBucketId == bucket.id,
                            onClick = { onSelectCompactBucket(bucket.id) },
                            label = { Text(compactChipLabel(provider, bucket)) },
                            colors = filterChipColors()
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 2. 4x3 Wide Provider Bento Deck In-App Preview
            Text(
                text = "Provider Quota Deck (4x3 Three-Column Bento)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF11161A))
                    .border(1.dp, Color(0xFF2E3940), RoundedCornerShape(22.dp))
                    .padding(10.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "PROVIDER QUOTA DECK • $polarityTag",
                            style = MaterialTheme.typography.labelSmall,
                            color = SLATE,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "$activePoolCount Active Pools",
                            style = MaterialTheme.typography.labelSmall,
                            color = QuotaEmerald,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    if (wideGroups.isEmpty()) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "--",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = SLATE
                            )
                            Text(
                                text = "All models hidden",
                                style = MaterialTheme.typography.bodySmall,
                                color = SLATE,
                                fontSize = 10.sp
                            )
                        }
                    } else {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            wideGroups.forEach { group ->
                                WideProviderColumnPreview(
                                    group = group,
                                    polarity = polarity,
                                    nowEpochMs = nowEpochMs,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "The Wide Provider Deck automatically displays every visible bucket " +
                    "across all providers. Configure which models appear with the " +
                    "dashboard's Model Filter.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 10.sp
            )
        }
    }
}

@Composable
private fun CompactBentoPreviewCell(
    item: WidgetQuotaItem?,
    polarity: QuotaPolarity,
    nowEpochMs: Long,
    modifier: Modifier = Modifier
) {
    val displayPercent = item?.displayPercent(polarity) ?: Double.NaN
    val isUnavailable = displayPercent.isNaN()
    val healthColor = widgetHealthColor(displayPercent, polarity)
    val countdown = item
        ?.formatResetCountdown(nowEpochMs = nowEpochMs, includeMinutesUnderDay = false)
        ?.ifBlank { "—" }
        ?: "—"

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(BENTO_CARD)
            .padding(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item?.shortHeaderLabel ?: "--",
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = SLATE,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(modifier = Modifier.width(2.dp))
            Text(
                text = countdown,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                color = healthColor,
                maxLines = 1
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = if (isUnavailable) "--%" else "${displayPercent.roundToInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = healthColor,
                maxLines = 1
            )
            Text(
                text = item?.shortWindowLabel.orEmpty(),
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                color = Color(0xFF64748B),
                maxLines = 1
            )
        }

        Spacer(modifier = Modifier.height(3.dp))

        LinearProgressIndicator(
            progress = {
                if (isUnavailable) 0f else (displayPercent / 100.0).toFloat().coerceIn(0f, 1f)
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = healthColor,
            trackColor = BENTO_TRACK
        )
    }
}

@Composable
private fun WideProviderColumnPreview(
    group: ProviderWidgetGroup,
    polarity: QuotaPolarity,
    nowEpochMs: Long,
    modifier: Modifier = Modifier
) {
    val titleColor = when (group.providerId.lowercase()) {
        "agy" -> Color(0xFF38BDF8)
        "opencode", "opencode_go" -> Color(0xFFA78BFA)
        "grok" -> EMERALD
        else -> SLATE
    }

    Column(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(BENTO_CARD)
            .padding(6.dp)
    ) {
        Text(
            text = group.title,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.sp,
            fontWeight = FontWeight.Bold,
            color = titleColor,
            maxLines = 1
        )

        group.items.forEach { item ->
            Spacer(modifier = Modifier.height(3.dp))
            WideBucketRowPreview(item = item, polarity = polarity, nowEpochMs = nowEpochMs)
        }

        val leaseLabel = group.oauthLeaseLabel
        if (leaseLabel != null) {
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OAuth Lease",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    color = Color(0xFF64748B),
                    maxLines = 1
                )
                Text(
                    text = leaseLabel,
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = EMERALD,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun WideBucketRowPreview(
    item: WidgetQuotaItem,
    polarity: QuotaPolarity,
    nowEpochMs: Long
) {
    val displayPercent = item.displayPercent(polarity)
    val isUnavailable = displayPercent.isNaN()
    val healthColor = widgetHealthColor(displayPercent, polarity)
    val pctText = if (isUnavailable) "--%" else "${displayPercent.roundToInt()}%"
    val resetText = item.formatResetCountdown(
        nowEpochMs = nowEpochMs,
        includeMinutesUnderDay = true
    )

    // Two-line micro-header: the label owns the full column width so provider
    // bucket names never collide with the reset countdown.
    Text(
        text = item.shortWideRowLabel,
        style = MaterialTheme.typography.labelSmall,
        fontSize = 8.5.sp,
        fontWeight = FontWeight.Medium,
        color = Color(0xFFE2E8F0),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis
    )

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = pctText,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 9.5.sp,
            fontWeight = FontWeight.Bold,
            color = healthColor,
            maxLines = 1
        )
        Text(
            text = resetText.ifBlank { "—" },
            style = MaterialTheme.typography.labelSmall,
            fontSize = 8.5.sp,
            fontWeight = FontWeight.Bold,
            color = healthColor,
            maxLines = 1,
            softWrap = false
        )
    }

    Spacer(modifier = Modifier.height(2.dp))

    LinearProgressIndicator(
        progress = {
            if (isUnavailable) 0f else (displayPercent / 100.0).toFloat().coerceIn(0f, 1f)
        },
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .clip(RoundedCornerShape(2.dp)),
        color = healthColor,
        trackColor = BENTO_TRACK
    )
}

@Composable
private fun filterChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
)

private fun compactChipLabel(provider: ProviderQuota, bucket: QuotaBucket): String {
    return when (provider.id.lowercase()) {
        "opencode", "opencode_go" -> "OpenCode ${bucket.label}"
        else -> bucket.label
    }
}
