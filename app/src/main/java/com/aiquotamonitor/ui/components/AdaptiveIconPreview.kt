package com.aiquotamonitor.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.R
import com.aiquotamonitor.ui.theme.AIQuotaMonitorTheme

private val VisibleLauncherSize = 72.dp
private val TotalCanvasSize = VisibleLauncherSize * (108f / 72f) // 108.dp

/**
 * Preview component showcasing the native Android adaptive launcher icon
 * across standard launcher masks (Pixel Circle, One UI Squircle) and
 * Android 13–17 Material You Dynamic Color themed mode.
 */
@Composable
fun AdaptiveIconPreviewCard(
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f), RoundedCornerShape(24.dp))
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "ADAPTIVE LAUNCHER ICON",
                style = MaterialTheme.typography.labelMedium.copy(
                    letterSpacing = 1.sp,
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = "Concept A: Chronos Radial Arc (Material You Dynamic Ready)",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 1. Pixel Launcher Circle (72dp visible mask, 108dp canvas)
            IconMaskPreview(
                title = "Pixel Circle",
                shape = CircleShape
            ) {
                AdaptiveIconContent()
            }

            // 2. Rounded Square mask (72dp visible mask, 18dp corner radius)
            IconMaskPreview(
                title = "Rounded Square",
                shape = RoundedCornerShape(18.dp)
            ) {
                AdaptiveIconContent()
            }

            // 3. Material You Themed Monochrome (72dp dynamic color container)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(VisibleLauncherSize)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.secondaryContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_launcher_monochrome),
                        contentDescription = "Monochrome Launcher Icon",
                        modifier = Modifier.size(TotalCanvasSize),
                        colorFilter = ColorFilter.tint(MaterialTheme.colorScheme.onSecondaryContainer)
                    )
                }
                Text(
                    text = "Themed Monet",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun IconMaskPreview(
    title: String,
    shape: androidx.compose.ui.graphics.Shape,
    content: @Composable () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(VisibleLauncherSize)
                .clip(shape),
            contentAlignment = Alignment.Center
        ) {
            content()
        }
        Text(
            text = title,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun AdaptiveIconContent() {
    Box(
        modifier = Modifier.size(TotalCanvasSize),
        contentAlignment = Alignment.Center
    ) {
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_background),
            contentDescription = null,
            modifier = Modifier.size(TotalCanvasSize)
        )
        Image(
            painter = painterResource(id = R.drawable.ic_launcher_foreground),
            contentDescription = null,
            modifier = Modifier.size(TotalCanvasSize)
        )
    }
}

@Preview(showBackground = true, backgroundColor = 0xFF0B0F12)
@Composable
private fun AdaptiveIconPreviewCardPreview() {
    AIQuotaMonitorTheme(darkTheme = true) {
        Box(modifier = Modifier.padding(16.dp)) {
            AdaptiveIconPreviewCard()
        }
    }
}
