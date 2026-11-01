package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class OrcaRoleReadiness(
    val role: String,
    val label: String,
    val primaryBucketId: String,
    val remainingPercent: Double,
    val status: String
) {
    fun displayPercent(polarity: QuotaPolarity): Double = when (polarity) {
        QuotaPolarity.REMAINING -> remainingPercent
        QuotaPolarity.USED -> (100.0 - remainingPercent).coerceAtLeast(0.0)
    }
}
