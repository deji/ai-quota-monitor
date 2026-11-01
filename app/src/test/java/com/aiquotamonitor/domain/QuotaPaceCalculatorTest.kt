package com.aiquotamonitor.domain

import com.aiquotamonitor.domain.calculator.QuotaPaceCalculator
import org.junit.Assert.assertEquals
import org.junit.Test

class QuotaPaceCalculatorTest {

    @Test
    fun testElapsedPercentHalfway() {
        val durationHours = 10
        val durationMs = durationHours * 3600 * 1000L
        val nowMs = 1_000_000_000L
        val resetMs = nowMs + (durationMs / 2) // 5 hours remaining

        val elapsed = QuotaPaceCalculator.computeElapsedPercent(
            windowDurationHours = durationHours,
            resetsAtEpochMs = resetMs,
            nowEpochMs = nowMs
        )
        assertEquals(50.0, elapsed, 0.1)
    }

    @Test
    fun testElapsedPercentAtWindowBoundary() {
        val durationHours = 168
        val durationMs = durationHours * 3600 * 1000L
        val resetMs = 2_000_000_000L

        // Exactly at reset -> 100% elapsed
        val elapsedAtReset = QuotaPaceCalculator.computeElapsedPercent(
            windowDurationHours = durationHours,
            resetsAtEpochMs = resetMs,
            nowEpochMs = resetMs
        )
        assertEquals(100.0, elapsedAtReset, 0.1)

        // At window start -> 0% elapsed
        val elapsedAtStart = QuotaPaceCalculator.computeElapsedPercent(
            windowDurationHours = durationHours,
            resetsAtEpochMs = resetMs,
            nowEpochMs = resetMs - durationMs
        )
        assertEquals(0.0, elapsedAtStart, 0.1)
    }

    @Test
    fun testPaceDeltaUnderBudget() {
        // Used 65.0%, elapsed 98.1% -> delta = -33.1% (33.1% under linear pace)
        val delta = QuotaPaceCalculator.computeLinearPaceDelta(
            usedPercent = 65.0,
            elapsedPercent = 98.1
        )
        assertEquals(-33.1, delta, 0.05)
    }

    @Test
    fun testPaceDeltaAheadOfBudget() {
        // Used 80.0%, elapsed 50.0% -> delta = +30.0% (30% ahead of linear burn)
        val delta = QuotaPaceCalculator.computeLinearPaceDelta(
            usedPercent = 80.0,
            elapsedPercent = 50.0
        )
        assertEquals(30.0, delta, 0.05)
    }

    @Test
    fun testBurnRatePerHour() {
        // Used 65.0% over 164.8 hours -> 0.394%/hr
        val rate = QuotaPaceCalculator.computeBurnRatePerHour(
            usedPercent = 65.0,
            elapsedHours = 164.8
        )
        assertEquals(0.39, rate, 0.01)
    }

    @Test
    fun testProjectedRemainingAtReset() {
        // Remaining 35.0%, burn rate 0.39%/hr, remaining 3.2 hours
        // Burn before reset = 0.39 * 3.2 = 1.248%
        // Projected = 35.0 - 1.248 = 33.75% ~ 33.8%
        val projected = QuotaPaceCalculator.computeProjectedRemainingAtReset(
            remainingPercent = 35.0,
            burnRatePerHour = 0.39,
            remainingHours = 3.2
        )
        assertEquals(33.8, projected, 0.1)
    }
}
