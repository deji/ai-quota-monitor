package com.aiquotamonitor.data.repository

import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import kotlinx.coroutines.flow.StateFlow

interface QuotaRepository {
    val snapshotFlow: StateFlow<QuotaSnapshot>
    val connectionStateFlow: StateFlow<ConnectionState>
    val settingsFlow: StateFlow<SyncSettings>
    val syncErrorFlow: StateFlow<String?>

    suspend fun refresh(forceTailscale: Boolean = false, isManual: Boolean = false): Result<QuotaSnapshot>
    suspend fun primeGrok(): Result<SmartPrimeStatus>
    suspend fun updateActiveHours(start: String, end: String): Result<QuotaSnapshot>
    suspend fun updateSettings(settings: SyncSettings)
    suspend fun saveSettings(settings: SyncSettings): Result<QuotaSnapshot>
    suspend fun testConnection(overrideSettings: SyncSettings? = null): Result<QuotaSnapshot>
    fun setSnapshotForSimulation(simulated: QuotaSnapshot)
    fun resetSimulation()
}
