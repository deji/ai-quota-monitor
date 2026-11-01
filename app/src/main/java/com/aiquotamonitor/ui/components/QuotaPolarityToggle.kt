package com.aiquotamonitor.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.QuotaPolarity

@Composable
fun QuotaPolarityToggle(
    polarity: QuotaPolarity,
    onToggle: (QuotaPolarity) -> Unit,
    modifier: Modifier = Modifier
) {
    val isRemaining = polarity == QuotaPolarity.REMAINING
    val animatedWeight by animateFloatAsState(
        targetValue = if (isRemaining) 0f else 1f,
        animationSpec = spring(),
        label = "polarity_pill_animation"
    )

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(3.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // "% Left" Option
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (isRemaining) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .clickable { onToggle(QuotaPolarity.REMAINING) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "% Left",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (isRemaining) FontWeight.Bold else FontWeight.Medium,
                    color = if (isRemaining) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // "% Used" Option
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        if (!isRemaining) MaterialTheme.colorScheme.primaryContainer
                        else Color.Transparent
                    )
                    .clickable { onToggle(QuotaPolarity.USED) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "% Used",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (!isRemaining) FontWeight.Bold else FontWeight.Medium,
                    color = if (!isRemaining) MaterialTheme.colorScheme.onPrimaryContainer
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
