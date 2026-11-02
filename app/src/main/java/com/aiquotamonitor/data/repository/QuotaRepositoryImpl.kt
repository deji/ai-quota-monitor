package com.aiquotamonitor.data.repository

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.data.remote.GistRelayClient
import com.aiquotamonitor.data.remote.TailscaleApiClient
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import com.aiquotamonitor.widget.QuotaCompactWidget
import com.aiquotamonitor.widget.QuotaWideWidget
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class QuotaRepositoryImpl(
    private val preferencesStore: PreferencesStore,
    private val tailscaleClient: TailscaleApiClient = TailscaleApiClient(),
    private val gistClient: GistRelayClient = GistRelayClient(),
    private val json: Json = Json { ignoreUnknownKeys = true },
    private val context: Context? = null
) : QuotaRepository {

    private val settingsMutex = Mutex()

    private var baseSnapshot: QuotaSnapshot
    private val _snapshotFlow: MutableStateFlow<QuotaSnapshot>
    override val snapshotFlow: StateFlow<QuotaSnapshot>

    private val _connectionStateFlow = MutableStateFlow(ConnectionState.DEMO_SEED)
    override val connectionStateFlow: StateFlow<ConnectionState> = _connectionStateFlow.asStateFlow()

    private val _syncErrorFlow = MutableStateFlow<String?>(null)
    override val syncErrorFlow: StateFlow<String?> = _syncErrorFlow.asStateFlow()

    override val settingsFlow: StateFlow<SyncSettings> = preferencesStore.settingsFlow
    override val hiddenBucketIdsFlow: StateFlow<Set<String>> = preferencesStore.hiddenBucketIdsFlow
    override val pinnedBucketIdFlow: StateFlow<String?> = preferencesStore.pinnedBucketIdFlow

    init {
        // Attempt to load cached snapshot from preferences or fallback to rich SeedData
        val cachedSnapshot = loadValidCachedSnapshot()
        val initialSnapshot = if (cachedSnapshot != null) {
            _connectionStateFlow.value = ConnectionState.OFFLINE
            cachedSnapshot
        } else {
            SeedData.createSeedSnapshot()
        }

        baseSnapshot = initialSnapshot
        _snapshotFlow = MutableStateFlow(initialSnapshot)
        snapshotFlow = _snapshotFlow.asStateFlow()
    }

    override suspend fun refresh(forceTailscale: Boolean, isManual: Boolean): Result<QuotaSnapshot> = withContext(Dispatchers.IO) {
        _connectionStateFlow.value = ConnectionState.SYNCING
        val settings = settingsFlow.value
        var tailscaleError: String? = null
        var gistError: String? = null

        // 1. Primary Path: Tailscale Live Daemon
        if (settings.tailscaleUrl.isNotBlank()) {
            try {
                val liveSnapshot = if (isManual) {
                    tailscaleClient.triggerRefresh(
                        baseUrl = settings.tailscaleUrl,
                        token = settings.tailscaleToken.ifBlank { null }
                    )
                } else {
                    tailscaleClient.fetchQuota(
                        baseUrl = settings.tailscaleUrl,
                        token = settings.tailscaleToken.ifBlank { null }
                    )
                }
                _syncErrorFlow.value = null
                applyNewSnapshot(liveSnapshot, ConnectionState.TAILSCALE_LIVE)
                return@withContext Result.success(liveSnapshot)
            } catch (e: Exception) {
                tailscaleError = e.message ?: "Connection failed"
                if (forceTailscale) {
                    _connectionStateFlow.value = ConnectionState.OFFLINE
                    _syncErrorFlow.value = "Tailscale refresh failed: $tailscaleError"
                    return@withContext Result.failure(e)
                }
                // Fall through to Cloud Relay fallback
            }
        }

        // 2. Fallback Path: Encrypted Cloud Relay (Secret GitHub Gist)
        if (settings.gistId.isNotBlank() && settings.gistAesKey.isNotBlank()) {
            try {
                val relaySnapshot = gistClient.fetchAndDecryptSnapshot(
                    gistId = settings.gistId,
                    aesKey = settings.gistAesKey,
                    githubToken = settings.githubToken.ifBlank { null }
                )
                _syncErrorFlow.value = null
                applyNewSnapshot(relaySnapshot, ConnectionState.CLOUD_RELAY_SYNCED)
                return@withContext Result.success(relaySnapshot)
            } catch (e: Exception) {
                gistError = e.message ?: "Decryption or network error"
                // Fall through to cached / seed snapshot
            }
        }

        // 3. Fallback: Local Cache or Demo Seed Data
        val errorParts = listOfNotNull(
            tailscaleError?.let { "Tailscale live daemon unreachable ($it)" },
            gistError?.let { "Cloud relay failed ($it)" }
        )
        val failureReason = if (errorParts.isNotEmpty()) {
            errorParts.joinToString("; ")
        } else if (!settings.hasUsableTransport) {
            "No sync endpoints configured"
        } else {
            "Sync endpoints unreachable"
        }

        val cached = loadValidCachedSnapshot()
        if (cached != null) {
            _syncErrorFlow.value = "$failureReason. Using cached snapshot."
            applyNewSnapshot(cached, ConnectionState.OFFLINE)
            return@withContext Result.success(cached)
        }

        val seed = SeedData.createSeedSnapshot()
        _syncErrorFlow.value = "$failureReason. Showing demo simulation data."
        applyNewSnapshot(seed, ConnectionState.DEMO_SEED)
        Result.success(seed)
    }

    override suspend fun primeGrok(): Result<SmartPrimeStatus> = withContext(Dispatchers.IO) {
        val settings = settingsFlow.value
        if (settings.tailscaleUrl.isBlank()) {
            // In demo / offline mode, simulate prime and clearly indicate offline demo status
            val current = _snapshotFlow.value
            val simulatedPrime = current.smartPrime.copy(
                grokTokenRemainingSeconds = 5400L, // 90 minutes
                lastPrimedAt = java.time.Instant.now().toString(),
                primed = true,
                reason = "offline_demo_primed"
            )
            val updatedSnapshot = current.copy(smartPrime = simulatedPrime)
            applyNewSnapshot(updatedSnapshot, _connectionStateFlow.value)
            _syncErrorFlow.value = "Offline simulation: Grok primed locally (no Tailscale daemon configured)"
            return@withContext Result.success(simulatedPrime)
        }

        try {
            val freshSnapshot = tailscaleClient.primeGrok(
                baseUrl = settings.tailscaleUrl,
                token = settings.tailscaleToken.ifBlank { null }
            )
            _syncErrorFlow.value = null
            applyNewSnapshot(freshSnapshot, ConnectionState.TAILSCALE_LIVE)
            if (!freshSnapshot.smartPrime.primed) {
                _syncErrorFlow.value = "Grok token not primed: ${freshSnapshot.smartPrime.reason}"
            }
            Result.success(freshSnapshot.smartPrime)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun updateActiveHours(start: String, end: String): Result<QuotaSnapshot> = withContext(Dispatchers.IO) {
        val updated = settingsMutex.withLock {
            val current = preferencesStore.settingsFlow.value
            val merged = current.copy(activeHoursStart = start, activeHoursEnd = end)
            preferencesStore.saveSettings(merged)
            merged
        }

        if (updated.tailscaleUrl.isNotBlank()) {
            try {
                val snapshot = tailscaleClient.updateActiveHours(
                    baseUrl = updated.tailscaleUrl,
                    token = updated.tailscaleToken.ifBlank { null },
                    start = start,
                    end = end
                )
                _syncErrorFlow.value = null
                applyNewSnapshot(snapshot, ConnectionState.TAILSCALE_LIVE)
                Result.success(snapshot)
            } catch (e: Exception) {
                Result.failure(e)
            }
        } else {
            Result.success(_snapshotFlow.value)
        }
    }

    override suspend fun saveSettings(settings: SyncSettings): Result<QuotaSnapshot> = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            val latest = preferencesStore.settingsFlow.value
            val merged = latest.copy(
                tailscaleUrl = settings.tailscaleUrl,
                tailscaleToken = settings.tailscaleToken,
                gistId = settings.gistId,
                gistAesKey = settings.gistAesKey,
                githubToken = settings.githubToken
            )
            preferencesStore.saveSettings(merged)
        }
        refresh(forceTailscale = false, isManual = true)
    }

    override suspend fun testConnection(overrideSettings: SyncSettings?): Result<QuotaSnapshot> = withContext(Dispatchers.IO) {
        val candidate = if (overrideSettings != null) {
            val latest = preferencesStore.settingsFlow.value
            latest.copy(
                tailscaleUrl = overrideSettings.tailscaleUrl,
                tailscaleToken = overrideSettings.tailscaleToken,
                gistId = overrideSettings.gistId,
                gistAesKey = overrideSettings.gistAesKey,
                githubToken = overrideSettings.githubToken
            )
        } else {
            preferencesStore.settingsFlow.value
        }

        if (!candidate.hasUsableTransport) {
            val error = "No sync endpoints configured to test"
            _syncErrorFlow.value = error
            return@withContext Result.failure(IllegalStateException(error))
        }

        val priorState = _connectionStateFlow.value
        _connectionStateFlow.value = ConnectionState.SYNCING
        val restoreState = if (priorState != ConnectionState.SYNCING) {
            priorState
        } else {
            if (loadValidCachedSnapshot() != null) ConnectionState.OFFLINE else ConnectionState.DEMO_SEED
        }
        var tailscaleError: String? = null

        if (candidate.tailscaleUrl.isNotBlank()) {
            try {
                val liveSnapshot = tailscaleClient.triggerRefresh(
                    baseUrl = candidate.tailscaleUrl,
                    token = candidate.tailscaleToken.ifBlank { null }
                )
                if (overrideSettings != null) {
                    settingsMutex.withLock {
                        val latest = preferencesStore.settingsFlow.value
                        preferencesStore.saveSettings(
                            latest.copy(
                                tailscaleUrl = candidate.tailscaleUrl,
                                tailscaleToken = candidate.tailscaleToken,
                                gistId = candidate.gistId,
                                gistAesKey = candidate.gistAesKey,
                                githubToken = candidate.githubToken
                            )
                        )
                    }
                }
                _syncErrorFlow.value = null
                applyNewSnapshot(liveSnapshot, ConnectionState.TAILSCALE_LIVE)
                return@withContext Result.success(liveSnapshot)
            } catch (e: Exception) {
                tailscaleError = e.message ?: "Connection failed"
            }
        }

        if (candidate.gistId.isNotBlank() && candidate.gistAesKey.isNotBlank()) {
            try {
                val relaySnapshot = gistClient.fetchAndDecryptSnapshot(
                    gistId = candidate.gistId,
                    aesKey = candidate.gistAesKey,
                    githubToken = candidate.githubToken.ifBlank { null }
                )
                if (overrideSettings != null) {
                    settingsMutex.withLock {
                        val latest = preferencesStore.settingsFlow.value
                        preferencesStore.saveSettings(
                            latest.copy(
                                tailscaleUrl = candidate.tailscaleUrl,
                                tailscaleToken = candidate.tailscaleToken,
                                gistId = candidate.gistId,
                                gistAesKey = candidate.gistAesKey,
                                githubToken = candidate.githubToken
                            )
                        )
                    }
                }
                _syncErrorFlow.value = null
                applyNewSnapshot(relaySnapshot, ConnectionState.CLOUD_RELAY_SYNCED)
                return@withContext Result.success(relaySnapshot)
            } catch (e: Exception) {
                val gistError = e.message ?: "Decryption or network error"
                val combinedError = if (tailscaleError != null) {
                    "Tailscale unreachable ($tailscaleError); Cloud relay failed ($gistError)"
                } else {
                    "Cloud relay failed ($gistError)"
                }
                _syncErrorFlow.value = combinedError
                _connectionStateFlow.value = restoreState
                return@withContext Result.failure(Exception(combinedError))
            }
        }

        val errorMsg = "Tailscale live daemon unreachable ($tailscaleError)"
        _syncErrorFlow.value = errorMsg
        _connectionStateFlow.value = restoreState
        Result.failure(IOException(errorMsg))
    }

    override suspend fun updateSettings(settings: SyncSettings) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            val latest = preferencesStore.settingsFlow.value
            val merged = latest.copy(
                tailscaleUrl = settings.tailscaleUrl,
                tailscaleToken = settings.tailscaleToken,
                gistId = settings.gistId,
                gistAesKey = settings.gistAesKey,
                githubToken = settings.githubToken
            )
            preferencesStore.saveSettings(merged)
        }
    }

    override suspend fun setHiddenBuckets(hiddenIds: Set<String>) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            preferencesStore.saveHiddenBucketIds(hiddenIds)
            val pinned = preferencesStore.getPinnedBucketId()
            if (pinned != null && hiddenIds.contains(pinned)) {
                preferencesStore.savePinnedBucketId(null)
            }
        }
        triggerWidgetUpdates()
    }

    override suspend fun toggleBucketVisibility(bucketId: String) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            val current = preferencesStore.getHiddenBucketIds()
            val updated = if (current.contains(bucketId)) {
                current - bucketId
            } else {
                current + bucketId
            }
            preferencesStore.saveHiddenBucketIds(updated)
            if (updated.contains(bucketId) && preferencesStore.getPinnedBucketId() == bucketId) {
                preferencesStore.savePinnedBucketId(null)
            }
        }
        triggerWidgetUpdates()
    }

    override suspend fun setPinnedBucket(bucketId: String?) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            preferencesStore.savePinnedBucketId(bucketId)
            if (bucketId != null) {
                val currentHidden = preferencesStore.getHiddenBucketIds()
                if (currentHidden.contains(bucketId)) {
                    preferencesStore.saveHiddenBucketIds(currentHidden - bucketId)
                }
            }
        }
        triggerWidgetUpdates()
    }

    override fun setSnapshotForSimulation(simulated: QuotaSnapshot) {
        _snapshotFlow.value = simulated
    }

    override fun resetSimulation() {
        _snapshotFlow.value = baseSnapshot
    }

    private fun loadValidCachedSnapshot(): QuotaSnapshot? {
        val cached = preferencesStore.getCachedSnapshotJson()
        if (cached.isNullOrBlank()) return null
        return try {
            json.decodeFromString<QuotaSnapshot>(cached)
        } catch (_: Exception) {
            null
        }
    }

    private fun applyNewSnapshot(snapshot: QuotaSnapshot, state: ConnectionState) {
        baseSnapshot = snapshot
        _snapshotFlow.value = snapshot
        _connectionStateFlow.value = state
        if (state == ConnectionState.TAILSCALE_LIVE || state == ConnectionState.CLOUD_RELAY_SYNCED) {
            try {
                preferencesStore.saveCachedSnapshotJson(json.encodeToString(snapshot))
            } catch (_: Exception) {}
        }

        triggerWidgetUpdates()
    }

    private fun triggerWidgetUpdates() {
        context?.let { ctx ->
            try {
                kotlinx.coroutines.CoroutineScope(Dispatchers.IO).launch {
                    QuotaCompactWidget().updateAll(ctx)
                    QuotaWideWidget().updateAll(ctx)
                }
            } catch (_: Exception) {}
        }
    }
}
