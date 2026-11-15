package com.aiquotamonitor.widget

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
import com.aiquotamonitor.domain.model.ProviderWidgetGroup
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.WidgetQuotaItem
import com.aiquotamonitor.ui.MainActivity
import kotlin.math.roundToInt
import kotlinx.serialization.json.Json

private val widgetJson = Json { ignoreUnknownKeys = true }

/**
 * 4x3 three-column Provider Bento Deck: every visible bucket of every provider
 * grouped into its own column, each row carrying a percent, a reset countdown,
 * and a health micro-bar. Grok exposes its OAuth lease at the column bottom.
 */
class QuotaWideWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            // Read preferences inside provideContent so updateAll() during an active
            // Glance session re-evaluates the deck immediately.
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
            val groups = snapshot.resolveWideProviderGroups(prefs.getHiddenBucketIds())
            val activePoolCount = groups.sumOf { it.items.size }

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
                        .padding(10.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                    contentAlignment = Alignment.TopStart
                ) {
                    Column(modifier = GlanceModifier.fillMaxSize()) {
                        Row(
                            modifier = GlanceModifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "PROVIDER QUOTA DECK • $polarityTag$sourceSuffix",
                                maxLines = 1,
                                modifier = GlanceModifier.defaultWeight(),
                                style = TextStyle(
                                    color = ColorProvider(Color(0xFF94A3B8)),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                            Text(
                                text = "$activePoolCount Active Pools • $ageLabel",
                                maxLines = 1,
                                style = TextStyle(
                                    color = ColorProvider(ageColor),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            )
                        }

                        Spacer(modifier = GlanceModifier.height(6.dp))

                        if (groups.isEmpty()) {
                            Column(
                                modifier = GlanceModifier.fillMaxSize(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "--",
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF94A3B8)),
                                        fontSize = 24.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                )
                                Text(
                                    text = if (hasCachedSnapshot) {
                                        "All models hidden"
                                    } else {
                                        "Sync with Collector to populate Provider Deck"
                                    },
                                    maxLines = 1,
                                    style = TextStyle(
                                        color = ColorProvider(Color(0xFF94A3B8)),
                                        fontSize = 9.sp
                                    )
                                )
                            }
                        } else {
                            Row(modifier = GlanceModifier.fillMaxWidth().defaultWeight()) {
                                groups.forEachIndexed { index, group ->
                                    ProviderDeckColumn(
                                        group = group,
                                        polarity = polarity,
                                        nowEpochMs = nowEpochMs,
                                        modifier = GlanceModifier
                                            .defaultWeight()
                                            .fillMaxHeight()
                                    )
                                    if (index < groups.lastIndex) {
                                        Spacer(modifier = GlanceModifier.width(6.dp))
                                    }
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
private fun ProviderDeckColumn(
    group: ProviderWidgetGroup,
    polarity: QuotaPolarity,
    nowEpochMs: Long,
    modifier: GlanceModifier
) {
    val titleColor = when (group.providerId.lowercase()) {
        "agy" -> Color(0xFF38BDF8)
        "opencode", "opencode_go" -> Color(0xFFA78BFA)
        "grok" -> Color(0xFF5CE196)
        else -> Color(0xFF94A3B8)
    }

    Column(
        modifier = modifier
            .cornerRadius(12.dp)
            .background(ColorProvider(Color(0xFF1A252A)))
            .padding(6.dp),
        verticalAlignment = Alignment.Top
    ) {
        Text(
            text = group.title,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(titleColor),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        )

        group.items.forEach { item ->
            Spacer(modifier = GlanceModifier.height(3.dp))
            WideBucketBlock(item = item, polarity = polarity, nowEpochMs = nowEpochMs)
        }

        val leaseLabel = group.oauthLeaseLabel
        if (leaseLabel != null) {
            Spacer(modifier = GlanceModifier.defaultWeight())
            Row(
                modifier = GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "OAuth Lease",
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF64748B)),
                        fontSize = 8.sp
                    )
                )
                Text(
                    text = leaseLabel,
                    maxLines = 1,
                    style = TextStyle(
                        color = ColorProvider(Color(0xFF5CE196)),
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold
                    )
                )
            }
        }
    }
}

@Composable
private fun WideBucketBlock(
    item: WidgetQuotaItem,
    polarity: QuotaPolarity,
    nowEpochMs: Long
) {
    val displayPercent = item.displayPercent(polarity)
    val healthColor = widgetHealthColor(displayPercent, polarity)
    val pctText = if (displayPercent.isNaN()) "--%" else "${displayPercent.roundToInt()}%"
    val resetText = item.formatResetCountdown(
        nowEpochMs = nowEpochMs,
        includeMinutesUnderDay = true
    )

    Column(modifier = GlanceModifier.fillMaxWidth()) {
        // Two-line micro-header: the label owns the full column width so provider
        // bucket names never collide with the reset countdown.
        Text(
            text = item.shortWideRowLabel,
            maxLines = 1,
            style = TextStyle(
                color = ColorProvider(Color(0xFFE2E8F0)),
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium
            )
        )
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pctText,
                maxLines = 1,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(
                    color = ColorProvider(healthColor),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            )
            Text(
                text = resetText.ifBlank { "—" },
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(healthColor),
                    fontSize = 8.5.sp,
                    fontWeight = FontWeight.Bold
                )
            )
        }
    }

    Spacer(modifier = GlanceModifier.height(2.dp))

    Image(
        provider = ImageProvider(createProgressBarBitmap(displayPercent, healthColor)),
        contentDescription = null,
        modifier = GlanceModifier.fillMaxWidth().height(4.dp)
    )
}

class QuotaWideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWideWidget()
}
