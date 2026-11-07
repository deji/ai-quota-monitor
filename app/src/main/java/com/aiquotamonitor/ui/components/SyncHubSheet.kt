package com.aiquotamonitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SheetState
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald
import com.aiquotamonitor.ui.viewmodel.QuotaViewModel
import kotlinx.coroutines.delay

/**
 * ModalBottomSheet opened from the Dashboard top-bar status pill.
 * Surfaces live connection health, Tailscale / Gist endpoints, real sync errors with amber/coral warning banners,
 * and quick actions for forced refresh and Grok Smart Prime.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncHubSheet(
    viewModel: QuotaViewModel,
    onDismiss: () -> Unit,
    sheetState: SheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
) {
    val connectionState by viewModel.connectionState.collectAsState()
    val settings by viewModel.settings.collectAsState()
    val syncError by viewModel.syncError.collectAsState()
    val isSyncError by viewModel.isSyncError.collectAsState()
    val snapshotIsStale by viewModel.isStale.collectAsState()
    val isPriming by viewModel.isPriming.collectAsState()
    val isAutoSyncing by viewModel.isAutoSyncing.collectAsState()
    val userMessage by viewModel.userMessage.collectAsState()
    val snapshot by viewModel.activeSnapshot.collectAsState()

    // Lifecycle-aware 30s clock so the snapshot age stays live while the sheet is open.
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
    // Combine the frozen-snapshot marker with a live 30s age evaluation so the
    // badge lands on the correct side of the 7200s boundary between emissions.
    val isStale = snapshotIsStale || (snapshot.ageSeconds(nowEpochMs) ?: 0L) > 7200L

    val isConfigured = settings.hasUsableTransport
    var isEditExpanded by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    ) {
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
                        text = "DUAL-TRANSPORT SYNC HUB",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        fontSize = 10.sp
                    )
                    Text(
                        text = "Collector & Relay Status",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
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

        // Bounded verticalScroll (never LazyColumn) so ConnectionSettingsForm keeps its
        // focus, draft text, `isEditExpanded`, and the 30s age ticker while scrolling.
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                .sheetContentNestedScroll(sheetState, scrollState)
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val statusColor = when {
                isSyncError -> QuotaCoral
                isStale -> QuotaAmber
                connectionState == ConnectionState.TAILSCALE_LIVE -> QuotaEmerald
                connectionState == ConnectionState.CLOUD_RELAY_SYNCED -> QuotaCyan
                connectionState == ConnectionState.DEMO_SEED -> QuotaAmber
                connectionState == ConnectionState.SYNCING -> MaterialTheme.colorScheme.primary
                else -> Color(0xFF94A3B8)
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(20.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(1.dp, statusColor.copy(alpha = 0.5f), RoundedCornerShape(20.dp))
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(12.dp)
                            .clip(CircleShape)
                            .background(statusColor)
                    )

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isStale) "${connectionState.label} (Stale)$ageSuffix" else "${connectionState.label}$ageSuffix",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = when {
                                connectionState == ConnectionState.SYNCING -> "Refreshing quota telemetry..."
                                isAutoSyncing -> "Syncing in background..."
                                isSyncError -> "Fallback active due to transport error"
                                isStale -> "Snapshot captured over 2 hours ago (>7200s)"
                                else -> "Operating nominally"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = if (isSyncError) QuotaCoral else if (isStale) QuotaAmber else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            if (!syncError.isNullOrBlank()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(QuotaCoral.copy(alpha = 0.12f))
                        .border(1.dp, QuotaCoral.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
                        .padding(14.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.Top
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = "Sync Warning",
                            tint = QuotaCoral,
                            modifier = Modifier.size(20.dp)
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Live Sync Unreachable",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = QuotaCoral
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = syncError ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            if (isConfigured) {
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
                            text = "CONFIGURED TRANSPORTS",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "1. Tailscale Live Daemon:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (settings.tailscaleUrl.isNotBlank()) settings.tailscaleUrl else "Not configured",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (settings.tailscaleUrl.isNotBlank()) QuotaCyan else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "2. Cloud Relay (Gist):",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            val isGistConfigured = settings.gistId.isNotBlank() && settings.gistAesKey.isNotBlank()
                            val isGistIncomplete = (settings.gistId.isNotBlank() || settings.gistAesKey.isNotBlank()) && !isGistConfigured
                            Text(
                                text = when {
                                    isGistConfigured -> "Configured"
                                    isGistIncomplete -> "Incomplete (needs ID & AES key)"
                                    else -> "Not configured"
                                },
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = when {
                                    isGistConfigured -> QuotaEmerald
                                    isGistIncomplete -> QuotaAmber
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(
                                text = "   GitHub Token:",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (settings.githubToken.isNotBlank()) "Configured" else "None (Raw URL / Public)",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = if (settings.githubToken.isNotBlank()) QuotaEmerald else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                OutlinedButton(
                    onClick = { isEditExpanded = !isEditExpanded },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = if (isEditExpanded) Icons.Default.ExpandLess else Icons.Default.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(
                        text = if (isEditExpanded) "Hide Connection Settings" else "Edit Connection Settings",
                        fontWeight = FontWeight.Bold,
                        fontSize = 12.sp
                    )
                }
            }

            if (!isConfigured || isEditExpanded) {
                ConnectionSettingsForm(
                    viewModel = viewModel
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { viewModel.refresh(forceTailscale = false, isManual = true) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = "Sync Now", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }

                OutlinedButton(
                    onClick = { viewModel.primeGrok() },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    enabled = !isPriming
                ) {
                    if (isPriming) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.Bolt,
                            contentDescription = null,
                            tint = QuotaCyan,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    Spacer(modifier = Modifier.size(6.dp))
                    Text(text = "Prime Grok", fontWeight = FontWeight.Bold, fontSize = 12.sp)
                }
            }

            if (isConfigured && !isEditExpanded && !userMessage.isNullOrBlank()) {
                Text(
                    text = userMessage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (isSyncError) QuotaCoral else QuotaEmerald,
                    fontSize = 11.sp
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
