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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Sensors
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald

/**
 * Prominent first-run card shown on the Dashboard when neither transport
 * (Tailscale daemon nor GitHub Gist relay) is configured.
 * Encourages connecting to the Mac collector and opens SyncHubSheet when tapped.
 */
@Composable
fun ConnectCollectorCard(
    onConnectClick: () -> Unit,
    modifier: Modifier = Modifier,
    connectionState: ConnectionState = ConnectionState.DEMO_SEED
) {
    val isOfflineCache = connectionState == ConnectionState.OFFLINE
    val isLive = connectionState == ConnectionState.TAILSCALE_LIVE || connectionState == ConnectionState.CLOUD_RELAY_SYNCED

    val badgeText = when {
        isOfflineCache -> "OFFLINE CACHE"
        isLive -> connectionState.label.uppercase()
        else -> "DEMO SIMULATION"
    }

    val badgeColor = when {
        isLive -> QuotaEmerald
        isOfflineCache -> QuotaCyan
        else -> QuotaAmber
    }

    val descriptionText = when {
        isOfflineCache -> "Currently viewing cached offline data. Connect your Pixel 8 Pro to the Mac collector over Tailscale or encrypted GitHub Gist relay to stream live quota windows and burn rates."
        isLive -> "Connected to ${connectionState.label}. Tap below to view or edit connection settings."
        else -> "Currently viewing demo simulation data. Connect your Pixel 8 Pro to the Mac collector over Tailscale or encrypted GitHub Gist relay to stream live quota windows and burn rates."
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(
                1.5.dp,
                QuotaCyan.copy(alpha = 0.6f),
                RoundedCornerShape(24.dp)
            )
            .padding(18.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "FIRST-RUN SETUP",
                    style = MaterialTheme.typography.labelSmall,
                    color = QuotaCyan,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )

                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(badgeColor.copy(alpha = 0.15f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = badgeText,
                        style = MaterialTheme.typography.labelSmall,
                        color = badgeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 9.sp
                    )
                }
            }

            Text(
                text = "Connect to your Mac collector",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Text(
                text = descriptionText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 13.sp,
                lineHeight = 18.sp
            )

            Spacer(modifier = Modifier.height(2.dp))

            Button(
                onClick = onConnectClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            ) {
                Icon(
                    imageVector = Icons.Default.Sensors,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Configure Connection",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}
