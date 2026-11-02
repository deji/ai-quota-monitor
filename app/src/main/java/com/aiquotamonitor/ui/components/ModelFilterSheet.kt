package com.aiquotamonitor.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald

val ORCA_CORE_VISIBLE_IDS: Set<String> = setOf(
    "gemini-argon-wtu-weekly",
    "gemini-5h",
    "opencode-rolling",
    "grok-weekly-credits"
)

val ORCA_CORE_HIDDEN_BUCKET_IDS: Set<String> = setOf(
    "3p-5h",
    "3p-weekly",
    "gemini-weekly",
    "opencode-weekly",
    "opencode-monthly"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ModelFilterSheet(
    snapshot: QuotaSnapshot,
    hiddenBucketIds: Set<String>,
    pinnedBucketId: String?,
    polarity: QuotaPolarity,
    onToggleVisibility: (String) -> Unit,
    onSetHiddenBuckets: (Set<String>) -> Unit,
    onSetPinnedBucket: (String?) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    BackHandler { onDismiss() }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = modifier
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Header: Title and Close button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "MODEL & QUOTA FILTERS",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 11.sp,
                        letterSpacing = 1.sp
                    )
                    Text(
                        text = "Customize Visible Models",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Dismiss",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = "Hide secondary model quotas from your Dashboard and 24h Burn Velocity chart. Tap the pin to lock any active model into the Hero Spotlight gauge.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                lineHeight = 16.sp
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Quick Presets
            Text(
                text = "QUICK PRESETS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                val allBucketIds = snapshot.providers.flatMap { it.buckets }.map { it.id }.toSet()
                val orcaCoreHidden = allBucketIds.filterNot { it in ORCA_CORE_VISIBLE_IDS }.toSet()

                val isHideClaudeActive = hiddenBucketIds == PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS
                val isOrcaCoreActive = hiddenBucketIds.isNotEmpty() && hiddenBucketIds == orcaCoreHidden
                val isShowAllActive = hiddenBucketIds.isEmpty()

                FilterChip(
                    selected = isHideClaudeActive,
                    onClick = { onSetHiddenBuckets(PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS) },
                    label = { Text("Hide Claude / 3P") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                FilterChip(
                    selected = isOrcaCoreActive,
                    onClick = { onSetHiddenBuckets(orcaCoreHidden) },
                    label = { Text("Orca Core Only") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )

                FilterChip(
                    selected = isShowAllActive,
                    onClick = { onSetHiddenBuckets(emptySet()) },
                    label = { Text("Show All") },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                        selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Grouped Model Quotas
            snapshot.providers.forEach { provider ->
                ProviderFilterSection(
                    provider = provider,
                    hiddenBucketIds = hiddenBucketIds,
                    pinnedBucketId = pinnedBucketId,
                    polarity = polarity,
                    onToggleVisibility = onToggleVisibility,
                    onTogglePin = { bucketId ->
                        if (pinnedBucketId == bucketId) {
                            onSetPinnedBucket(null)
                        } else {
                            if (bucketId in hiddenBucketIds) {
                                onToggleVisibility(bucketId)
                            }
                            onSetPinnedBucket(bucketId)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(14.dp))
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun ProviderFilterSection(
    provider: ProviderQuota,
    hiddenBucketIds: Set<String>,
    pinnedBucketId: String?,
    polarity: QuotaPolarity,
    onToggleVisibility: (String) -> Unit,
    onTogglePin: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = provider.displayName.uppercase(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )
                Text(
                    text = provider.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 11.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        provider.buckets.forEachIndexed { index, bucket ->
            val isHidden = bucket.id in hiddenBucketIds
            val isPinned = pinnedBucketId == bucket.id
            val displayPercent = bucket.displayPercent(polarity)
            val paceColor = if (bucket.paceDeltaPercent <= 0) QuotaEmerald else QuotaAmber

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(
                        if (isPinned) MaterialTheme.colorScheme.primary.copy(alpha = 0.08f)
                        else Color.Transparent
                    )
                    .padding(vertical = 6.dp, horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Pin spotlight button
                IconButton(
                    onClick = { onTogglePin(bucket.id) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = if (isPinned) Icons.Default.PushPin else Icons.Outlined.PushPin,
                        contentDescription = if (isPinned) "Unpin spotlight" else "Pin as Hero spotlight",
                        tint = if (isPinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        modifier = Modifier.size(18.dp)
                    )
                }

                Spacer(modifier = Modifier.width(4.dp))

                // Label and metadata
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = bucket.label,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isHidden) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f)
                            else MaterialTheme.colorScheme.onSurface
                        )
                        if (isPinned) {
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                                    .padding(horizontal = 5.dp, vertical = 1.dp)
                            ) {
                                Text(
                                    text = "HERO",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "${bucket.group} • ${bucket.windowType}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (isHidden) 0.5f else 1f),
                            fontSize = 10.sp
                        )
                        Text(
                            text = "• ${displayPercent.toInt()}%",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else paceColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }
                }

                // Visibility toggle button
                IconButton(
                    onClick = { onToggleVisibility(bucket.id) },
                    modifier = Modifier.size(36.dp)
                ) {
                    val tintColor by animateColorAsState(
                        targetValue = if (isHidden) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
                        else MaterialTheme.colorScheme.primary,
                        label = "VisibilityTint"
                    )
                    Icon(
                        imageVector = if (isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        contentDescription = if (isHidden) "Show bucket" else "Hide bucket",
                        tint = tintColor,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            if (index < provider.buckets.lastIndex) {
                Spacer(modifier = Modifier.height(4.dp))
            }
        }
    }
}
