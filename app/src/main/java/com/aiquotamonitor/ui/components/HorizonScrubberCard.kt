package com.aiquotamonitor.ui.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.RestartAlt
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.ui.theme.GlowCyan
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCyan
import kotlin.math.round

@Composable
fun HorizonScrubberCard(
    scrubOffsetHours: Double,
    onScrubOffsetChanged: (Double) -> Unit,
    onResetScrub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isScrubbing = scrubOffsetHours > 0.0
    val borderColor = if (isScrubbing) GlowCyan else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
    val backgroundTint = if (isScrubbing) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(backgroundTint)
            .border(1.dp, borderColor, RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Header Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = "Time-Travel Horizon",
                        tint = if (isScrubbing) GlowCyan else MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "HORIZON TIME-TRAVEL SCRUBBER",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (isScrubbing) GlowCyan else MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                }

                // Current Offset Badge
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isScrubbing) QuotaCyan.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(
                            text = if (isScrubbing) "+${round(scrubOffsetHours * 10.0) / 10.0}h" else "NOW (LIVE)",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (isScrubbing) QuotaCyan else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.Bold,
                            fontSize = 10.sp
                        )
                    }

                    if (isScrubbing) {
                        IconButton(
                            onClick = onResetScrub,
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.RestartAlt,
                                contentDescription = "Reset to live time",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Continuous Slider (0.0 to 24.0 hours)
            Slider(
                value = scrubOffsetHours.toFloat(),
                onValueChange = { onScrubOffsetChanged(round(it.toDouble() * 10.0) / 10.0) },
                valueRange = 0f..24f,
                steps = 47, // 0.5h increments
                colors = SliderDefaults.colors(
                    thumbColor = if (isScrubbing) GlowCyan else MaterialTheme.colorScheme.primary,
                    activeTrackColor = if (isScrubbing) GlowCyan else MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = Color(0xFF263238).copy(alpha = 0.5f)
                ),
                modifier = Modifier.fillMaxWidth()
            )

            // Preset Quick Buttons
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val presets = listOf(
                    0.0 to "NOW",
                    1.0 to "+1H",
                    3.0 to "+3H",
                    6.0 to "+6H",
                    12.0 to "+12H",
                    24.0 to "+24H"
                )

                presets.forEach { (hours, label) ->
                    val selected = (scrubOffsetHours == hours) || (hours == 0.0 && scrubOffsetHours == 0.0)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerHigh
                            )
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 9.sp,
                            modifier = Modifier.clickable { onScrubOffsetChanged(hours) }
                        )
                    }
                }
            }

            if (isScrubbing) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Projecting consumption at +${round(scrubOffsetHours * 10.0) / 10.0}h horizon across all Orca roles and buckets",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp
                )
            }
        }
    }
}
