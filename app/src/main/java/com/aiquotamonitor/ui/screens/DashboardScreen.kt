package com.aiquotamonitor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.ui.components.ActivityBoardCard
import com.aiquotamonitor.ui.components.BucketDetailSheet
import com.aiquotamonitor.ui.components.ConnectCollectorCard
import com.aiquotamonitor.ui.components.ModelFilterSheet
import com.aiquotamonitor.ui.components.QuotaChronographCard
import com.aiquotamonitor.ui.components.QuotaPolarityToggle
import com.aiquotamonitor.ui.components.SelectedPoolStrip
import com.aiquotamonitor.ui.components.SyncHubSheet
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald
import com.aiquotamonitor.ui.viewmodel.QuotaViewModel
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    viewModel: QuotaViewModel,
    modifier: Modifier = Modifier
) {
    val snapshot by viewModel.activeSnapshot.collectAsState()
    val polarity by viewModel.polarity.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isPriming by viewModel.isPriming.collectAsState()
    val isSyncError by viewModel.isSyncError.collectAsState()
    val snapshotIsStale by viewModel.isStale.collectAsState()
    val isAutoSyncing by viewModel.isAutoSyncing.collectAsState()
    val hiddenBucketIds by viewModel.hiddenBucketIds.collectAsState()
    val pinnedBucketId by viewModel.pinnedBucketId.collectAsState()

    // Lifecycle-aware 30s clock so countdowns and ages advance between polls.
    val lifecycleOwner = LocalLifecycleOwner.current
    val nowEpochMs by produceState(
        initialValue = System.currentTimeMillis(),
        key1 = lifecycleOwner
    ) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                value = System.currentTimeMillis()
                delay(30_000L)
            }
        }
    }
    val ageLabel = snapshot.formatAgeShort(nowEpochMs)
    val ageSuffix = if (ageLabel.isNotEmpty()) " · $ageLabel" else ""
    val isStale = snapshotIsStale || (snapshot.ageSeconds(nowEpochMs) ?: 0L) > 7200L
    val isRefreshing = connectionState == ConnectionState.SYNCING || isAutoSyncing

    var selectedBucket by remember { mutableStateOf<QuotaBucket?>(null) }
    var showSyncHub by remember { mutableStateOf(false) }
    var showFilterSheet by remember { mutableStateOf(false) }

    // Chronograph selection: a bucket wedge wins over a provider legend filter.
    var selectedBucketId by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedProviderId by rememberSaveable { mutableStateOf<String?>(null) }

    val effectiveBucketId = selectedBucketId?.takeIf { it !in hiddenBucketIds }
    val effectiveProviderId = selectedProviderId

    // Intercept back gesture to close modal bottom sheets in topmost z-order
    if (selectedBucket != null) {
        BackHandler { selectedBucket = null }
    } else if (showSyncHub) {
        BackHandler { showSyncHub = false }
    } else if (showFilterSheet) {
        BackHandler { showFilterSheet = false }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Fixed-width status pill: the leading slot swaps dot -> spinner in place,
        // so the label never reflows horizontally during a refresh.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val dotColor = when {
                isSyncError -> QuotaCoral
                isStale -> QuotaAmber
                connectionState == ConnectionState.TAILSCALE_LIVE -> QuotaEmerald
                connectionState == ConnectionState.CLOUD_RELAY_SYNCED -> QuotaCyan
                connectionState == ConnectionState.DEMO_SEED -> QuotaAmber
                connectionState == ConnectionState.SYNCING -> MaterialTheme.colorScheme.primary
                else -> Color(0xFF94A3B8)
            }

            val pillBorderColor = when {
                isSyncError -> QuotaCoral.copy(alpha = 0.6f)
                isStale -> QuotaAmber.copy(alpha = 0.6f)
                else -> Color.Transparent
            }

            val statusText = when {
                isSyncError -> "Fallback Mode$ageSuffix"
                isStale -> "${connectionState.label} (Stale)$ageSuffix"
                else -> "${connectionState.label}$ageSuffix"
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier
                    .width(164.dp)
                    .height(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { showSyncHub = true }
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, pillBorderColor, RoundedCornerShape(16.dp))
                    .padding(horizontal = 9.dp)
            ) {
                Box(
                    modifier = Modifier.size(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    when {
                        isRefreshing -> CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 2.dp,
                            color = dotColor
                        )
                        isSyncError -> Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Sync Alert",
                            tint = QuotaCoral,
                            modifier = Modifier.size(12.dp)
                        )
                        else -> Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }
                }

                Text(
                    text = statusText,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isSyncError) QuotaCoral else if (isStale) QuotaAmber else MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                BadgedBox(
                    badge = {
                        if (hiddenBucketIds.isNotEmpty() || pinnedBucketId != null) {
                            Badge(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            ) {
                                Text(
                                    text = if (pinnedBucketId != null) "★" else "${hiddenBucketIds.size}",
                                    fontSize = 9.sp
                                )
                            }
                        }
                    }
                ) {
                    IconButton(
                        onClick = { showFilterSheet = true },
                        modifier = Modifier.size(36.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "Model Filters",
                            tint = if (hiddenBucketIds.isNotEmpty() || pinnedBucketId != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }

                IconButton(
                    onClick = { viewModel.refresh(forceTailscale = false, isManual = true) },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Refresh",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp)
                    )
                }

                QuotaPolarityToggle(
                    polarity = polarity,
                    onToggle = { viewModel.setPolarity(it) }
                )
            }
        }

        if (!settings.hasUsableTransport) {
            ConnectCollectorCard(
                connectionState = connectionState,
                onConnectClick = { showSyncHub = true },
                compact = true
            )
        }

        QuotaChronographCard(
            snapshot = snapshot,
            polarity = polarity,
            hiddenBucketIds = hiddenBucketIds,
            selectedBucketId = effectiveBucketId,
            selectedProviderId = effectiveProviderId,
            onSelectBucket = { bucketId ->
                selectedBucketId = bucketId
                selectedProviderId = null
            },
            onSelectProvider = { providerId ->
                selectedBucketId = null
                selectedProviderId = providerId
            },
            nowEpochMs = nowEpochMs
        )

        SelectedPoolStrip(
            snapshot = snapshot,
            polarity = polarity,
            hiddenBucketIds = hiddenBucketIds,
            selectedBucketId = effectiveBucketId,
            selectedProviderId = effectiveProviderId,
            pinnedBucketId = pinnedBucketId,
            onOpenDetails = { selectedBucket = it },
            onSetPinnedBucket = { viewModel.setPinnedBucket(it) },
            onPrime = { viewModel.primeGrok() },
            isPriming = isPriming,
            nowEpochMs = nowEpochMs
        )

        ActivityBoardCard(
            snapshot = snapshot,
            polarity = polarity,
            hiddenBucketIds = hiddenBucketIds,
            selectedBucketId = effectiveBucketId,
            selectedProviderId = effectiveProviderId
        )
    }

    selectedBucket?.let { bucket ->
        BucketDetailSheet(
            bucket = bucket,
            polarity = polarity,
            history = snapshot.history,
            onDismiss = { selectedBucket = null }
        )
    }

    if (showSyncHub) {
        SyncHubSheet(
            viewModel = viewModel,
            onDismiss = { showSyncHub = false }
        )
    }

    if (showFilterSheet) {
        ModelFilterSheet(
            snapshot = snapshot,
            hiddenBucketIds = hiddenBucketIds,
            pinnedBucketId = pinnedBucketId,
            polarity = polarity,
            onToggleVisibility = { viewModel.toggleBucketVisibility(it) },
            onSetHiddenBuckets = { viewModel.setHiddenBuckets(it) },
            onSetPinnedBucket = { viewModel.setPinnedBucket(it) },
            onDismiss = { showFilterSheet = false }
        )
    }
}
