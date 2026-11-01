package com.aiquotamonitor.domain

import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.domain.calculator.HorizonSimulator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HorizonSimulatorTest {

    @Test
    fun testZeroOffsetReturnsIdentical() {
        val baseSnapshot = SeedData.createSeedSnapshot()
        val simulated = HorizonSimulator.project(baseSnapshot, 0.0)

        assertEquals(
            baseSnapshot.bottleneckBucket?.remainingPercent,
            simulated.bottleneckBucket?.remainingPercent
        )
    }

    @Test
    fun testOneHourProjectionBurnsQuota() {
        val baseSnapshot = SeedData.createSeedSnapshot()
        val argonBefore = baseSnapshot.findBucket("gemini-argon-wtu-weekly")!!
        val simulated = HorizonSimulator.project(baseSnapshot, 1.0)
        val argonAfter = simulated.findBucket("gemini-argon-wtu-weekly")!!

        // Expected burn: 0.39% in 1 hour -> 35.0 - 0.39 = 34.6%
        assertTrue("Remaining quota should decrease", argonAfter.remainingPercent < argonBefore.remainingPercent)
        assertEquals(34.6, argonAfter.remainingPercent, 0.1)
    }

    @Test
    fun testWindowResetRestoresFullQuota() {
        val baseSnapshot = SeedData.createSeedSnapshot()
        // In seed snapshot, Argon resets in ~3.3 hours. Projecting 5 hours forward should trigger reset!
        val simulated = HorizonSimulator.project(baseSnapshot, 5.0)
        val argonAfter = simulated.findBucket("gemini-argon-wtu-weekly")!!

        assertEquals(100.0, argonAfter.remainingPercent, 0.01)
        assertEquals(0.0, argonAfter.usedPercent, 0.01)
        assertEquals("healthy", argonAfter.status)
    }

    @Test
    fun testSmartPrimeLeaseCountsDown() {
        val baseSnapshot = SeedData.createSeedSnapshot()
        val beforeSeconds = baseSnapshot.smartPrime.grokTokenRemainingSeconds ?: 21054L
        val simulated = HorizonSimulator.project(baseSnapshot, 2.0)
        val afterSeconds = simulated.smartPrime.grokTokenRemainingSeconds!!

        // 2 hours = 7200 seconds
        assertEquals(beforeSeconds - 7200L, afterSeconds)
    }
}
