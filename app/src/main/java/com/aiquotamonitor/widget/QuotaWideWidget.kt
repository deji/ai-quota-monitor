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
import com.aiquotamonitor.domain.model.OrcaRoleReadiness
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.ui.MainActivity
import kotlinx.serialization.json.Json

private val widgetJson = Json { ignoreUnknownKeys = true }

class QuotaWideWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val prefs = PreferencesStore(context)
        val polarity = prefs.getPolarity()
        val cachedJson = prefs.getCachedSnapshotJson()
        val snapshot = if (!cachedJson.isNullOrBlank()) {
            try {
                widgetJson.decodeFromString<QuotaSnapshot>(cachedJson)
            } catch (_: Exception) {
                SeedData.createSeedSnapshot()
            }
        } else {
            SeedData.createSeedSnapshot()
        }
        val polarityTag = if (polarity == QuotaPolarity.REMAINING) "REM" else "USED"

        provideContent {
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
                                text = "ORCA STAGE DECK • $polarityTag",
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

                        // 3-Column Roles Row
                        Row(
                            modifier = GlanceModifier.fillMaxWidth()
                        ) {
                            snapshot.orcaRoles.forEachIndexed { index, role ->
                                GlanceRoleCard(
                                    role = role,
                                    polarity = polarity,
                                    modifier = GlanceModifier.defaultWeight()
                                )
                                if (index < snapshot.orcaRoles.lastIndex) {
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
private fun GlanceRoleCard(
    role: OrcaRoleReadiness,
    polarity: QuotaPolarity,
    modifier: GlanceModifier
) {
    val displayPercent = role.displayPercent(polarity)
    val percentColor = when {
        displayPercent <= 20.0 -> Color(0xFFFFB4AB)
        displayPercent <= 40.0 -> Color(0xFFFFB951)
        else -> Color(0xFF5CE196)
    }

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
                text = role.role,
                style = TextStyle(
                    color = ColorProvider(Color(0xFF94A3B8)),
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Spacer(modifier = GlanceModifier.height(4.dp))

            Text(
                text = "${displayPercent.toInt()}%",
                style = TextStyle(
                    color = ColorProvider(percentColor),
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold
                )
            )

            Text(
                text = role.label,
                maxLines = 1,
                style = TextStyle(
                    color = ColorProvider(Color(0xFFE2E8F0)),
                    fontSize = 10.sp
                )
            )
        }
    }
}

class QuotaWideWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWideWidget()
}
