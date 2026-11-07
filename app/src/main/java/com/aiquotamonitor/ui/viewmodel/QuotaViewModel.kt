package com.aiquotamonitor.ui.viewmodel

import android.content.Context
import androidx.glance.appwidget.updateAll
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.QuotaRepository
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.calculator.HorizonSimulator
import com.aiquotamonitor.domain.model.QuotaPolarity
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.widget.QuotaCompactWidget
import com.aiquotamonitor.widget.QuotaWideWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class QuotaViewModel(
    private val repository: QuotaRepository,
    private val preferencesStore: PreferencesStore? = null,
    private val context: Context? = null,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ViewModel() {

    val connectionState: StateFlow<ConnectionState> = repository.connectionStateFlow
    val settings: StateFlow<SyncSettings> = repository.settingsFlow
    val syncError: StateFlow<String?> = repository.syncErrorFlow
    val hiddenBucketIds: StateFlow<Set<String>> = repository.hiddenBucketIdsFlow
    val pinnedBucketId: StateFlow<String?> = repository.pinnedBucketIdFlow
    val compactWidgetBucketId: StateFlow<String?> = repository.compactWidgetBucketIdFlow
    val wideWidgetBucketIds: StateFlow<List<String>> = repository.wideWidgetBucketIdsFlow
    val isAutoSyncing: StateFlow<Boolean> = repository.isAutoSyncingFlow

    val isSyncError: StateFlow<Boolean> = repository.syncErrorFlow
        .map { !it.isNullOrBlank() }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = !repository.syncErrorFlow.value.isNullOrBlank()
        )

    private val _polarity = MutableStateFlow(preferencesStore?.getPolarity() ?: QuotaPolarity.REMAINING)
    val polarity: StateFlow<QuotaPolarity> = _polarity.asStateFlow()

    private val _scrubOffsetHours = MutableStateFlow(0.0)
    val scrubOffsetHours: StateFlow<Double> = _scrubOffsetHours.asStateFlow()

    val isScrubbing: StateFlow<Boolean> = _scrubOffsetHours
        .map { it > 0.0 }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = false
        )

    private val _isPriming = MutableStateFlow(false)
    val isPriming: StateFlow<Boolean> = _isPriming.asStateFlow()

    private val _userMessage = MutableStateFlow<String?>(null)
    val userMessage: StateFlow<String?> = _userMessage.asStateFlow()

    /**
     * Base snapshot frozen at the moment the user starts scrubbing, so an in-flight
     * refresh that lands mid-drag cannot make the projected UI jump. Cleared as soon
     * as the scrub offset returns to 0.0 (the latest repository snapshot is then shown).
     */
    private var scrubFrozenSnapshot: QuotaSnapshot? = null

    // Active displayed snapshot: projects base snapshot forward in time when scrubbing!
    val activeSnapshot: StateFlow<QuotaSnapshot> = combine(
        repository.snapshotFlow,
        _scrubOffsetHours
    ) { latestSnapshot, offsetHours ->
        if (offsetHours > 0.0) {
            HorizonSimulator.project(scrubFrozenSnapshot ?: latestSnapshot, offsetHours)
        } else {
            latestSnapshot
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = repository.snapshotFlow.value
    )

    /**
     * Staleness follows the displayed snapshot so a frozen scrub base keeps its own
     * marker; UIs additionally OR this with the live 30s age clock to cross the
     * 7200s boundary without waiting for a repository emission.
     */
    val isStale: StateFlow<Boolean> = activeSnapshot
        .map { it.isStale }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = activeSnapshot.value.isStale
        )

    fun togglePolarity() {
        setPolarity(_polarity.value.toggle())
    }

    fun setPolarity(newPolarity: QuotaPolarity) {
        _polarity.value = newPolarity
        preferencesStore?.savePolarity(newPolarity)
        triggerWidgetUpdate()
    }

    fun setScrubOffset(hours: Double) {
        val coerced = hours.coerceIn(0.0, 24.0)
        if (coerced > 0.0) {
            if (scrubFrozenSnapshot == null) {
                scrubFrozenSnapshot = repository.snapshotFlow.value
            }
        } else {
            scrubFrozenSnapshot = null
        }
        _scrubOffsetHours.value = coerced
    }

    fun resetScrub() {
        scrubFrozenSnapshot = null
        _scrubOffsetHours.value = 0.0
    }

    fun refresh(forceTailscale: Boolean = false, isManual: Boolean = true) {
        viewModelScope.launch {
            val result = repository.refresh(forceTailscale = forceTailscale, isManual = isManual)
            // Background auto-sync must never surface or clear user-facing messages.
            if (isManual) {
                if (result.isFailure) {
                    _userMessage.value = "Refresh failed: ${result.exceptionOrNull()?.message}"
                } else {
                    val err = repository.syncErrorFlow.value
                    if (!err.isNullOrBlank()) {
                        _userMessage.value = err
                    } else {
                        _userMessage.value = "Quota refreshed successfully"
                    }
                }
            }
            triggerWidgetUpdate()
        }
    }

    /**
     * Single lifecycle entry point for foreground auto-sync. Call from
     * `repeatOnLifecycle(Lifecycle.State.STARTED)` only — never from a second
     * `ON_START` / `LifecycleResumeEffect` observer, which would race.
     *
     * A resume (loop start) performs an immediate tick only when the 30s debounce
     * has elapsed since the last attempt; the loop then polls every [pollIntervalMs].
     */
    suspend fun runForegroundSyncLoop(
        pollIntervalMs: Long = AUTO_SYNC_INTERVAL_MS,
        minResumeGapMs: Long = AUTO_SYNC_MIN_GAP_MS
    ) {
        if (isAutoSyncEligible() && isResumeGapElapsed(minResumeGapMs)) {
            performAutoSync()
        }
        while (currentCoroutineContext().isActive) {
            delay(pollIntervalMs)
            if (isAutoSyncEligible() && isResumeGapElapsed(minResumeGapMs)) {
                performAutoSync()
            }
        }
    }

    private fun isAutoSyncEligible(): Boolean =
        settings.value.hasUsableTransport &&
            _scrubOffsetHours.value == 0.0 &&
            !_isPriming.value &&
            connectionState.value != ConnectionState.SYNCING &&
            !isAutoSyncing.value

    private fun isResumeGapElapsed(minGapMs: Long): Boolean {
        val lastAttempt = repository.lastSyncAttemptEpochMsFlow.value
        return lastAttempt == 0L || clock() - lastAttempt >= minGapMs
    }

    private suspend fun performAutoSync() {
        repository.refresh(forceTailscale = false, isManual = false)
        triggerWidgetUpdate()
    }

    fun primeGrok() {
        viewModelScope.launch {
            _isPriming.value = true
            val isBlankEndpoint = settings.value.tailscaleUrl.isBlank()
            val result = repository.primeGrok()
            _isPriming.value = false
            if (result.isSuccess) {
                if (isBlankEndpoint) {
                    _userMessage.value = "Grok OAuth token primed (Offline Simulation — no Tailscale daemon endpoint configured)"
                } else {
                    _userMessage.value = "Grok OAuth token primed successfully via Tailscale daemon"
                }
            } else {
                _userMessage.value = "Prime failed: ${result.exceptionOrNull()?.message}"
            }
            triggerWidgetUpdate()
        }
    }

    fun saveSettings(newSettings: SyncSettings) {
        viewModelScope.launch {
            val result = repository.saveSettings(newSettings)
            if (result.isFailure) {
                _userMessage.value = "Save failed: ${result.exceptionOrNull()?.message}"
            } else {
                val err = repository.syncErrorFlow.value
                if (!err.isNullOrBlank()) {
                    _userMessage.value = err
                } else {
                    _userMessage.value = "Settings saved & synced successfully"
                }
            }
            triggerWidgetUpdate()
        }
    }

    fun testConnection(newSettings: SyncSettings? = null) {
        viewModelScope.launch {
            val result = repository.testConnection(newSettings)
            if (result.isFailure) {
                _userMessage.value = "Test connection failed: ${result.exceptionOrNull()?.message}"
            } else {
                val err = repository.syncErrorFlow.value
                if (!err.isNullOrBlank()) {
                    _userMessage.value = err
                } else {
                    _userMessage.value = "Connection test succeeded"
                }
            }
            triggerWidgetUpdate()
        }
    }

    fun updateActiveHours(start: String, end: String) {
        viewModelScope.launch {
            repository.updateActiveHours(start, end)
            triggerWidgetUpdate()
        }
    }

    fun setHiddenBuckets(hiddenIds: Set<String>) {
        viewModelScope.launch {
            repository.setHiddenBuckets(hiddenIds)
            triggerWidgetUpdate()
        }
    }

    fun toggleBucketVisibility(bucketId: String) {
        viewModelScope.launch {
            repository.toggleBucketVisibility(bucketId)
            triggerWidgetUpdate()
        }
    }

    fun setPinnedBucket(bucketId: String?) {
        viewModelScope.launch {
            repository.setPinnedBucket(bucketId)
            triggerWidgetUpdate()
        }
    }

    fun setCompactWidgetBucket(bucketId: String?) {
        viewModelScope.launch {
            repository.setCompactWidgetBucket(bucketId)
            triggerWidgetUpdate()
        }
    }

    fun setWideWidgetBuckets(bucketIds: List<String>) {
        viewModelScope.launch {
            repository.setWideWidgetBuckets(bucketIds)
            triggerWidgetUpdate()
        }
    }

    /**
     * Delegates directly to the repository so the next list is computed inside
     * `settingsMutex`; computing it from a `StateFlow` here would drop rapid taps.
     */
    fun toggleWideWidgetBucket(bucketId: String) {
        viewModelScope.launch {
            repository.toggleWideWidgetBucket(bucketId)
            triggerWidgetUpdate()
        }
    }

    fun clearUserMessage() {
        _userMessage.value = null
    }

    private fun triggerWidgetUpdate() {
        context?.let { ctx ->
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    QuotaCompactWidget().updateAll(ctx)
                    QuotaWideWidget().updateAll(ctx)
                } catch (_: Exception) {}
            }
        }
    }

    companion object {
        /** Foreground polling cadence while the app is STARTED. */
        const val AUTO_SYNC_INTERVAL_MS = 60_000L

        /** Minimum gap measured from the last sync attempt before a resume tick. */
        const val AUTO_SYNC_MIN_GAP_MS = 30_000L
    }

    class Factory(
        private val repository: QuotaRepository,
        private val preferencesStore: PreferencesStore? = null,
        private val context: Context? = null,
        private val clock: () -> Long = { System.currentTimeMillis() }
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return QuotaViewModel(repository, preferencesStore, context, clock) as T
        }
    }
}
