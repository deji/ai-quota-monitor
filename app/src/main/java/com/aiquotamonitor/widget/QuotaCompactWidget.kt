package com.aiquotamonitor.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.Image
import androidx.glance.ImageProvider
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
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
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

private val widgetJson = Json { ignoreUnknownKeys = true }

/**
 * 2x2 Quad Micro-Bento grid: the four most critical quota pools, one micro-card
 * per cell, each with a reset countdown and a polarity-aware health micro-bar.
 */
class QuotaCompactWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read preferences inside provideContent so updateAll() during an active
            // Glance session re-evaluates the selection instead of reusing stale values.
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

            val nowEpochMs = System.currentTimeMillis()
            val items = snapshot.resolveCompactCriticalBuckets(
                hiddenBucketIds = prefs.getHiddenBucketIds(),
                pinnedBucketId = prefs.getPinnedBucketId(),
                compactBucketId = prefs.getCompactWidgetBucketId()
            )

            val polarityTag = if (polarity == QuotaPolarity.REMAINING) "REM" else "USED"
            val sourceSuffix = if (isDemo) " • DEMO" else ""
            val ageLabel = snapshot.formatAgeShort(nowEpochMs).ifBlank { "LIVE" }
            val ageColor = if (snapshot.isStale) Color(0xFFF59E0B) else Color(0xFF5CE196)
            val hasCachedSnapshot = !cachedJson.isNullOrBlank()

            GlanceTheme {
                Box(
                    modifier = GlanceModifier
                        .fillMaxSize()
                        .cornerRadius(24.dp)
                        .background(ColorProvider(Color(0xFF11161A)))
                        .padding(12.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.TopStart
                ) {
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "CRITICAL POOLS • $polarityTag$sourceSuffix",
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight(),
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF94A3B8)),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = ageLabel,
                                maxLines = 1,
                                style = TextStyle(
                                    color = ColorProvider(ageColor),
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Spacer(modifier = GlanceModifier.height(6.dp))

                        if (items.isEmpty()) {
                            CompactEmptyState(
                                message = if (hasCachedSnapshot) "All models hidden" else "Tap to sync quotas"
                            )
                        } else {
                            Column(
                                modifier = GlanceModifier.fillMaxSize(),
                                verticalAlignment = Alignment.Top
                            ) {
                                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                                    CompactBentoCell(
                                        item = items.getOrNull(0),
                                        polarity = polarity,
                                        nowEpochMs = nowEpochMs,
                                        modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                                    )
                                    Spacer(modifier = GlanceModifier.width(4.dp))
                                    CompactBentoCell(
                                        item = items.getOrNull(1),
                                        polarity = polarity,
                                        nowEpochMs = nowEpochMs,
                                        modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                                    )
                                }
                                Spacer(modifier = GlanceModifier.height(4.dp))
                                Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                                    CompactBentoCell(
                                        item = items.getOrNull(2),
                                        polarity = polarity,
                                        nowEpochMs = nowEpochMs,
                                        modifier = GlanceModifier.defaultWeight().fillMaxHeight()
                                    )
                                    Spacer(modifier = GlanceModifier.width(4.dp))
                                    CompactBentoCell(
                                        item = items.getOrNull(3),
                                        polarity = polarity,
                                        nowEpochMs = nowEpochMs,
                                        modifier = GlanceModifier.defaultWeight().fillMaxHeight()
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

@Composable
private fun CompactEmptyState(message: String) {
    Column(modifier = GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = "--",
            style = TextStyle(
                color = ColorProvider(Color(0xFF94A3B8)),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
        )
        Text(
            text = message,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(Color(0xFF94A3B8)),
                fontSize = 9.sp
            )
        )
    }
}

@Composable
private fun CompactBentoCell(
    item: WidgetQuotaItem?,
    polarity: QuotaPolarity,
    nowEpochMs: Long,
    modifier: GlanceModifier
) {
    val displayPercent = item?.displayPercent(polarity) ?: Double.NaN
    val isUnavailable = displayPercent.isNaN()
    val healthColor = widgetHealthColor(displayPercent, polarity)
    val countdown = item
        ?.formatResetCountdown(nowEpochMs = nowEpochMs, includeMinutesUnderDay = false)
        ?.ifBlank { "—" }
        ?: "—"
    val pctText = if (isUnavailable) "--%" else "${displayPercent.roundToInt()}%"

    Column(
        modifier = modifier
            .cornerRadius(10.dp)
            .background(ColorProvider(Color(0xFF1A252A)))
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = item?.shortHeaderLabel ?: "--",
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = ColorProvider(Color(0xFF94A3B8)),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = countdown,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(healthColor),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }

        Spacer(modifier = GlanceModifier.height(1.dp))

        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pctText,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(healthColor),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = item?.shortWindowLabel.orEmpty(),
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(Color(0xFF64748B)),
                    fontSize = 8.sp
                )
            )
        }

        Spacer(modifier = GlanceModifier.height(3.dp))

        Image(
            provider = ImageProvider(createProgressBarBitmap(displayPercent, healthColor)),
            contentDescription = null,
            modifier = GlanceModifier.fillMaxWidth().height(4.dp)
        )
    }
}

class QuotaCompactWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaCompactWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        WidgetSyncScheduler.ensurePeriodicSync(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // Only the periodic request belongs here; a one-time enqueue would retrigger onUpdate.
        WidgetSyncScheduler.ensurePeriodicSync(context)
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        WidgetSyncScheduler.cancelPeriodicSyncIfNoWidgets(context)
    }
}
