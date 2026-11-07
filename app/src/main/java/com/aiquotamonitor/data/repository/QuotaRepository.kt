package com.aiquotamonitor.data.repository

import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import kotlinx.coroutines.flow.StateFlow

interface QuotaRepository {
    val snapshotFlow: StateFlow<QuotaSnapshot>
    val connectionStateFlow: StateFlow<ConnectionState>
    val settingsFlow: StateFlow<SyncSettings>
    val syncErrorFlow: StateFlow<String?>
    val hiddenBucketIdsFlow: StateFlow<Set<String>>
    val pinnedBucketIdFlow: StateFlow<String?>

    /** True only while a background/foreground auto-sync tick is in flight. */
    val isAutoSyncingFlow: StateFlow<Boolean>

    /** Epoch millis of the last sync attempt (successful or failed), or 0 when none yet. */
    val lastSyncAttemptEpochMsFlow: StateFlow<Long>

    suspend fun refresh(forceTailscale: Boolean = false, isManual: Boolean = false): Result<QuotaSnapshot>
    suspend fun primeGrok(): Result<SmartPrimeStatus>
    suspend fun updateActiveHours(start: String, end: String): Result<QuotaSnapshot>
    suspend fun updateSettings(settings: SyncSettings)
    suspend fun saveSettings(settings: SyncSettings): Result<QuotaSnapshot>
    suspend fun testConnection(overrideSettings: SyncSettings? = null): Result<QuotaSnapshot>
    suspend fun setHiddenBuckets(hiddenIds: Set<String>)
    suspend fun toggleBucketVisibility(bucketId: String)
    suspend fun setPinnedBucket(bucketId: String?)
    fun setSnapshotForSimulation(simulated: QuotaSnapshot)
    fun resetSimulation()
}
