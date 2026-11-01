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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.OrcaRoleReadiness
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald

@Composable
fun OrcaRoleReadinessStrip(
    roles: List<OrcaRoleReadiness>,
    polarity: QuotaPolarity,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        roles.forEach { role ->
            OrcaRolePill(
                role = role,
                polarity = polarity,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun OrcaRolePill(
    role: OrcaRoleReadiness,
    polarity: QuotaPolarity,
    modifier: Modifier = Modifier
) {
    val displayValue = if (polarity == QuotaPolarity.REMAINING) {
        role.remainingPercent
    } else {
        (100.0 - role.remainingPercent).coerceIn(0.0, 100.0)
    }

    val (statusColor, statusBg) = when (role.status.lowercase()) {
        "healthy" -> QuotaEmerald to QuotaEmerald.copy(alpha = 0.12f)
        "primed" -> QuotaCyan to QuotaCyan.copy(alpha = 0.12f)
        "warning" -> QuotaAmber to QuotaAmber.copy(alpha = 0.12f)
        "critical" -> QuotaCoral to QuotaCoral.copy(alpha = 0.15f)
        else -> MaterialTheme.colorScheme.primary to MaterialTheme.colorScheme.primaryContainer
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(14.dp))
            .padding(horizontal = 8.dp, vertical = 7.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = role.role,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp,
                    letterSpacing = 0.6.sp
                )

                // Glowing indicator dot
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }

            Spacer(modifier = Modifier.height(3.dp))

            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = role.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Medium,
                    fontSize = 11.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f, fill = false)
                )

                Text(
                    text = "${displayValue.toInt()}%",
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp
                )
            }
        }
    }
}
