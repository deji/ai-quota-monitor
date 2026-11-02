package com.aiquotamonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.ui.text.style.TextAlign
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

val ALL_STREAM_SERIES: List<StreamSeries> = listOf(
    StreamSeries("Argon 4", "gemini-argon-wtu-weekly", QuotaCyan),
    StreamSeries("OpenCode Wk", "opencode-weekly", QuotaAmber),
    StreamSeries("Grok Build", "grok-weekly-credits", QuotaEmerald),
    StreamSeries("Gemini 5h", "gemini-5h", Color(0xFF64B5F6)),
    StreamSeries("Gemini Wk", "gemini-weekly", Color(0xFF81C784)),
    StreamSeries("OpenCode 5h", "opencode-rolling", Color(0xFFFFD54F)),
    StreamSeries("OpenCode Mo", "opencode-monthly", Color(0xFFFF8A65)),
    StreamSeries("3P 5h", "3p-5h", Color(0xFFCE93D8)),
    StreamSeries("3P Weekly", "3p-weekly", Color(0xFFBA68C8))
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BurnVelocityChart(
    history: List<HistoryPoint>,
    polarity: QuotaPolarity,
    modifier: Modifier = Modifier,
    hiddenBucketIds: Set<String> = emptySet(),
    streams: List<StreamSeries> = ALL_STREAM_SERIES,
    height: Dp = 220.dp
) {
    val visibleStreams = streams.filterNot { it.bucketId in hiddenBucketIds }
    val streamsWithData = visibleStreams.filter { stream ->
        history.any { it.bucketRemaining.containsKey(stream.bucketId) }
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f), RoundedCornerShape(24.dp))
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Chart Header
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

                Text(
                    text = "${visibleStreams.size} visible",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 10.sp
                )
            }

            if (visibleStreams.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    visibleStreams.forEach { stream ->
                        val hasHistory = history.any { it.bucketRemaining.containsKey(stream.bucketId) }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(if (hasHistory) stream.color else stream.color.copy(alpha = 0.3f))
                            )
                            Text(
                                text = stream.name,
                                style = MaterialTheme.typography.labelSmall,
                                color = if (hasHistory) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                                fontSize = 9.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(14.dp))

            if (visibleStreams.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "All model streams hidden",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Enable model filters to plot consumption curves.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else if (streamsWithData.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "No history recorded yet",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontWeight = FontWeight.SemiBold
                        )
                        Text(
                            text = "Historical burn velocity curves will appear after collection rounds.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
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

                    // 3. Multi-Stream Bezier Curves (Only for streams with recorded points)
                    if (history.isNotEmpty()) {
                        val stepX = if (history.size > 1) w / (history.size - 1) else w

                        streamsWithData.forEach { stream ->
                            val dataPoints = history.mapIndexedNotNull { idx, point ->
                                point.bucketRemaining[stream.bucketId]?.let { raw -> idx to raw }
                            }
                            if (dataPoints.isNotEmpty()) {
                                val path = Path()
                                val fillPath = Path()
                                var firstPoint = true
                                var firstX = 0f
                                var lastX = 0f
                                var prevX = 0f
                                var prevY = 0f

                                dataPoints.forEach { (idx, rawRemaining) ->
                                    val value = if (polarity == QuotaPolarity.REMAINING) rawRemaining else (100.0 - rawRemaining)
                                    val x = idx * stepX
                                    val normalizedY = (value.toFloat() / 100f).coerceIn(0f, 1f)
                                    val y = chartH * (1f - normalizedY)

                                    if (firstPoint) {
                                        path.moveTo(x, y)
                                        fillPath.moveTo(x, chartH)
                                        fillPath.lineTo(x, y)
                                        firstX = x
                                        firstPoint = false
                                    } else {
                                        val midX = (prevX + x) / 2f
                                        path.cubicTo(midX, prevY, midX, y, x, y)
                                        fillPath.cubicTo(midX, prevY, midX, y, x, y)
                                    }
                                    prevX = x
                                    prevY = y
                                    lastX = x
                                }

                                if (dataPoints.size == 1) {
                                    drawCircle(
                                        color = stream.color,
                                        radius = 3.dp.toPx(),
                                        center = Offset(prevX, prevY)
                                    )
                                } else {
                                    fillPath.lineTo(lastX, chartH)
                                    fillPath.lineTo(firstX, chartH)
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
}
