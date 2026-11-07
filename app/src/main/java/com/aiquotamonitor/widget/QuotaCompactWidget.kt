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
import com.aiquotamonitor.ui.MainActivity
import kotlinx.serialization.json.Json

private val widgetJson = Json { ignoreUnknownKeys = true }

class QuotaCompactWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read preferences and resolve inside provideContent so updateAll() during an
            // active Glance session re-evaluates the selection instead of reusing stale values.
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

            val hiddenBucketIds = prefs.getHiddenBucketIds()
            val pinnedBucketId = prefs.getPinnedBucketId()
            val item = snapshot.resolveCompactWidgetItem(
                compactBucketId = prefs.getCompactWidgetBucketId(),
                hiddenBucketIds = hiddenBucketIds,
                pinnedBucketId = pinnedBucketId
            )
            val displayPercent = item?.displayPercent(polarity)
            val polaritySuffix = if (polarity == QuotaPolarity.REMAINING) "Left" else "Used"
            val pace = item?.paceDeltaPercent ?: 0.0
            val polarityTag = if (polarity == QuotaPolarity.REMAINING) "REM" else "USED"
            val headerText = if (isDemo) "AI QUOTA • $polarityTag • DEMO" else "AI QUOTA • $polarityTag"
            val dotColor = if (isDemo) Color(0xFFFFB951) else Color(0xFF5CE196)

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
                        modifier = GlanceModifier.fillMaxSize(),
                        verticalAlignment = Alignment.Top,
                        horizontalAlignment = Alignment.Start
                    ) {
                        // Header
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = headerText,
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF80D4DC)),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Box(
                                modifier = GlanceModifier
                                    .size(8.dp)
                                    .cornerRadius(4.dp)
                                    .background(ColorProvider(dotColor))
                            ) {}
                        }

                        Spacer(modifier = GlanceModifier.height(10.dp))

                        if (item == null) {
                            Text(
                                text = "--",
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF94A3B8)),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "All models hidden",
                                maxLines = 1,
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF94A3B8)),
                                    fontSize = 11.sp
                                )
                            )
                            Spacer(modifier = GlanceModifier.defaultWeight())
                            Row(
                                modifier = GlanceModifier
                                    .fillMaxWidth()
                                    .cornerRadius(8.dp)
                                    .background(ColorProvider(Color(0xFF1E252B)))
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Tap to configure",
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF80D4DC)),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                            }
                        } else {
                            if (item.isFallback) {
                                Text(
                                    text = "FALLBACK • ${item.categoryLabel}",
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFFFFB951)),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Spacer(modifier = GlanceModifier.height(2.dp))
                            }

                            // Large percentage
                            Text(
                                text = if (displayPercent == null || displayPercent.isNaN()) {
                                    "--"
                                } else {
                                    "${displayPercent.toInt()}%"
                                },
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFFE2E8F0)),
                                    fontSize = 36.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )

                            Text(
                                text = "${item.titleLabel} $polaritySuffix",
                                maxLines = 1,
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF94A3B8)),
                                    fontSize = 11.sp
                                )
                            )

                            Spacer(modifier = GlanceModifier.defaultWeight())

                            // Bottom Pace Status (hidden for NaN placeholders)
                            if (displayPercent != null && !displayPercent.isNaN()) {
                                Row(
                                    modifier = GlanceModifier
                                        .fillMaxWidth()
                                        .cornerRadius(8.dp)
                                        .background(ColorProvider(Color(0xFF1E252B)))
                                        .padding(horizontal = 6.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = if (pace <= 0) "Ahead: ${pace}%" else "Burn: +${pace}%",
                                        style = TextStyle(
                                            color = ColorProvider(if (pace <= 0) Color(0xFF5CE196) else Color(0xFFFFB951)),
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

class QuotaCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaCompactWidget()
}
