package com.aiquotamonitor.widget

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import com.aiquotamonitor.domain.model.QuotaPolarity

/** Coral track used for exhausted/degraded pools. */
internal const val WIDGET_COLOR_CORAL = 0xFFFFB4AB.toInt()

/** Amber track used for pools approaching their limit. */
internal const val WIDGET_COLOR_AMBER = 0xFFFFB951.toInt()

/** Emerald track used for healthy pools. */
internal const val WIDGET_COLOR_EMERALD = 0xFF5CE196.toInt()

/** Slate track used for unknown/unavailable pools. */
internal const val WIDGET_COLOR_SLATE = 0xFF94A3B8.toInt()

/**
 * Health colour for a [displayPercent] already resolved through [polarity].
 *
 * `NaN` (an unavailable placeholder) always wins and renders slate. `REMAINING`
 * degrades as the number shrinks while `USED` degrades as it grows, so the two
 * polarities read the same colour for the same real-world condition.
 */
fun widgetHealthColor(displayPercent: Double, polarity: QuotaPolarity): Color {
    if (displayPercent.isNaN()) return Color(WIDGET_COLOR_SLATE)
    return when (polarity) {
        QuotaPolarity.REMAINING -> when {
            displayPercent <= 20.0 -> Color(WIDGET_COLOR_CORAL)
            displayPercent <= 50.0 -> Color(WIDGET_COLOR_AMBER)
            else -> Color(WIDGET_COLOR_EMERALD)
        }
        QuotaPolarity.USED -> when {
            displayPercent >= 80.0 -> Color(WIDGET_COLOR_CORAL)
            displayPercent >= 50.0 -> Color(WIDGET_COLOR_AMBER)
            else -> Color(WIDGET_COLOR_EMERALD)
        }
    }
}

/**
 * Renders a rounded progress bar for a Glance `Image` cell.
 *
 * Glance cannot draw arbitrary Compose shapes inside a widget, so every micro-bar
 * is a tiny ARGB bitmap. Unknown percentages draw only the track, and any positive
 * fill keeps a minimum width equal to the bar height so a sliver stays visible.
 */
fun createProgressBarBitmap(
    displayPercent: Double,
    fillColor: Color,
    trackColor: Color = Color(0xFF243338),
    widthPx: Int = 120,
    heightPx: Int = 6
): Bitmap {
    val bitmap = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val radius = heightPx / 2f
    val bounds = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())

    val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = trackColor.toArgb()
        style = Paint.Style.FILL
    }
    canvas.drawRoundRect(bounds, radius, radius, trackPaint)

    if (!displayPercent.isNaN() && displayPercent > 0.0) {
        val fillWidth = (widthPx * (displayPercent / 100.0).toFloat().coerceIn(0f, 1f))
            .coerceAtLeast(heightPx.toFloat())
        val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = fillColor.toArgb()
            style = Paint.Style.FILL
        }
        canvas.drawRoundRect(
            RectF(0f, 0f, fillWidth, heightPx.toFloat()),
            radius,
            radius,
            fillPaint
        )
    }

    return bitmap
}
