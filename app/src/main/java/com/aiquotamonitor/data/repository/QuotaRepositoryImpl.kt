package com.aiquotamonitor.data.repository

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.data.remote.GistRelayClient
import com.aiquotamonitor.data.remote.TailscaleApiClient
import com.aiquotamonitor.domain.model.MAX_WIDE_WIDGET_SLOTS
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
    private val context: Context? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : QuotaRepository {

    private val settingsMutex = Mutex()

    /**
     * Serializes every network/snapshot writer (manual refresh, background auto-sync,
     * connection probes, Grok priming, and active-hours updates) so responses can never
     * be applied out of order. Auto-sync ticks skip immediately instead of queueing.
     */
    private val syncMutex = Mutex()

    /** Monotonic sequence used as defense-in-depth against stale response application. */
    private var syncGeneration = 0L

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
    override val compactWidgetBucketIdFlow: StateFlow<String?> = preferencesStore.compactWidgetBucketIdFlow
    override val wideWidgetBucketIdsFlow: StateFlow<List<String>> = preferencesStore.wideWidgetBucketIdsFlow

    private val _isAutoSyncingFlow = MutableStateFlow(false)
    override val isAutoSyncingFlow: StateFlow<Boolean> = _isAutoSyncingFlow.asStateFlow()

    private val _lastSyncAttemptEpochMsFlow = MutableStateFlow(0L)
    override val lastSyncAttemptEpochMsFlow: StateFlow<Long> = _lastSyncAttemptEpochMsFlow.asStateFlow()

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
        // Background ticks skip immediately rather than queueing behind an in-flight
        // manual operation (or another auto-sync tick). `tryLock` makes the skip
        // decision and the acquisition a single atomic operation, so a manual refresh
        // cannot slip in between the check and the lock.
        if (!isManual) {
            if (!syncMutex.tryLock()) {
                return@withContext Result.success(_snapshotFlow.value)
            }
        } else {
            syncMutex.lock()
        }
        try {
            val generation = ++syncGeneration
            _lastSyncAttemptEpochMsFlow.value = clock()
            if (isManual) {
                _connectionStateFlow.value = ConnectionState.SYNCING
            } else {
                // Background auto-sync must never disable settings buttons or corrupt
                // testConnection's prior-state restoration; use the dedicated flag.
                _isAutoSyncingFlow.value = true
            }
            try {
                performRefreshLocked(forceTailscale, isManual, generation)
            } finally {
                if (!isManual) {
                    _isAutoSyncingFlow.value = false
                }
            }
        } finally {
            syncMutex.unlock()
        }
    }

    private suspend fun performRefreshLocked(
        forceTailscale: Boolean,
        isManual: Boolean,
        generation: Long
    ): Result<QuotaSnapshot> {
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
                applyNewSnapshot(liveSnapshot, ConnectionState.TAILSCALE_LIVE, generation)
                return Result.success(liveSnapshot)
            } catch (e: Exception) {
                tailscaleError = e.message ?: "Connection failed"
                if (forceTailscale) {
                    _connectionStateFlow.value = ConnectionState.OFFLINE
                    _syncErrorFlow.value = "Tailscale refresh failed: $tailscaleError"
                    return Result.failure(e)
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
                applyNewSnapshot(relaySnapshot, ConnectionState.CLOUD_RELAY_SYNCED, generation)
                return Result.success(relaySnapshot)
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
            applyNewSnapshot(cached, ConnectionState.OFFLINE, generation)
            return Result.success(cached)
        }

        val seed = SeedData.createSeedSnapshot()
        _syncErrorFlow.value = "$failureReason. Showing demo simulation data."
        applyNewSnapshot(seed, ConnectionState.DEMO_SEED, generation)
        return Result.success(seed)
    }

    override suspend fun primeGrok(): Result<SmartPrimeStatus> = withContext(Dispatchers.IO) {
        syncMutex.withLock {
            val generation = ++syncGeneration
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
                applyNewSnapshot(updatedSnapshot, _connectionStateFlow.value, generation)
                _syncErrorFlow.value = "Offline simulation: Grok primed locally (no Tailscale daemon configured)"
                return@withContext Result.success(simulatedPrime)
            }

            try {
                _lastSyncAttemptEpochMsFlow.value = clock()
                val freshSnapshot = tailscaleClient.primeGrok(
                    baseUrl = settings.tailscaleUrl,
                    token = settings.tailscaleToken.ifBlank { null }
                )
                _syncErrorFlow.value = null
                applyNewSnapshot(freshSnapshot, ConnectionState.TAILSCALE_LIVE, generation)
                if (!freshSnapshot.smartPrime.primed) {
                    _syncErrorFlow.value = "Grok token not primed: ${freshSnapshot.smartPrime.reason}"
                }
                Result.success(freshSnapshot.smartPrime)
            } catch (e: Exception) {
                Result.failure(e)
            }
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
            syncMutex.withLock {
                val generation = ++syncGeneration
                _lastSyncAttemptEpochMsFlow.value = clock()
                try {
                    val snapshot = tailscaleClient.updateActiveHours(
                        baseUrl = updated.tailscaleUrl,
                        token = updated.tailscaleToken.ifBlank { null },
                        start = start,
                        end = end
                    )
                    _syncErrorFlow.value = null
                    applyNewSnapshot(snapshot, ConnectionState.TAILSCALE_LIVE, generation)
                    Result.success(snapshot)
                } catch (e: Exception) {
                    Result.failure(e)
                }
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
        _lastSyncAttemptEpochMsFlow.value = clock()
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

        var persistMergedSettings = false
        val result = syncMutex.withLock {
            val generation = ++syncGeneration
            _lastSyncAttemptEpochMsFlow.value = clock()
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
                    persistMergedSettings = overrideSettings != null
                    _syncErrorFlow.value = null
                    applyNewSnapshot(liveSnapshot, ConnectionState.TAILSCALE_LIVE, generation)
                    return@withLock Result.success(liveSnapshot)
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
                    persistMergedSettings = overrideSettings != null
                    _syncErrorFlow.value = null
                    applyNewSnapshot(relaySnapshot, ConnectionState.CLOUD_RELAY_SYNCED, generation)
                    return@withLock Result.success(relaySnapshot)
                } catch (e: Exception) {
                    val gistError = e.message ?: "Decryption or network error"
                    val combinedError = if (tailscaleError != null) {
                        "Tailscale unreachable ($tailscaleError); Cloud relay failed ($gistError)"
                    } else {
                        "Cloud relay failed ($gistError)"
                    }
                    _syncErrorFlow.value = combinedError
                    if (generation == syncGeneration) {
                        _connectionStateFlow.value = restoreState
                    }
                    return@withLock Result.failure(Exception(combinedError))
                }
            }

            val errorMsg = "Tailscale live daemon unreachable ($tailscaleError)"
            _syncErrorFlow.value = errorMsg
            if (generation == syncGeneration) {
                _connectionStateFlow.value = restoreState
            }
            Result.failure(IOException(errorMsg))
        }

        // Persist a successful draft probe only after releasing `syncMutex`, keeping
        // `settingsMutex` strictly disjoint from `syncMutex` (never nested) so the
        // lock order cannot invert and deadlock Save/Test against a refresh.
        if (persistMergedSettings) {
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

        result
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
            if (bucketId != null) {
                val currentHidden = preferencesStore.getHiddenBucketIds()
                if (currentHidden.contains(bucketId)) {
                    preferencesStore.saveHiddenBucketIds(currentHidden - bucketId)
                }
            }
            preferencesStore.savePinnedBucketId(bucketId)
        }
        triggerWidgetUpdates()
    }

    override suspend fun setCompactWidgetBucket(bucketId: String?) = withContext(Dispatchers.IO) {
        val sanitized = bucketId?.trim()?.takeIf { it.isNotEmpty() }
        settingsMutex.withLock {
            preferencesStore.saveCompactWidgetBucketId(sanitized)
            if (sanitized != null) {
                unhideConcreteBucketIds(listOf(sanitized))
            }
        }
        triggerWidgetUpdates()
    }

    override suspend fun setWideWidgetBuckets(bucketIds: List<String>) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            preferencesStore.saveWideWidgetBucketIds(bucketIds)
            unhideConcreteBucketIds(preferencesStore.getWideWidgetBucketIds())
        }
        triggerWidgetUpdates()
    }

    override suspend fun toggleWideWidgetBucket(bucketId: String) = withContext(Dispatchers.IO) {
        settingsMutex.withLock {
            val trimmed = bucketId.trim()
            if (trimmed.isNotEmpty()) {
                val current = preferencesStore.getWideWidgetBucketIds()
                val next = when {
                    current.contains(trimmed) -> current - trimmed
                    current.size < MAX_WIDE_WIDGET_SLOTS -> (current + trimmed).distinct()
                    else -> (current.take(MAX_WIDE_WIDGET_SLOTS - 1) + trimmed).distinct()
                }
                preferencesStore.saveWideWidgetBucketIds(next)
                unhideConcreteBucketIds(listOf(trimmed))
            }
        }
        triggerWidgetUpdates()
    }

    /**
     * Remove concrete bucket ids from the hidden set in the current [settingsMutex]
     * transaction. `role:` tokens are never bucket ids and must not touch visibility.
     */
    private fun unhideConcreteBucketIds(ids: Collection<String>) {
        val concreteIds = ids
            .filter { it.isNotBlank() && !it.startsWith("role:", ignoreCase = true) }
            .toSet()
        if (concreteIds.isEmpty()) return
        val currentHidden = preferencesStore.getHiddenBucketIds()
        val stillHidden = currentHidden - concreteIds
        if (stillHidden != currentHidden) {
            preferencesStore.saveHiddenBucketIds(stillHidden)
        }
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

    private fun applyNewSnapshot(snapshot: QuotaSnapshot, state: ConnectionState, generation: Long? = null) {
        // Never let an older in-flight response overwrite a newer committed snapshot.
        if (generation != null && generation != syncGeneration) return
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
