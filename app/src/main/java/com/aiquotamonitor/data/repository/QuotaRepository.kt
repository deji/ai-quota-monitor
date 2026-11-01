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
    fun updateSettings(settings: SyncSettings)
    fun setSnapshotForSimulation(simulated: QuotaSnapshot)
    fun resetSimulation()
}
