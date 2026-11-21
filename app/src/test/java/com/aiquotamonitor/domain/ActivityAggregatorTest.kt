package com.aiquotamonitor.domain

import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.domain.calculator.ActivityAggregator
import com.aiquotamonitor.domain.model.ActivityBin
import com.aiquotamonitor.domain.model.ActivityRange
import com.aiquotamonitor.domain.model.ActivitySummary
import com.aiquotamonitor.domain.model.ActivityViewMode
import com.aiquotamonitor.domain.model.HistoryPoint
import com.aiquotamonitor.domain.model.ProviderQuota
import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.defaultViewMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ActivityAggregatorTest {

    @Test
    fun testRangeDefaultViewModeIsTheWildcardHybrid() {
        assertEquals(ActivityViewMode.LED_SPECTRUM, ActivityRange.H5.defaultViewMode())
        assertEquals(ActivityViewMode.LED_SPECTRUM, ActivityRange.H24.defaultViewMode())
        assertEquals(ActivityViewMode.GITHUB_QUILT, ActivityRange.D7.defaultViewMode())
        assertEquals(ActivityViewMode.GITHUB_QUILT, ActivityRange.D30.defaultViewMode())
    }

    @Test
    fun testAllFourSeedWindowsProduceCells() {
        val snapshot = SeedData.createSeedSnapshot()
        val expected = mapOf(
            ActivityRange.H5 to 20,
            ActivityRange.H24 to 24,
            ActivityRange.D7 to 28,
            ActivityRange.D30 to 35
        )

        expected.forEach { (range, count) ->
            val board = ActivityAggregator.aggregate(snapshot, range)
            assertEquals("$range cell count", count, board.cells.size)
            assertTrue("$range has seed activity", board.hasData)
            assertTrue("$range active cells", board.activeCount > 0)
            assertTrue("$range peak delta", board.peakDelta > 0.0)
        }
    }

    @Test
    fun testAllProviderVsSingleWedgeVsProviderFiltering() {
        val snapshot = snapshotWith(activityWithFirstBin())

        val all = ActivityAggregator.aggregate(snapshot, ActivityRange.H5)
        assertEquals(9.0, all.cells.first().totalDelta, 0.001)
        assertEquals(5.0, all.cells.first().agyDelta, 0.001)
        assertEquals(3.0, all.cells.first().openCodeDelta, 0.001)
        assertEquals(1.0, all.cells.first().grokDelta, 0.001)

        val selectedWedge = ActivityAggregator.aggregate(
            snapshot,
            ActivityRange.H5,
            selectedBucketId = "gemini-5h"
        )
        assertEquals(5.0, selectedWedge.cells.first().totalDelta, 0.001)
        assertEquals(0.0, selectedWedge.cells.first().openCodeDelta, 0.001)
        assertEquals("agy", selectedWedge.cells.first().dominantProviderId)

        val provider = ActivityAggregator.aggregate(
            snapshot,
            ActivityRange.H5,
            selectedProviderId = "opencode_go"
        )
        assertEquals(3.0, provider.cells.first().totalDelta, 0.001)
        assertEquals(0.0, provider.cells.first().agyDelta, 0.001)
        assertEquals("opencode", provider.cells.first().dominantProviderId)
    }

    @Test
    fun testHiddenBucketsAreExcluded() {
        val snapshot = snapshotWith(activityWithFirstBin())
        val board = ActivityAggregator.aggregate(
            snapshot,
            ActivityRange.H5,
            hiddenBucketIds = setOf("gemini-5h")
        )

        assertEquals(4.0, board.cells.first().totalDelta, 0.001)
        assertEquals(0.0, board.cells.first().agyDelta, 0.001)
    }

    @Test
    fun testIntensityAndLedDotsStayWithinBounds() {
        val snapshot = SeedData.createSeedSnapshot()
        ActivityRange.values().forEach { range ->
            ActivityAggregator.aggregate(snapshot, range).cells.forEach { cell ->
                assertTrue("intensity 0..4 for $range", cell.intensityLevel in 0..4)
                val dots = cell.agyDots + cell.openCodeDots + cell.grokDots
                assertTrue("dots <= 10 for $range", dots <= 10)
                if (cell.totalDelta > 0.0) {
                    assertTrue("active cells light a dot for $range", dots > 0)
                }
            }
        }
    }

    @Test
    fun testWallHitAndResetFlagsFollowSelectedScope() {
        val snapshot = snapshotWith(activityWithFirstBin())

        val selected = ActivityAggregator.aggregate(
            snapshot,
            ActivityRange.H5,
            selectedBucketId = "gemini-5h"
        )
        assertTrue(selected.cells.first().hasWallHit)
        assertTrue(selected.cells.first().hasReset)

        val hidden = ActivityAggregator.aggregate(
            snapshot,
            ActivityRange.H5,
            hiddenBucketIds = setOf("gemini-5h")
        )
        assertFalse(hidden.cells.first().hasWallHit)
        assertFalse(hidden.cells.first().hasReset)
    }

    @Test
    fun testDefaultSelectedIndexPointsAtPeakCell() {
        val bins = List(4) { index ->
            ActivityBin(
                startAt = "2026-11-18T0${index}:00:00Z",
                endAt = "2026-11-18T0${index + 1}:00:00Z",
                deltas = when (index) {
                    1 -> mapOf("gemini-5h" to 6.0)
                    3 -> mapOf("gemini-5h" to 14.0)
                    else -> emptyMap()
                }
            )
        }
        val snapshot = snapshotWith(
            ActivitySummary(window5h = bins)
        )

        val board = ActivityAggregator.aggregate(snapshot, ActivityRange.H5)
        assertEquals(3, board.defaultSelectedIndex)
        assertEquals(14.0, board.cells[board.defaultSelectedIndex].totalDelta, 0.001)
    }

    @Test
    fun testLegacyNullActivityFallsBackToHistory() {
        val history = listOf(
            HistoryPoint("2026-11-18T10:00:00Z", mapOf("gemini-5h" to 100.0)),
            HistoryPoint("2026-11-18T11:00:00Z", mapOf("gemini-5h" to 80.0)),
            HistoryPoint("2026-11-18T12:00:00Z", mapOf("gemini-5h" to 70.0))
        )
        val snapshot = snapshotWith(activity = null, history = history)

        val board = ActivityAggregator.aggregate(snapshot, ActivityRange.H5)
        assertEquals(2, board.cells.size)
        assertTrue(board.hasData)
        assertEquals(20.0, board.cells[0].totalDelta, 0.001)
        assertEquals(10.0, board.cells[1].totalDelta, 0.001)
    }

    @Test
    fun testMissingActivityAndHistoryIsAnHonestEmptyState() {
        val snapshot = snapshotWith(
            activity = ActivitySummary(),
            history = emptyList()
        )

        val board = ActivityAggregator.aggregate(snapshot, ActivityRange.D30)
        assertTrue(board.cells.isEmpty())
        assertFalse(board.hasData)
    }

    @Test
    fun test7dPeriodLabelsUseStartHourNotBinIndex() {
        val bins = listOf(
            ActivityBin("2026-11-18T12:00:00Z", "2026-11-18T18:00:00Z", deltas = mapOf("gemini-5h" to 5.0)),
            ActivityBin("2026-11-18T18:00:00Z", "2026-11-19T00:00:00Z", deltas = mapOf("gemini-5h" to 4.0)),
            ActivityBin("2026-11-19T00:00:00Z", "2026-11-19T06:00:00Z", deltas = mapOf("gemini-5h" to 3.0)),
            ActivityBin("2026-11-19T06:00:00Z", "2026-11-19T12:00:00Z", deltas = mapOf("gemini-5h" to 2.0))
        )
        val board = ActivityAggregator.aggregate(
            snapshot = snapshotWith(ActivitySummary(window7d = bins)),
            range = ActivityRange.D7,
            zone = java.time.ZoneOffset.UTC
        )
        assertEquals(listOf("PM", "Eve", "Night", "AM"), board.cells.map { it.shortLabel })
        assertEquals("Wed · PM", board.cells[0].tooltipDateLabel)
    }

    @Test
    fun test30dLabelsStayUtcMondayAlignedInWesternTimezones() {
        val mondayBin = ActivityBin(
            startAt = "2026-11-16T00:00:00Z",
            endAt = "2026-11-17T00:00:00Z",
            deltas = mapOf("gemini-5h" to 8.0)
        )
        val board = ActivityAggregator.aggregate(
            snapshot = snapshotWith(ActivitySummary(window30d = listOf(mondayBin))),
            range = ActivityRange.D30,
            zone = java.time.ZoneId.of("America/Los_Angeles")
        )
        assertEquals("Nov 16", board.cells.single().shortLabel)
        assertEquals("Mon Nov 16", board.cells.single().tooltipDateLabel)
    }

    private fun activityWithFirstBin(): ActivitySummary = ActivitySummary(
        window5h = listOf(
            ActivityBin(
                startAt = "2026-11-18T07:00:00Z",
                endAt = "2026-11-18T07:15:00Z",
                deltas = mapOf(
                    "gemini-5h" to 5.0,
                    "opencode-rolling" to 3.0,
                    "grok-weekly-credits" to 1.0
                ),
                wallHits = listOf("gemini-5h"),
                resets = listOf("gemini-5h")
            )
        ) + List(19) { index ->
            ActivityBin(
                startAt = "2026-11-18T07:${15 + index}:00Z",
                endAt = "2026-11-18T07:${16 + index}:00Z"
            )
        }
    )

    private fun snapshotWith(
        activity: ActivitySummary?,
        history: List<HistoryPoint> = emptyList()
    ): QuotaSnapshot = QuotaSnapshot(
        capturedAt = "2026-11-18T12:00:00Z",
        providers = listOf(
            ProviderQuota(
                id = "agy",
                displayName = "Antigravity (CLI)",
                subtitle = "Gemini 4 Argon, Gemini Pool & 3P",
                status = "ok",
                buckets = listOf(bucket("gemini-5h", "Gemini Pool 5h"))
            ),
            ProviderQuota(
                id = "opencode_go",
                displayName = "OpenCode Go",
                subtitle = "5h, Weekly & Monthly",
                status = "ok",
                buckets = listOf(bucket("opencode-rolling", "5-Hour Rolling"))
            ),
            ProviderQuota(
                id = "grok",
                displayName = "Grok",
                subtitle = "Build, Bot & Cursor Weekly",
                status = "ok",
                buckets = listOf(bucket("grok-weekly-credits", "Grok Weekly Credits"))
            )
        ),
        history = history,
        activity = activity
    )

    private fun bucket(id: String, label: String): QuotaBucket = QuotaBucket(
        id = id,
        group = "Test",
        label = label,
        windowType = "rolling",
        windowDurationHours = 5,
        remainingPercent = 50.0,
        usedPercent = 50.0
    )
}
