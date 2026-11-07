package com.aiquotamonitor.data

import com.aiquotamonitor.data.local.PreferencesStore
import com.aiquotamonitor.data.local.SeedData
import com.aiquotamonitor.data.remote.GistRelayClient
import com.aiquotamonitor.data.remote.TailscaleApiClient
import com.aiquotamonitor.data.repository.ConnectionState
import com.aiquotamonitor.data.repository.QuotaRepositoryImpl
import com.aiquotamonitor.data.repository.SyncSettings
import com.aiquotamonitor.domain.model.QuotaSnapshot
import com.aiquotamonitor.domain.model.SmartPrimeStatus
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class QuotaRepositoryImplTest {

    private lateinit var preferencesStore: PreferencesStore

    @Before
    fun setUp() {
        preferencesStore = PreferencesStore()
    }

    private class FakeTailscaleClient(
        var mockSnapshot: QuotaSnapshot = SeedData.createSeedSnapshot(),
        var mockPrimeStatus: SmartPrimeStatus = SeedData.createSeedSnapshot().smartPrime,
        var shouldFailFetch: Boolean = false,
        var shouldFailRefresh: Boolean = false,
        var shouldFailPrime: Boolean = false,
        var shouldFailActiveHours: Boolean = false
    ) : TailscaleApiClient() {
        var fetchQuotaCalled = false
        var triggerRefreshCalled = false
        var primeGrokCalled = false
        var updateActiveHoursCalled = false
        var lastUpdatedStart: String? = null
        var lastUpdatedEnd: String? = null

        override fun fetchQuota(baseUrl: String, token: String?): QuotaSnapshot {
            fetchQuotaCalled = true
            if (shouldFailFetch) throw IOException("Tailscale fetch network error")
            return mockSnapshot
        }

        override fun triggerRefresh(baseUrl: String, token: String?): QuotaSnapshot {
            triggerRefreshCalled = true
            if (shouldFailRefresh) throw IOException("Tailscale refresh daemon error")
            return mockSnapshot
        }

        override fun primeGrok(baseUrl: String, token: String?): QuotaSnapshot {
            primeGrokCalled = true
            if (shouldFailPrime) throw IOException("Tailscale grok prime error")
            return mockSnapshot.copy(smartPrime = mockPrimeStatus)
        }

        override fun updateActiveHours(baseUrl: String, token: String?, start: String, end: String): QuotaSnapshot {
            updateActiveHoursCalled = true
            lastUpdatedStart = start
            lastUpdatedEnd = end
            if (shouldFailActiveHours) throw IOException("Tailscale active hours error")
            return mockSnapshot.copy(
                smartPrime = mockPrimeStatus.copy(activeHoursStart = start, activeHoursEnd = end)
            )
        }
    }

    private class FakeGistClient(
        var mockSnapshot: QuotaSnapshot = SeedData.createSeedSnapshot(),
        var shouldFail: Boolean = false
    ) : GistRelayClient() {
        var fetchCalled = false
        var lastGithubToken: String? = null

        override fun fetchAndDecryptSnapshot(gistId: String, aesKey: String, githubToken: String?): QuotaSnapshot {
            fetchCalled = true
            lastGithubToken = githubToken
            if (shouldFail) throw IOException("Gist relay 404 or decryption failed")
            return mockSnapshot
        }
    }

    @Test
    fun testTailscalePrimaryRefreshSuccess() = runTest {
        val fakeTailscale = FakeTailscaleClient()
        val fakeGist = FakeGistClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                tailscaleToken = "test_token"
            )
        )

        // Manual refresh should invoke triggerRefresh()
        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        assertTrue("triggerRefresh should be called for manual refresh", fakeTailscale.triggerRefreshCalled)
        assertEquals(ConnectionState.TAILSCALE_LIVE, repository.connectionStateFlow.value)
        assertNull(repository.syncErrorFlow.value)
    }

    @Test
    fun testTailscaleFailureFallsBackToGistSuccess() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true, shouldFailFetch = true)
        val expectedSnapshot = SeedData.createSeedSnapshot().copy(host = "gist-cloud-relay")
        val fakeGist = FakeGistClient(mockSnapshot = expectedSnapshot)

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                gistId = "gist_abc123",
                gistAesKey = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
            )
        )

        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        assertTrue("Gist client should be invoked on Tailscale failure", fakeGist.fetchCalled)
        assertEquals(ConnectionState.CLOUD_RELAY_SYNCED, repository.connectionStateFlow.value)
        assertEquals("gist-cloud-relay", repository.snapshotFlow.value.host)
        assertNull(repository.syncErrorFlow.value)
    }

    @Test
    fun testForceTailscaleFailurePropagatesError() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true)
        val fakeGist = FakeGistClient()

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                gistId = "gist_abc123",
                gistAesKey = "some_key"
            )
        )

        val result = repository.refresh(forceTailscale = true, isManual = true)

        assertTrue(result.isFailure)
        assertTrue("Gist client should NOT be called when forceTailscale is true", !fakeGist.fetchCalled)
        assertEquals(ConnectionState.OFFLINE, repository.connectionStateFlow.value)
        assertNotNull(repository.syncErrorFlow.value)
        assertTrue(repository.syncErrorFlow.value!!.contains("Tailscale refresh failed"))
    }

    @Test
    fun testAllEndpointsFailSurfacesDescriptiveError() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true)
        val fakeGist = FakeGistClient(shouldFail = true)

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080",
                gistId = "gist_abc123",
                gistAesKey = "key_123"
            )
        )

        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        // Falls back to seed or offline cache
        assertEquals(ConnectionState.DEMO_SEED, repository.connectionStateFlow.value)
        val errorMsg = repository.syncErrorFlow.value
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Tailscale live daemon unreachable"))
        assertTrue(errorMsg.contains("Cloud relay failed"))
    }

    @Test
    fun testPrimeGrokParsesFullSnapshotWithoutSecondRefresh() = runTest {
        val primedPrime = SmartPrimeStatus(
            policy = "active_hours",
            activeHoursStart = "08:00",
            activeHoursEnd = "22:00",
            grokTokenExpiresAt = "2026-11-01T20:00:00Z",
            grokTokenRemainingSeconds = 5400L,
            lastPrimedAt = "2026-11-01T17:00:00Z",
            primed = true,
            reason = "manual_refresh"
        )
        val fakeTailscale = FakeTailscaleClient(
            mockPrimeStatus = primedPrime,
            // Prime succeeds, but follow-up refresh should NOT be called!
            shouldFailPrime = false,
            shouldFailRefresh = true
        )

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080"
            )
        )

        val primeResult = repository.primeGrok()

        assertTrue("primeGrok must succeed without calling second refresh", primeResult.isSuccess)
        assertTrue("primeGrok on client should be called", fakeTailscale.primeGrokCalled)
        assertEquals("triggerRefresh must NOT be called on primeGrok", false, fakeTailscale.triggerRefreshCalled)
        assertEquals(5400L, repository.snapshotFlow.value.smartPrime.grokTokenRemainingSeconds)
        assertTrue(repository.snapshotFlow.value.smartPrime.primed)
        assertEquals(ConnectionState.TAILSCALE_LIVE, repository.connectionStateFlow.value)
    }

    @Test
    fun testUpdateActiveHoursCallsTailscaleAndUpdatesState() = runTest {
        val fakeTailscale = FakeTailscaleClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8080"
            )
        )

        val result = repository.updateActiveHours("09:00", "21:00")

        assertTrue(result.isSuccess)
        assertTrue(fakeTailscale.updateActiveHoursCalled)
        assertEquals("09:00", fakeTailscale.lastUpdatedStart)
        assertEquals("21:00", fakeTailscale.lastUpdatedEnd)
        assertEquals("09:00", repository.snapshotFlow.value.smartPrime.activeHoursStart)
        assertEquals("21:00", repository.snapshotFlow.value.smartPrime.activeHoursEnd)
    }

    @Test
    fun testGistRelayReceivesGithubToken() = runTest {
        val fakeGist = FakeGistClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(shouldFailFetch = true, shouldFailRefresh = true),
            gistClient = fakeGist
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "",
                gistId = "gist_test_123",
                gistAesKey = "key_test",
                githubToken = "ghp_secrettoken"
            )
        )

        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        assertTrue(fakeGist.fetchCalled)
        assertEquals("ghp_secrettoken", fakeGist.lastGithubToken)
    }

    @Test
    fun testPrimeGrokInOfflineModeLabelsSimulationClearly() = runTest {
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        // Blank tailscale URL simulates offline mode
        repository.updateSettings(SyncSettings(tailscaleUrl = ""))

        val primeResult = repository.primeGrok()

        assertTrue(primeResult.isSuccess)
        val errorMsg = repository.syncErrorFlow.value
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Offline simulation"))
    }

    @Test
    fun testSaveSettingsPreservesActiveHours() = runTest {
        val fakeTailscale = FakeTailscaleClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        repository.updateActiveHours("09:00", "21:00")
        assertEquals("09:00", repository.settingsFlow.value.activeHoursStart)
        assertEquals("21:00", repository.settingsFlow.value.activeHoursEnd)

        val saveResult = repository.saveSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8741",
                tailscaleToken = "new_token"
            )
        )

        assertTrue(saveResult.isSuccess)
        assertEquals("http://100.115.92.2:8741", repository.settingsFlow.value.tailscaleUrl)
        assertEquals("new_token", repository.settingsFlow.value.tailscaleToken)
        assertEquals("09:00", repository.settingsFlow.value.activeHoursStart)
        assertEquals("21:00", repository.settingsFlow.value.activeHoursEnd)
    }

    @Test
    fun testTestConnectionPreservesActiveHoursAndCommitsOnSuccess() = runTest {
        val fakeTailscale = FakeTailscaleClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        repository.updateActiveHours("07:30", "19:30")

        val probeResult = repository.testConnection(
            SyncSettings(
                tailscaleUrl = "http://100.64.0.1:8741",
                tailscaleToken = "probe_token"
            )
        )

        assertTrue(probeResult.isSuccess)
        assertEquals("http://100.64.0.1:8741", repository.settingsFlow.value.tailscaleUrl)
        assertEquals("probe_token", repository.settingsFlow.value.tailscaleToken)
        assertEquals("07:30", repository.settingsFlow.value.activeHoursStart)
        assertEquals("19:30", repository.settingsFlow.value.activeHoursEnd)
        assertEquals(ConnectionState.TAILSCALE_LIVE, repository.connectionStateFlow.value)
    }

    @Test
    fun testTestConnectionFailureDoesNotOverwriteStoredSecrets() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true)
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8741",
                tailscaleToken = "valid_token"
            )
        )

        val failResult = repository.testConnection(
            SyncSettings(
                tailscaleUrl = "http://100.99.99.99:8741",
                tailscaleToken = "bad_token"
            )
        )

        assertTrue(failResult.isFailure)
        assertEquals("http://100.115.92.2:8741", repository.settingsFlow.value.tailscaleUrl)
        assertEquals("valid_token", repository.settingsFlow.value.tailscaleToken)
        assertNotNull(repository.syncErrorFlow.value)
    }

    @Test
    fun testGistWithoutAesKeyIsNotConfiguredAndNotReportedAsRelaySuccess() = runTest {
        val fakeGist = FakeGistClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(shouldFailRefresh = true, shouldFailFetch = true),
            gistClient = fakeGist
        )

        val settingsWithoutKey = SyncSettings(
            tailscaleUrl = "",
            gistId = "gist_abc_123",
            gistAesKey = ""
        )
        assertFalse(settingsWithoutKey.hasUsableTransport)

        repository.updateSettings(settingsWithoutKey)
        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        assertFalse("Gist client must not be called when AES key is blank", fakeGist.fetchCalled)
        assertEquals(ConnectionState.DEMO_SEED, repository.connectionStateFlow.value)
        val errorMsg = repository.syncErrorFlow.value
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("No sync endpoints configured"))
    }

    @Test
    fun testBlankEndpointRefreshWithCachedSnapshotIsReportedAsCacheNotDemo() = runTest {
        val cachedSnapshot = SeedData.createSeedSnapshot().copy(host = "cached-mac-mini")
        preferencesStore.saveCachedSnapshotJson(
            kotlinx.serialization.json.Json.encodeToString(
                com.aiquotamonitor.domain.model.QuotaSnapshot.serializer(),
                cachedSnapshot
            )
        )

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "",
                gistId = "",
                gistAesKey = ""
            )
        )

        val result = repository.refresh(forceTailscale = false, isManual = true)

        assertTrue(result.isSuccess)
        assertEquals(ConnectionState.OFFLINE, repository.connectionStateFlow.value)
        assertEquals("cached-mac-mini", repository.snapshotFlow.value.host)
        val errorMsg = repository.syncErrorFlow.value
        assertNotNull(errorMsg)
        assertTrue(errorMsg!!.contains("Using cached snapshot"))
    }

    @Test
    fun testUpdateActiveHoursCannotClobberFreshlySavedCredentials() = runTest {
        val fakeTailscale = FakeTailscaleClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        repository.saveSettings(
            SyncSettings(
                tailscaleUrl = "http://100.115.92.2:8741",
                tailscaleToken = "fresh_token_123",
                gistId = "gist_456",
                gistAesKey = "key_789"
            )
        )

        val activeResult = repository.updateActiveHours("06:00", "23:00")

        assertTrue(activeResult.isSuccess)
        val currentSettings = repository.settingsFlow.value
        assertEquals("http://100.115.92.2:8741", currentSettings.tailscaleUrl)
        assertEquals("fresh_token_123", currentSettings.tailscaleToken)
        assertEquals("gist_456", currentSettings.gistId)
        assertEquals("key_789", currentSettings.gistAesKey)
        assertEquals("06:00", currentSettings.activeHoursStart)
        assertEquals("23:00", currentSettings.activeHoursEnd)
    }

    @Test
    fun testSeedDataNeverPersistedToCachedSnapshotPreference() = runTest {
        // Preferences store is empty initially
        assertNull(preferencesStore.getCachedSnapshotJson())

        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(shouldFailFetch = true, shouldFailRefresh = true),
            gistClient = FakeGistClient(shouldFail = true)
        )

        // Initial state is DEMO_SEED
        assertEquals(ConnectionState.DEMO_SEED, repository.connectionStateFlow.value)
        assertNull("Initial demo seed snapshot must not be saved to preferences", preferencesStore.getCachedSnapshotJson())

        // Refresh with unconfigured / failing transports falls back to demo seed data
        val result = repository.refresh()
        assertTrue(result.isSuccess)
        assertEquals(ConnectionState.DEMO_SEED, repository.connectionStateFlow.value)
        assertNull("Fallback demo seed snapshot must NEVER be saved to preferences", preferencesStore.getCachedSnapshotJson())

        // Now configure a real live Tailscale client that succeeds
        val liveSnapshot = SeedData.createSeedSnapshot().copy(host = "real-mac-mini")
        val liveRepo = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(mockSnapshot = liveSnapshot),
            gistClient = FakeGistClient()
        )
        liveRepo.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))
        val liveResult = liveRepo.refresh()
        assertTrue(liveResult.isSuccess)
        assertEquals(ConnectionState.TAILSCALE_LIVE, liveRepo.connectionStateFlow.value)
        assertNotNull("Real live snapshot must be persisted to preferences", preferencesStore.getCachedSnapshotJson())
        assertTrue(preferencesStore.getCachedSnapshotJson()!!.contains("real-mac-mini"))
    }

    @Test
    fun testTestConnectionRestoresPriorStateOnFailure() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true)
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )

        // Case A: Prior state is DEMO_SEED
        assertEquals(ConnectionState.DEMO_SEED, repository.connectionStateFlow.value)
        val failResult1 = repository.testConnection(
            SyncSettings(tailscaleUrl = "http://100.99.99.99:8741")
        )
        assertTrue(failResult1.isFailure)
        assertEquals("Probe failure must restore prior DEMO_SEED state", ConnectionState.DEMO_SEED, repository.connectionStateFlow.value)

        // Case B: Preload a cached snapshot so prior state is OFFLINE
        val cachedSnapshot = SeedData.createSeedSnapshot().copy(host = "cached-host")
        preferencesStore.saveCachedSnapshotJson(
            kotlinx.serialization.json.Json.encodeToString(
                com.aiquotamonitor.domain.model.QuotaSnapshot.serializer(),
                cachedSnapshot
            )
        )
        val offlineRepo = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )
        assertEquals(ConnectionState.OFFLINE, offlineRepo.connectionStateFlow.value)

        val failResult2 = offlineRepo.testConnection(
            SyncSettings(tailscaleUrl = "http://100.99.99.99:8741")
        )
        assertTrue(failResult2.isFailure)
        assertEquals("Probe failure must restore prior OFFLINE state", ConnectionState.OFFLINE, offlineRepo.connectionStateFlow.value)
    }

    @Test
    fun testTestConnectionCombinesBothErrorsWhenTailscaleAndGistFail() = runTest {
        val fakeTailscale = FakeTailscaleClient(shouldFailRefresh = true)
        val fakeGist = FakeGistClient(shouldFail = true)
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = fakeGist
        )

        val dualSettings = SyncSettings(
            tailscaleUrl = "http://100.115.92.2:8741",
            tailscaleToken = "tok",
            gistId = "gist_xyz",
            gistAesKey = "key_secret"
        )

        val result = repository.testConnection(dualSettings)
        assertTrue(result.isFailure)

        val error = result.exceptionOrNull()?.message
        assertNotNull(error)
        assertTrue("Error message must contain Tailscale error", error!!.contains("Tailscale unreachable (Tailscale refresh daemon error)"))
        assertTrue("Error message must contain Cloud relay error", error.contains("Cloud relay failed (Gist relay 404 or decryption failed)"))
        assertEquals(error, repository.syncErrorFlow.value)
    }

    @Test
    fun testUpdateSettingsProtectsWithMutex() = runTest {
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        repository.updateSettings(
            SyncSettings(
                tailscaleUrl = "http://100.64.0.1:8741",
                tailscaleToken = "token1",
                gistId = "gist1",
                gistAesKey = "key1"
            )
        )

        val current = repository.settingsFlow.value
        assertEquals("http://100.64.0.1:8741", current.tailscaleUrl)
        assertEquals("token1", current.tailscaleToken)
        assertEquals("gist1", current.gistId)
        assertEquals("key1", current.gistAesKey)
    }

    @Test
    fun testDefaultHiddenBucketIdsContainsClaudeAndGpt3P() = runTest {
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        val hidden = repository.hiddenBucketIdsFlow.value
        assertEquals(PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS, hidden)
        assertTrue(hidden.contains("3p-5h"))
        assertTrue(hidden.contains("3p-weekly"))
    }

    @Test
    fun testShowAllEmptySetPersistsAcrossRestarts() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val store1 = PreferencesStore(sharedPreferences = sharedPrefs)
        val repository = QuotaRepositoryImpl(
            preferencesStore = store1,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        // Default before saving
        assertEquals(PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS, repository.hiddenBucketIdsFlow.value)

        // User selects "Show All"
        repository.setHiddenBuckets(emptySet())
        assertTrue(repository.hiddenBucketIdsFlow.value.isEmpty())

        // Simulate app restart with a fresh PreferencesStore over the same SharedPreferences
        val store2 = PreferencesStore(sharedPreferences = sharedPrefs)
        assertTrue("User selected emptySet must not be clobbered back to default on restart", store2.getHiddenBucketIds().isEmpty())
        assertTrue(store2.hiddenBucketIdsFlow.value.isEmpty())
    }

    @Test
    fun testToggleBucketVisibility() = runTest {
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        // Initially hidden: 3p-5h, 3p-weekly
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))

        // Unhide 3p-5h
        repository.toggleBucketVisibility("3p-5h")
        assertFalse(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("3p-weekly"))

        // Hide gemini-5h
        repository.toggleBucketVisibility("gemini-5h")
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("gemini-5h"))
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("3p-weekly"))
    }

    @Test
    fun testPinnedBucketPersistenceAndClear() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val store1 = PreferencesStore(sharedPreferences = sharedPrefs)
        val repository = QuotaRepositoryImpl(
            preferencesStore = store1,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        assertNull(repository.pinnedBucketIdFlow.value)

        repository.setPinnedBucket("gemini-5h")
        assertEquals("gemini-5h", repository.pinnedBucketIdFlow.value)

        val store2 = PreferencesStore(sharedPreferences = sharedPrefs)
        assertEquals("gemini-5h", store2.getPinnedBucketId())

        repository.setPinnedBucket(null)
        assertNull(repository.pinnedBucketIdFlow.value)
        assertNull(store2.getPinnedBucketId())
    }

    @Test
    fun testHeroBucketAndVisibleBucketsResolution() {
        val snapshot = SeedData.createSeedSnapshot()
        val defaultHidden = PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS

        // 1. Visible buckets exclude default 3P buckets
        val visible = snapshot.visibleBuckets(defaultHidden)
        assertTrue(visible.none { it.id == "3p-5h" || it.id == "3p-weekly" })
        assertTrue(visible.any { it.id == "gemini-argon-wtu-weekly" })
        assertTrue(visible.any { it.id == "gemini-5h" })

        // 2. Default hero bucket is primary bottleneck among visible buckets
        val hero = snapshot.heroBucket(defaultHidden, pinnedBucketId = null)
        assertNotNull(hero)
        assertEquals("gemini-argon-wtu-weekly", hero?.id)

        // 3. Pinned bucket spotlights requested bucket when visible
        val pinnedHero = snapshot.heroBucket(defaultHidden, pinnedBucketId = "gemini-5h")
        assertNotNull(pinnedHero)
        assertEquals("gemini-5h", pinnedHero?.id)

        // 4. Pinned bucket falls back to visible bottleneck if pinned bucket is hidden
        val hiddenPinnedHero = snapshot.heroBucket(defaultHidden, pinnedBucketId = "3p-5h")
        assertNotNull(hiddenPinnedHero)
        assertEquals("gemini-argon-wtu-weekly", hiddenPinnedHero?.id)

        // 5. Returns null when all buckets hidden (omits hero card)
        val allBucketIds = snapshot.providers.flatMap { it.buckets }.map { it.id }.toSet()
        val allHiddenHero = snapshot.heroBucket(allBucketIds, pinnedBucketId = null)
        assertNull(allHiddenHero)
    }

    @Test
    fun testDefaultHideKeeps3POffHeroWhen3PIsLowestRemaining() {
        val baseSnapshot = SeedData.createSeedSnapshot()
        // Create snapshot where 3p-weekly is at 10% (lower than Argon at 35%)
        val modifiedProviders = baseSnapshot.providers.map { provider ->
            if (provider.id == "agy") {
                provider.copy(
                    buckets = provider.buckets.map { bucket ->
                        if (bucket.id == "3p-weekly") bucket.copy(remainingPercent = 10.0) else bucket
                    }
                )
            } else provider
        }
        val snapshot = baseSnapshot.copy(providers = modifiedProviders)

        // With default hidden buckets, 3p-weekly (10%) is ignored and Argon (35%) is hero
        val defaultHero = snapshot.heroBucket(PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS)
        assertNotNull(defaultHero)
        assertEquals("gemini-argon-wtu-weekly", defaultHero?.id)

        // With empty hidden set ("Show All"), 3p-weekly (10%) becomes the hero
        val showAllHero = snapshot.heroBucket(emptySet())
        assertNotNull(showAllHero)
        assertEquals("3p-weekly", showAllHero?.id)
    }

    @Test
    fun testTieBreakingOrderForEqualRemainingPercent() {
        val baseSnapshot = SeedData.createSeedSnapshot()
        // Set two buckets to exactly 20.0% remaining: "gemini-5h" and "gemini-argon-wtu-weekly"
        val modifiedProviders = baseSnapshot.providers.map { provider ->
            if (provider.id == "agy") {
                provider.copy(
                    buckets = provider.buckets.map { bucket ->
                        if (bucket.id == "gemini-5h" || bucket.id == "gemini-argon-wtu-weekly") {
                            bucket.copy(remainingPercent = 20.0)
                        } else bucket
                    }
                )
            } else provider
        }
        val snapshot = baseSnapshot.copy(providers = modifiedProviders)

        // "gemini-5h" < "gemini-argon-wtu-weekly" alphabetically
        val hero = snapshot.heroBucket(emptySet())
        assertNotNull(hero)
        assertEquals("gemini-5h", hero?.id)
    }

    @Test
    fun testMutatingCallerSetDoesNotMutateStoredSet() {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val store = PreferencesStore(sharedPreferences = sharedPrefs)

        val mutableSet = mutableSetOf("bucket-1", "bucket-2")
        store.saveHiddenBucketIds(mutableSet)

        // Mutate caller's set
        mutableSet.add("bucket-3")

        val stored = store.getHiddenBucketIds()
        assertEquals(setOf("bucket-1", "bucket-2"), stored)
        assertFalse(stored.contains("bucket-3"))
    }

    @Test
    fun testMutatingPreferencesStoreFlowValueDoesNotMutateStoredSet() {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val store = PreferencesStore(sharedPreferences = sharedPrefs)

        store.saveHiddenBucketIds(setOf("bucket-1", "bucket-2"))
        val flowValue = store.hiddenBucketIdsFlow.value
        val mutable = flowValue as? MutableSet<String>
        mutable?.add("injected-bucket")

        val stored = store.getHiddenBucketIds()
        assertEquals(setOf("bucket-1", "bucket-2"), stored)
        assertFalse(stored.contains("injected-bucket"))
    }

    @Test
    fun testPinningHiddenBucketUnhidesItAndHidingPinnedBucketClearsPin() = runTest {
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        // 3p-5h starts hidden
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))

        // Pinning 3p-5h must remove it from hidden in the same transaction
        repository.setPinnedBucket("3p-5h")
        assertEquals("3p-5h", repository.pinnedBucketIdFlow.value)
        assertFalse(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))

        // Hiding 3p-5h must clear the pinnedBucketId in the same transaction
        repository.toggleBucketVisibility("3p-5h")
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))
        assertNull(repository.pinnedBucketIdFlow.value)
    }

    @Test
    fun testToggleWhileUnsetPersistsDefaultPlusOrMinusId() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val store = PreferencesStore(sharedPreferences = sharedPrefs)
        val repository = QuotaRepositoryImpl(
            preferencesStore = store,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        // While unset, toggle 3p-5h (which is in default)
        repository.toggleBucketVisibility("3p-5h")
        // Stored set is now only {"3p-weekly"}
        assertEquals(setOf("3p-weekly"), repository.hiddenBucketIdsFlow.value)
        assertEquals(setOf("3p-weekly"), store.getHiddenBucketIds())

        // Toggle gemini-5h (which was not in default)
        repository.toggleBucketVisibility("gemini-5h")
        assertEquals(setOf("3p-weekly", "gemini-5h"), repository.hiddenBucketIdsFlow.value)
        assertEquals(setOf("3p-weekly", "gemini-5h"), store.getHiddenBucketIds())
    }

    @Test
    fun testConcurrentPinningAndUnhidingKeepsBucketPinnedAndVisible() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val store = PreferencesStore(sharedPreferences = sharedPrefs)
        val repository = QuotaRepositoryImpl(
            preferencesStore = store,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )

        // Verify initial state: 3p-5h is hidden
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))

        // Run setPinnedBucket and unhide concurrently across background dispatchers inside coroutineScope
        coroutineScope {
            val pinJob = async(Dispatchers.Default) {
                repository.setPinnedBucket("3p-5h")
            }
            val unhideJob = async(Dispatchers.Default) {
                repository.setHiddenBuckets(PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS - "3p-5h")
            }
            pinJob.await()
            unhideJob.await()
        }

        assertEquals("3p-5h", repository.pinnedBucketIdFlow.value)
        assertFalse(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))
    }

    // ---- Single-flight sync mutex & auto-sync signaling (Issue #1 / APP-SYNC1) ----

    @Test
    fun testAutoRefreshUsesIsAutoSyncingAndPreservesConnectionState() = runTest {
        lateinit var repository: QuotaRepositoryImpl
        var observedState: ConnectionState? = null
        var observedAutoSyncing = false
        val observingClient = object : TailscaleApiClient() {
            override fun fetchQuota(baseUrl: String, token: String?): QuotaSnapshot {
                observedState = repository.connectionStateFlow.value
                observedAutoSyncing = repository.isAutoSyncingFlow.value
                return SeedData.createSeedSnapshot()
            }

            override fun triggerRefresh(baseUrl: String, token: String?): QuotaSnapshot =
                SeedData.createSeedSnapshot()
        }
        repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = observingClient,
            gistClient = FakeGistClient(),
            clock = { 1234L }
        )
        repository.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))

        val result = repository.refresh(forceTailscale = false, isManual = false)

        assertTrue(result.isSuccess)
        assertEquals(
            "Background auto-sync must preserve the prior connection state (never SYNCING)",
            ConnectionState.DEMO_SEED,
            observedState
        )
        assertTrue("isAutoSyncing must be true while the background fetch is in flight", observedAutoSyncing)
        assertFalse("isAutoSyncing must clear after the background fetch", repository.isAutoSyncingFlow.value)
        assertEquals(ConnectionState.TAILSCALE_LIVE, repository.connectionStateFlow.value)
        assertEquals(1234L, repository.lastSyncAttemptEpochMsFlow.value)
    }

    @Test
    fun testAutoRefreshSkipsImmediatelyWhenSyncMutexLocked() = runTest {
        val gate = CompletableDeferred<Unit>()
        val manualStarted = CompletableDeferred<Unit>()
        var autoFetchCalls = 0
        val blockingClient = object : TailscaleApiClient() {
            override fun triggerRefresh(baseUrl: String, token: String?): QuotaSnapshot {
                manualStarted.complete(Unit)
                runBlocking { gate.await() }
                return SeedData.createSeedSnapshot().copy(host = "manual-refresh")
            }

            override fun fetchQuota(baseUrl: String, token: String?): QuotaSnapshot {
                autoFetchCalls++
                return SeedData.createSeedSnapshot().copy(host = "auto-refresh")
            }
        }
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = blockingClient,
            gistClient = FakeGistClient()
        )
        repository.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))

        val manual = async(Dispatchers.IO) { repository.refresh(forceTailscale = false, isManual = true) }
        withTimeout(5_000L) { manualStarted.await() }
        val snapshotBeforeManualCompletes = repository.snapshotFlow.value

        val auto = repository.refresh(forceTailscale = false, isManual = false)

        assertTrue("Skipped auto-sync must report success", auto.isSuccess)
        assertEquals("Skipped auto-sync must not hit the network", 0, autoFetchCalls)
        assertEquals(snapshotBeforeManualCompletes, auto.getOrNull())

        gate.complete(Unit)
        assertTrue(manual.await().isSuccess)
        assertEquals("manual-refresh", repository.snapshotFlow.value.host)
    }

    @Test
    fun testConcurrentManualRefreshesAreSerializedBySyncMutex() = runTest {
        val gate = CompletableDeferred<Unit>()
        val firstStarted = CompletableDeferred<Unit>()
        val secondStarted = CompletableDeferred<Unit>()
        val calls = AtomicInteger(0)
        val serializingClient = object : TailscaleApiClient() {
            override fun triggerRefresh(baseUrl: String, token: String?): QuotaSnapshot {
                val call = calls.incrementAndGet()
                if (call == 1) {
                    firstStarted.complete(Unit)
                    runBlocking { gate.await() }
                } else {
                    secondStarted.complete(Unit)
                }
                return SeedData.createSeedSnapshot().copy(host = "host-$call")
            }

            override fun fetchQuota(baseUrl: String, token: String?): QuotaSnapshot =
                SeedData.createSeedSnapshot()
        }
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = serializingClient,
            gistClient = FakeGistClient()
        )
        repository.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))

        val first = async(Dispatchers.IO) { repository.refresh(forceTailscale = false, isManual = true) }
        withTimeout(5_000L) { firstStarted.await() }
        val second = async(Dispatchers.IO) { repository.refresh(forceTailscale = false, isManual = true) }
        Thread.sleep(50)
        assertFalse("Second refresh must wait on syncMutex", secondStarted.isCompleted)

        gate.complete(Unit)
        assertTrue(first.await().isSuccess)
        assertTrue(second.await().isSuccess)

        assertEquals("Both refreshes must eventually run exactly once", 2, calls.get())
        assertEquals("The newest response must be the final applied snapshot", "host-2", repository.snapshotFlow.value.host)
    }

    @Test
    fun testTestConnectionRestoresLiveStateAfterBackgroundSync() = runTest {
        val fakeTailscale = FakeTailscaleClient()
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = fakeTailscale,
            gistClient = FakeGistClient()
        )
        repository.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))

        assertTrue(repository.refresh(forceTailscale = false, isManual = false).isSuccess)
        assertEquals(ConnectionState.TAILSCALE_LIVE, repository.connectionStateFlow.value)

        fakeTailscale.shouldFailRefresh = true
        val probe = repository.testConnection(SyncSettings(tailscaleUrl = "http://100.99.99.99:8741"))

        assertTrue(probe.isFailure)
        assertEquals(
            "Probe failure must restore the prior live state untouched by background sync",
            ConnectionState.TAILSCALE_LIVE,
            repository.connectionStateFlow.value
        )
        assertFalse(repository.isAutoSyncingFlow.value)
    }

    @Test
    fun testFailedRefreshStillStampsLastSyncAttemptAndClearsAutoFlag() = runTest {
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(shouldFailFetch = true, shouldFailRefresh = true),
            gistClient = FakeGistClient(shouldFail = true),
            clock = { 555L }
        )
        repository.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))

        val result = repository.refresh(forceTailscale = false, isManual = false)

        assertTrue(result.isSuccess)
        assertEquals("Failed/unreachable attempts must still stamp the debounce clock", 555L, repository.lastSyncAttemptEpochMsFlow.value)
        assertFalse(repository.isAutoSyncingFlow.value)
    }

    @Test
    fun testRefreshAndSaveSettingsStampLastSyncAttempt() = runTest {
        var now = 111L
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient(),
            clock = { now }
        )
        repository.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))

        repository.refresh(forceTailscale = false, isManual = false)
        assertEquals(111L, repository.lastSyncAttemptEpochMsFlow.value)

        now = 222L
        assertTrue(repository.saveSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741")).isSuccess)
        assertEquals(222L, repository.lastSyncAttemptEpochMsFlow.value)
    }

    @Test
    fun testPrimeGrokAndUpdateActiveHoursStampLastSyncAttempt() = runTest {
        var now = 333L
        val repository = QuotaRepositoryImpl(
            preferencesStore = preferencesStore,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient(),
            clock = { now }
        )
        repository.updateSettings(SyncSettings(tailscaleUrl = "http://100.64.0.1:8741"))

        now = 444L
        assertTrue(repository.primeGrok().isSuccess)
        assertEquals(
            "Live primeGrok network calls must stamp the resume-debounce clock",
            444L,
            repository.lastSyncAttemptEpochMsFlow.value
        )

        now = 555L
        assertTrue(repository.updateActiveHours("09:00", "21:00").isSuccess)
        assertEquals(
            "Live updateActiveHours network calls must stamp the resume-debounce clock",
            555L,
            repository.lastSyncAttemptEpochMsFlow.value
        )
    }

    // ---- Configurable widget buckets (Issue #4 / APP-UX4) ----

    private fun newRepository(
        sharedPrefs: com.aiquotamonitor.data.local.InMemorySharedPreferences
    ): Pair<PreferencesStore, QuotaRepositoryImpl> {
        val store = PreferencesStore(sharedPreferences = sharedPrefs)
        val repository = QuotaRepositoryImpl(
            preferencesStore = store,
            tailscaleClient = FakeTailscaleClient(),
            gistClient = FakeGistClient()
        )
        return store to repository
    }

    @Test
    fun testCompactWidgetBucketPersistenceAndAutoUnhide() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val (_, repository) = newRepository(sharedPrefs)

        assertNull(repository.compactWidgetBucketIdFlow.value)

        // 3p-weekly is hidden by default but selecting it must unhide it in the same transaction.
        repository.setCompactWidgetBucket("3p-weekly")
        assertEquals("3p-weekly", repository.compactWidgetBucketIdFlow.value)
        assertFalse(repository.hiddenBucketIdsFlow.value.contains("3p-weekly"))

        val restartedStore = PreferencesStore(sharedPreferences = sharedPrefs)
        assertEquals("3p-weekly", restartedStore.getCompactWidgetBucketId())
        assertEquals("3p-weekly", restartedStore.compactWidgetBucketIdFlow.value)

        // A role token is not a bucket id and must never mutate visibility.
        repository.setHiddenBuckets(setOf("3p-5h"))
        repository.setCompactWidgetBucket("  role:PLANNER  ")
        assertEquals("role:PLANNER", repository.compactWidgetBucketIdFlow.value)
        assertTrue(repository.hiddenBucketIdsFlow.value.contains("3p-5h"))

        // Blank / null both mean Auto and remove the stored key.
        repository.setCompactWidgetBucket("   ")
        assertNull(repository.compactWidgetBucketIdFlow.value)
        assertNull(restartedStore.getCompactWidgetBucketId())
        assertFalse(sharedPrefs.getAll().containsKey("compact_widget_bucket_id"))
    }

    @Test
    fun testWideWidgetBucketsOrderPersistenceAndAutoUnhide() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val (_, repository) = newRepository(sharedPrefs)

        assertTrue(repository.wideWidgetBucketIdsFlow.value.isEmpty())

        repository.setWideWidgetBuckets(
            listOf(
                "3p-weekly",
                " gemini-5h ",
                "3p-weekly",
                "grok-weekly-credits",
                "opencode-rolling"
            )
        )

        assertEquals(
            listOf("3p-weekly", "gemini-5h", "grok-weekly-credits"),
            repository.wideWidgetBucketIdsFlow.value
        )
        assertFalse(repository.hiddenBucketIdsFlow.value.contains("3p-weekly"))

        val restartedStore = PreferencesStore(sharedPreferences = sharedPrefs)
        assertEquals(
            listOf("3p-weekly", "gemini-5h", "grok-weekly-credits"),
            restartedStore.getWideWidgetBucketIds()
        )
        assertEquals(restartedStore.getWideWidgetBucketIds(), restartedStore.wideWidgetBucketIdsFlow.value)

        // Empty/blank means the Orca Roles default and removes the key.
        repository.setWideWidgetBuckets(listOf(" ", ""))
        assertTrue(repository.wideWidgetBucketIdsFlow.value.isEmpty())
        assertFalse(sharedPrefs.getAll().containsKey("wide_widget_bucket_ids"))
    }

    @Test
    fun testToggleWideWidgetBucketSanitizesAndSerializes() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val (_, repository) = newRepository(sharedPrefs)

        repository.toggleWideWidgetBucket("  gemini-argon-wtu-weekly  ")
        repository.toggleWideWidgetBucket("opencode-rolling")
        repository.toggleWideWidgetBucket("grok-weekly-credits")
        assertEquals(
            listOf("gemini-argon-wtu-weekly", "opencode-rolling", "grok-weekly-credits"),
            repository.wideWidgetBucketIdsFlow.value
        )

        // A fourth selection replaces slot 3, preserving the first two.
        repository.toggleWideWidgetBucket("gemini-5h")
        assertEquals(
            listOf("gemini-argon-wtu-weekly", "opencode-rolling", "gemini-5h"),
            repository.wideWidgetBucketIdsFlow.value
        )

        // Tapping a selected chip removes it.
        repository.toggleWideWidgetBucket("opencode-rolling")
        assertEquals(
            listOf("gemini-argon-wtu-weekly", "gemini-5h"),
            repository.wideWidgetBucketIdsFlow.value
        )

        // Blank ids are a no-op.
        repository.toggleWideWidgetBucket("   ")
        assertEquals(listOf("gemini-argon-wtu-weekly", "gemini-5h"), repository.wideWidgetBucketIdsFlow.value)
    }

    @Test
    fun testConcurrentToggleWideWidgetBucketsDoesNotLoseUpdates() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val (_, repository) = newRepository(sharedPrefs)
        val ids = listOf("gemini-argon-wtu-weekly", "opencode-rolling", "grok-weekly-credits")

        coroutineScope {
            ids.map { id ->
                async(Dispatchers.Default) { repository.toggleWideWidgetBucket(id) }
            }.forEach { it.await() }
        }

        assertEquals(3, repository.wideWidgetBucketIdsFlow.value.size)
        assertEquals(ids.toSet(), repository.wideWidgetBucketIdsFlow.value.toSet())
    }

    @Test
    fun testResolveCompactWidgetItemFallbackOnMissingOrHiddenBucket() {
        val snapshot = SeedData.createSeedSnapshot()
        val defaultHidden = PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS

        // Auto mode selects the hero spotlight without a fallback cue.
        val auto = snapshot.resolveCompactWidgetItem(null, defaultHidden, null)
        assertNotNull(auto)
        assertEquals("gemini-argon-wtu-weekly", auto?.id)
        assertFalse(auto!!.isFallback)

        // Concrete bucket match keeps its own provider category.
        val concrete = snapshot.resolveCompactWidgetItem("gemini-5h", defaultHidden, null)
        assertNotNull(concrete)
        assertEquals("gemini-5h", concrete?.id)
        assertEquals("AGY", concrete?.categoryLabel)
        assertEquals("Gemini 3.8 Flash 5h Rolling", concrete?.titleLabel)
        assertFalse(concrete!!.isFallback)

        // Role tokens are case-insensitive and use OrcaRoleReadiness percents, not the raw bucket copy.
        val planner = snapshot.resolveCompactWidgetItem("role:planner", defaultHidden, null)
        assertNotNull(planner)
        assertEquals("role:PLANNER", planner?.id)
        assertTrue(planner?.isRole == true)
        assertEquals("PLANNER", planner?.categoryLabel)
        assertEquals(35.0, planner!!.remainingPercent, 0.0001)
        assertEquals(65.0, planner.usedPercent, 0.0001)
        assertEquals(-33.1, planner.paceDeltaPercent, 0.0001)
        assertFalse(planner.isFallback)

        // A missing concrete id falls back to the hero bucket with isFallback = true.
        val missing = snapshot.resolveCompactWidgetItem("missing-bucket-id", defaultHidden, null)
        assertNotNull(missing)
        assertEquals("gemini-argon-wtu-weekly", missing?.id)
        assertTrue(missing!!.isFallback)

        // A hidden concrete id is treated as absent.
        val hidden = snapshot.resolveCompactWidgetItem("3p-weekly", defaultHidden, null)
        assertNotNull(hidden)
        assertEquals("gemini-argon-wtu-weekly", hidden?.id)
        assertTrue(hidden!!.isFallback)

        // A hidden role primary cannot resolve the role; it falls back to the hero bucket.
        val hiddenRole = snapshot.resolveCompactWidgetItem(
            "role:PLANNER",
            defaultHidden + "gemini-argon-wtu-weekly",
            null
        )
        assertNotNull(hiddenRole)
        assertEquals("grok-weekly-credits", hiddenRole?.id)
        assertTrue(hiddenRole!!.isFallback)

        // All hidden => no cell at all.
        val allBucketIds = snapshot.providers.flatMap { it.buckets }.map { it.id }.toSet()
        assertNull(snapshot.resolveCompactWidgetItem("gemini-5h", allBucketIds, null))
    }

    @Test
    fun testResolveWideWidgetItemsCustomTwoPassDoesNotStealLaterSlots() {
        val snapshot = SeedData.createSeedSnapshot()
        val defaultHidden = PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS

        val items = snapshot.resolveWideWidgetItems(
            wideBucketIds = listOf("missing-bucket-id", "gemini-argon-wtu-weekly", "grok-weekly-credits"),
            hiddenBucketIds = defaultHidden
        )

        assertEquals(3, items.size)
        // Slot 1 must not steal the bucket explicitly configured for slot 2.
        assertEquals("gemini-argon-wtu-weekly", items[1].id)
        assertFalse(items[1].isFallback)
        assertEquals("grok-weekly-credits", items[2].id)
        assertFalse(items[2].isFallback)
        // Slot 0 resolves from its own fallback substitute, with that substitute's identity.
        assertEquals("opencode-weekly", items[0].id)
        assertTrue(items[0].isFallback)
        assertEquals("OPENCODE", items[0].categoryLabel)
        assertFalse(items.any { it.remainingPercent.isNaN() })
    }

    @Test
    fun testResolveWideWidgetItemsCustomRoleTokensReserveTheirPrimaries() {
        val snapshot = SeedData.createSeedSnapshot()
        val defaultHidden = PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS

        val items = snapshot.resolveWideWidgetItems(
            wideBucketIds = listOf("role:planner", "missing-bucket-id", "role:REVIEWER"),
            hiddenBucketIds = defaultHidden
        )

        assertEquals(3, items.size)
        assertEquals("role:PLANNER", items[0].id)
        assertEquals(35.0, items[0].remainingPercent, 0.0001)
        assertFalse(items[0].isFallback)
        assertEquals("role:REVIEWER", items[2].id)
        assertFalse(items[2].isFallback)
        // The substitute must not reuse the missing slot's request or a reserved primary.
        assertTrue(items[1].isFallback)
        assertNotEquals("gemini-argon-wtu-weekly", items[1].id)
        assertNotEquals("grok-weekly-credits", items[1].id)
    }

    @Test
    fun testResolveWideWidgetItemsDefaultModeReservesRolePrimaries() {
        val snapshot = SeedData.createSeedSnapshot()
        val hidden = PreferencesStore.DEFAULT_HIDDEN_BUCKET_IDS + "gemini-argon-wtu-weekly"

        val items = snapshot.resolveWideWidgetItems(emptyList(), hidden)

        assertEquals(3, items.size)
        // PLANNER is unresolved, so slot 0 fills from the pool without stealing BUILDER/REVIEWER.
        assertTrue(items[0].isFallback)
        assertEquals("opencode-weekly", items[0].id)
        assertEquals("role:BUILDER", items[1].id)
        assertEquals("role:REVIEWER", items[2].id)
        assertFalse(items[1].isFallback)
        assertFalse(items[2].isFallback)
    }

    @Test
    fun testResolveWideWidgetItemsAlwaysReturnsThreeCells() {
        // Empty snapshot: default mode still returns 3 NaN placeholders.
        val emptySnapshot = SeedData.createSeedSnapshot().copy(providers = emptyList(), orcaRoles = emptyList())
        val emptyItems = emptySnapshot.resolveWideWidgetItems(emptyList(), emptySet())
        assertEquals(3, emptyItems.size)
        assertTrue(emptyItems.all { it.remainingPercent.isNaN() })
        assertTrue(emptyItems.all { it.titleLabel == "All models hidden" })
        assertTrue(emptyItems.all { it.categoryLabel == "ORCA STAGE" })
        assertTrue(emptyItems.all { it.isFallback })

        // All hidden in custom mode: 3 NaN placeholders, never a 1- or 2-item list.
        val snapshot = SeedData.createSeedSnapshot()
        val allIds = snapshot.providers.flatMap { it.buckets }.map { it.id }.toSet()
        val customItems = snapshot.resolveWideWidgetItems(
            wideBucketIds = listOf("gemini-5h", "missing-bucket-id"),
            hiddenBucketIds = allIds
        )
        assertEquals(3, customItems.size)
        assertTrue(customItems.all { it.remainingPercent.isNaN() })
        assertTrue(customItems.all { it.titleLabel == "All models hidden" })
        assertEquals("gemini-5h", customItems[0].id)
        assertEquals("missing-bucket-id", customItems[1].id)
    }

    @Test
    fun testWideWidgetSelectionPreservedWhenBucketIsLaterHidden() = runTest {
        val sharedPrefs = com.aiquotamonitor.data.local.InMemorySharedPreferences()
        val (_, repository) = newRepository(sharedPrefs)

        repository.setCompactWidgetBucket("gemini-5h")
        repository.setWideWidgetBuckets(listOf("gemini-argon-wtu-weekly", "gemini-5h"))

        repository.setHiddenBuckets(setOf("gemini-5h"))

        assertEquals("gemini-5h", repository.compactWidgetBucketIdFlow.value)
        assertEquals(listOf("gemini-argon-wtu-weekly", "gemini-5h"), repository.wideWidgetBucketIdsFlow.value)
    }
}
