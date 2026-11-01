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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.ui.theme.GlowCyan
import com.aiquotamonitor.ui.theme.QuotaCyan
import kotlin.math.round

/**
 * Persistent bottom dock for Time-Travel Horizon simulation on the Dashboard.
 * Includes quick presets: NOW, +1H, +3H, and RESET (which scrubs to the nearest bucket's reset window).
 */
@Composable
fun TimeTravelScrubberDock(
    scrubOffsetHours: Double,
    onScrubOffsetChanged: (Double) -> Unit,
    nearestResetHours: Double,
    onResetScrub: () -> Unit,
    modifier: Modifier = Modifier
) {
    val isScrubbing = scrubOffsetHours > 0.0
    val activeColor = if (isScrubbing) GlowCyan else MaterialTheme.colorScheme.primary

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
            .border(
                width = 1.dp,
                color = if (isScrubbing) GlowCyan.copy(alpha = 0.6f)
                else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
            ),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 8.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            // Header Row: Status Label + Offset Badge + Live Reset
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
                        tint = activeColor,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = "TIME-TRAVEL HORIZON",
                        style = MaterialTheme.typography.labelSmall,
                        color = activeColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.8.sp
                    )
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isScrubbing) QuotaCyan.copy(alpha = 0.2f)
                                else MaterialTheme.colorScheme.surfaceContainer
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

            // Continuous Slider (0.0 to 24.0 hours)
            Slider(
                value = scrubOffsetHours.toFloat(),
                onValueChange = { onScrubOffsetChanged(round(it.toDouble() * 10.0) / 10.0) },
                valueRange = 0f..24f,
                steps = 47, // 0.5h increments
                colors = SliderDefaults.colors(
                    thumbColor = activeColor,
                    activeTrackColor = activeColor,
                    inactiveTrackColor = Color(0xFF263238).copy(alpha = 0.5f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(32.dp)
            )

            // Preset Quick Buttons: NOW, +1H, +3H, and RESET (to nearest bucket's hoursUntilReset)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                val roundedNearest = round(nearestResetHours * 10.0) / 10.0
                val presets = listOf(
                    0.0 to "NOW",
                    1.0 to "+1H",
                    3.0 to "+3H",
                    roundedNearest to "RESET (${roundedNearest}h)"
                )

                presets.forEach { (hours, label) ->
                    val isSelected = (scrubOffsetHours == hours) || (hours == 0.0 && scrubOffsetHours == 0.0)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(
                                if (isSelected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer
                            )
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                            color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 10.sp,
                            modifier = Modifier.clickable { onScrubOffsetChanged(hours) }
                        )
                    }
                }
            }
        }
    }
}
