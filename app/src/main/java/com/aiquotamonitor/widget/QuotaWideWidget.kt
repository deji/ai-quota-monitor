package com.aiquotamonitor.widget

import android.content.Context
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.GlanceTheme
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.WidgetQuotaItem
import com.aiquotamonitor.ui.MainActivity
import kotlinx.serialization.json.Json

private val widgetJson = Json { ignoreUnknownKeys = true }

class QuotaWideWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read preferences and resolve inside provideContent so updateAll() during an
            // active Glance session re-evaluates the ordered selection immediately.
            val prefs = PreferencesStore(context)
            val polarity = prefs.getPolarity()
            val cachedJson = prefs.getCachedSnapshotJson()
            var isDemo = false
            val snapshot = if (!cachedJson.isNullOrBlank()) {
                try {
                    widgetJson.decodeFromString<QuotaSnapshot>(cachedJson)
                } catch (_: Exception) {
                    isDemo = true
                    SeedData.createSeedSnapshot()
                }
            } else {
                isDemo = true
                SeedData.createSeedSnapshot()
            }
            val items = snapshot.resolveWideWidgetItems(
                wideBucketIds = prefs.getWideWidgetBucketIds(),
                hiddenBucketIds = prefs.getHiddenBucketIds()
            )
            val polarityTag = if (polarity == QuotaPolarity.REMAINING) "REM" else "USED"
            val headerText = if (isDemo) "ORCA STAGE DECK • $polarityTag • DEMO" else "ORCA STAGE DECK • $polarityTag"

            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(24.dp)
                        .background(ColorProvider(Color(0xFF11161A)))
                        .padding(14.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.TopStart
                ) {
                    Column(
                        modifier = GlanceModifier.fillMaxSize()
                    ) {
                        // Top Bar
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = headerText,
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF80D4DC)),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Text(
                                text = "Multi-Agent AI Quota",
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF94A3B8)),
                                    fontSize = 10.sp
                                )
                            )
                        }

                        Spacer(modifier = GlanceModifier.height(10.dp))

                        // 3-slot deck (always exactly MAX_WIDE_WIDGET_SLOTS cells)
                        Row(
                            modifier = GlanceModifier.fillMaxWidth()
                        ) {
                            items.forEachIndexed { index, item ->
                                GlanceWideCell(
                                    item = item,
                                    polarity = polarity,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                if (index < items.lastIndex) {
                                    Spacer(modifier = GlanceModifier.width(8.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun GlanceWideCell(
    item: WidgetQuotaItem,
    polarity: QuotaPolarity,
    modifier: GlanceModifier
) {
    val displayPercent = item.displayPercent(polarity)
    val isUnavailable = displayPercent.isNaN()
    val percentColor = when {
        isUnavailable -> Color(0xFF94A3B8)
        displayPercent <= 20.0 -> Color(0xFFFFB4AB)
        displayPercent <= 40.0 -> Color(0xFFFFB951)
        else -> Color(0xFF5CE196)
    }
    val showFallbackCue = item.isFallback && !isUnavailable
    val headerColor = if (showFallbackCue) Color(0xFFFFB951) else Color(0xFF94A3B8)
    val headerText = if (showFallbackCue) {
        "FALLBACK • ${item.categoryLabel}"
    } else {
        item.categoryLabel
    }
    val pace = item.paceDeltaPercent

    Box(
        modifier = modifier
            .cornerRadius(14.dp)
            .background(ColorProvider(Color(0xFF1B2329)))
            .padding(10.dp)
    ) {
        Column(
            modifier = GlanceModifier.fillMaxWidth()
        ) {
            Text(
                text = headerText,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(headerColor),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(modifier = GlanceModifier.height(4.dp))

            Text(
                text = if (isUnavailable) "--" else "${displayPercent.toInt()}%",
                style = TextStyle(
                    color = ColorProvider(percentColor),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = item.titleLabel,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(Color(0xFFE2E8F0)),
                    fontSize = 10.sp
                )
            )

            if (!isUnavailable && pace != 0.0) {
                Spacer(modifier = GlanceModifier.height(2.dp))
                Text(
                    text = if (pace <= 0) "Ahead: ${pace}%" else "Burn: +${pace}%",
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(if (pace <= 0) Color(0xFF5CE196) else Color(0xFFFFB951)),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

class QuotaWideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWideWidget()
}
