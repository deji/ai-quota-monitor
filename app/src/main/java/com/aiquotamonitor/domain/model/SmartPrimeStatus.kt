package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class SmartPrimeStatus(
    val policy: String = "active_hours_or_manual",
    val activeHoursStart: String = "08:00",
    val activeHoursEnd: String = "22:00",
    val grokTokenExpiresAt: String? = null,
    val grokTokenRemainingSeconds: Long? = null,
    val lastPrimedAt: String? = null
)
