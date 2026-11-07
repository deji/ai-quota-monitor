package com.aiquotamonitor.ui.viewmodel

import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.QuotaRepository
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.calculator.HorizonSimulator
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import java.io.IOException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var preferencesStore: PreferencesStore

    private class FakeQuotaRepository(
        initialSettings: SyncSettings = SyncSettings(),
        private val clock: () -> Long = { System.currentTimeMillis() }
    ) : QuotaRepository {
        private val _snapshotFlow = MutableStateFlow(SeedData.createSeedSnapshot())
        override val snapshotFlow: StateFlow<QuotaSnapshot> = _snapshotFlow.asStateFlow()

        private val _connectionStateFlow = MutableStateFlow(ConnectionState.DEMO_SEED)
        override val connectionStateFlow: StateFlow<ConnectionState> = _connectionStateFlow.asStateFlow()

        private val _settingsFlow = MutableStateFlow(initialSettings)
        override val settingsFlow: StateFlow<SyncSettings> = _settingsFlow.asStateFlow()

        private val _syncErrorFlow = MutableStateFlow<String?>(null)
        override val syncErrorFlow: StateFlow<String?> = _syncErrorFlow.asStateFlow()

        private val _hiddenBucketIdsFlow = MutableStateFlow(PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS)
        override val hiddenBucketIdsFlow: StateFlow<Set<String>> = _hiddenBucketIdsFlow.asStateFlow()

        private val _pinnedBucketIdFlow = MutableStateFlow<String?>(null)
        override val pinnedBucketIdFlow: StateFlow<String?> = _pinnedBucketIdFlow.asStateFlow()

        private val _compactWidgetBucketIdFlow = MutableStateFlow<String?>(null)
        override val compactWidgetBucketIdFlow: StateFlow<String?> = _compactWidgetBucketIdFlow.asStateFlow()

        private val _wideWidgetBucketIdsFlow = MutableStateFlow<List<String>>(emptyList())
        override val wideWidgetBucketIdsFlow: StateFlow<List<String>> = _wideWidgetBucketIdsFlow.asStateFlow()

        private val _isAutoSyncingFlow = MutableStateFlow(false)
        override val isAutoSyncingFlow: StateFlow<Boolean> = _isAutoSyncingFlow.asStateFlow()

        private val _lastSyncAttemptEpochMsFlow = MutableStateFlow(0L)
        override val lastSyncAttemptEpochMsFlow: StateFlow<Long> = _lastSyncAttemptEpochMsFlow.asStateFlow()

        var primeGate: CompletableDeferred<Unit>? = null
        var refreshCallCount = 0
        var refreshCalled = false
        var testConnectionCalled = false
        var lastForceTailscale: Boolean = false
        var lastIsManual: Boolean = false
        var shouldFailRefresh: Boolean = false
        var refreshErrorMessage: String? = null

        override suspend fun refresh(forceTailscale: Boolean, isManual: Boolean): Result<QuotaSnapshot> {
            refreshCallCount++
            refreshCalled = true
            lastForceTailscale = forceTailscale
            lastIsManual = isManual
            _lastSyncAttemptEpochMsFlow.value = clock()
            if (!isManual) _isAutoSyncingFlow.value = true
            return try {
                if (shouldFailRefresh) {
                    val ex = IOException(refreshErrorMessage ?: "Refresh error")
                    if (forceTailscale) {
                        _connectionStateFlow.value = ConnectionState.OFFLINE
                        _syncErrorFlow.value = ex.message
                        Result.failure(ex)
                    } else {
                        _connectionStateFlow.value = ConnectionState.OFFLINE
                        _syncErrorFlow.value = ex.message
                        Result.success(_snapshotFlow.value)
                    }
                } else {
                    val settings = _settingsFlow.value
                    if (settings.tailscaleUrl.isNotBlank()) {
                        _connectionStateFlow.value = ConnectionState.TAILSCALE_LIVE
                        _syncErrorFlow.value = null
                    } else if (settings.gistId.isNotBlank() && settings.gistAesKey.isNotBlank()) {
                        _connectionStateFlow.value = ConnectionState.CLOUD_RELAY_SYNCED
                        _syncErrorFlow.value = null
                    } else {
                        _connectionStateFlow.value = ConnectionState.DEMO_SEED
                        _syncErrorFlow.value = "No sync endpoints configured"
                    }
                    Result.success(_snapshotFlow.value)
                }
            } finally {
                if (!isManual) _isAutoSyncingFlow.value = false
            }
        }

        override suspend fun primeGrok(): Result<SmartPrimeStatus> {
            primeGate?.await()
            return Result.success(_snapshotFlow.value.smartPrime)
        }

        override suspend fun updateActiveHours(start: String, end: String): Result<QuotaSnapshot> {
            val latest = _settingsFlow.value
            _settingsFlow.value = latest.copy(activeHoursStart = start, activeHoursEnd = end)
            return Result.success(_snapshotFlow.value)
        }

        override suspend fun updateSettings(settings: SyncSettings) {
            val latest = _settingsFlow.value
            _settingsFlow.value = latest.copy(
                tailscaleUrl = settings.tailscaleUrl,
                tailscaleToken = settings.tailscaleToken,
                gistId = settings.gistId,
                gistAesKey = settings.gistAesKey,
                githubToken = settings.githubToken
            )
        }

        override suspend fun saveSettings(settings: SyncSettings): Result<QuotaSnapshot> {
            val latest = _settingsFlow.value
            _settingsFlow.value = latest.copy(
                tailscaleUrl = settings.tailscaleUrl,
                tailscaleToken = settings.tailscaleToken,
                gistId = settings.gistId,
                gistAesKey = settings.gistAesKey,
                githubToken = settings.githubToken
            )
            return refresh(forceTailscale = false, isManual = true)
        }

        override suspend fun testConnection(overrideSettings: SyncSettings?): Result<QuotaSnapshot> {
            testConnectionCalled = true
            val candidate = if (overrideSettings != null) {
                val latest = _settingsFlow.value
                latest.copy(
                    tailscaleUrl = overrideSettings.tailscaleUrl,
                    tailscaleToken = overrideSettings.tailscaleToken,
                    gistId = overrideSettings.gistId,
                    gistAesKey = overrideSettings.gistAesKey,
                    githubToken = overrideSettings.githubToken
                )
            } else {
                _settingsFlow.value
            }

            if (!candidate.hasUsableTransport) {
                val error = "No sync endpoints configured to test"
                _syncErrorFlow.value = error
                return Result.failure(IllegalStateException(error))
            }

            if (shouldFailRefresh) {
                val ex = IOException(refreshErrorMessage ?: "Probe connection failed")
                _syncErrorFlow.value = ex.message
                return Result.failure(ex)
            }

            _settingsFlow.value = candidate
            if (candidate.tailscaleUrl.isNotBlank()) {
                _connectionStateFlow.value = ConnectionState.TAILSCALE_LIVE
            } else {
                _connectionStateFlow.value = ConnectionState.CLOUD_RELAY_SYNCED
            }
            _syncErrorFlow.value = null
            return Result.success(_snapshotFlow.value)
        }

        override suspend fun setHiddenBuckets(hiddenIds: Set<String>) {
            _hiddenBucketIdsFlow.value = hiddenIds
            val pinned = _pinnedBucketIdFlow.value
            if (pinned != null && hiddenIds.contains(pinned)) {
                _pinnedBucketIdFlow.value = null
            }
        }

        override suspend fun toggleBucketVisibility(bucketId: String) {
            val current = _hiddenBucketIdsFlow.value
            val updated = if (current.contains(bucketId)) current - bucketId else current + bucketId
            _hiddenBucketIdsFlow.value = updated
            if (updated.contains(bucketId) && _pinnedBucketIdFlow.value == bucketId) {
                _pinnedBucketIdFlow.value = null
            }
        }

        override suspend fun setPinnedBucket(bucketId: String?) {
            _pinnedBucketIdFlow.value = bucketId
            if (bucketId != null) {
                val current = _hiddenBucketIdsFlow.value
                if (current.contains(bucketId)) {
                    _hiddenBucketIdsFlow.value = current - bucketId
                }
            }
        }

        override suspend fun setCompactWidgetBucket(bucketId: String?) {
            val sanitized = bucketId?.trim()?.takeIf { it.isNotEmpty() }
            _compactWidgetBucketIdFlow.value = sanitized
            sanitized?.let { unhideConcrete(it) }
        }

        override suspend fun setWideWidgetBuckets(bucketIds: List<String>) {
            val sanitized = bucketIds
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .distinct()
                .take(3)
            _wideWidgetBucketIdsFlow.value = sanitized
            sanitized.forEach { unhideConcrete(it) }
        }

        override suspend fun toggleWideWidgetBucket(bucketId: String) {
            val trimmed = bucketId.trim()
            if (trimmed.isEmpty()) return
            val current = _wideWidgetBucketIdsFlow.value
            val next = when {
                current.contains(trimmed) -> current - trimmed
                current.size < 3 -> (current + trimmed).distinct()
                else -> (current.take(2) + trimmed).distinct()
            }
            _wideWidgetBucketIdsFlow.value = next
            unhideConcrete(trimmed)
        }

        private fun unhideConcrete(bucketId: String) {
            if (bucketId.startsWith("role:", ignoreCase = true)) return
            val current = _hiddenBucketIdsFlow.value
            if (current.contains(bucketId)) {
                _hiddenBucketIdsFlow.value = current - bucketId
            }
        }

        override fun setSnapshotForSimulation(simulated: QuotaSnapshot) {
            _snapshotFlow.value = simulated
        }

        override fun resetSimulation() {}

        fun setConnectionState(state: ConnectionState) {
            _connectionStateFlow.value = state
        }

        fun setAutoSyncing(value: Boolean) {
            _isAutoSyncingFlow.value = value
        }

        fun setLastSyncAttempt(epochMs: Long) {
            _lastSyncAttemptEpochMsFlow.value = epochMs
        }

        fun setSnapshot(snapshot: QuotaSnapshot) {
            _snapshotFlow.value = snapshot
        }
    }

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        preferencesStore = PreferencesStore()
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun testSaveSettingsUpdatesRepositoryAndRefreshes() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository()
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        val newSettings = SyncSettings(
            tailscaleUrl = "http://100.115.92.2:8741",
            tailscaleToken = "test-token"
        )
        viewModel.saveSettings(newSettings)
        advanceUntilIdle()

        assertEquals("http://100.115.92.2:8741", viewModel.settings.value.tailscaleUrl)
        assertTrue(fakeRepo.refreshCalled)
        assertEquals(ConnectionState.TAILSCALE_LIVE, viewModel.connectionState.value)
        assertEquals("Settings saved & synced successfully", viewModel.userMessage.value)
        assertFalse(viewModel.isSyncError.value)
    }

    @Test
    fun testTestConnectionWithNewSettingsSavesAndRefreshes() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository()
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        val testSettings = SyncSettings(
            tailscaleUrl = "http://100.64.1.2:8741",
            tailscaleToken = "auth-token"
        )
        viewModel.testConnection(testSettings)
        advanceUntilIdle()

        assertEquals("http://100.64.1.2:8741", viewModel.settings.value.tailscaleUrl)
        assertTrue(fakeRepo.testConnectionCalled)
        assertEquals(ConnectionState.TAILSCALE_LIVE, viewModel.connectionState.value)
        assertEquals("Connection test succeeded", viewModel.userMessage.value)
        assertFalse(viewModel.isSyncError.value)
    }

    @Test
    fun testTestConnectionUnconfiguredEndpointsSurfacesError() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository()
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        // Neither endpoint configured
        viewModel.testConnection()
        advanceUntilIdle()

        assertTrue(viewModel.isSyncError.value)
        assertNotNull(viewModel.userMessage.value)
        assertTrue(viewModel.userMessage.value!!.contains("No sync endpoints configured"))
        assertEquals(ConnectionState.DEMO_SEED, viewModel.connectionState.value)
    }

    @Test
    fun testTestConnectionFailureSurfacesSyncError() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository().apply {
            shouldFailRefresh = true
            refreshErrorMessage = "Daemon unreachable at 8741"
        }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        val failSettings = SyncSettings(
            tailscaleUrl = "http://100.64.0.5:8741"
        )
        viewModel.testConnection(failSettings)
        advanceUntilIdle()

        assertTrue(viewModel.isSyncError.value)
        assertNotNull(viewModel.userMessage.value)
        assertTrue(viewModel.userMessage.value!!.contains("Daemon unreachable at 8741"))
    }

    @Test
    fun testSaveSettingsPreservesActiveHours() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository(
            initialSettings = SyncSettings(
                activeHoursStart = "09:30",
                activeHoursEnd = "21:30"
            )
        )
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        viewModel.saveSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8741",
                tailscaleToken = "token_xyz"
            )
        )
        advanceUntilIdle()

        assertEquals("http://100.115.92.2:8741", viewModel.settings.value.tailscaleUrl)
        assertEquals("token_xyz", viewModel.settings.value.tailscaleToken)
        assertEquals("09:30", viewModel.settings.value.activeHoursStart)
        assertEquals("21:30", viewModel.settings.value.activeHoursEnd)
    }

    @Test
    fun testTestConnectionPreservesActiveHours() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository(
            initialSettings = SyncSettings(
                activeHoursStart = "07:00",
                activeHoursEnd = "20:00"
            )
        )
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        viewModel.testConnection(
            SyncSettings(
                tailscaleUrl = "http://100.64.0.1:8741",
                tailscaleToken = "test_tok"
            )
        )
        advanceUntilIdle()

        assertEquals("http://100.64.0.1:8741", viewModel.settings.value.tailscaleUrl)
        assertEquals("test_tok", viewModel.settings.value.tailscaleToken)
        assertEquals("07:00", viewModel.settings.value.activeHoursStart)
        assertEquals("20:00", viewModel.settings.value.activeHoursEnd)
    }

    @Test
    fun testTestConnectionFailureDoesNotClobberSettings() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository(
            initialSettings = SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8741",
                tailscaleToken = "good_secret",
                activeHoursStart = "08:00",
                activeHoursEnd = "22:00"
            )
        ).apply {
            shouldFailRefresh = true
            refreshErrorMessage = "Failed to connect to host"
        }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        viewModel.testConnection(
            SyncSettings(
                tailscaleUrl = "http://100.99.99.99:8741",
                tailscaleToken = "bad_secret"
            )
        )
        advanceUntilIdle()

        // Persisted settings must not be clobbered by failed probe
        assertEquals("http://100.115.92.2:8741", viewModel.settings.value.tailscaleUrl)
        assertEquals("good_secret", viewModel.settings.value.tailscaleToken)
        assertTrue(viewModel.isSyncError.value)
        assertNotNull(viewModel.userMessage.value)
        assertTrue(viewModel.userMessage.value!!.contains("Failed to connect to host"))
    }

    @Test
    fun testCloudRelayConfiguredStateLogic() {
        val fullyConfigured = SyncSettings(gistId = "gist_123", gistAesKey = "key_abc")
        assertTrue(fullyConfigured.gistId.isNotBlank() && fullyConfigured.gistAesKey.isNotBlank())
        assertTrue(fullyConfigured.hasUsableTransport)

        val idOnly = SyncSettings(gistId = "gist_123", gistAesKey = "")
        assertFalse(idOnly.gistId.isNotBlank() && idOnly.gistAesKey.isNotBlank())
        assertTrue(idOnly.gistId.isNotBlank() || idOnly.gistAesKey.isNotBlank())
        assertFalse(idOnly.hasUsableTransport)

        val keyOnly = SyncSettings(gistId = "", gistAesKey = "key_abc")
        assertFalse(keyOnly.gistId.isNotBlank() && keyOnly.gistAesKey.isNotBlank())
        assertTrue(keyOnly.gistId.isNotBlank() || keyOnly.gistAesKey.isNotBlank())
        assertFalse(keyOnly.hasUsableTransport)

        val unconfigured = SyncSettings(gistId = "", gistAesKey = "")
        assertFalse(unconfigured.gistId.isNotBlank() || unconfigured.gistAesKey.isNotBlank())
        assertFalse(unconfigured.hasUsableTransport)
    }

    @Test
    fun testUnconfiguredSubtitleReflectsConnectionState() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository(
            initialSettings = SyncSettings()
        )
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.connectionState.collect {} }

        fakeRepo.setConnectionState(ConnectionState.DEMO_SEED)
        assertEquals(ConnectionState.DEMO_SEED, viewModel.connectionState.value)

        fakeRepo.setConnectionState(ConnectionState.OFFLINE)
        assertEquals(ConnectionState.OFFLINE, viewModel.connectionState.value)
    }

    @Test
    fun testHiddenBucketsFlowAndSetHiddenBuckets() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository()
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.hiddenBucketIds.collect {} }

        assertEquals(PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS, viewModel.hiddenBucketIds.value)

        viewModel.setHiddenBuckets(emptySet())
        advanceUntilIdle()
        assertTrue(viewModel.hiddenBucketIds.value.isEmpty())

        viewModel.setHiddenBuckets(setOf("gemini-5h"))
        advanceUntilIdle()
        assertEquals(setOf("gemini-5h"), viewModel.hiddenBucketIds.value)
    }

    @Test
    fun testToggleBucketVisibility() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository()
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.hiddenBucketIds.collect {} }

        assertTrue(viewModel.hiddenBucketIds.value.contains("3p-5h"))

        viewModel.toggleBucketVisibility("3p-5h")
        advanceUntilIdle()
        assertFalse(viewModel.hiddenBucketIds.value.contains("3p-5h"))

        viewModel.toggleBucketVisibility("gemini-argon-wtu-weekly")
        advanceUntilIdle()
        assertTrue(viewModel.hiddenBucketIds.value.contains("gemini-argon-wtu-weekly"))
    }

    @Test
    fun testPinnedBucketFlowAndSetPinnedBucket() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository()
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore)
        backgroundScope.launch(testDispatcher) { viewModel.pinnedBucketId.collect {} }

        assertEquals(null, viewModel.pinnedBucketId.value)

        viewModel.setPinnedBucket("grok-weekly-credits")
        advanceUntilIdle()
        assertEquals("grok-weekly-credits", viewModel.pinnedBucketId.value)

        viewModel.setPinnedBucket(null)
        advanceUntilIdle()
        assertEquals(null, viewModel.pinnedBucketId.value)
    }

    // ---- Foreground auto-sync (Issue #1 / APP-SYNC1) ----

    private fun configuredRepo(clock: () -> Long): FakeQuotaRepository =
        FakeQuotaRepository(
            initialSettings = SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"),
            clock = clock
        )

    @Test
    fun testForegroundLoopSkipsResumeWithinDebounceGap() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        fakeRepo.setLastSyncAttempt(testScheduler.currentTime - 10_000L)
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        val job = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 30_000L)
        }
        runCurrent()

        assertEquals("Resume tick must wait for the 30s debounce gap", 0, fakeRepo.refreshCallCount)
        job.cancelAndJoin()
    }

    @Test
    fun testForegroundLoopRunsResumeTickAndPollsEveryInterval() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        val job = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 30_000L)
        }
        runCurrent()

        assertEquals(1, fakeRepo.refreshCallCount)
        assertFalse("Foreground loop must issue non-manual refreshes", fakeRepo.lastIsManual)

        advanceTimeBy(60_000L)
        runCurrent()
        assertEquals(2, fakeRepo.refreshCallCount)

        job.cancelAndJoin()
    }

    @Test
    fun testForegroundLoopStopsAfterCancellation() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        val job = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 0L)
        }
        runCurrent()
        val callsBeforeCancel = fakeRepo.refreshCallCount

        job.cancelAndJoin()
        advanceTimeBy(300_000L)
        runCurrent()

        assertEquals(callsBeforeCancel, fakeRepo.refreshCallCount)
        assertTrue("Cancelled loop must not leave an auto-sync in flight", !viewModel.isAutoSyncing.value)
    }

    @Test
    fun testForegroundLoopPausesWhileScrubbing() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        viewModel.setScrubOffset(1.5)
        val scrubbingJob = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 0L)
        }
        runCurrent()
        assertEquals("Auto-sync must not run while the user scrubs", 0, fakeRepo.refreshCallCount)
        scrubbingJob.cancelAndJoin()

        viewModel.resetScrub()
        val resetJob = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 0L)
        }
        runCurrent()
        assertEquals(1, fakeRepo.refreshCallCount)
        resetJob.cancelAndJoin()
    }

    @Test
    fun testForegroundLoopPausesWhilePriming() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val gate = CompletableDeferred<Unit>()
        fakeRepo.primeGate = gate
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        viewModel.primeGrok()
        runCurrent()
        assertTrue(viewModel.isPriming.value)

        val job = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 0L)
        }
        runCurrent()
        assertEquals("Auto-sync must not run while priming", 0, fakeRepo.refreshCallCount)

        job.cancelAndJoin()
        gate.complete(Unit)
        runCurrent()
        assertFalse(viewModel.isPriming.value)
    }

    @Test
    fun testForegroundLoopPausesWhenTransportUnconfigured() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository(clock = { testScheduler.currentTime })
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        val job = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 0L)
        }
        runCurrent()
        assertEquals(0, fakeRepo.refreshCallCount)
        job.cancelAndJoin()
    }

    @Test
    fun testForegroundLoopPausesDuringManualSyncingState() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        fakeRepo.setConnectionState(ConnectionState.SYNCING)
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        val job = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 0L)
        }
        runCurrent()
        assertEquals(0, fakeRepo.refreshCallCount)
        job.cancelAndJoin()
    }

    @Test
    fun testForegroundLoopPausesWhileAutoSyncAlreadyInFlight() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        fakeRepo.setAutoSyncing(true)
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })

        val job = launch(testDispatcher) {
            viewModel.runForegroundSyncLoop(pollIntervalMs = 60_000L, minResumeGapMs = 0L)
        }
        runCurrent()
        assertEquals(0, fakeRepo.refreshCallCount)
        job.cancelAndJoin()
    }

    @Test
    fun testScrubFreezesBaseSnapshotUntilReset() = runTest(testDispatcher) {
        val fakeRepo = FakeQuotaRepository(clock = { testScheduler.currentTime })
        val original = SeedData.createSeedSnapshot().copy(host = "original-host")
        fakeRepo.setSnapshot(original)
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })
        backgroundScope.launch(testDispatcher) { viewModel.activeSnapshot.collect {} }
        runCurrent()

        viewModel.setScrubOffset(2.0)
        runCurrent()
        val frozenProjection = HorizonSimulator.project(original, 2.0)
        assertEquals(frozenProjection, viewModel.activeSnapshot.value)

        // Simulate an in-flight refresh landing mid-scrub.
        val refreshed = original.copy(host = "refreshed-host")
        fakeRepo.setSnapshot(refreshed)
        runCurrent()
        assertEquals(
            "Mid-scrub snapshot updates must not move the projected UI",
            frozenProjection,
            viewModel.activeSnapshot.value
        )

        viewModel.resetScrub()
        runCurrent()
        assertEquals("Resetting the scrubber must immediately show the latest snapshot", refreshed, viewModel.activeSnapshot.value)
    }

    @Test
    fun testAutoSyncNeverTouchesUserMessage() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })
        backgroundScope.launch(testDispatcher) { viewModel.isSyncError.collect {} }

        viewModel.refresh(isManual = false)
        advanceUntilIdle()
        assertNull("Background sync must never set a user message", viewModel.userMessage.value)

        viewModel.refresh(isManual = true)
        advanceUntilIdle()
        val manualMessage = viewModel.userMessage.value
        assertNotNull(manualMessage)

        viewModel.refresh(isManual = false)
        advanceUntilIdle()
        assertEquals("Background sync must never clear an existing user message", manualMessage, viewModel.userMessage.value)
    }

    @Test
    fun testIsAutoSyncingMirrorsRepositoryFlow() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })
        backgroundScope.launch(testDispatcher) { viewModel.isAutoSyncing.collect {} }

        assertFalse(viewModel.isAutoSyncing.value)
        fakeRepo.setAutoSyncing(true)
        runCurrent()
        assertTrue(viewModel.isAutoSyncing.value)
        fakeRepo.setAutoSyncing(false)
        runCurrent()
        assertFalse(viewModel.isAutoSyncing.value)
    }

    @Test
    fun testSetCompactWidgetBucketDelegatesAndEmits() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })
        backgroundScope.launch(testDispatcher) { viewModel.compactWidgetBucketId.collect {} }

        assertNull(viewModel.compactWidgetBucketId.value)

        viewModel.setCompactWidgetBucket("gemini-5h")
        advanceUntilIdle()
        assertEquals("gemini-5h", viewModel.compactWidgetBucketId.value)

        viewModel.setCompactWidgetBucket("  role:planner  ")
        advanceUntilIdle()
        assertEquals("role:planner", viewModel.compactWidgetBucketId.value)

        viewModel.setCompactWidgetBucket(null)
        advanceUntilIdle()
        assertNull(viewModel.compactWidgetBucketId.value)
    }

    @Test
    fun testToggleWideWidgetBucketDelegatesAndRotatesSlots() = runTest(testDispatcher) {
        val fakeRepo = configuredRepo { testScheduler.currentTime }
        val viewModel = QuotaViewModel(fakeRepo, preferencesStore, clock = { testScheduler.currentTime })
        backgroundScope.launch(testDispatcher) { viewModel.wideWidgetBucketIds.collect {} }

        assertTrue(viewModel.wideWidgetBucketIds.value.isEmpty())

        viewModel.toggleWideWidgetBucket("gemini-argon-wtu-weekly")
        advanceUntilIdle()
        viewModel.toggleWideWidgetBucket("opencode-rolling")
        advanceUntilIdle()
        viewModel.toggleWideWidgetBucket("grok-weekly-credits")
        advanceUntilIdle()
        assertEquals(
            listOf("gemini-argon-wtu-weekly", "opencode-rolling", "grok-weekly-credits"),
            viewModel.wideWidgetBucketIds.value
        )

        // A fourth selection replaces slot 3 while preserving the first two.
        viewModel.toggleWideWidgetBucket("gemini-5h")
        advanceUntilIdle()
        assertEquals(
            listOf("gemini-argon-wtu-weekly", "opencode-rolling", "gemini-5h"),
            viewModel.wideWidgetBucketIds.value
        )

        // Tapping a selected chip removes it.
        viewModel.toggleWideWidgetBucket("opencode-rolling")
        advanceUntilIdle()
        assertEquals(
            listOf("gemini-argon-wtu-weekly", "gemini-5h"),
            viewModel.wideWidgetBucketIds.value
        )

        viewModel.setWideWidgetBuckets(listOf(" role:PLANNER ", "role:PLANNER", ""))
        advanceUntilIdle()
        assertEquals(listOf("role:PLANNER"), viewModel.wideWidgetBucketIds.value)
    }
}
