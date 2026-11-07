package com.aiquotamonitor.domain

import com.aiquotamonitor.domain.model.QuotaSnapshot
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
}
