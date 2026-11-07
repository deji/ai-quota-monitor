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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.WidgetQuotaItem
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald

private val ORCA_CORE_TRIO_IDS = listOf(
    "gemini-argon-wtu-weekly",
    "opencode-rolling",
    "grok-weekly-credits"
)

private val SLATE = Color(0xFF94A3B8)
private val AMBER = Color(0xFFFFB951)
private val EMERALD = Color(0xFF5CE196)
private val CORAL = Color(0xFFFFB4AB)

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
    val compactItem = snapshot.resolveCompactWidgetItem(
        compactBucketId = compactWidgetBucketId,
        hiddenBucketIds = hiddenBucketIds,
        pinnedBucketId = pinnedBucketId
    )
    val wideItems = snapshot.resolveWideWidgetItems(
        wideBucketIds = wideWidgetBucketIds,
        hiddenBucketIds = hiddenBucketIds
    )
    val polaritySuffix = if (polarity == QuotaPolarity.REMAINING) "Left" else "Used"
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

            // 1. 2x2 Compact Widget In-App Preview
            Text(
                text = "Compact Widget (2x2 Launcher Tile)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .size(width = 160.dp, height = 150.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xFF141A1E))
                    .border(1.dp, Color(0xFF2E3940), RoundedCornerShape(20.dp))
                    .padding(14.dp)
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "AI QUOTA • $polarityTag",
                            style = MaterialTheme.typography.labelSmall,
                            color = QuotaCyan,
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

                    if (compactItem == null) {
                        Column {
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
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "Tap to configure",
                                style = MaterialTheme.typography.labelSmall,
                                color = QuotaCyan,
                                fontSize = 8.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else {
                        CompactPreviewBody(
                            item = compactItem,
                            polarity = polarity,
                            polaritySuffix = polaritySuffix
                        )
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
                    label = { Text("Auto (Hero Spotlight)") },
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

            // 2. 4x2 Wide Orca Stage Deck In-App Preview
            Text(
                text = "Orca Stage Deck (4x2 Responsive Span)",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 11.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(22.dp))
                    .background(Color(0xFF141A1E))
                    .border(1.dp, Color(0xFF2E3940), RoundedCornerShape(22.dp))
                    .padding(14.dp)
            ) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "ORCA STAGE DECK • $polarityTag",
                            style = MaterialTheme.typography.labelSmall,
                            color = QuotaCyan,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.5.sp
                        )
                        Text(
                            text = "Pixel 8 Pro Live",
                            style = MaterialTheme.typography.labelSmall,
                            color = SLATE,
                            fontSize = 9.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        wideItems.forEach { item ->
                            WidePreviewCell(
                                item = item,
                                polarity = polarity,
                                modifier = Modifier.weight(1f)
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
                    selected = wideWidgetBucketIds.isEmpty(),
                    onClick = { onSetWideBuckets(emptyList()) },
                    label = { Text("Orca Roles (Default)") },
                    colors = filterChipColors()
                )
                FilterChip(
                    selected = wideWidgetBucketIds == ORCA_CORE_TRIO_IDS,
                    onClick = { onSetWideBuckets(ORCA_CORE_TRIO_IDS) },
                    label = { Text("Orca Core Trio") },
                    colors = filterChipColors()
                )

                snapshot.orcaRoles.forEach { role ->
                    val token = "role:${role.role}"
                    val slot = wideWidgetBucketIds.indexOf(token)
                    val base = "Role: ${role.role.lowercase().replaceFirstChar { it.uppercase() }}"
                    FilterChip(
                        selected = slot >= 0,
                        onClick = { onToggleWideBucket(token) },
                        label = { Text(if (slot >= 0) "$base #${slot + 1}" else base) },
                        colors = filterChipColors()
                    )
                }

                snapshot.providers.forEach { provider ->
                    provider.buckets.forEach { bucket ->
                        val slot = wideWidgetBucketIds.indexOf(bucket.id)
                        val base = compactChipLabel(provider, bucket)
                        FilterChip(
                            selected = slot >= 0,
                            onClick = { onToggleWideBucket(bucket.id) },
                            label = { Text(if (slot >= 0) "$base #${slot + 1}" else base) },
                            colors = filterChipColors()
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactPreviewBody(
    item: WidgetQuotaItem,
    polarity: QuotaPolarity,
    polaritySuffix: String
) {
    val displayPercent = item.displayPercent(polarity)
    val isUnavailable = displayPercent.isNaN()

    Column {
        if (item.isFallback && !isUnavailable) {
            Text(
                text = "FALLBACK • ${item.categoryLabel}",
                style = MaterialTheme.typography.labelSmall,
                color = AMBER,
                fontWeight = FontWeight.Bold,
                fontSize = 8.sp
            )
        }
        Text(
            text = if (isUnavailable) "--" else "${displayPercent.toInt()}%",
            style = MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.ExtraBold,
            color = if (isUnavailable) SLATE else Color(0xFFE2E8F0),
            fontSize = 32.sp
        )
        Text(
            text = "${item.titleLabel} $polaritySuffix",
            style = MaterialTheme.typography.bodySmall,
            color = SLATE,
            fontSize = 10.sp,
            maxLines = 1
        )
    }

    if (!isUnavailable) {
        LinearProgressIndicator(
            progress = { (displayPercent / 100f).toFloat().coerceIn(0f, 1f) },
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp)),
            color = QuotaCyan,
            trackColor = Color(0xFF263238)
        )
    }
}

@Composable
private fun WidePreviewCell(
    item: WidgetQuotaItem,
    polarity: QuotaPolarity,
    modifier: Modifier = Modifier
) {
    val displayPercent = item.displayPercent(polarity)
    val isUnavailable = displayPercent.isNaN()
    val percentColor = when {
        isUnavailable -> SLATE
        displayPercent <= 20.0 -> CORAL
        displayPercent <= 40.0 -> AMBER
        else -> EMERALD
    }
    val showFallbackCue = item.isFallback && !isUnavailable
    val headerColor = if (showFallbackCue) AMBER else SLATE
    val header = if (showFallbackCue) "FALLBACK • ${item.categoryLabel}" else item.categoryLabel
    val pace = item.paceDeltaPercent

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF1B2329))
            .padding(8.dp)
    ) {
        Column {
            Text(
                text = header,
                style = MaterialTheme.typography.labelSmall,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold,
                color = headerColor,
                maxLines = 1
            )
            Text(
                text = if (isUnavailable) "--" else "${displayPercent.toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = percentColor
            )
            Text(
                text = item.titleLabel,
                style = MaterialTheme.typography.bodySmall,
                fontSize = 9.sp,
                color = Color(0xFFE2E8F0),
                maxLines = 1
            )
            if (!isUnavailable && pace != 0.0) {
                Text(
                    text = if (pace <= 0) "Ahead: ${pace}%" else "Burn: +${pace}%",
                    style = MaterialTheme.typography.labelSmall,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (pace <= 0) EMERALD else AMBER,
                    maxLines = 1
                )
            }
        }
    }
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
