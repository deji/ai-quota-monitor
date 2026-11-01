package com.aiquotamonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun MiniSparkline(
    points: List<Double>,
    lineColor: Color,
    modifier: Modifier = Modifier,
    height: Dp = 36.dp
) {
    if (points.size < 2) return

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
    ) {
        val width = size.width
        val h = size.height
        val minVal = (points.minOrNull() ?: 0.0).coerceAtLeast(0.0)
        val maxVal = (points.maxOrNull() ?: 100.0).coerceAtLeast(minVal + 1.0)
        val range = maxVal - minVal

        val stepX = width / (points.size - 1)
        val path = Path()
        val fillPath = Path()

        val coordinates = points.mapIndexed { index, value ->
            val x = index * stepX
            val normalizedY = ((value - minVal) / range).toFloat()
            val y = h - (normalizedY * (h - 8.dp.toPx())) - 4.dp.toPx()
            Offset(x, y)
        }

        path.moveTo(coordinates.first().x, coordinates.first().y)
        fillPath.moveTo(coordinates.first().x, h)
        fillPath.lineTo(coordinates.first().x, coordinates.first().y)

        for (i in 1 until coordinates.size) {
            val prev = coordinates[i - 1]
            val curr = coordinates[i]
            val midX = (prev.x + curr.x) / 2f
            path.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
            fillPath.cubicTo(midX, prev.y, midX, curr.y, curr.x, curr.y)
        }

        fillPath.lineTo(coordinates.last().x, h)
        fillPath.close()

        // Draw soft gradient area under sparkline
        drawPath(
            path = fillPath,
            brush = Brush.verticalGradient(
                colors = listOf(lineColor.copy(alpha = 0.25f), Color.Transparent)
            )
        )

        // Draw bezier stroke
        drawPath(
            path = path,
            color = lineColor,
            style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
