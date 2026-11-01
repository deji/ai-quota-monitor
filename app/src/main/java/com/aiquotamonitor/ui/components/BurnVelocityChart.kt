package com.aiquotamonitor.ui.components

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.model.HistoryPoint
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.ui.theme.GlowOrange
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCyan
import com.aiquotamonitor.ui.theme.QuotaEmerald

data class StreamSeries(
    val name: String,
    val bucketId: String,
    val color: Color
)

@Composable
fun BurnVelocityChart(
    history: List<HistoryPoint>,
    polarity: QuotaPolarity,
    modifier: Modifier = Modifier,
    height: Dp = 220.dp
) {
    val streams = listOf(
        StreamSeries("Argon 4", "gemini-argon-wtu-weekly", QuotaCyan),
        StreamSeries("OpenCode", "opencode-weekly", QuotaAmber),
        StreamSeries("Grok Build", "grok-weekly-credits", QuotaEmerald)
    )

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Chart Header & Legend
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "24H HORIZON BURN VELOCITY",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold,
                    fontSize = 10.sp,
                    letterSpacing = 0.8.sp
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    streams.forEach { stream ->
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(stream.color)
                            )
                            Text(
                                text = stream.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Canvas Chart Area
            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
            ) {
                val w = size.width
                val h = size.height
                val paddingBottom = 20.dp.toPx()
                val chartH = h - paddingBottom

                // 1. Horizontal Grid lines (25%, 50%, 75%, 100%)
                val gridLevels = listOf(0.25f, 0.50f, 0.75f, 1.0f)
                gridLevels.forEach { level ->
                    val y = chartH * (1f - level)
                    drawLine(
                        color = Color(0xFF263238).copy(alpha = 0.35f),
                        start = Offset(0f, y),
                        end = Offset(w, y),
                        strokeWidth = 1.dp.toPx()
                    )
                }

                // 2. Linear Pace Reference Diagonal Guideline (Dashed Orange)
                val pacePath = Path().apply {
                    moveTo(0f, chartH * 0.15f)
                    lineTo(w, chartH * 0.95f)
                }
                drawPath(
                    path = pacePath,
                    color = GlowOrange.copy(alpha = 0.45f),
                    style = Stroke(
                        width = 1.5.dp.toPx(),
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), 0f)
                    )
                )

                // 3. Multi-Stream Bezier Curves
                if (history.isNotEmpty()) {
                    val stepX = if (history.size > 1) w / (history.size - 1) else w

                    streams.forEach { stream ->
                        val path = Path()
                        val fillPath = Path()
                        var firstPoint = true

                        history.forEachIndexed { idx, point ->
                            val rawRemaining = point.bucketRemaining[stream.bucketId] ?: 100.0
                            val value = if (polarity == QuotaPolarity.REMAINING) rawRemaining else (100.0 - rawRemaining)
                            val x = idx * stepX
                            val normalizedY = (value.toFloat() / 100f).coerceIn(0f, 1f)
                            val y = chartH * (1f - normalizedY)

                            if (firstPoint) {
                                path.moveTo(x, y)
                                fillPath.moveTo(x, chartH)
                                fillPath.lineTo(x, y)
                                firstPoint = false
                            } else {
                                val prevX = (idx - 1) * stepX
                                val prevVal = if (polarity == QuotaPolarity.REMAINING) {
                                    history[idx - 1].bucketRemaining[stream.bucketId] ?: 100.0
                                } else {
                                    100.0 - (history[idx - 1].bucketRemaining[stream.bucketId] ?: 100.0)
                                }
                                val prevY = chartH * (1f - (prevVal.toFloat() / 100f).coerceIn(0f, 1f))
                                val midX = (prevX + x) / 2f
                                path.cubicTo(midX, prevY, midX, y, x, y)
                                fillPath.cubicTo(midX, prevY, midX, y, x, y)
                            }
                        }

                        fillPath.lineTo(w, chartH)
                        fillPath.close()

                        // Draw subtle stream area
                        drawPath(
                            path = fillPath,
                            brush = Brush.verticalGradient(
                                colors = listOf(stream.color.copy(alpha = 0.15f), Color.Transparent),
                                startY = 0f,
                                endY = chartH
                            )
                        )

                        // Draw smooth curve stroke
                        drawPath(
                            path = path,
                            color = stream.color,
                            style = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
                        )
                    }
                }

                // 4. Time Axis markers at bottom
                drawLine(
                    color = Color(0xFF37474F).copy(alpha = 0.5f),
                    start = Offset(0f, chartH),
                    end = Offset(w, chartH),
                    strokeWidth = 1.dp.toPx()
                )
            }

            // Time Axis Labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                listOf("-24h", "-18h", "-12h", "-6h", "NOW").forEach { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontSize = 9.sp
                    )
                }
            }
        }
    }
}
