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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
}
