package com.aiquotamonitor.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.ui.components.ActiveHoursSlider
import com.aiquotamonitor.ui.components.ConnectionSettingsForm
import com.aiquotamonitor.ui.components.GlancePreviewCard
import com.aiquotamonitor.ui.viewmodel.QuotaViewModel

@Composable
fun WidgetsAndSyncScreen(
    viewModel: QuotaViewModel,
    modifier: Modifier = Modifier
) {
    val snapshot by viewModel.activeSnapshot.collectAsState()
    val savedSettings by viewModel.settings.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val hiddenBucketIds by viewModel.hiddenBucketIds.collectAsState()
    val pinnedBucketId by viewModel.pinnedBucketId.collectAsState()

    val startHour = try {
        savedSettings.activeHoursStart.split(":").first().toInt()
    } catch (_: Exception) { 8 }

    val endHour = try {
        savedSettings.activeHoursEnd.split(":").first().toInt()
    } catch (_: Exception) { 22 }

    var currentStartHour by remember(startHour) { mutableStateOf(startHour) }
    var currentEndHour by remember(endHour) { mutableStateOf(endHour) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column {
            Text(
                text = "WIDGETS & DUAL-TRANSPORT SYNC",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Bold,
                fontSize = 10.sp,
                letterSpacing = 0.8.sp
            )
            Text(
                text = "Glance Hub & Connection Settings",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            val unconfiguredSubtitle = when (connectionState) {
                ConnectionState.OFFLINE -> "No transport configured — displaying cached snapshot"
                else -> "No transport configured — displaying demo simulation data"
            }
            Text(
                text = if (savedSettings.hasUsableTransport) {
                    "Live endpoints configured for collector streaming"
                } else {
                    unconfiguredSubtitle
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        ConnectionSettingsForm(viewModel = viewModel)

        GlancePreviewCard(
            snapshot = snapshot,
            hiddenBucketIds = hiddenBucketIds,
            pinnedBucketId = pinnedBucketId
        )

        ActiveHoursSlider(
            startHour = currentStartHour,
            endHour = currentEndHour,
            onHoursChanged = { s, e ->
                currentStartHour = s
                currentEndHour = e
                viewModel.updateActiveHours("%02d:00".format(s), "%02d:00".format(e))
            }
        )

        Spacer(modifier = Modifier.height(16.dp))
    }
}
