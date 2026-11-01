package com.aiquotamonitor.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = QuotaCyan,
    onPrimary = Color(0xFF00363D),
    primaryContainer = QuotaCyanDark,
    onPrimaryContainer = Color(0xFF9EEFF9),
    secondary = QuotaEmerald,
    onSecondary = Color(0xFF00391C),
    secondaryContainer = QuotaEmeraldDark,
    onSecondaryContainer = Color(0xFF7AF8AF),
    tertiary = QuotaAmber,
    onTertiary = Color(0xFF452B00),
    tertiaryContainer = QuotaAmberDark,
    onTertiaryContainer = Color(0xFFFFDDB5),
    error = QuotaCoral,
    onError = Color(0xFF690005),
    errorContainer = QuotaCoralDark,
    onErrorContainer = Color(0xFFFFDAD6),
    background = SurfaceDark,
    onBackground = TextPrimaryDark,
    surface = SurfaceDark,
    onSurface = TextPrimaryDark,
    surfaceVariant = SurfaceContainerHigh,
    onSurfaceVariant = TextSecondaryDark,
    outline = OutlineDark,
    outlineVariant = OutlineVariantDark,
    surfaceContainer = SurfaceContainer,
    surfaceContainerHigh = SurfaceContainerHigh,
    surfaceContainerHighest = SurfaceContainerHighest,
    surfaceContainerLow = SurfaceContainerLow,
    surfaceContainerLowest = SurfaceContainerLowest
)

private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF006874),
    onPrimary = Color.White,
    primaryContainer = Color(0xFF9EEFF9),
    onPrimaryContainer = Color(0xFF002024),
    secondary = Color(0xFF006D39),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF7AF8AF),
    onSecondaryContainer = Color(0xFF00210E),
    tertiary = Color(0xFF865300),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDDB5),
    onTertiaryContainer = Color(0xFF2B1700),
    background = Color(0xFFF8FAFC),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFE2E8F0),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8)
)

@Composable
fun AIQuotaMonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit
) {
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = TabularTypography,
        content = content
    )
}
