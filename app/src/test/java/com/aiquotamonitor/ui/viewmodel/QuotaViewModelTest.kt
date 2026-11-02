package com.aiquotamonitor.ui.viewmodel

import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.QuotaRepository
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuotaViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var preferencesStore: PreferencesStore

    private class FakeQuotaRepository(
        initialSettings: SyncSettings = SyncSettings()
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

        var refreshCalled = false
        var testConnectionCalled = false
        var lastForceTailscale: Boolean = false
        var lastIsManual: Boolean = false
        var shouldFailRefresh: Boolean = false
        var refreshErrorMessage: String? = null

        override suspend fun refresh(forceTailscale: Boolean, isManual: Boolean): Result<QuotaSnapshot> {
            refreshCalled = true
            lastForceTailscale = forceTailscale
            lastIsManual = isManual
            if (shouldFailRefresh) {
                val ex = IOException(refreshErrorMessage ?: "Refresh error")
                if (forceTailscale) {
                    _connectionStateFlow.value = ConnectionState.OFFLINE
                    _syncErrorFlow.value = ex.message
                    return Result.failure(ex)
                }
                _connectionStateFlow.value = ConnectionState.OFFLINE
                _syncErrorFlow.value = ex.message
                return Result.success(_snapshotFlow.value)
            }
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
            return Result.success(_snapshotFlow.value)
        }

        override suspend fun primeGrok(): Result<SmartPrimeStatus> =
            Result.success(_snapshotFlow.value.smartPrime)

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

        override fun setSnapshotForSimulation(simulated: QuotaSnapshot) {
            _snapshotFlow.value = simulated
        }

        override fun resetSimulation() {}

        fun setConnectionState(state: ConnectionState) {
            _connectionStateFlow.value = state
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
}
