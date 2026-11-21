package com.aiquotamonitor.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiquotamonitor.domain.calculator.ActivityAggregator
import com.aiquotamonitor.domain.calculator.ActivityBoardModel
import com.aiquotamonitor.domain.calculator.ActivityCell
import com.aiquotamonitor.domain.model.ActivityRange
import com.aiquotamonitor.domain.model.ActivityViewMode
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.defaultViewMode
import com.aiquotamonitor.ui.theme.QuotaAmber
import com.aiquotamonitor.ui.theme.QuotaCoral
import com.aiquotamonitor.ui.theme.QuotaEmerald
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Wildcard Hybrid historical display: a 10-dot LED Burst Spectrum for short
 * horizons and an Adaptive GitHub Quilt for multi-day horizons. Range changes
 * follow [ActivityRange.defaultViewMode]; the LED/Quilt toggle overrides freely.
 */
@Composable
fun ActivityBoardCard(
    snapshot: QuotaSnapshot,
    polarity: QuotaPolarity,
    hiddenBucketIds: Set<String>,
    selectedBucketId: String?,
    selectedProviderId: String?,
    modifier: Modifier = Modifier
) {
    var rangeName by rememberSaveable { mutableStateOf(ActivityRange.H24.name) }
    var viewModeName by rememberSaveable { mutableStateOf(ActivityViewMode.LED_SPECTRUM.name) }
    var selectedIndex by remember { mutableStateOf<Int?>(null) }

    val range = ActivityRange.values().firstOrNull { it.name == rangeName } ?: ActivityRange.H24
    val viewMode = ActivityViewMode.values().firstOrNull { it.name == viewModeName }
        ?: ActivityViewMode.LED_SPECTRUM

    val effectiveBucketId = selectedBucketId?.takeIf { it !in hiddenBucketIds }
    val board = remember(snapshot, range, effectiveBucketId, selectedProviderId, hiddenBucketIds) {
        ActivityAggregator.aggregate(
            snapshot = snapshot,
            range = range,
            selectedBucketId = effectiveBucketId,
            hiddenBucketIds = hiddenBucketIds,
            selectedProviderId = selectedProviderId
        )
    }

    val activeIndex = selectedIndex?.takeIf { it in board.cells.indices }
        ?: board.defaultSelectedIndex.takeIf { it in board.cells.indices }
    val selectedCell = activeIndex?.let { board.cells.getOrNull(it) }
    val scopeLabel = when {
        effectiveBucketId != null && selectedProviderId == null ->
            snapshot.findBucket(effectiveBucketId)?.shortHeaderLabel() ?: "Selected"
        selectedProviderId != null -> providerShortLabel(selectedProviderId)
        else -> "All Providers"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .border(
                width = 1.dp,
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                shape = RoundedCornerShape(26.dp)
            )
            .padding(14.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (viewMode == ActivityViewMode.LED_SPECTRUM) {
                        "Burst Spectrum"
                    } else {
                        "$scopeLabel Activity Quilt"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = when (viewMode) {
                        ActivityViewMode.LED_SPECTRUM -> "10-dot usage LED columns"
                        ActivityViewMode.GITHUB_QUILT -> "5-level provider intensity matrix"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontSize = 9.sp,
                    maxLines = 1
                )
            }

            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                ViewModeToggle(
                    selected = viewMode == ActivityViewMode.LED_SPECTRUM,
                    label = "LED",
                    isLed = true,
                    onClick = { viewModeName = ActivityViewMode.LED_SPECTRUM.name }
                )
                ViewModeToggle(
                    selected = viewMode == ActivityViewMode.GITHUB_QUILT,
                    label = "Quilt",
                    isLed = false,
                    onClick = { viewModeName = ActivityViewMode.GITHUB_QUILT.name }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            ActivityRange.values().forEach { option ->
                val selected = option == range
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .height(30.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(
                            if (selected) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                        .clickable {
                            rangeName = option.name
                            viewModeName = option.defaultViewMode().name
                            selectedIndex = null
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = option.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        fontSize = 10.sp
                    )
                }
            }
        }

        CalloutBar(
            cell = selectedCell,
            viewMode = viewMode,
            hasData = board.hasData
        )

        if (!board.hasData) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(120.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No history yet.\nActivity appears after the collector records readings.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        } else if (viewMode == ActivityViewMode.LED_SPECTRUM) {
            LedSpectrum(
                board = board,
                selectedProviderId = selectedProviderId
                    ?: providerForBucket(snapshot, effectiveBucketId),
                selectedIndex = activeIndex,
                onSelectIndex = { selectedIndex = it }
            )
        } else {
            ActivityQuilt(
                board = board,
                range = range,
                selectedProviderId = selectedProviderId
                    ?: providerForBucket(snapshot, effectiveBucketId),
                selectedIndex = activeIndex,
                onSelectIndex = { selectedIndex = it }
            )
        }

        if (viewMode == ActivityViewMode.GITHUB_QUILT) {
            QuiltLegend(
                providerId = selectedProviderId ?: providerForBucket(snapshot, effectiveBucketId)
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${board.activeCount} active · ${board.resetCount} resets · ${board.wallHitCount} wall hits",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp,
                maxLines = 1
            )
            Text(
                text = "peak ${board.peakDelta.roundToInt()}%",
                style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 8.sp
            )
        }
    }
}

@Composable
private fun ViewModeToggle(
    selected: Boolean,
    label: String,
    isLed: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .height(32.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer
                else Color.Transparent
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        Icon(
            imageVector = if (isLed) Icons.Default.Lightbulb else Icons.Default.GridOn,
            contentDescription = label,
            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(13.dp)
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
            else MaterialTheme.colorScheme.onSurfaceVariant,
            fontWeight = FontWeight.Bold,
            fontSize = 9.sp
        )
    }
}

@Composable
private fun CalloutBar(
    cell: ActivityCell?,
    viewMode: ActivityViewMode,
    hasData: Boolean
) {
    val text = when {
        !hasData -> "No history yet — activity appears after the first recorded readings."
        cell == null -> "Tap a column or quilt cell to inspect that interval."
        viewMode == ActivityViewMode.LED_SPECTRUM -> ledCallout(cell)
        else -> quiltCallout(cell)
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall.copy(fontFeatureSettings = "tnum"),
            color = MaterialTheme.colorScheme.onSurface,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun ledCallout(cell: ActivityCell): String {
    val parts = buildList {
        if (cell.agyDelta > 0.0) add("AGY -${cell.agyDelta.roundToInt()}%")
        if (cell.openCodeDelta > 0.0) add("OC -${cell.openCodeDelta.roundToInt()}%")
        if (cell.grokDelta > 0.0) add("GRK -${cell.grokDelta.roundToInt()}%")
    }
    val range = timeRangeLabel(cell)
    val body = if (parts.isEmpty()) "no burn" else parts.joinToString(" · ")
    val flags = buildString {
        if (cell.hasWallHit) append(" · Wall hit")
        if (cell.hasReset) append(" · Reset")
    }
    return "$range · $body$flags"
}

private fun quiltCallout(cell: ActivityCell): String {
    val flags = buildString {
        if (cell.hasWallHit) append(" · Wall hit")
        if (cell.hasReset) append(" · Reset")
    }
    if (cell.totalDelta <= 0.0 && flags.isEmpty()) {
        return "${cell.tooltipDateLabel} · no burn recorded"
    }
    return "-${cell.totalDelta.roundToInt()}% used on ${cell.tooltipDateLabel}$flags"
}

private fun timeRangeLabel(cell: ActivityCell): String {
    val start = parseInstantOrNull(cell.startAt)
    val end = parseInstantOrNull(cell.endAt)
    if (start == null || end == null) return cell.shortLabel
    val zone = ZoneId.systemDefault()
    return "${HHMM_FORMAT.format(start.atZone(zone))}–${HHMM_FORMAT.format(end.atZone(zone))}"
}

@Composable
private fun LedSpectrum(
    board: ActivityBoardModel,
    selectedProviderId: String?,
    selectedIndex: Int?,
    onSelectIndex: (Int) -> Unit
) {
    val visibleCells = if (board.cells.size > MAX_LED_COLUMNS) {
        board.cells.takeLast(MAX_LED_COLUMNS)
    } else {
        board.cells
    }
    val offset = board.cells.size - visibleCells.size
    val unlit = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.14f)
    val highlight = MaterialTheme.colorScheme.primary

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .pointerInput(visibleCells, offset) {
                detectTapGestures { tap ->
                    if (visibleCells.isEmpty()) return@detectTapGestures
                    val columnWidth = size.width.toFloat() / visibleCells.size
                    val local = (tap.x / columnWidth).toInt().coerceIn(0, visibleCells.lastIndex)
                    onSelectIndex(offset + local)
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            if (visibleCells.isEmpty()) return@Canvas
            val columnWidth = size.width / visibleCells.size
            val rowHeight = size.height / DOT_ROWS
            val dotRadius = min(columnWidth * 0.30f, rowHeight * 0.31f)

            visibleCells.forEachIndexed { localIndex, cell ->
                val centerX = columnWidth * (localIndex + 0.5f)
                val provider = selectedProviderId ?: cell.dominantProviderId
                val hue = providerHue(provider)
                val litCount = when {
                    selectedProviderId == null -> cell.agyDots + cell.openCodeDots + cell.grokDots
                    selectedProviderId == "agy" -> cell.agyDots
                    selectedProviderId == "opencode" -> cell.openCodeDots
                    selectedProviderId == "grok" -> cell.grokDots
                    else -> cell.agyDots + cell.openCodeDots + cell.grokDots
                }.coerceIn(0, DOT_ROWS)

                // Base track dots.
                repeat(DOT_ROWS) { row ->
                    val cy = size.height - rowHeight * (row + 0.5f)
                    drawCircle(
                        color = unlit,
                        radius = dotRadius,
                        center = Offset(centerX, cy)
                    )
                }

                // Provider dot stack, bottom-up (cyan -> violet -> emerald in All mode).
                if (selectedProviderId == null) {
                    drawProviderStack(
                        centerX = centerX,
                        rowHeight = rowHeight,
                        dotRadius = dotRadius,
                        startRow = 0,
                        count = cell.agyDots,
                        color = LedAgy
                    )
                    drawProviderStack(
                        centerX = centerX,
                        rowHeight = rowHeight,
                        dotRadius = dotRadius,
                        startRow = cell.agyDots,
                        count = cell.openCodeDots,
                        color = ChronoOpenCode
                    )
                    drawProviderStack(
                        centerX = centerX,
                        rowHeight = rowHeight,
                        dotRadius = dotRadius,
                        startRow = cell.agyDots + cell.openCodeDots,
                        count = cell.grokDots,
                        color = QuotaEmerald
                    )
                } else {
                    drawProviderStack(
                        centerX = centerX,
                        rowHeight = rowHeight,
                        dotRadius = dotRadius,
                        startRow = 0,
                        count = litCount,
                        color = hue
                    )
                }

                // Coral cap on a wall hit.
                if (cell.hasWallHit) {
                    drawCircle(
                        color = QuotaCoral,
                        radius = dotRadius * 1.12f,
                        center = Offset(centerX, rowHeight * 0.5f)
                    )
                }

                // Subtle reset marker beneath the column.
                if (cell.hasReset) {
                    drawRect(
                        color = QuotaAmber,
                        topLeft = Offset(centerX - columnWidth * 0.30f, size.height - 1.5.dp.toPx()),
                        size = Size(columnWidth * 0.60f, 1.5.dp.toPx())
                    )
                }

                if (selectedIndex == offset + localIndex) {
                    drawRoundRect(
                        color = highlight,
                        topLeft = Offset(centerX - columnWidth * 0.42f, 0f),
                        size = Size(columnWidth * 0.84f, size.height),
                        cornerRadius = CornerRadius(3.dp.toPx()),
                        style = Stroke(width = 1.4.dp.toPx())
                    )
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawProviderStack(
    centerX: Float,
    rowHeight: Float,
    dotRadius: Float,
    startRow: Int,
    count: Int,
    color: Color
) {
    repeat(count.coerceAtLeast(0)) { index ->
        val row = startRow + index
        if (row >= DOT_ROWS) return@repeat
        drawCircle(
            color = color.copy(alpha = 0.95f),
            radius = dotRadius,
            center = Offset(centerX, size.height - rowHeight * (row + 0.5f))
        )
    }
}

@Composable
private fun ActivityQuilt(
    board: ActivityBoardModel,
    range: ActivityRange,
    selectedProviderId: String?,
    selectedIndex: Int?,
    onSelectIndex: (Int) -> Unit
) {
    val rows: Int
    val columns: Int
    val quiltHeight = when (range) {
        ActivityRange.D30 -> {
            rows = 5
            columns = 7
            150.dp
        }
        ActivityRange.D7 -> {
            rows = 4
            columns = 7
            120.dp
        }
        ActivityRange.H24 -> {
            rows = 4
            columns = 6
            120.dp
        }
        ActivityRange.H5 -> {
            rows = 2
            columns = 10
            64.dp
        }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(quiltHeight),
        verticalArrangement = Arrangement.spacedBy(3.dp)
    ) {
        repeat(rows) { row ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                repeat(columns) { column ->
                    val cellIndex = if (range == ActivityRange.D7) {
                        column * rows + row
                    } else {
                        row * columns + column
                    }
                    val cell = board.cells.getOrNull(cellIndex)
                    QuiltCell(
                        cell = cell,
                        cellIndex = cellIndex,
                        selectedProviderId = selectedProviderId,
                        isSelected = selectedIndex == cellIndex,
                        onClick = { onSelectIndex(cellIndex) },
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                    )
                }
            }
        }
    }
}

@Composable
private fun QuiltCell(
    cell: ActivityCell?,
    cellIndex: Int,
    selectedProviderId: String?,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val provider = selectedProviderId ?: cell?.dominantProviderId
    val hue = providerHue(provider)
    val level = cell?.intensityLevel ?: 0
    val alpha = when (level) {
        0 -> 0f
        1 -> 0.20f
        2 -> 0.40f
        3 -> 0.65f
        else -> 0.95f
    }
    val background = if (level == 0) {
        MaterialTheme.colorScheme.surfaceContainerHigh
    } else {
        hue.copy(alpha = alpha)
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(background)
            .then(
                if (isSelected) {
                    Modifier.border(1.5.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp))
                } else {
                    Modifier
                }
            )
            .clickable(enabled = cell != null, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (cell?.hasReset == true) {
            Text(
                text = "↺",
                style = MaterialTheme.typography.labelSmall,
                color = if (level >= 3) Color.Black.copy(alpha = 0.72f)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.78f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold
            )
        }
        if (cell?.hasWallHit == true) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
                    .size(4.dp)
                    .clip(CircleShape)
                    .background(QuotaCoral)
            )
        }
    }
}

@Composable
private fun QuiltLegend(providerId: String?) {
    val legendHue = if (providerId != null) providerHue(providerId) else QuotaEmerald
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Less",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 8.sp
        )
        (0..4).forEach { level ->
            Box(
                modifier = Modifier
                    .padding(start = 3.dp)
                    .size(10.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(
                        if (level == 0) MaterialTheme.colorScheme.surfaceContainerHighest
                        else legendHue.copy(alpha = 0.15f + level * 0.20f)
                    )
            )
        }
        Text(
            text = "More",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            fontSize = 8.sp,
            modifier = Modifier.padding(start = 4.dp)
        )
    }
}

private val HHMM_FORMAT = DateTimeFormatter.ofPattern("HH:mm", Locale.US)
private const val DOT_ROWS = 10
private const val MAX_LED_COLUMNS = 30
