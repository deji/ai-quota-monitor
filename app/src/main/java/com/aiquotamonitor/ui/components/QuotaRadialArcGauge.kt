package com.aiquotamonitor.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.ui.theme.GlowOrange
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaEmerald
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun QuotaRadialArcGauge(
    percent: Double,
    elapsedPercent: Double,
    subtitle: String,
    modifier: Modifier = Modifier,
    size: Dp = 190.dp,
    strokeWidth: Dp = 14.dp,
    paceNotchColor: Color = GlowOrange,
    isScrubbing: Boolean = false
) {
    val percentAnimSpec = if (isScrubbing) snap<Float>() else spring<Float>(
        dampingRatio = Spring.DampingRatioLowBouncy,
        stiffness = Spring.StiffnessLow
    )

    val elapsedAnimSpec = if (isScrubbing) snap<Float>() else spring<Float>(
        dampingRatio = Spring.DampingRatioNoBouncy,
        stiffness = Spring.StiffnessMediumLow
    )

    val animatedPercent by animateFloatAsState(
        targetValue = percent.toFloat().coerceIn(0f, 100f),
        animationSpec = percentAnimSpec,
        label = "gauge_percent_animation"
    )

    val animatedElapsed by animateFloatAsState(
        targetValue = elapsedPercent.toFloat().coerceIn(0f, 100f),
        animationSpec = elapsedAnimSpec,
        label = "gauge_elapsed_animation"
    )

    // Gauge sweeps 240 degrees (from 150° down to 390° / 30°)
    val startAngle = 150f
    val sweepAngle = 240f
    val progressSweep = (animatedPercent / 100f) * sweepAngle
    val paceAngle = startAngle + (animatedElapsed / 100f) * sweepAngle

    val arcColor = when {
        percent <= 15.0 -> QuotaCoral
        percent <= 40.0 -> QuotaAmber
        else -> QuotaEmerald
    }

    Box(
        modifier = modifier.size(size),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val strokePx = strokeWidth.toPx()
            val arcSize = Size(size.toPx() - strokePx, size.toPx() - strokePx)
            val topLeft = Offset(strokePx / 2f, strokePx / 2f)

            // 1. Background Track Arc
            drawArc(
                color = Color(0xFF263238).copy(alpha = 0.5f),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokePx, cap = StrokeCap.Round)
            )

            // 2. Foreground Progress Arc
            if (progressSweep > 0f) {
                drawArc(
                    color = arcColor,
                    startAngle = startAngle,
                    sweepAngle = progressSweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = strokePx, cap = StrokeCap.Round)
                )
            }

            // 3. Glowing Target Pace Notch on outer rim
            val center = Offset(size.toPx() / 2f, size.toPx() / 2f)
            val radius = (size.toPx() - strokePx) / 2f
            val rad = Math.toRadians(paceAngle.toDouble())
            val innerTickR = radius - (strokePx * 0.7f)
            val outerTickR = radius + (strokePx * 0.8f)

            val p1 = Offset(
                x = (center.x + innerTickR * cos(rad)).toFloat(),
                y = (center.y + innerTickR * sin(rad)).toFloat()
            )
            val p2 = Offset(
                x = (center.x + outerTickR * cos(rad)).toFloat(),
                y = (center.y + outerTickR * sin(rad)).toFloat()
            )

            // Draw glowing halo line
            drawLine(
                color = paceNotchColor.copy(alpha = 0.4f),
                start = p1,
                end = p2,
                strokeWidth = 7.dp.toPx(),
                cap = StrokeCap.Round
            )
            // Draw crisp notch tick
            drawLine(
                color = paceNotchColor,
                start = p1,
                end = p2,
                strokeWidth = 3.dp.toPx(),
                cap = StrokeCap.Round
            )
        }

        // Center Content: Large percentage & subtitle
        Column(
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "${animatedPercent.toInt()}%",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                fontSize = 44.sp
            )
            Text(
                text = subtitle.uppercase(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp
            )
        }
    }
}
