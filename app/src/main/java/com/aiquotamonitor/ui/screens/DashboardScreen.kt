package com.aiquotamonitor.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.ui.components.AntigravityBucketsCard
import com.aiquotamonitor.ui.components.BucketDetailSheet
import com.aiquotamonitor.ui.components.ConnectCollectorCard
import com.aiquotamonitor.ui.components.HeroBottleneckCard
import com.aiquotamonitor.ui.components.OrcaRoleReadinessStrip
import com.aiquotamonitor.ui.components.ProviderBentoRow
import com.aiquotamonitor.ui.components.QuotaPolarityToggle
import com.aiquotamonitor.ui.components.SyncHubSheet
import com.aiquotamonitor.ui.components.TimeTravelScrubberDock
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald
import com.aiquotamonitor.ui.viewmodel.QuotaViewModel

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
    val scrubOffset by viewModel.scrubOffsetHours.collectAsState()
    val isScrubbing by viewModel.isScrubbing.collectAsState()
    val isPriming by viewModel.isPriming.collectAsState()
    val isSyncError by viewModel.isSyncError.collectAsState()
    val isStale by viewModel.isStale.collectAsState()

    var selectedBucket by remember { mutableStateOf<QuotaBucket?>(null) }
    var showSyncHub by remember { mutableStateOf(false) }

    // Intercept back gesture to close modal bottom sheets first
    if (selectedBucket != null) {
        BackHandler { selectedBucket = null }
    }
    if (showSyncHub) {
        BackHandler { showSyncHub = false }
    }

    val bottleneckBucket = snapshot.bottleneckBucket
    val agyProvider = snapshot.providers.firstOrNull { it.id == "agy" }
    val openCodeProvider = snapshot.providers.firstOrNull { it.id == "opencode_go" }
    val grokProvider = snapshot.providers.firstOrNull { it.id == "grok" }

    val historyValues = snapshot.history.mapNotNull { it.bucketRemaining["gemini-argon-wtu-weekly"] }

    // Nearest reset hours across all buckets for quick RESET preset
    val nearestResetHours = snapshot.providers
        .flatMap { it.buckets }
        .mapNotNull { it.hoursUntilReset }
        .filter { it > 0.0 }
        .minOrNull() ?: 3.4

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 136.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
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

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .clickable { showSyncHub = true }
                        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                        .border(1.dp, pillBorderColor, RoundedCornerShape(16.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    if (connectionState == ConnectionState.SYNCING) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(10.dp),
                            strokeWidth = 2.dp,
                            color = dotColor
                        )
                    } else if (isSyncError) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Sync Alert",
                            tint = QuotaCoral,
                            modifier = Modifier.size(12.dp)
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(dotColor)
                        )
                    }

                    Text(
                        text = when {
                            isSyncError -> "Fallback Mode"
                            isStale -> "${connectionState.label} (Stale)"
                            else -> connectionState.label
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isSyncError) QuotaCoral else if (isStale) QuotaAmber else MaterialTheme.colorScheme.onSurface,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 11.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
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
                    onConnectClick = { showSyncHub = true }
                )
            }

            if (snapshot.orcaRoles.isNotEmpty()) {
                OrcaRoleReadinessStrip(
                    roles = snapshot.orcaRoles,
                    polarity = polarity
                )
            }

            if (bottleneckBucket != null) {
                HeroBottleneckCard(
                    bucket = bottleneckBucket,
                    polarity = polarity,
                    historyPoints = historyValues,
                    isScrubbing = isScrubbing,
                    onClick = { selectedBucket = bottleneckBucket }
                )
            }

            ProviderBentoRow(
                openCodeProvider = openCodeProvider,
                grokProvider = grokProvider,
                smartPrime = snapshot.smartPrime,
                polarity = polarity,
                onPrimePulse = { viewModel.primeGrok() },
                isPriming = isPriming,
                isScrubbing = isScrubbing,
                onOpenCodeClick = {
                    selectedBucket = openCodeProvider?.buckets?.firstOrNull()
                },
                onGrokClick = {
                    selectedBucket = grokProvider?.buckets?.firstOrNull()
                }
            )

            AntigravityBucketsCard(
                agyProvider = agyProvider,
                polarity = polarity,
                onBucketClick = { selectedBucket = it }
            )

            Spacer(modifier = Modifier.height(8.dp))
        }

        TimeTravelScrubberDock(
            scrubOffsetHours = scrubOffset,
            onScrubOffsetChanged = { viewModel.setScrubOffset(it) },
            nearestResetHours = nearestResetHours,
            onResetScrub = { viewModel.resetScrub() },
            modifier = Modifier.align(Alignment.BottomCenter)
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
}
