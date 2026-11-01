package com.aiquotamonitor.domain.model

import kotlinx.serialization.Serializable

@Serializable
data class ProviderQuota(
    val id: String,
    val displayName: String,
    val subtitle: String,
    val status: String,
    val buckets: List<QuotaBucket>
)
