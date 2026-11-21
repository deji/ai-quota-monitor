package com.aiquotamonitor.domain

import com.aiquotamonitor.domain.model.QuotaBucket
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.formatResetCountdownFromIso
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuotaSnapshotAgeTest {

    private val nowEpochMs = 1_700_000_000_000L

    private fun snapshotCapturedSecondsAgo(seconds: Long): QuotaSnapshot {
        val capturedAt = Instant.ofEpochMilli(nowEpochMs).minusSeconds(seconds).toString()
        return QuotaSnapshot(capturedAt = capturedAt)
    }

    @Test
    fun testBlankCapturedAtHasUnknownAge() {
        val snapshot = QuotaSnapshot(capturedAt = "   ")

        assertNull(snapshot.ageSeconds(nowEpochMs))
        assertEquals("", snapshot.formatAgeShort(nowEpochMs))
    }

    @Test
    fun testUnparseableCapturedAtHasUnknownAge() {
        val snapshot = QuotaSnapshot(capturedAt = "not-a-timestamp")

        assertNull(snapshot.ageSeconds(nowEpochMs))
        assertEquals("", snapshot.formatAgeShort(nowEpochMs))
    }

    @Test
    fun testFutureTimestampIsJustNow() {
        val snapshot = snapshotCapturedSecondsAgo(-60)

        assertEquals(-60L, snapshot.ageSeconds(nowEpochMs))
        assertEquals("Just now", snapshot.formatAgeShort(nowEpochMs))
    }

    @Test
    fun testUnderOneMinuteIsJustNow() {
        assertEquals("Just now", snapshotCapturedSecondsAgo(0).formatAgeShort(nowEpochMs))
        assertEquals("Just now", snapshotCapturedSecondsAgo(59).formatAgeShort(nowEpochMs))
    }

    @Test
    fun testMinutesBoundaries() {
        assertEquals("1m old", snapshotCapturedSecondsAgo(60).formatAgeShort(nowEpochMs))
        assertEquals("3m old", snapshotCapturedSecondsAgo(180).formatAgeShort(nowEpochMs))
        assertEquals("59m old", snapshotCapturedSecondsAgo(3599).formatAgeShort(nowEpochMs))
    }

    @Test
    fun testWholeHours() {
        assertEquals("1h old", snapshotCapturedSecondsAgo(3600).formatAgeShort(nowEpochMs))
        assertEquals("2h old", snapshotCapturedSecondsAgo(7200).formatAgeShort(nowEpochMs))
        assertEquals("3h old", snapshotCapturedSecondsAgo(10_800).formatAgeShort(nowEpochMs))
    }

    @Test
    fun testHoursAndMinutes() {
        assertEquals("1h 30m old", snapshotCapturedSecondsAgo(5400).formatAgeShort(nowEpochMs))
        assertEquals("2h 1m old", snapshotCapturedSecondsAgo(7260).formatAgeShort(nowEpochMs))
    }

    @Test
    fun testAgeSecondsUsesProvidedClock() {
        val snapshot = snapshotCapturedSecondsAgo(120)

        assertEquals(120L, snapshot.ageSeconds(nowEpochMs))
        assertEquals(180L, snapshot.ageSeconds(nowEpochMs + 60_000L))
    }

    // ---- Shared reset countdown (Issue #13 / APP-UX6) ----

    private fun resetsInSeconds(seconds: Long): String =
        Instant.ofEpochMilli(nowEpochMs).plusSeconds(seconds).toString()

    private fun seedBucket(resetsAt: String?): com.aiquotamonitor.domain.model.QuotaBucket =
        QuotaBucket(
            id = "gemini-5h",
            group = "Gemini Models",
            label = "Gemini Pool 5h",
            windowType = "rolling",
            windowDurationHours = 5,
            remainingPercent = 99.0,
            usedPercent = 1.0,
            resetsAt = resetsAt
        )

    @Test
    fun testCountdownIsBlankForMissingBlankOrMalformedResetsAt() {
        assertEquals("", formatResetCountdownFromIso(null, nowEpochMs))
        assertEquals("", formatResetCountdownFromIso("   ", nowEpochMs))
        assertEquals("", formatResetCountdownFromIso("not-a-timestamp", nowEpochMs))
        assertEquals("", seedBucket(null).formatResetCountdown(nowEpochMs))
        assertEquals("", seedBucket("13/11/2026").formatResetCountdown(nowEpochMs))
    }

    @Test
    fun testCountdownShowsNowOnceElapsed() {
        assertEquals("Now", formatResetCountdownFromIso(resetsInSeconds(0), nowEpochMs))
        assertEquals("Now", formatResetCountdownFromIso(resetsInSeconds(-90), nowEpochMs))
    }

    @Test
    fun testCountdownTruncatesSubMinuteAndSubHourWithoutCarry() {
        assertEquals("1m", formatResetCountdownFromIso(resetsInSeconds(30), nowEpochMs))
        // 59m 1s must never round up to "1h".
        assertEquals("59m", formatResetCountdownFromIso(resetsInSeconds(3541), nowEpochMs))
    }

    @Test
    fun testCountdownSubDayNeverEmitsZeroDays() {
        val twentyThreeHoursFiftyNineMinutesOneSecond = 23L * 3600L + 59L * 60L + 1L
        assertEquals(
            "23h 59m",
            formatResetCountdownFromIso(resetsInSeconds(twentyThreeHoursFiftyNineMinutesOneSecond), nowEpochMs)
        )
        assertEquals(
            "23h",
            formatResetCountdownFromIso(
                resetsInSeconds(twentyThreeHoursFiftyNineMinutesOneSecond),
                nowEpochMs,
                includeMinutesUnderDay = false
            )
        )
        assertEquals("4h", formatResetCountdownFromIso(resetsInSeconds(4L * 3600L), nowEpochMs))
    }

    @Test
    fun testCountdownWholeDaysNeverEmitZeroHours() {
        assertEquals("1d", formatResetCountdownFromIso(resetsInSeconds(24L * 3600L), nowEpochMs))
        // 24h 59m still truncates to a bare day, never "1d 0h".
        assertEquals("1d", formatResetCountdownFromIso(resetsInSeconds(24L * 3600L + 3540L), nowEpochMs))
        assertEquals("27d", formatResetCountdownFromIso(resetsInSeconds(27L * 86400L), nowEpochMs))
    }

    @Test
    fun testCountdownMultiDayKeepsHoursAndDropsMinutes() {
        val fiveDaysNineteenHoursFortyFiveMinutes =
            5L * 86400L + 19L * 3600L + 45L * 60L
        assertEquals(
            "5d 19h",
            formatResetCountdownFromIso(resetsInSeconds(fiveDaysNineteenHoursFortyFiveMinutes), nowEpochMs)
        )
        assertEquals(
            "5d 19h",
            formatResetCountdownFromIso(
                resetsInSeconds(fiveDaysNineteenHoursFortyFiveMinutes),
                nowEpochMs,
                includeMinutesUnderDay = false
            )
        )
    }
}
