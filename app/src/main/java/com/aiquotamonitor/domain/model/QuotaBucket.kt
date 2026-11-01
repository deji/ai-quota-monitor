package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class QuotaBucket(
    val id: String,
    val group: String,
    val label: String,
    val windowType: String,
    val windowDurationHours: Int,
    val remainingPercent: Double,
    val usedPercent: Double,
    val resetsAt: String? = null,
    val elapsedPercent: Double = 0.0,
    val paceDeltaPercent: Double = 0.0,
    val burnRatePerHour: Double = 0.0,
    val projectedRemainingAtReset: Double = 0.0,
    val status: String = "healthy"
) {
    /**
     * Value depending on chosen polarity
     */
    fun displayPercent(polarity: QuotaPolarity): Double = when (polarity) {
        QuotaPolarity.REMAINING -> remainingPercent
        QuotaPolarity.USED -> usedPercent
    }

    /**
     * Hours until scheduled reset timestamp
     */
    val hoursUntilReset: Double?
        get() = try {
            val at = resetsAt ?: return null
            val instant = java.time.Instant.parse(at)
            val now = java.time.Instant.now()
            val duration = java.time.Duration.between(now, instant)
            if (duration.isNegative) 0.0 else duration.toMinutes() / 60.0
        } catch (_: Exception) {
            null
        }
}
